package sibarum.ternion.designer;

import sibarum.mcc.embedding.AutoTokenizer;
import sibarum.mcc.primitive.Primitive;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.StringValue;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;

import java.util.List;

/**
 * 1-D auto-tokenizer primitive — wraps {@link AutoTokenizer}. Takes a
 * {@link ValueType#STRING} input, emits a {@link ValueType#NUMBER} (the
 * integer id encoded as a double). Vocabulary grows on first sight of
 * each token via the wrapped tokenizer's chosen {@link AutoTokenizer.Mode}.
 *
 * <p>Not {@code Differentiable} — gradients stop at the tokenizer.
 * Downstream embedding primitives (e.g. {@code IntToVector}) propagate
 * backward to their own parameters; the tokenizer itself has no
 * gradient-learnable state.
 *
 * <p>Stateful: the wrapped tokenizer's vocabulary accumulates across
 * forward calls. Vocab is not yet persisted by {@code ProjectIo}; a
 * fresh project session starts with an empty vocab and re-discovers
 * tokens as examples flow through. Delete + re-add the node for a clean
 * vocab without reloading the project.
 */
public final class AutoTokenizerPrimitive implements Primitive {

    private final AutoTokenizer tokenizer;
    private final AutoTokenizer.Mode mode;
    private final int idRange;
    private final long seed;

    public AutoTokenizerPrimitive(AutoTokenizer.Mode mode, int idRange, long seed) {
        if (mode == null) throw new IllegalArgumentException("mode");
        if (idRange <= 0) throw new IllegalArgumentException("idRange must be > 0: " + idRange);
        this.mode = mode;
        this.idRange = idRange;
        this.seed = seed;
        this.tokenizer = new AutoTokenizer(mode, idRange, seed);
    }

    public AutoTokenizer tokenizer() { return tokenizer; }
    public AutoTokenizer.Mode mode() { return mode; }
    public int idRange() { return idRange; }
    public long seed()   { return seed; }

    @Override
    public String name() {
        return "auto-tokenizer-" + mode.name().toLowerCase();
    }

    @Override
    public List<ValueType> inputTypes() {
        return List.of(ValueType.STRING);
    }

    @Override
    public ValueType outputType() {
        return ValueType.NUMBER;
    }

    @Override
    public Value apply(List<Value> inputs) {
        Value v = inputs.getFirst();
        if (!(v instanceof StringValue s)) {
            throw new IllegalArgumentException(
                "AutoTokenizer expects STRING input, got " + v.type());
        }
        return new NumberValue(tokenizer.idFor(s.s()));
    }
}
