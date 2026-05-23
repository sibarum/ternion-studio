package sibarum.ternion.designer;

import sibarum.dasum.gui.core.render.Color;
import sibarum.mcc.op.Add;
import sibarum.mcc.op.Concat;
import sibarum.mcc.op.CosineSimilarity;
import sibarum.mcc.op.CrossProduct3;
import sibarum.mcc.op.DotProduct;
import sibarum.mcc.op.HarmonicLift;
import sibarum.mcc.op.IntToVector;
import sibarum.mcc.op.Linear;
import sibarum.mcc.op.Magnitude;
import sibarum.mcc.op.Mul;
import sibarum.mcc.op.Normalize;
import sibarum.mcc.op.Parameter;
import sibarum.mcc.op.QuaternionToVector;
import sibarum.mcc.op.Relu;
import sibarum.mcc.op.Sigmoid;
import sibarum.mcc.op.SimilarityGate;
import sibarum.mcc.op.Softmax;
import sibarum.mcc.op.Sub;
import sibarum.mcc.op.Tanh;
import sibarum.mcc.op.TensorToVector;
import sibarum.mcc.op.TernionToVector;
import sibarum.mcc.op.VectorToInt;
import sibarum.mcc.op.VectorToQuaternion;
import sibarum.mcc.op.VectorToTensor;
import sibarum.mcc.op.VectorToTernion;
import sibarum.mcc.embedding.AutoTokenizer;
import sibarum.mcc.embedding.AutoTokenizer2D;
import sibarum.mcc.op.advanced.SmoothedBasisElement.Kernel;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.data.curated.CuratedDataset;
import sibarum.ternion.data.curated.CuratedDatasets;
import sibarum.ternion.designer.config.ConfigField;
import sibarum.ternion.data.source.DataSource;
import sibarum.ternion.data.source.DataSourceRegistry;
import sibarum.ternion.designer.config.ConfigField.BoolField;
import sibarum.ternion.designer.config.ConfigField.DoubleField;
import sibarum.ternion.designer.config.ConfigField.DynamicEnumField;
import sibarum.ternion.designer.config.ConfigField.EnumField;
import sibarum.ternion.designer.config.ConfigField.IntField;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Registry of all spawnable primitives. Each entry is uniquely shaped by
 * its key — structural variants (matrix length, etc.) live in the
 * per-spawn {@link ConfigField} schema, not in separate palette entries.
 */
public final class Palette {

    private static final Color BG_INPUT      = new Color(0.20f, 0.32f, 0.42f, 1f);
    private static final Color BG_OUTPUT     = new Color(0.42f, 0.32f, 0.20f, 1f);
    private static final Color BG_ARITHMETIC = new Color(0.22f, 0.32f, 0.22f, 1f);
    private static final Color BG_ACTIVATION = new Color(0.32f, 0.22f, 0.32f, 1f);
    private static final Color BG_LINEAR     = new Color(0.25f, 0.22f, 0.35f, 1f);
    private static final Color BG_GEOMETRY   = new Color(0.32f, 0.28f, 0.22f, 1f);
    private static final Color BG_RESHAPE    = new Color(0.22f, 0.32f, 0.35f, 1f);
    private static final Color BG_CONVERSION = new Color(0.30f, 0.30f, 0.30f, 1f);
    private static final Color BG_VISUALIZER = new Color(0.18f, 0.22f, 0.28f, 1f);
    private static final Color BG_EMBEDDING  = new Color(0.35f, 0.22f, 0.32f, 1f);

    private static final Random RNG = new Random(0xC0FFEE);

    private static final List<PaletteItem> ITEMS = buildItems();

