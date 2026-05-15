package sibarum.ternion.data;

import sibarum.mcc.value.ValueType;

/**
 * The corpus's declared target ValueType — derived at "Detect inputs"
 * time from the active terminal node's output type. STRING and NUMBER
 * are the only types the MVP corpus editor supports (NUMBER for
 * regression, STRING for symbol output via embeddings — though
 * STRING targets are not yet trainable by mcc-core's MSE-only
 * {@code GraphTrainer}, the surface is still useful for inspection).
 */
public record TargetSchema(ValueType type) {

    public boolean isSupported() {
        return type == ValueType.NUMBER || type == ValueType.STRING;
    }
}
