package sibarum.ternion.designer;

import sibarum.dasum.gui.core.component.Component;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.substrate.TransformationNode;

import java.util.List;
import java.util.Map;

/**
 * One spawned node in the designer: its visual component, its mcc
 * {@link CompGraphNode}, the {@link TransformationNode} wrapping its
 * primitive, the palette key it was spawned from (for save/load), and
 * its declared port bindings keyed by port component.
 *
 * <p>{@link #previewText} is a stable {@link Component.Text} attached
 * as a dynamic child of {@link #visual}. The training worker updates
 * it via {@link sibarum.dasum.gui.core.input.TextStates#setContent}
 * after each example so the user sees the node's most-recent produced
 * value and gradient magnitude inline.
 *
 * <p>Created by a {@link PaletteItem}'s factory in
 * {@link GraphSync#spawn(PaletteItem, float, float)}.
 */
public record NodeSpec(
    String paletteKey,
    Map<String, Object> config,          // schema-resolved config used to build this primitive
    Component visual,
    TransformationNode tNode,
    CompGraphNode cgNode,
    Map<Component, PortBinding> portBindings,
    List<Component> orderedInputPorts,   // index = slot index
    List<String> inputLabels,            // parallel to orderedInputPorts
    Component outputPort,                // null if the primitive has no observable output port
    String outputLabel,                  // null when outputPort is null
    Component.Text previewText           // stable, in-place updatable via TextStates
) {
    /** Look up a port's declared label, or {@code null} if {@code port} isn't on this spec. */
    public String portNameOf(Component port) {
        if (port == null) return null;
        for (int i = 0; i < orderedInputPorts.size(); i++) {
            if (orderedInputPorts.get(i) == port) return inputLabels.get(i);
        }
        if (port == outputPort) return outputLabel;
        return null;
    }
}
