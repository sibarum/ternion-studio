package sibarum.ternion.designer;

import sibarum.mcc.graph.CompGraphNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identity-keyed registry of every {@link SourceBound} primitive in
 * the active graph — the merge of the old {@code DatasetColumns}
 * (input columns) and {@code ExpectedOutputNodes} (loss target) under
 * one uniform abstraction.
 *
 * <p>Each entry carries its {@link Role}; the trainer uses this both
 * for the single-source preflight ({@link #distinctSources()} must
 * have size ≤ 1) and the lockstep iteration loop (advance the row
 * index on every binding before each {@code graph.execute()}).
 *
 * <p>Lifecycle mirrors the other graph-side sidecars:
 * {@link #registerIfApplicable} fires from
 * {@link DesignerPanel#afterSpawn}; {@link #unregister} from
 * {@code GraphSync.removeNode} / {@code clearAll}.
 */
public final class DataSourceBoundNodes {

    /** Distinguishes inputs from the loss target — surfaces in error
     *  messages and lets the trainer pick out the loss sink for the
     *  backward seed without re-scanning the graph. */
    public enum Role {
        /** Graph-input column ({@link DatasetColumnPrimitive}). Iterated. */
        INPUT,
        /** Supervisory target ({@link LossOutputPrimitive}). Iterated. */
        LOSS_TARGET,
        /** Key-indexed lookup table (e.g. {@link LookupColumnPrimitive}).
         *  Not iterated — the trainer doesn't drive its row pointer. */
        LOOKUP
    }

    /** One row in the registry. */
    public record Binding(CompGraphNode node, SourceBound primitive, Role role) {
        public String sourceId()    { return primitive.sourceId(); }
        public String columnName()  { return primitive.columnName(); }
    }

    private static final Object LOCK = new Object();
    private static final Map<CompGraphNode, Binding> BY_NODE = new IdentityHashMap<>();

    private DataSourceBoundNodes() {}

    /** Register {@code spec} if its primitive is a {@link SourceBound}. No-op otherwise. */
    public static void registerIfApplicable(NodeSpec spec) {
        if (spec == null) return;
        if (!(spec.tNode().primitive() instanceof SourceBound sb)) return;
        Role role;
        if (spec.tNode().primitive() instanceof LossOutputPrimitive) {
            role = Role.LOSS_TARGET;
        } else if (!sb.iterated()) {
            role = Role.LOOKUP;
        } else {
            role = Role.INPUT;
        }
        synchronized (LOCK) {
            BY_NODE.put(spec.cgNode(), new Binding(spec.cgNode(), sb, role));
        }
    }

    /** Drop {@code spec}'s registration. Safe on any node. */
    public static void unregister(NodeSpec spec) {
        if (spec == null) return;
        synchronized (LOCK) {
            BY_NODE.remove(spec.cgNode());
        }
    }

    /** Lookup the binding for a given graph node, or {@code null}. */
    public static Binding of(CompGraphNode node) {
        synchronized (LOCK) { return BY_NODE.get(node); }
    }

    /** Snapshot of every binding (inputs + loss target). Iteration-safe. */
    public static List<Binding> all() {
        synchronized (LOCK) { return List.copyOf(BY_NODE.values()); }
    }

    /** Subset filtered by role. */
    public static List<Binding> byRole(Role role) {
        synchronized (LOCK) {
            List<Binding> out = new ArrayList<>();
            for (Binding b : BY_NODE.values()) {
                if (b.role() == role) out.add(b);
            }
            return out;
        }
    }

    /** The distinct {@code sourceId}s referenced across every binding —
     *  iterated AND lookup. Used by source-removal preflight to refuse
     *  dropping a source that any graph node still references. */
    public static Set<String> distinctSources() {
        synchronized (LOCK) {
            Set<String> out = new LinkedHashSet<>();
            for (Binding b : BY_NODE.values()) out.add(b.sourceId());
            return out;
        }
    }

    /** Like {@link #distinctSources()} but only iterated roles
     *  (INPUT + LOSS_TARGET). Lookup tables are excluded — they
     *  random-access their source and don't constrain the trainer's
     *  single-source iteration rule. */
    public static Set<String> distinctIteratedSources() {
        synchronized (LOCK) {
            Set<String> out = new LinkedHashSet<>();
            for (Binding b : BY_NODE.values()) {
                if (b.primitive().iterated()) out.add(b.sourceId());
            }
            return out;
        }
    }

    /** Whether any binding exists (the trainer uses this to pick its
     *  iteration path: registry-driven vs legacy corpus). */
    public static boolean any() {
        synchronized (LOCK) { return !BY_NODE.isEmpty(); }
    }

    /** Test-only: wipe every registration. Production code should rely
     *  on {@link GraphSync#clearAll} / {@code removeNode} to unregister
     *  individual specs; tests that spin up multiple AppContexts in the
     *  same JVM need a static reset since the GC'd nodes from the
     *  previous test's GraphSync linger in {@link #BY_NODE}. */
    public static void clearForTesting() {
        synchronized (LOCK) { BY_NODE.clear(); }
    }

    /** Locate the loss-target node, or {@code null} if the graph has no
     *  {@link LossOutputPrimitive} bound. There should be at most one in
     *  V1 (the palette only spawns a single Loss Output per graph). */
    public static CompGraphNode findLossTarget() {
        synchronized (LOCK) {
            for (Binding b : BY_NODE.values()) {
                if (b.role() == Role.LOSS_TARGET) return b.node();
            }
            return null;
        }
    }
}