    private static List<PaletteItem> buildItems() {
        List<PaletteItem> list = new ArrayList<>();

        // INPUT — Parameter (MATRIX-4 — matches Linear/Relu/Add/Terminal MATRIX wiring).
        // Other shapes / value types arrive with the properties pane in Phase 4.
        list.add(PaletteItem.source(
            "input.parameter.matrix4", "Parameter (MATRIX-4)",
            PaletteItem.Category.INPUT, BG_INPUT,
            cfg -> new Parameter(ValueType.MATRIX, new int[] { 4 }, RNG.nextLong()),
            "value"));

        // INPUT — Dataset Column. Source primitive that emits one column
        // from a bundled curated dataset; each training step the trainer
        // advances the row and every dataset column publishes its
        // current cell. Pair with AutoTokenizer to feed real text into
        // the graph. See DatasetColumnPrimitive / DatasetColumns sidecar.
        list.add(PaletteItem.source(
            "input.dataset.column", "Dataset Column",
            PaletteItem.Category.INPUT, BG_INPUT,
            List.of(
                new DynamicEnumField("source", "Source",
                    Palette::defaultDatasetSource, Palette::allDatasetSources),
                new EnumField("outputType", "Output type", "AUTO",  List.of("AUTO", "STRING", "NUMBER", "MATRIX"))
            ),
            cfg -> {
                String source = (String) cfg.get("source");
                int colon = source.lastIndexOf(':');
                if (colon < 0) {
                    throw new IllegalArgumentException(
                        "Dataset Column source must be 'datasetId:columnName'; got: " + source);
                }
                String datasetId  = source.substring(0, colon);
                String columnName = source.substring(colon + 1);
                ValueType outputType = parseOutputType((String) cfg.get("outputType"));
                return new DatasetColumnPrimitive(datasetId, columnName, outputType);
            },
            "value"));

        // INPUT — Lookup Column. Key-indexed random-access read against
        // any registered source: STRING input → row whose keyColumn
        // matches → valueColumn cell parsed as outputType. The natural
        // use is a bundled embedding table (word_embed_k16:surface →
        // embedding). Not iterated — pairs with an iterated source
        // (DatasetColumn / LossOutput) without tripping the trainer's
        // single-source preflight.
        list.add(PaletteItem.unary(
            "input.lookup.column", "Lookup Column",
            PaletteItem.Category.INPUT, BG_INPUT,
            List.of(
                new DynamicEnumField("source", "Source",
                    Palette::defaultLookupTriple, Palette::allLookupTriples),
                new EnumField("outputType", "Output type", "AUTO", List.of("AUTO", "STRING", "NUMBER", "MATRIX"))
            ),
            cfg -> {
                String source = (String) cfg.get("source");
                String[] parts = source.split(":", 3);
                if (parts.length != 3) {
                    throw new IllegalArgumentException(
                        "Lookup Column source must be 'sourceId:keyColumn:valueColumn'; got: " + source);
                }
                ValueType outputType = parseOutputType((String) cfg.get("outputType"));
                return new LookupColumnPrimitive(parts[0], parts[1], parts[2], outputType);
            },
            "key", "value"));

        // ARITHMETIC.
        list.add(PaletteItem.binary("arith.add",       "Add",       PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, cfg -> new Add(), "a", "b", "result"));
        list.add(PaletteItem.binary("arith.sub",       "Sub",       PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, cfg -> new Sub(), "a", "b", "result"));
        list.add(PaletteItem.binary("arith.mul",       "Mul",       PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, cfg -> new Mul(), "a", "b", "result"));
        list.add(PaletteItem.binary("arith.dot",       "Dot",       PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, cfg -> new DotProduct(), "a", "b", "result"));
        list.add(PaletteItem.unary( "arith.magnitude", "Magnitude", PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, cfg -> new Magnitude(), "x", "y"));
        list.add(PaletteItem.unary( "arith.normalize", "Normalize", PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, cfg -> new Normalize(), "x", "y"));

        // ACTIVATIONS.
        list.add(PaletteItem.unary("act.relu",    "ReLU",    PaletteItem.Category.ACTIVATION, BG_ACTIVATION, cfg -> new Relu(),    "x", "y"));
        list.add(PaletteItem.unary("act.sigmoid", "Sigmoid", PaletteItem.Category.ACTIVATION, BG_ACTIVATION, cfg -> new Sigmoid(), "x", "y"));
        list.add(PaletteItem.unary("act.tanh",    "Tanh",    PaletteItem.Category.ACTIVATION, BG_ACTIVATION, cfg -> new Tanh(),    "x", "y"));
        list.add(PaletteItem.unary("act.softmax", "Softmax", PaletteItem.Category.ACTIVATION, BG_ACTIVATION, cfg -> new Softmax(), "x", "y"));

        // LINEAR — configurable inDim/outDim/withBias.
        list.add(PaletteItem.unary(
            "linear.linear", "Linear",
            PaletteItem.Category.LINEAR, BG_LINEAR,
            List.of(
                new IntField("inDim",     "Input dim",  4, 1, 4096),
                new IntField("outDim",    "Output dim", 4, 1, 4096),
                new BoolField("withBias", "With bias",  true)
            ),
            cfg -> new Linear(
                ((Number) cfg.get("outDim")).intValue(),
                ((Number) cfg.get("inDim")).intValue(),
                (Boolean) cfg.get("withBias"),
                RNG.nextLong()),
            "x", "y"));

        // LINEAR — Harmonic basis lift. Configurable K/inputDim/kernel/widthFrac.
        list.add(PaletteItem.unary(
            "linear.harmonic_lift", "Harmonic Lift",
            PaletteItem.Category.LINEAR, BG_LINEAR,
            List.of(
                new IntField("K",          "Frequencies (K)", 4, 1, 32),
                new IntField("inputDim",   "Input dim",       4, 1, 256),
                new EnumField("kernel",    "Kernel",          "BOX", List.of("DELTA", "BOX", "TENT")),
                new DoubleField("widthFrac", "Width fraction", 0.125, 0.0, 1.0)
            ),
            cfg -> new HarmonicLift(
                ((Number) cfg.get("K")).intValue(),
                ((Number) cfg.get("inputDim")).intValue(),
                Kernel.valueOf((String) cfg.get("kernel")),
                ((Number) cfg.get("widthFrac")).doubleValue()),
            "x", "y"));

        // RESHAPE.
        list.add(PaletteItem.binary("reshape.concat", "Concat", PaletteItem.Category.RESHAPE, BG_RESHAPE, cfg -> new Concat(), "a", "b", "result"));

        // GEOMETRY / similarity.
        list.add(PaletteItem.binary("geom.cross3",  "Cross (T)",   PaletteItem.Category.GEOMETRY, BG_GEOMETRY, cfg -> new CrossProduct3(), "a", "b", "result"));
        list.add(PaletteItem.binary("geom.cosine",  "CosineSim",   PaletteItem.Category.GEOMETRY, BG_GEOMETRY, cfg -> new CosineSimilarity(), "a", "b", "result"));
        list.add(PaletteItem.binary("geom.simgate", "SimGate (Q)", PaletteItem.Category.GEOMETRY, BG_GEOMETRY, cfg -> new SimilarityGate(), "a", "b", "result"));

        // OUTPUT — Loss Output. Unified sink that fuses Terminal +
        // Expected Output: predicted wires into the only input port,
        // and the supervisory target is read from a configured
        // (source, column) cell at the trainer's current row. The
        // produced value is the scalar loss, so the trainer's backward
        // seed becomes the trivial dL/dL=1 regardless of loss fn.
        // See LossOutputPrimitive / DataSourceBoundNodes sidecar.
        list.add(PaletteItem.unary(
            "output.loss", "Loss Output",
            PaletteItem.Category.OUTPUT, BG_OUTPUT,
            List.of(
                new DynamicEnumField("source", "Source",
                    Palette::defaultDatasetSource, Palette::allDatasetSources),
                new EnumField("inputType",  "Predicted type", "MATRIX", List.of("NUMBER", "MATRIX")),
                new EnumField("lossFn",     "Loss fn",        "MSE",    List.of("MSE", "CATEGORICAL")),
                new IntField("numClasses",  "Num classes (CATEGORICAL only)", 16, 1, 4096)
            ),
            cfg -> {
                String source = (String) cfg.get("source");
                int colon = source.lastIndexOf(':');
                if (colon < 0) {
                    throw new IllegalArgumentException(
                        "Loss Output source must be 'sourceId:columnName'; got: " + source);
                }
                String sourceId  = source.substring(0, colon);
                String columnName = source.substring(colon + 1);
                ValueType inputType = ValueType.valueOf((String) cfg.get("inputType"));
                LossOutputPrimitive.LossFn lossFn =
                    LossOutputPrimitive.LossFn.valueOf((String) cfg.get("lossFn"));
                int numClasses = ((Number) cfg.get("numClasses")).intValue();
                return new LossOutputPrimitive(sourceId, columnName, inputType, lossFn, numClasses);
            },
            "predicted", null));

        // CONVERSIONS between MATRIX and the other value types.
        list.add(PaletteItem.unary("conv.ternion_to_vector",  "Ternion → Vector",   PaletteItem.Category.CONVERSION, BG_CONVERSION, cfg -> new TernionToVector(),    "t", "v"));
        list.add(PaletteItem.unary("conv.vector_to_ternion",  "Vector → Ternion",   PaletteItem.Category.CONVERSION, BG_CONVERSION, cfg -> new VectorToTernion(),    "v", "t"));
        list.add(PaletteItem.unary("conv.quat_to_vector",     "Quaternion → Vector",PaletteItem.Category.CONVERSION, BG_CONVERSION, cfg -> new QuaternionToVector(), "q", "v"));
        list.add(PaletteItem.unary("conv.vector_to_quat",     "Vector → Quaternion",PaletteItem.Category.CONVERSION, BG_CONVERSION, cfg -> new VectorToQuaternion(), "v", "q"));
        list.add(PaletteItem.unary("conv.tensor_to_vector",   "Tensor → Vector",    PaletteItem.Category.CONVERSION, BG_CONVERSION, cfg -> new TensorToVector(),     "t", "v"));
        list.add(PaletteItem.unary("conv.int_to_vector",      "Int → Vector (vocab=64, dim=8)", PaletteItem.Category.CONVERSION, BG_CONVERSION,
            cfg -> new IntToVector(64, 8, RNG.nextLong()), "i", "v"));
        list.add(PaletteItem.unary("conv.vector_to_int",      "Vector → Int",       PaletteItem.Category.CONVERSION, BG_CONVERSION, cfg -> new VectorToInt(),        "v", "i"));
        list.add(PaletteItem.unary("conv.vector_to_tensor",   "Vector → Tensor (shape=[4])", PaletteItem.Category.CONVERSION, BG_CONVERSION,
            cfg -> new VectorToTensor(new int[] { 4 }), "v", "t"));

        // VISUALIZER — identity pass-through that taps the wire's forward
        // value + backward gradient and renders them as a live point cloud.
        // No effect on training math; cost is per-step snapshot construction.
        list.add(PaletteItem.unary(
            "visualizer.matrix", "Visualizer (MATRIX)",
            PaletteItem.Category.VISUALIZER, BG_VISUALIZER,
            cfg -> new VisualizerPrimitive(ValueType.MATRIX),
            "in", "out"));

        // EMBEDDING — 1-D auto-tokenizer (STRING → NUMBER). Vocab grows on
        // first sight; mode controls how new ids are distributed across
        // [0, idRange). Feed into IntToVector (with matching vocabSize)
        // for a learnable embedding.
        list.add(PaletteItem.unary(
            "embedding.auto_tokenizer", "AutoTokenizer (STRING → NUMBER)",
            PaletteItem.Category.EMBEDDING, BG_EMBEDDING,
            List.of(
                new EnumField("mode",    "Mode",     "DISTRIBUTED",
                    List.of("SEQUENTIAL", "RANDOM", "DISTRIBUTED")),
                new IntField("idRange", "ID range",  256, 2, 65536),
                new IntField("seed",    "Seed",      0,   Integer.MIN_VALUE, Integer.MAX_VALUE)
            ),
            cfg -> new AutoTokenizerPrimitive(
                AutoTokenizer.Mode.valueOf((String) cfg.get("mode")),
                ((Number) cfg.get("idRange")).intValue(),
                ((Number) cfg.get("seed")).longValue()),
            "text", "id"));

        // EMBEDDING — 2-D auto-tokenizer (STRING → MATRIX[2]). Coord is
        // (x, y) packed into a length-2 MATRIX; downstream nodes can
        // index into a 2-D embedding table or use the coord directly.
        list.add(PaletteItem.unary(
            "embedding.auto_tokenizer_2d", "AutoTokenizer 2D (STRING → MATRIX[2])",
            PaletteItem.Category.EMBEDDING, BG_EMBEDDING,
            List.of(
                new EnumField("mode",  "Mode",   "SUNFLOWER",
                    List.of("GRID", "RANDOM", "SUNFLOWER", "GAUSSIAN_PRIME")),
                new IntField("range", "Range",  256, 2, 65536),
                new IntField("seed",  "Seed",   0,   Integer.MIN_VALUE, Integer.MAX_VALUE)
            ),
            cfg -> new AutoTokenizer2DPrimitive(
                AutoTokenizer2D.Mode.valueOf((String) cfg.get("mode")),
                ((Number) cfg.get("range")).intValue(),
                ((Number) cfg.get("seed")).longValue()),
            "text", "coord"));

        return List.copyOf(list);
    }

