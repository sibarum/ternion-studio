package sibarum.ternion.designer;

import sibarum.mcc.embedding.AutoTokenizer2D;
import sibarum.mcc.embedding.TokenCoord;
import sibarum.mcc.primitive.Primitive;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.StringValue;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;

import java.util.List;

/**
 * 2-D auto-tokenizer primitive — wraps {@link AutoTokenizer2D}. Takes a
 * {@link ValueType#STRING} input, emits a {@link ValueType#MATRIX} of
 * length 2 carrying the {@link TokenCoord}'s {@code (x, y)} as doubles.
 * Vocabulary grows on first sight of each token via the wrapped
 * tokenizer's chosen {@link AutoTokenizer2D.Mode}.
 *
 * <p>Not {@code Differentiable} — gradients stop at the tokenizer.
 * Pairs naturally with a 2-D embedding lookup (or any consumer that
 * uses the coord as a structural index).
 *
 * <p>Stateful, same caveats as {@link AutoTokenizerPrimitive}.
 */
public final class AutoTokenizer2DPrimitive implements Primitive {

    private final AutoTokenizer2D tokenizer;
    private final AutoTokenizer2D.Mode mode;
    private final int range;
    private final long seed;

    public AutoTokenizer2DPrimitive(AutoTokenizer2D.Mode mode, int range, long seed) {
        if (mode == null) throw new IllegalArgumentException("mode");
        if (range <= 0) throw new IllegalArgumentException("range must be > 0: " + range);
        this.mode = mode;
        this.range = range;
        this.seed = seed;
        this.tokenizer = new AutoTokenizer2D(mode, range, seed);
    }

    public AutoTokenizer2D tokenizer() { return tokenizer; }
    public AutoTokenizer2D.Mode mode() { return mode; }
    public int range() { return range; }
    public long seed() { return seed; }

    @Override
    public String name() {
        return "auto-tokenizer-2d-" + mode.name().toLowerCase();
    }

    @Override
    public List<ValueType> inputTypes() {
        return List.of(ValueType.STRING);
    }

    @Override
    public ValueType outputType() {
        return ValueType.MATRIX;
    }

    @Override
    public Value apply(List<Value> inputs) {
        Value v = inputs.getFirst();
        if (!(v instanceof StringValue s)) {
            throw new IllegalArgumentException(
                "AutoTokenizer2D expects STRING input, got " + v.type());
        }
        TokenCoord c = tokenizer.coordFor(s.s());
        return new MatrixValue(new double[] { c.x(), c.y() });
    }
}
