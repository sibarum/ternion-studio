package sibarum.ternion.designer;

import sibarum.dasum.gui.core.graph.ConnectionRule;
import sibarum.dasum.gui.core.graph.Ports;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.SlotSource;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Application-level connection rule installed via
 * {@link ConnectionRule#override}. Two checks:
 * <ol>
 *   <li><b>Same-node block</b> — connections from a node's port to another
 *       port on the same node are not meaningful (a primitive's output
 *       wired back to its own input is a self-loop).</li>
 *   <li><b>Cycle prevention</b> — wiring {@code source → target} is
 *       rejected if {@code target} can already reach {@code source} via
 *       the existing {@link sibarum.mcc.graph.SlotSource} edges. mcc's
 *       {@link sibarum.mcc.graph.ComputationGraph#execute} requires a
 *       DAG; surfacing the error at connection time is friendlier than
 *       at run time.</li>
 * </ol>
 *
 * <p>Type compatibility is handled by dasum's default
 * {@link sibarum.dasum.gui.core.graph.PortTypeCompat#check}: each mcc
 * {@code ValueType} maps to a unique {@code PortType} id (see
 * {@link ValueTypeMapping}), so the default "same id" rule already
 * blocks cross-type drags.
 */
public final class ConnectionRules {

    private final GraphSync sync;

    public ConnectionRules(GraphSync sync) {
        this.sync = sync;
    }

    public void install() {
        ConnectionRule.override((out, in) -> sameNodeOk(out, in) && noCycle(out, in));
    }

    private boolean sameNodeOk(Ports.Port out, Ports.Port in) {
        return out.node() != in.node();
    }

    /**
     * Reject if {@code in}'s owning mcc node already reaches {@code out}'s
     * via existing slot wires. If either endpoint isn't tracked by
     * GraphSync (e.g. the rule fires before the spawn finishes) we err on
     * the side of allowing the drag.
     */
    private boolean noCycle(Ports.Port out, Ports.Port in) {
        PortBinding outBind = sync.bindingOf(out.component());
        PortBinding inBind  = sync.bindingOf(in.component());
        if (outBind == null || inBind == null) return true;
        CompGraphNode source = outBind.owner();
        CompGraphNode target = inBind.owner();
        if (source == target) return false;  // belt-and-suspenders w/ sameNodeOk
        return !reachesFrom(target, source);
    }

    /**
     * Forward-DFS from {@code start} along {@link SlotSource} edges to see
     * if {@code goal} is reachable. Forward direction = "consumers of
     * start's output" — slot wires point upstream, so we have to reverse-
     * walk via the snapshotted graph maintained in {@link GraphSync}.
     */
    private boolean reachesFrom(CompGraphNode start, CompGraphNode goal) {
        Set<CompGraphNode> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CompGraphNode> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            CompGraphNode cur = stack.pop();
            if (!seen.add(cur)) continue;
            if (cur == goal) return true;
            for (CompGraphNode consumer : sync.consumersOf(cur)) {
                if (!seen.contains(consumer)) stack.push(consumer);
            }
        }
        return false;
    }
}