    public static List<PaletteItem> items() {
        return ITEMS;
    }

    public static PaletteItem byKey(String key) {
        for (PaletteItem item : ITEMS) {
            if (item.key().equals(key)) return item;
        }
        return null;
    }

    /**
     * Every {@code sourceId:columnName} known to the live
     * {@link DataSourceRegistry}. Re-queried each time a config
     * dialog opens (via {@link DynamicEnumField}) so editable +
     * imported sources registered after JVM startup show up
     * immediately. Falls back to {@link CuratedDatasets} when the
     * registry isn't initialised (test paths that build a Palette
     * without an AppContext).
     */
    private static List<String> allDatasetSources() {
        List<String> out = new ArrayList<>();
        DataSourceRegistry reg = DataSourceRegistry.current();
        if (reg != null) {
            for (DataSource ds : reg.all()) {
                for (String col : ds.columns()) {
                    out.add(ds.id() + ":" + col);
                }
            }
        } else {
            for (CuratedDataset ds : CuratedDatasets.all()) {
                for (String col : ds.columns()) {
                    out.add(ds.id() + ":" + col);
                }
            }
        }
        if (out.isEmpty()) out.add("none:none");
        return List.copyOf(out);
    }

    private static String defaultDatasetSource() {
        DataSourceRegistry reg = DataSourceRegistry.current();
        if (reg != null) {
            for (DataSource ds : reg.all()) {
                if (!ds.columns().isEmpty()) {
                    return ds.id() + ":" + ds.columns().get(0);
                }
            }
        }
        for (CuratedDataset ds : CuratedDatasets.all()) {
            if (!ds.columns().isEmpty()) {
                return ds.id() + ":" + ds.columns().get(0);
            }
        }
        return "none:none";
    }

