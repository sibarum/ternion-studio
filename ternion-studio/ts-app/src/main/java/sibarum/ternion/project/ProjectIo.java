package sibarum.ternion.project;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.graph.GraphSurfacePositions;
import sibarum.dasum.gui.core.status.Status;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.ComputationGraph;
import sibarum.mcc.primitive.Parameterized;
import sibarum.ternion.designer.DataSourceBoundNodes;
import sibarum.mcc.serialization.Exporter;
import sibarum.mcc.serialization.GraphSchema;
import sibarum.mcc.serialization.GraphSchemaCodec;
import sibarum.mcc.serialization.JsonReader;
import sibarum.mcc.serialization.JsonWriter;
import sibarum.mcc.serialization.ParameterBlob;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.source.BundledDataSource;
import sibarum.ternion.data.source.DataSource;
import sibarum.ternion.data.source.DataSourceRegistry;
import sibarum.ternion.data.source.EditableDataSource;
import sibarum.ternion.data.source.ImportedDataSource;
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

    public static final int SCHEMA_VERSION = 3;
    /** Lowest reader-compatible version. v1 was corpus-only (legacy);
     *  v2 added {@code sources.json} alongside; v3 drops the legacy
     *  {@code corpus.json} entirely. Phase C-2 still tolerates loading
     *  v2 projects — the corpus is silently dropped. */
    private static final int MIN_READABLE_VERSION = 2;
    private static final String GRAPH_DIR    = "graph";
    private static final String PROJECT_JSON = "project.json";
    private static final String DESIGNER_JSON = "designer.json";
    private static final String SOURCES_JSON = "sources.json";

    private ProjectIo() {}

    // ---------- save ----------

    public static void save(Path projectDir, AppContext ctx) throws IOException {
        CompGraphNode terminal = findTerminal(ctx);
        if (terminal == null) {
            throw new IOException("no Loss Output (or Terminal) node in graph — add one before saving");
        }
        Files.createDirectories(projectDir);

        // 1. mcc graph + params. With the registry-driven Phase C path,
        //    DatasetColumn nodes are self-supplying — they have no
        //    unwired root slots that need named binding — so the roots
        //    list is empty.
        ComputationGraph graph = ctx.graphSync().snapshot(terminal);
        new Exporter().export(graph, List.of(), projectDir.resolve(GRAPH_DIR));

        // 2. designer layout.
        writeJson(projectDir.resolve(DESIGNER_JSON), designerJson(ctx));

        // 3. data source registry (editable + imported; bundled are
        //    re-seeded from CuratedDatasets at AppContext construction
        //    so we emit id-only entries for those just to preserve the
        //    project's intended source set if a future build drops a
        //    bundled dataset).
        writeJson(projectDir.resolve(SOURCES_JSON), sourcesJson(ctx));

        // 4. project metadata.
        String name = derivedName(projectDir);
        writeJson(projectDir.resolve(PROJECT_JSON), projectJson(name));
        ctx.project().name().set(name);
        ctx.project().path().set(projectDir);
        ctx.project().markClean();
    }

    // ---------- load ----------

    public static void load(Path projectDir, AppContext ctx) throws IOException {
        // Validate the metadata first so failures abort before mutating
        // the active app state.
        Map<String, Object> meta = readJsonObject(projectDir.resolve(PROJECT_JSON));
        int v = asInt(meta.get("schemaVersion"), -1);
        if (v < MIN_READABLE_VERSION || v > SCHEMA_VERSION) {
            throw new IOException("unsupported project schemaVersion: " + v
                + " (this build reads " + MIN_READABLE_VERSION + ".." + SCHEMA_VERSION + ")");
        }

        Map<String, Object> designerObj = readJsonObject(projectDir.resolve(DESIGNER_JSON));
        // sources.json is v2+; absent for v1 projects (which had a
        // corpus.json that Phase C-2 no longer reads — old corpus rows
        // are silently dropped on load and never round-trip back out).
        Path sourcesPath = projectDir.resolve(SOURCES_JSON);
        Map<String, Object> sourcesObj = Files.exists(sourcesPath)
            ? readJsonObject(sourcesPath) : null;

        // Read mcc graph schema. Read params.bin separately so we can
        // apply tensors after re-spawning via the palette.
        Path graphJsonPath = projectDir.resolve(GRAPH_DIR).resolve("graph.json");
        GraphSchema schema = GraphSchemaCodec.fromJson(
            Files.readString(graphJsonPath, StandardCharsets.UTF_8));

        // ---- mutate active state ----

        // Stop training (if running) so we don't snapshot the graph mid-step.
        ctx.trainingController().stop();
        ctx.graphSync().clearAll();

        // Restore the data source registry BEFORE graph nodes are
        // re-spawned, so any graph node config that references a
        // source by id resolves cleanly on first apply().
        restoreSources(ctx, sourcesObj);

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
                sibarum.ternion.designer.DesignerPanel.afterSpawn(ctx.graphSync(), spec);
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

        // Metadata last, so any failure above leaves the project path
        // unset and the user can retry without confusion.
        String name = asString(meta.get("name"), derivedName(projectDir));
        ctx.project().name().set(name);
        ctx.project().path().set(projectDir);
        // Every restore-from-disk mutation marked the project dirty;
        // clear that — the loaded state matches what's on disk.
        ctx.project().markClean();

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

    /** Loss Output is the snapshot root for save/export — Phase C-2
     *  removed the legacy Terminal palette entries. */
    private static CompGraphNode findTerminal(AppContext ctx) {
        return DataSourceBoundNodes.findLossTarget();
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

    /**
     * Serialize the project's data source registry. Each entry carries
     * its origin so the loader knows which subtype to reconstruct.
     * Bundled entries store only the id — rows + columns rehydrate
     * from {@link sibarum.ternion.data.curated.CuratedDatasets} via
     * {@link DataSourceRegistry#seedBundled} at app startup.
     *
     * <p>Layout:
     * <pre>
     * {
     *   "schemaVersion": 2,
     *   "sources": [
     *     {"id": "pos_train", "origin": "BUNDLED"},
     *     {"id": "scratch",   "origin": "EDITABLE",
     *      "displayLabel": "Scratch",
     *      "suggestedTargetColumnIndex": -1,
     *      "columns": ["a", "b"],
     *      "rows": [["1", "2"], ["3", "4"]]}
     *   ]
     * }
     * </pre>
     */
    private static Map<String, Object> sourcesJson(AppContext ctx) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("schemaVersion", SCHEMA_VERSION);
        List<Map<String, Object>> sources = new ArrayList<>();
        for (DataSource src : ctx.dataSources().all()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", src.id());
            s.put("origin", src.origin().name());
            if (src.origin() != sibarum.ternion.data.source.Origin.BUNDLED) {
                s.put("displayLabel", src.displayLabel());
                s.put("suggestedTargetColumnIndex", src.suggestedTargetColumnIndex());
                s.put("columns", new ArrayList<>(src.columns()));
                List<List<String>> rows = new ArrayList<>(src.rowCount());
                for (int r = 0; r < src.rowCount(); r++) {
                    List<String> row = new ArrayList<>(src.columns().size());
                    for (int c = 0; c < src.columns().size(); c++) {
                        row.add(src.get(r, c));
                    }
                    rows.add(row);
                }
                s.put("rows", rows);
            }
            sources.add(s);
        }
        o.put("sources", sources);
        return o;
    }

    /**
     * Inverse of {@link #sourcesJson}. Removes every editable +
     * imported source currently in the registry, then re-adds those
     * declared in the project file. Bundled sources are left untouched
     * — they re-seed from {@link sibarum.ternion.data.curated.CuratedDatasets}
     * at app startup and don't need restoring. {@code null} {@code obj}
     * is the v1 case (no {@code sources.json}); the registry stays at
     * its startup-seeded state.
     */
    @SuppressWarnings("unchecked")
    private static void restoreSources(AppContext ctx, Map<String, Object> obj) {
        DataSourceRegistry reg = ctx.dataSources();
        // Drop any non-bundled sources first so a reload doesn't leak
        // sources from the previous project.
        for (DataSource src : new ArrayList<>(reg.all())) {
            if (src.origin() != sibarum.ternion.data.source.Origin.BUNDLED) {
                reg.removeForced(src.id());
            }
        }
        if (obj == null) return; // v1 project — no sources to restore.

        List<?> arr = (List<?>) obj.get("sources");
        if (arr == null) return;
        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String id = asString(m.get("id"), null);
            String originName = asString(m.get("origin"), "EDITABLE");
            if (id == null) {
                Status.warn("Load: skipping source with missing id");
                continue;
            }
            sibarum.ternion.data.source.Origin origin;
            try {
                origin = sibarum.ternion.data.source.Origin.valueOf(originName);
            } catch (IllegalArgumentException ex) {
                Status.warn("Load: unknown source origin '" + originName + "' for '" + id + "'");
                continue;
            }
            switch (origin) {
                case BUNDLED -> {
                    if (!reg.contains(id)) {
                        Status.warn("Load: project references bundled source '" + id
                            + "' which isn't available in this build.");
                    }
                }
                case EDITABLE -> {
                    if (reg.contains(id)) {
                        Status.warn("Load: editable source '" + id
                            + "' collides with an existing source; skipping.");
                        continue;
                    }
                    String label = asString(m.get("displayLabel"), id);
                    List<String> cols = stringList((List<?>) m.get("columns"));
                    int suggested = asInt(m.get("suggestedTargetColumnIndex"), -1);
                    EditableDataSource eds = new EditableDataSource(id, label, cols);
                    if (suggested >= 0 && suggested < cols.size()) {
                        eds.setSuggestedTargetColumnIndex(suggested);
                    }
                    List<String[]> rows = rowMatrix((List<?>) m.get("rows"), cols.size());
                    eds.replaceRows(rows);
                    reg.add(eds);
                }
                case IMPORTED -> {
                    if (reg.contains(id)) continue;
                    String label = asString(m.get("displayLabel"), id);
                    List<String> cols = stringList((List<?>) m.get("columns"));
                    List<String[]> rows = rowMatrix((List<?>) m.get("rows"), cols.size());
                    reg.add(new ImportedDataSource(id, label, cols,
                        rows.toArray(new String[0][]), null));
                }
            }
        }
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
    private static List<String> stringList(List<?> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (Object o : in) if (o instanceof String s) out.add(s);
        return out;
    }

    private static List<String[]> rowMatrix(List<?> in, int cols) {
        List<String[]> out = new ArrayList<>();
        if (in == null) return out;
        for (Object o : in) {
            if (!(o instanceof List<?> row)) continue;
            String[] cells = new String[cols];
            for (int i = 0; i < cols; i++) {
                cells[i] = (i < row.size() && row.get(i) instanceof String s) ? s : "";
            }
            out.add(cells);
        }
        return out;
    }

}
