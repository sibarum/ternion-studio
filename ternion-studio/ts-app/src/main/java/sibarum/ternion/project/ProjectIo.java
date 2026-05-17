package sibarum.ternion.project;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.graph.GraphSurfacePositions;
import sibarum.dasum.gui.core.status.Status;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.ComputationGraph;
import sibarum.mcc.op.Terminal;
import sibarum.mcc.primitive.Parameterized;
import sibarum.mcc.serialization.Exporter;
import sibarum.mcc.serialization.GraphSchema;
import sibarum.mcc.serialization.GraphSchemaCodec;
import sibarum.mcc.serialization.JsonReader;
import sibarum.mcc.serialization.JsonWriter;
import sibarum.mcc.serialization.ParameterBlob;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.CorpusModel;
import sibarum.ternion.data.InputSchema;
import sibarum.ternion.designer.FrozenBadge;
import sibarum.ternion.designer.FrozenNodes;
import sibarum.ternion.designer.NodeSpec;
import sibarum.ternion.designer.Palette;
import sibarum.ternion.designer.PaletteItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Save / load a project as a {@code .tsp} directory:
 *
 * <pre>
 *   ${name}.tsp/
 *   ├── project.json     metadata (schema version, name)
 *   ├── graph/           mcc-core Exporter format (graph.json + params.bin)
 *   ├── corpus.json      raw row data — strings as the user typed them
 *   └── designer.json    per-node {id, paletteKey, emX, emY}
 * </pre>
 *
 * <p>The split between {@code graph/} (mcc's responsibility) and
 * {@code designer.json} (ours) keeps each layer authoritative for its
 * own concerns: the topology + parameters live in graph/, the visual
 * placement + palette mapping lives in designer.json.
 *
 * <p>Loading bypasses mcc-core's {@code Importer} because the
 * ternion-studio flow needs the visual node to come from
 * {@link PaletteItem#spawn} (which builds the ports + visual + click
 * wiring atomically). We re-spawn via the palette, then overlay the
 * saved parameters by reading {@code params.bin} directly via
 * {@link ParameterBlob}.
 */
public final class ProjectIo {

    public static final int SCHEMA_VERSION = 1;
    private static final String GRAPH_DIR    = "graph";
    private static final String PROJECT_JSON = "project.json";
    private static final String CORPUS_JSON  = "corpus.json";
    private static final String DESIGNER_JSON = "designer.json";

    private ProjectIo() {}

    // ---------- save ----------

    public static void save(Path projectDir, AppContext ctx) throws IOException {
        CompGraphNode terminal = findTerminal(ctx);
        if (terminal == null) {
            throw new IOException("no Terminal node in graph — add one before saving");
        }
        Files.createDirectories(projectDir);

        // 1. mcc graph + params.
        ComputationGraph graph = ctx.graphSync().snapshot(terminal);
        List<Exporter.RootInput> roots = new ArrayList<>();
        for (InputSchema in : ctx.corpus().inputSchema()) {
            roots.add(new Exporter.RootInput(in.key(), in.node(), in.slotIndex()));
        }
        new Exporter().export(graph, roots, projectDir.resolve(GRAPH_DIR));

        // 2. designer layout.
        writeJson(projectDir.resolve(DESIGNER_JSON), designerJson(ctx));

        // 3. corpus rows (raw strings).
        writeJson(projectDir.resolve(CORPUS_JSON), corpusJson(ctx));

        // 4. project metadata.
        String name = derivedName(projectDir);
        writeJson(projectDir.resolve(PROJECT_JSON), projectJson(name));
        ctx.project().name().set(name);
        ctx.project().path().set(projectDir);
    }

    // ---------- load ----------

    public static void load(Path projectDir, AppContext ctx) throws IOException {
        // Validate the metadata first so failures abort before mutating
        // the active app state.
        Map<String, Object> meta = readJsonObject(projectDir.resolve(PROJECT_JSON));
        int v = asInt(meta.get("schemaVersion"), -1);
        if (v != SCHEMA_VERSION) {
            throw new IOException("unsupported project schemaVersion: " + v);
        }

        Map<String, Object> designerObj = readJsonObject(projectDir.resolve(DESIGNER_JSON));
        Map<String, Object> corpusObj   = readJsonObject(projectDir.resolve(CORPUS_JSON));

        // Read mcc graph schema. Read params.bin separately so we can
        // apply tensors after re-spawning via the palette.
        Path graphJsonPath = projectDir.resolve(GRAPH_DIR).resolve("graph.json");
        GraphSchema schema = GraphSchemaCodec.fromJson(
            Files.readString(graphJsonPath, StandardCharsets.UTF_8));

        // ---- mutate active state ----

        // Stop training (if running) so we don't snapshot the graph mid-step.
        ctx.trainingController().stop();
        ctx.graphSync().clearAll();

        // Re-spawn each node by paletteKey with its saved id, applying
        // its saved config (or schema defaults for older projects).
        List<?> nodes = (List<?>) designerObj.get("nodes");
        Map<String, NodeSpec> bySpec = new HashMap<>();
        if (nodes != null) {
            for (Object o : nodes) {
                Map<?, ?> n = (Map<?, ?>) o;
                String id      = (String) n.get("id");
                String palKey  = (String) n.get("paletteKey");
                float emX = asFloat(n.get("emX"), 0f);
                float emY = asFloat(n.get("emY"), 0f);
                @SuppressWarnings("unchecked")
                Map<String, Object> savedConfig = (Map<String, Object>) n.get("config");
                boolean frozen = Boolean.TRUE.equals(n.get("frozen"));
                PaletteItem item = Palette.byKey(palKey);
                if (item == null) {
                    Status.warn("Load: unknown palette item '" + palKey + "'; skipping node '" + id + "'");
                    continue;
                }
                Map<String, Object> config = savedConfig != null
                    ? savedConfig
                    : sibarum.ternion.designer.config.ConfigField.defaultsOf(item.configSchema());
                NodeSpec spec = ctx.graphSync().spawn(item, emX, emY, config, id);
                bySpec.put(id, spec);
                if (frozen) FrozenNodes.mark(spec.cgNode());
            }
        }

        // Re-wire edges via Connections.add — the dasum listener inside
        // GraphSync will route to CompGraphNode.wire on the mcc side.
        Component.GraphSurface surface = ctx.designerSurface();
        for (GraphSchema.EdgeDesc edge : schema.edges()) {
            NodeSpec from = bySpec.get(edge.from());
            NodeSpec to   = bySpec.get(edge.to());
            if (from == null || to == null) continue;
            if (from.outputPort() == null
                || edge.slot() >= to.orderedInputPorts().size()) {
                continue;
            }
            try {
                Connections.add(surface, from.outputPort(),
                    to.orderedInputPorts().get(edge.slot()));
            } catch (RuntimeException ex) {
                Status.warn("Load: rejecting edge " + edge.from() + " -> " + edge.to()
                    + ": " + ex.getMessage());
            }
        }

        // Overlay saved parameters onto the freshly-spawned (random-init)
        // primitives.
        Path paramsPath = projectDir.resolve(GRAPH_DIR).resolve("params.bin");
        if (Files.exists(paramsPath) && schema.parameters() != null) {
            applyParams(paramsPath, schema, bySpec);
        }

        // Re-detect the schema from the loaded graph + replay saved rows.
        CorpusModel corpus = ctx.corpus();
        corpus.clearAll();
        CompGraphNode loadedTerminal = findTerminal(ctx);
        if (loadedTerminal != null) {
            corpus.detectFromGraph(ctx.graphSync(), loadedTerminal);
        }
        String corpusName = asString(corpusObj.get("name"), "corpus");
        corpus.setName(corpusName);
        List<?> examples = (List<?>) corpusObj.get("examples");
        if (examples != null && !examples.isEmpty()) {
            List<CorpusModel.Row> built = new ArrayList<>(examples.size());
            for (Object o : examples) {
                Map<?, ?> ex = (Map<?, ?>) o;
                String label = asString(ex.get("label"), "");
                Map<String, String> ins = stringMap((Map<?, ?>) ex.get("inputs"));
                String target = asString(ex.get("target"), "");
                built.add(corpus.newRow(label, ins, target));
            }
            corpus.replaceAll(built);
        }

        // Metadata last, so any failure above leaves the project path
        // unset and the user can retry without confusion.
        String name = asString(meta.get("name"), derivedName(projectDir));
        ctx.project().name().set(name);
        ctx.project().path().set(projectDir);

        // Restore lock badges for nodes that were frozen at save time.
        FrozenBadge.refresh(ctx.graphSync());
    }

    // ---------- helpers ----------

    private static void applyParams(Path paramsPath, GraphSchema schema,
                                    Map<String, NodeSpec> bySpec) throws IOException {
        ParameterBlob.ReadResult blob;
        try (InputStream in = Files.newInputStream(paramsPath)) {
            blob = ParameterBlob.read(in);
        }
        String expectedSha = schema.parameters().sha256();
        if (expectedSha != null && !expectedSha.equals(blob.sha256())) {
            throw new IOException("params.bin checksum mismatch: expected "
                + expectedSha + " got " + blob.sha256());
        }
        // Group tensors by node id.
        Map<String, Map<String, Parameterized.NamedTensor>> byNode = new HashMap<>();
        for (GraphSchema.TensorEntry te : schema.parameters().tensors()) {
            double[] data = ParameterBlob.decode(blob.body(), te.offset(), te.length());
            byNode.computeIfAbsent(te.node(), k -> new HashMap<>())
                .put(te.name(),
                    new Parameterized.NamedTensor(te.name(), te.shape(), data));
        }
        for (Map.Entry<String, Map<String, Parameterized.NamedTensor>> e : byNode.entrySet()) {
            NodeSpec spec = bySpec.get(e.getKey());
            if (spec == null) continue;
            if (spec.cgNode().tNode().primitive() instanceof Parameterized pp) {
                try {
                    pp.loadParameters(e.getValue());
                } catch (RuntimeException ex) {
                    Status.warn("Load: parameters for '" + e.getKey()
                        + "' didn't apply: " + ex.getMessage());
                }
            }
        }
    }

    private static CompGraphNode findTerminal(AppContext ctx) {
        for (NodeSpec spec : ctx.graphSync().liveNodes()) {
            if (spec.tNode().primitive() instanceof Terminal) {
                return spec.cgNode();
            }
        }
        return null;
    }

    private static Map<String, Object> projectJson(String name) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("schemaVersion", SCHEMA_VERSION);
        o.put("name", name);
        return o;
    }

    private static Map<String, Object> designerJson(AppContext ctx) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("schemaVersion", SCHEMA_VERSION);
        List<Map<String, Object>> nodes = new ArrayList<>();
        Component.GraphSurface surface = ctx.designerSurface();
        for (NodeSpec spec : ctx.graphSync().liveNodes()) {
            GraphSurfacePositions.Pos pos =
                GraphSurfacePositions.of(surface, spec.visual());
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id",         spec.cgNode().id());
            n.put("paletteKey", spec.paletteKey());
            n.put("emX",        (double) pos.emX());
            n.put("emY",        (double) pos.emY());
            if (!spec.config().isEmpty()) {
                n.put("config", spec.config());
            }
            if (FrozenNodes.isFrozen(spec.cgNode())) {
                n.put("frozen", true);
            }
            nodes.add(n);
        }
        o.put("nodes", nodes);
        return o;
    }

    private static Map<String, Object> corpusJson(AppContext ctx) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("schemaVersion", SCHEMA_VERSION);
        o.put("name", ctx.corpus().name());
        List<Map<String, Object>> examples = new ArrayList<>();
        for (CorpusModel.Row r : ctx.corpus().rows()) {
            Map<String, Object> ex = new LinkedHashMap<>();
            ex.put("label", r.label == null ? "" : r.label);
            ex.put("inputs", new LinkedHashMap<>(r.inputStrings));
            ex.put("target", r.targetString == null ? "" : r.targetString);
            examples.add(ex);
        }
        o.put("examples", examples);
        return o;
    }

    private static String derivedName(Path projectDir) {
        String n = projectDir.getFileName().toString();
        if (n.endsWith(".tsp")) n = n.substring(0, n.length() - 4);
        return n.isEmpty() ? "Untitled" : n;
    }

    private static void writeJson(Path path, Object value) throws IOException {
        JsonWriter w = new JsonWriter();
        w.writeValue(value);
        Files.writeString(path, w.result(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonObject(Path path) throws IOException {
        Object parsed = JsonReader.parse(Files.readString(path, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IOException("expected JSON object at " + path);
        }
        return (Map<String, Object>) m;
    }

    private static int asInt(Object o, int def) {
        return (o instanceof Number n) ? n.intValue() : def;
    }
    private static float asFloat(Object o, float def) {
        return (o instanceof Number n) ? n.floatValue() : def;
    }
    private static String asString(Object o, String def) {
        return (o instanceof String s) ? s : def;
    }
    private static Map<String, String> stringMap(Map<?, ?> m) {
        Map<String, String> out = new LinkedHashMap<>();
        if (m == null) return out;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() instanceof String k && e.getValue() instanceof String v) {
                out.put(k, v);
            }
        }
        return out;
    }
}
