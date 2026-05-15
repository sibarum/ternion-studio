package sibarum.ternion.designer;

import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.value.ValueType;

/**
 * Maps a dasum port {@link sibarum.dasum.gui.core.component.Component}
 * to the mcc {@link CompGraphNode} it belongs to, the role it plays
 * (input slot or output), and the {@link ValueType} flowing through.
 *
 * <p>For input ports, {@link #slotIndex} is the index into
 * {@code CompGraphNode.slot(i)}. For output ports it is
 * {@link #OUTPUT_SLOT} ({@code -1}) — there is one output per primitive.
 */
public record PortBinding(CompGraphNode owner, int slotIndex, ValueType type) {

    public static final int OUTPUT_SLOT = -1;

    public boolean isOutput() { return slotIndex == OUTPUT_SLOT; }
    public boolean isInput()  { return slotIndex >= 0; }
}
