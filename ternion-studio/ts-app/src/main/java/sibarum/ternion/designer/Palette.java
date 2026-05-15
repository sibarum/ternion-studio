package sibarum.ternion.designer;

import sibarum.dasum.gui.core.render.Color;
import sibarum.mcc.op.Add;
import sibarum.mcc.op.Concat;
import sibarum.mcc.op.CosineSimilarity;
import sibarum.mcc.op.CrossProduct3;
import sibarum.mcc.op.DotProduct;
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
import sibarum.mcc.value.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Registry of all spawnable primitives. Phase 3 ships five entries — the
 * full palette lands in Phase 4. Order in {@link #items} is the order
 * they appear in the spawn context menu.
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
            () -> new Parameter(ValueType.MATRIX, new int[] { 4 }, RNG.nextLong()),
            "value"));

        // ARITHMETIC (componentwise MATRIX×MATRIX → MATRIX unless noted).
        list.add(PaletteItem.binary("arith.add",      "Add",     PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, Add::new, "a", "b", "result"));
        list.add(PaletteItem.binary("arith.sub",      "Sub",     PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, Sub::new, "a", "b", "result"));
        list.add(PaletteItem.binary("arith.mul",      "Mul",     PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, Mul::new, "a", "b", "result"));
        list.add(PaletteItem.binary("arith.dot",      "Dot",     PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, DotProduct::new, "a", "b", "result"));
        list.add(PaletteItem.unary( "arith.magnitude","Magnitude", PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, Magnitude::new, "x", "y"));
        list.add(PaletteItem.unary( "arith.normalize","Normalize", PaletteItem.Category.ARITHMETIC, BG_ARITHMETIC, Normalize::new, "x", "y"));

        // ACTIVATIONS (MATRIX → MATRIX).
        list.add(PaletteItem.unary("act.relu",    "ReLU",    PaletteItem.Category.ACTIVATION, BG_ACTIVATION, Relu::new,    "x", "y"));
        list.add(PaletteItem.unary("act.sigmoid", "Sigmoid", PaletteItem.Category.ACTIVATION, BG_ACTIVATION, Sigmoid::new, "x", "y"));
        list.add(PaletteItem.unary("act.tanh",    "Tanh",    PaletteItem.Category.ACTIVATION, BG_ACTIVATION, Tanh::new,    "x", "y"));
        list.add(PaletteItem.unary("act.softmax", "Softmax", PaletteItem.Category.ACTIVATION, BG_ACTIVATION, Softmax::new, "x", "y"));

        // LINEAR — Linear (MATRIX → MATRIX); default 4 → 4 with bias.
        // Different shapes will be a properties-pane affordance in a later iteration.
        list.add(PaletteItem.unary(
            "linear.linear_4_4", "Linear (4→4)",
            PaletteItem.Category.LINEAR, BG_LINEAR,
            () -> new Linear(4, 4, true, RNG.nextLong()),
            "x", "y"));

        // RESHAPE.
        list.add(PaletteItem.binary("reshape.concat", "Concat", PaletteItem.Category.RESHAPE, BG_RESHAPE, Concat::new, "a", "b", "result"));

        // GEOMETRY / similarity.
        list.add(PaletteItem.binary("geom.cross3",    "Cross (T)",       PaletteItem.Category.GEOMETRY, BG_GEOMETRY, CrossProduct3::new, "a", "b", "result"));
        list.add(PaletteItem.binary("geom.cosine",    "CosineSim",       PaletteItem.Category.GEOMETRY, BG_GEOMETRY, CosineSimilarity::new, "a", "b", "result"));
        list.add(PaletteItem.binary("geom.simgate",   "SimGate (Q)",     PaletteItem.Category.GEOMETRY, BG_GEOMETRY, SimilarityGate::new, "a", "b", "result"));

        // OUTPUT — Terminal variants for the common types.
        list.add(PaletteItem.unary(
            "output.terminal.matrix", "Terminal (MATRIX)",
            PaletteItem.Category.OUTPUT, BG_OUTPUT,
            () -> new Terminal(ValueType.MATRIX), "in", null));
        list.add(PaletteItem.unary(
            "output.terminal.number", "Terminal (NUMBER)",
            PaletteItem.Category.OUTPUT, BG_OUTPUT,
            () -> new Terminal(ValueType.NUMBER), "in", null));

        // CONVERSIONS between MATRIX and the other value types.
        list.add(PaletteItem.unary("conv.ternion_to_vector",  "Ternion → Vector",   PaletteItem.Category.CONVERSION, BG_CONVERSION, TernionToVector::new,    "t", "v"));
        list.add(PaletteItem.unary("conv.vector_to_ternion",  "Vector → Ternion",   PaletteItem.Category.CONVERSION, BG_CONVERSION, VectorToTernion::new,    "v", "t"));
        list.add(PaletteItem.unary("conv.quat_to_vector",     "Quaternion → Vector",PaletteItem.Category.CONVERSION, BG_CONVERSION, QuaternionToVector::new, "q", "v"));
        list.add(PaletteItem.unary("conv.vector_to_quat",     "Vector → Quaternion",PaletteItem.Category.CONVERSION, BG_CONVERSION, VectorToQuaternion::new, "v", "q"));
        list.add(PaletteItem.unary("conv.tensor_to_vector",   "Tensor → Vector",    PaletteItem.Category.CONVERSION, BG_CONVERSION, TensorToVector::new,     "t", "v"));
        list.add(PaletteItem.unary("conv.int_to_vector",      "Int → Vector (vocab=64, dim=8)", PaletteItem.Category.CONVERSION, BG_CONVERSION,
            () -> new IntToVector(64, 8, RNG.nextLong()), "i", "v"));
        list.add(PaletteItem.unary("conv.vector_to_int",      "Vector → Int",       PaletteItem.Category.CONVERSION, BG_CONVERSION, VectorToInt::new,        "v", "i"));
        list.add(PaletteItem.unary("conv.vector_to_tensor",   "Vector → Tensor (shape=[4])", PaletteItem.Category.CONVERSION, BG_CONVERSION,
            () -> new VectorToTensor(new int[] { 4 }), "v", "t"));

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
