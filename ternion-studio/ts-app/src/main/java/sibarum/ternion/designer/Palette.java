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
import sibarum.mcc.op.Terminal;
import sibarum.mcc.op.TernionToVector;
import sibarum.mcc.op.VectorToInt;
import sibarum.mcc.op.VectorToQuaternion;
import sibarum.mcc.op.VectorToTensor;
import sibarum.mcc.op.VectorToTernion;
import sibarum.mcc.op.advanced.SmoothedBasisElement.Kernel;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.designer.config.ConfigField;
import sibarum.ternion.designer.config.ConfigField.BoolField;
import sibarum.ternion.designer.config.ConfigField.DoubleField;
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

        // OUTPUT — Terminal variants for the common types.
        list.add(PaletteItem.unary(
            "output.terminal.matrix", "Terminal (MATRIX)",
            PaletteItem.Category.OUTPUT, BG_OUTPUT,
            cfg -> new Terminal(ValueType.MATRIX), "in", null));
        list.add(PaletteItem.unary(
            "output.terminal.number", "Terminal (NUMBER)",
            PaletteItem.Category.OUTPUT, BG_OUTPUT,
            cfg -> new Terminal(ValueType.NUMBER), "in", null));

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

    private Palette() {}
}
