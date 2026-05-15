package sibarum.ternion.train;

import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.QuaternionValue;
import sibarum.mcc.value.StringValue;
import sibarum.mcc.value.TensorValue;
import sibarum.mcc.value.TernionValue;
import sibarum.mcc.value.Value;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-{@link CompGraphNode} runtime snapshots written by the worker
 * thread after each example and read by the Designer (Phase 8) for
 * value previews and gradient-magnitude tints.
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap}. Note that
 * {@link CompGraphNode}'s {@code equals} is by id and ids are unique
 * per spawn, so the map's value-equality semantics are equivalent to
 * reference-identity for our purposes.
 */
public final class NodeRuntime {

    public record Snapshot(String preview, double gradMagnitude) {}

    private static final Snapshot EMPTY = new Snapshot("", 0.0);

    private final Map<CompGraphNode, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Property<Integer> version = new Property<>(0);
    private double runningMaxGrad = 0.0;

    public Property<Integer> version() { return version; }

    public Snapshot of(CompGraphNode node) {
        Snapshot s = snapshots.get(node);
        return s == null ? EMPTY : s;
    }

    public double runningMaxGrad() {
        return runningMaxGrad;
    }

    /** Worker-thread entry point — called once per node per example. */
    public void update(CompGraphNode node, Value lastOutput, double gradMagnitude) {
        snapshots.put(node, new Snapshot(formatValue(lastOutput), gradMagnitude));
        if (gradMagnitude > runningMaxGrad) {
            runningMaxGrad = gradMagnitude;
        }
    }

    /** Bump version once per training step, after all per-node updates land. */
    public void publish() {
        version.set(version.get() + 1);
        Invalidator.invalidate();
    }

    public void clear() {
        snapshots.clear();
        runningMaxGrad = 0.0;
        publish();
    }

    /**
     * Compact textual preview of a Value — used as the inline label on
     * each Designer node. Long arrays are truncated to the first few
     * components so the preview fits inside a node's body.
     */
    public static String formatValue(Value v) {
        if (v == null) return "";
        return switch (v) {
            case NumberValue n -> "num " + fmt(n.n());
            case StringValue s -> "\"" + ellipsize(s.s(), 12) + "\"";
            case MatrixValue m -> "vec[" + m.data().length + "] " + previewDoubles(m.data());
            case TernionValue t -> "ter " + previewDoubles(t.toArray());
            case QuaternionValue q -> "qua " + previewDoubles(q.toArray());
            case TensorValue te -> "tns" + java.util.Arrays.toString(te.shape()) + " " + previewDoubles(te.data());
            default -> v.toString();
        };
    }

    private static String previewDoubles(double[] data) {
        int n = Math.min(data.length, 3);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fmt(data[i]));
        }
        if (data.length > n) sb.append(", …");
        sb.append("]");
        return sb.toString();
    }

    private static String fmt(double d) {
        return String.format("%.3g", d);
    }

    private static String ellipsize(String s, int n) {
        if (s.length() <= n) return s;
        return s.substring(0, n - 1) + "…";
    }
}
