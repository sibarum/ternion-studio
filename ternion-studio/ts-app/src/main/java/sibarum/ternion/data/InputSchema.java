package sibarum.ternion.data;

import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.value.ValueType;

/**
 * One declared input column in the corpus. Bound to a specific
 * {@link CompGraphNode}'s unwired input slot at "Detect inputs" time —
 * the trainer's root binder uses {@link #key()} to look up
 * {@code (node, slot)} when binding values to graph roots.
 */
public record InputSchema(String key, CompGraphNode node, int slotIndex, ValueType type, String displayLabel) {

    /** Stable name used both as the {@link sibarum.mcc.training.Example} inputs-map key
     *  and the column header. */
    public static String makeKey(CompGraphNode node, int slotIndex) {
        return node.id() + "#" + slotIndex;
    }
}
