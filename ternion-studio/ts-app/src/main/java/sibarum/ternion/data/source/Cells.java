package sibarum.ternion.data.source;

import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.StringValue;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;

/**
 * Centralized cell parsing for {@link DataSource} values. Replaces
 * the per-primitive {@code parseFloats}/{@code parse} triples that
 * had drifted between {@code DatasetColumnPrimitive},
 * {@code LookupColumnPrimitive}, {@code LossOutputPrimitive}, and
 * {@code CorpusModel}.
 *
 * <p>All ts-app primitives that read a {@link DataSource} cell should
 * route through {@link #parse(ValueType, String)} or
 * {@link #parseFloats(String)} so a future format tweak (e.g.
 * accepting hex floats, supporting bracketed vectors) lands in one
 * place.
 */
public final class Cells {

    private Cells() {}

    /**
     * Parse {@code cell} into a {@link Value} of {@code type}. Null /
     * blank cells produce a sensible zero/empty value rather than
     * throwing — embedding tables and training data both tolerate
     * sparse cells better than they tolerate spurious exceptions
     * mid-step.
     */
    public static Value parse(ValueType type, String cell) {
        if (cell == null) cell = "";
        return switch (type) {
            case STRING -> new StringValue(cell);
            case NUMBER -> new NumberValue(
                cell.isBlank() ? 0.0 : Double.parseDouble(cell.trim()));
            case MATRIX -> new MatrixValue(parseFloats(cell));
            default -> throw new IllegalArgumentException(
                "Cells.parse doesn't yet support ValueType " + type
                + " — supported: STRING, NUMBER, MATRIX");
        };
    }

    /** Zero/empty value of {@code type}. Used for OOV lookups + sparse-cell defaults. */
    public static Value zeroFor(ValueType type) {
        return switch (type) {
            case STRING -> new StringValue("");
            case NUMBER -> new NumberValue(0.0);
            case MATRIX -> new MatrixValue(new double[0]);
            default -> throw new IllegalArgumentException(
                "Cells.zeroFor doesn't yet support ValueType " + type);
        };
    }

    /**
     * Parse a vector cell into a {@code double[]}. Accepts
     * comma-separated and/or whitespace-separated formats — both
     * {@code "1, 2, 3, 4"} and {@code "1 2 3 4"} produce the same
     * array. Bracketed forms ({@code [..]}, {@code (..)}, <code>{..}</code>)
     * are stripped before splitting so values copied from numpy /
     * Python REPL output also work.
     */
    public static double[] parseFloats(String raw) {
        if (raw == null) return new double[0];
        String s = raw.trim();
        if (s.isEmpty()) return new double[0];
        if ((s.startsWith("[") && s.endsWith("]"))
            || (s.startsWith("(") && s.endsWith(")"))
            || (s.startsWith("{") && s.endsWith("}"))) {
            s = s.substring(1, s.length() - 1).trim();
            if (s.isEmpty()) return new double[0];
        }
        String[] parts = s.split("[\\s,]+");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(
                    "vector entry " + i + " not a number: '" + parts[i]
                    + "' (use comma- or space-separated numbers, e.g. \"1, 2, 3, 4\")");
            }
        }
        return out;
    }
}
