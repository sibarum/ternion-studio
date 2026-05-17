package sibarum.ternion.designer;

import sibarum.mcc.graph.CompGraphNode;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Sidecar tracking which mcc nodes are frozen — i.e. have participated
 * in at least one successful training cycle within the current project.
 * Frozen nodes can't be reconfigured in place; the user must delete and
 * re-create to change structural parameters.
 *
 * <p>Process-global by design (matches the identity-keyed sidecar pattern
 * the framework uses everywhere else). The set is cleared by
 * {@code ProjectIo.load} / {@code MainShell.onNew} via {@link #clearAll}
 * and re-populated from the saved {@code designer.json} state.
 */
public final class FrozenNodes {

    private static final Set<CompGraphNode> FROZEN =
        Collections.newSetFromMap(new IdentityHashMap<>());

    private FrozenNodes() {}

    public static synchronized boolean isFrozen(CompGraphNode node) {
        return FROZEN.contains(node);
    }

    public static synchronized boolean isFrozen(NodeSpec spec) {
        return spec != null && FROZEN.contains(spec.cgNode());
    }

    public static synchronized void mark(CompGraphNode node) {
        if (node != null) FROZEN.add(node);
    }

    public static synchronized void markAll(Collection<CompGraphNode> nodes) {
        FROZEN.addAll(nodes);
    }

    public static synchronized void unmark(CompGraphNode node) {
        FROZEN.remove(node);
    }

    public static synchronized void clearAll() {
        FROZEN.clear();
    }
}
