package sibarum.ternion.designer;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Components;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.graph.Connection;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.graph.GraphSurfaceChildren;
import sibarum.dasum.gui.core.graph.GraphSurfacePositions;
import sibarum.dasum.gui.core.graph.Ports;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.ComputationGraph;
import sibarum.mcc.graph.SlotSource;
import sibarum.mcc.graph.substrate.TransformationEdge;
import sibarum.mcc.primitive.Primitive;
import sibarum.mcc.primitive.Trainable;
import sibarum.ternion.designer.config.ConfigField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges the dasum visual graph (a {@link Component.GraphSurface} with
 * spawned node Components, declared {@link sibarum.dasum.gui.core.graph.Ports},
 * and {@link Connections}) to the mcc model (a list of
 * {@link CompGraphNode}s with wired slots).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Construct {@code GraphSync} once per Designer panel, passing the
 *       host {@link Component.GraphSurface}.</li>
 *   <li>Call {@link #install()} to register the dasum
 *       {@link Connections#addListener connection listener} and the
 *       {@link ConnectionRules} (cycle + same-node) hooks.</li>
 *   <li>Call {@link #spawn} to drop a fresh palette item onto the
 *       surface — that handles position, dynamic-children registration,
 *       and internal bookkeeping.</li>
 *   <li>Call {@link #removeNode} to delete a node — that runs
 *       {@link Components#detach}, drops every connection incident on it
 *       (cascade-fires REMOVED events that walk back through the
 *       listener), and drops the spec.</li>
 *   <li>Call {@link #snapshot(CompGraphNode)} to materialize a fresh
 *       {@link ComputationGraph} for execution / training.</li>
 * </ol>
 *
 * <p>Identity-keyed throughout — uses {@code IdentityHashMap} so two
 * structurally-equal {@link sibarum.dasum.gui.core.component.Component}
 * records don't collide.
 */
public final class GraphSync {

    private final Component.GraphSurface surface;
    private final AtomicLong idCounter = new AtomicLong();

    /** Visual node Component → spec. Order preserved by separate insertion list. */
    private final Map<Component, NodeSpec> nodes = new IdentityHashMap<>();
    /** Insertion-ordered visual nodes, for stable iteration in {@link #snapshot}. */
    private final List<Component> nodeOrder = new ArrayList<>();
    /** Port Component → binding (covers ports across every node). */
    private final Map<Component, PortBinding> portBindings = new IdentityHashMap<>();

    public GraphSync(Component.GraphSurface surface) {
        this.surface = surface;
    }

    /** Install dasum-side hooks (connection listener + rule). Call once at startup. */
    public void install() {
        new ConnectionRules(this).install();
        Connections.addListener(this::onConnectionEvent);
    }

    public Component.GraphSurface surface() { return surface; }

    /** @return spec for {@code visualNode}, or {@code null} if not tracked. */
    public NodeSpec specOf(Component visualNode) {
        return nodes.get(visualNode);
    }

    /** @return binding for {@code port}, or {@code null} if it isn't a port we know. */
    public PortBinding bindingOf(Component port) {
        return portBindings.get(port);
    }

    /** @return spec that owns {@code port}, or {@code null} if unknown. */
    public NodeSpec specForPort(Component port) {
        PortBinding b = portBindings.get(port);
        if (b == null) return null;
        for (NodeSpec spec : nodes.values()) {
            if (spec.cgNode() == b.owner()) return spec;
        }
        return null;
    }

    /** Insertion-ordered live nodes. */
    public List<NodeSpec> liveNodes() {
        List<NodeSpec> out = new ArrayList<>(nodeOrder.size());
        for (Component c : nodeOrder) {
            NodeSpec spec = nodes.get(c);
            if (spec != null) out.add(spec);
        }
        return out;
    }

    /**
     * Forward consumers of {@code node}: every {@link CompGraphNode}
     * whose any wired slot points back at {@code node}. Used by
     * {@link ConnectionRules} cycle detection.
     */
    public List<CompGraphNode> consumersOf(CompGraphNode node) {
        List<CompGraphNode> out = new ArrayList<>();
        for (NodeSpec spec : nodes.values()) {
            CompGraphNode c = spec.cgNode();
            if (c == node) continue;
            for (int i = 0; i < c.slotCount(); i++) {
                SlotSource s = c.slot(i);
                if (s != null && s.source() == node) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Spawn a palette item with the given config (or
     * {@link ConfigField#defaultsOf} when the user accepted defaults).
     */
    public NodeSpec spawn(PaletteItem item, float emX, float emY, Map<String, Object> config) {
        String nodeId = item.key() + "#" + idCounter.incrementAndGet();
        return spawnWith(item, emX, emY, nodeId, config);
    }

    /** Convenience: spawn with the schema's defaults. */
    public NodeSpec spawn(PaletteItem item, float emX, float emY) {
        return spawn(item, emX, emY, ConfigField.defaultsOf(item.configSchema()));
    }

    /**
     * Spawn with an explicit id — used by {@code ProjectIo.load} to
     * preserve saved node ids so edges + parameter manifests line up
     * across save/restore. Bumps the internal counter past any numeric
     * suffix in the id so subsequent auto-spawns won't collide.
     */
    public NodeSpec spawn(PaletteItem item, float emX, float emY,
                          Map<String, Object> config, String explicitId) {
        int hash = explicitId.lastIndexOf('#');
        if (hash >= 0) {
            try {
                long n = Long.parseLong(explicitId.substring(hash + 1));
                while (idCounter.get() < n) idCounter.incrementAndGet();
            } catch (NumberFormatException ignored) {}
        }
        return spawnWith(item, emX, emY, explicitId, config);
    }

    private NodeSpec spawnWith(PaletteItem item, float emX, float emY,
                               String id, Map<String, Object> config) {
        NodeSpec spec = item.spawn(id, config);
        GraphSurfacePositions.set(surface, spec.visual(), emX, emY);
        GraphSurfaceChildren.add(surface, spec.visual());
        nodes.put(spec.visual(), spec);
        nodeOrder.add(spec.visual());
        for (Map.Entry<Component, PortBinding> e : spec.portBindings().entrySet()) {
            portBindings.put(e.getKey(), e.getValue());
        }
        return spec;
    }

    /**
     * Detach every spawned node from the surface and clear all
     * GraphSync bookkeeping. Used by New / Open project flows to wipe
     * the slate before populating with fresh state.
     */
    public void clearAll() {
        List<Component> snapshot = new ArrayList<>(nodeOrder);
        for (Component v : snapshot) {
            Components.detach(v);
        }
        nodes.clear();
        nodeOrder.clear();
        portBindings.clear();
        FrozenNodes.clearAll();
        FrozenBadge.clearAll();
        Invalidator.invalidate();
    }

    /**
     * Detach {@code visualNode} from the surface and clear its mcc
     * footprint. Connection sidecar entries incident on its ports are
     * cleared by {@link Components#detach} (which cascades into
     * {@link Connections#clear}); each cascaded REMOVED event flows
     * through {@link #onConnectionEvent} and clears the matching mcc
     * slot. Then the spec / port-binding entries are dropped.
     */
    public void removeNode(Component visualNode) {
        NodeSpec spec = nodes.get(visualNode);
        if (spec == null) return;
        // Detach first so connection REMOVED events fire while the spec
        // is still in our maps and onConnectionEvent can resolve slots.
        Components.detach(visualNode);
        // Now remove from internal bookkeeping.
        nodes.remove(visualNode);
        nodeOrder.remove(visualNode);
        for (Component port : spec.portBindings().keySet()) {
            portBindings.remove(port);
        }
        FrozenNodes.unmark(spec.cgNode());
        Invalidator.invalidate();
    }

    /**
     * Build a snapshot {@link ComputationGraph} for execution.
     * {@code terminalNode} must be one of the spawned mcc nodes (typically
     * a {@code Terminal} primitive). The returned graph is independent
     * of further edits to the GraphSync.
     */
    public ComputationGraph snapshot(CompGraphNode terminalNode) {
        List<CompGraphNode> all = new ArrayList<>(nodeOrder.size());
        for (Component c : nodeOrder) {
            NodeSpec s = nodes.get(c);
            if (s != null) all.add(s.cgNode());
        }
        return new ComputationGraph(all, terminalNode);
    }

    /** @return immutable view of all known port-bindings (test/debug only). */
    public Set<Map.Entry<Component, PortBinding>> bindings() {
        return Collections.unmodifiableMap(portBindings).entrySet();
    }

    /**
     * Reinitialize every {@link Trainable}'s parameters from a fresh
     * random seed and unfreeze every node. Topology, connections, corpus,
     * and per-node config are preserved. Use after divergence (NaN
     * weights) to recover without rebuilding the graph by hand.
     *
     * @return summary of (reinitialized, skipped) trainables; skipped means
     *         the primitive's class hasn't implemented {@link Trainable#reinitialize}.
     */
    public ResetSummary resetTraining(long baseSeed) {
        int reset = 0;
        List<String> skipped = new ArrayList<>();
        int idx = 0;
        for (NodeSpec spec : liveNodes()) {
            Primitive p = spec.tNode().primitive();
            if (p instanceof Trainable t) {
                try {
                    // Stir the seed so two same-shape Linears don't end up
                    // with identical weights.
                    t.reinitialize(baseSeed ^ ((long) idx * 0x9E3779B97F4A7C15L));
                    reset++;
                } catch (UnsupportedOperationException ex) {
                    skipped.add(spec.cgNode().id() + " (" + p.name() + ")");
                }
                idx++;
            }
            FrozenNodes.unmark(spec.cgNode());
        }
        Invalidator.invalidate();
        return new ResetSummary(reset, List.copyOf(skipped));
    }

    public record ResetSummary(int reinitialized, List<String> skipped) {}

    /**
     * Replace a node's primitive with a fresh instance built from the
     * same palette item and the given new config. Preserves the node id,
     * position, and any connections whose port name still exists on the
     * new visual. Used by the Properties dialog on unfrozen nodes.
     *
     * <p>Connections that can't be re-established (matching port name no
     * longer exists on the new node) are silently dropped; the count of
     * dropped + preserved is returned in {@link ReplaceSummary}. The
     * caller is responsible for refusing this call on frozen nodes — by
     * design, frozen nodes can only be reconfigured via delete + re-add.
     *
     * @return the new NodeSpec replacing {@code oldSpec}
     */
    public ReplaceResult replaceConfig(NodeSpec oldSpec, Map<String, Object> newConfig) {
        PaletteItem item = Palette.byKey(oldSpec.paletteKey());
        if (item == null) {
            throw new IllegalStateException("Palette item not found: " + oldSpec.paletteKey());
        }
        GraphSurfacePositions.Pos pos = GraphSurfacePositions.of(surface, oldSpec.visual());
        float emX = pos.emX();
        float emY = pos.emY();
        String id = oldSpec.cgNode().id();

        List<CapturedEdge> captured = captureEdgesByName(oldSpec);

        removeNode(oldSpec.visual());
        NodeSpec newSpec = spawn(item, emX, emY, newConfig, id);

        int preserved = 0;
        List<String> dropped = new ArrayList<>();
        for (CapturedEdge e : captured) {
            NodeSpec otherSpec = specForId(e.otherNodeId());
            if (otherSpec == null) {
                dropped.add(e.thisPortName() + " ↔ " + e.otherNodeId() + ":" + e.otherPortName() + " (other node gone)");
                continue;
            }
            Ports.Port otherPort = Ports.byName(otherSpec.visual(), e.otherPortName());
            Ports.Port thisPort  = Ports.byName(newSpec.visual(), e.thisPortName());
            if (otherPort == null || thisPort == null) {
                dropped.add(e.thisPortName() + " ↔ " + e.otherNodeId() + ":" + e.otherPortName() + " (port no longer exists)");
                continue;
            }
            try {
                Connections.add(surface, otherPort.component(), thisPort.component());
                preserved++;
            } catch (RuntimeException ex) {
                dropped.add(e.thisPortName() + " ↔ " + e.otherNodeId() + ":" + e.otherPortName() + " (" + ex.getMessage() + ")");
            }
        }
        Invalidator.invalidate();
        return new ReplaceResult(newSpec, preserved, List.copyOf(dropped));
    }

    public record ReplaceResult(NodeSpec newSpec, int connectionsPreserved, List<String> connectionsDropped) {}

    private record CapturedEdge(String thisPortName, String otherNodeId, String otherPortName) {}

    private List<CapturedEdge> captureEdgesByName(NodeSpec spec) {
        List<CapturedEdge> out = new ArrayList<>();
        for (Connection conn : Connections.on(surface)) {
            Component from = conn.from();
            Component to   = conn.to();
            String myName;
            Component otherPort;
            String fromName = spec.portNameOf(from);
            String toName   = spec.portNameOf(to);
            if (fromName != null && toName == null) {
                myName = fromName;
                otherPort = to;
            } else if (toName != null && fromName == null) {
                myName = toName;
                otherPort = from;
            } else {
                continue;  // neither end is on this spec, or both are (self-loop — shouldn't happen)
            }
            NodeSpec otherSpec = specForPort(otherPort);
            if (otherSpec == null) continue;
            String otherName = otherSpec.portNameOf(otherPort);
            if (otherName == null) continue;
            out.add(new CapturedEdge(myName, otherSpec.cgNode().id(), otherName));
        }
        return out;
    }

    private NodeSpec specForId(String nodeId) {
        for (NodeSpec spec : nodes.values()) {
            if (spec.cgNode().id().equals(nodeId)) return spec;
        }
        return null;
    }

    // ---------- listener ----------

    private void onConnectionEvent(Connections.Event ev) {
        if (ev.surface() != surface) return;  // not our surface
        PortBinding fromBind = portBindings.get(ev.connection().from());
        PortBinding toBind   = portBindings.get(ev.connection().to());
        if (fromBind == null || toBind == null) return;

        CompGraphNode source = fromBind.owner();
        CompGraphNode target = toBind.owner();
        int slot = toBind.slotIndex();
        if (slot < 0) return;  // 'to' should be the input side; defensive.

        switch (ev.kind()) {
            case ADDED -> {
                // Build a one-shot TransformationEdge for this wire. It's
                // local to the snapshot — substrate-wide stats aren't
                // shared across edges since we're doing manual composition.
                TransformationEdge edge = new TransformationEdge(
                    source.tNode(), target.tNode());
                try {
                    target.wire(slot, new SlotSource(source, edge));
                } catch (IllegalStateException ignored) {
                    // Slot already wired — dasum allowed a second drag onto an
                    // input that's already taken. Surface this as a no-op for
                    // now; richer UX is a future polish task.
                }
            }
            case REMOVED -> {
                SlotSource current = target.slot(slot);
                if (current != null && current.source() == source) {
                    target.clearSlot(slot);
                }
            }
        }
    }
}