    /**
     * Every {@code sourceId:keyColumn:valueColumn} triple known to the
     * live {@link DataSourceRegistry}. Re-queried each time the Lookup
     * Column config dialog opens. Skips pairs where key == value.
     * Falls back to {@link CuratedDatasets} when the registry isn't
     * initialised.
     */
    private static List<String> allLookupTriples() {
        List<String> out = new ArrayList<>();
        DataSourceRegistry reg = DataSourceRegistry.current();
        if (reg != null) {
            for (DataSource ds : reg.all()) {
                List<String> cols = ds.columns();
                for (String key : cols) {
                    for (String value : cols) {
                        if (key.equals(value)) continue;
                        out.add(ds.id() + ":" + key + ":" + value);
                    }
                }
            }
        } else {
            for (CuratedDataset ds : CuratedDatasets.all()) {
                List<String> cols = ds.columns();
                for (String key : cols) {
                    for (String value : cols) {
                        if (key.equals(value)) continue;
                        out.add(ds.id() + ":" + key + ":" + value);
                    }
                }
            }
        }
        if (out.isEmpty()) out.add("none:none:none");
        return List.copyOf(out);
    }

    private static String defaultLookupTriple() {
        // Prefer surface→embedding (the canonical pretrained-table
        // shape) when any registered source has it.
        DataSourceRegistry reg = DataSourceRegistry.current();
        if (reg != null) {
            for (DataSource ds : reg.all()) {
                List<String> cols = ds.columns();
                if (cols.contains("surface") && cols.contains("embedding")) {
                    return ds.id() + ":surface:embedding";
                }
            }
        }
        for (CuratedDataset ds : CuratedDatasets.all()) {
            List<String> cols = ds.columns();
            if (cols.contains("surface") && cols.contains("embedding")) {
                return ds.id() + ":surface:embedding";
            }
        }
        List<String> all = allLookupTriples();
        return all.isEmpty() ? "none:none:none" : all.get(0);
    }

    /** {@code "AUTO"} → {@code null} (defer to source's
     *  {@link sibarum.ternion.data.source.DataSource#columnType
     *  columnType}); otherwise parse as a {@link ValueType} name. */
    private static ValueType parseOutputType(String s) {
        if (s == null || "AUTO".equals(s)) return null;
        return ValueType.valueOf(s);
    }

    private Palette() {}
}
