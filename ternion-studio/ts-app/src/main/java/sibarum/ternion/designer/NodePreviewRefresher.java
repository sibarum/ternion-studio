package sibarum.ternion.designer;

import sibarum.dasum.gui.core.input.TextStates;
import sibarum.ternion.train.NodeRuntime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Marshals per-node preview-text updates from the training worker thread
 * onto the main render thread.
 *
 * <p>{@link TextStates} is backed by an unsynchronized
 * {@link java.util.IdentityHashMap}, so calling
 * {@link TextStates#setContent} from the training worker while the render
 * thread reads {@link TextStates#contentOf} can corrupt the map's
 * open-addressed table and cause infinite-loop hangs in the renderer.
 * The worker therefore only flips a dirty flag here; the actual updates
 * happen on the main thread via {@link #refresh()} in the event-loop
 * render closure.
 *
 * <p>Singleton-scoped per process — one designer per {@code AppContext}.
 */
public final class NodePreviewRefresher {

    private static final long MIN_INTERVAL_NS = 16_000_000L;  // ~60Hz

    private static final AtomicBoolean DIRTY = new AtomicBoolean(true);
    private static volatile GraphSync sync;
    private static volatile NodeRuntime runtime;
    private static long lastNs = 0L;

    private NodePreviewRefresher() {}

    public static void install(GraphSync s, NodeRuntime r) {
        sync = s;
        runtime = r;
        DIRTY.set(true);
    }

    /** Worker-thread hook: cheap atomic write, safe to call at any rate. */
    public static void markDirty() {
        DIRTY.set(true);
    }

    /** Force the next {@link #refresh} call to ignore the throttle —
     *  used for end-of-training-state transitions so the final frame is
     *  always accurate. Safe from any thread. */
    public static void forceRefresh() {
        lastNs = 0L;
        DIRTY.set(true);
    }

    /** Main render thread tick. Throttled to ~60Hz; idempotent. */
    public static void refresh() {
        GraphSync s = sync;
        NodeRuntime r = runtime;
        if (s == null || r == null) return;
        if (!DIRTY.get()) return;
        long now = System.nanoTime();
        if (now - lastNs < MIN_INTERVAL_NS) return;
        lastNs = now;
        DIRTY.set(false);
        for (NodeSpec spec : s.liveNodes()) {
            NodeRuntime.Snapshot snap = r.of(spec.cgNode());
            TextStates.setContent(spec.previewText(), formatPreview(snap));
        }
    }

    private static String formatPreview(NodeRuntime.Snapshot snap) {
        if (snap == null) return "";
        if ((snap.preview() == null || snap.preview().isEmpty()) && snap.gradMagnitude() == 0.0) {
            return "";
        }
        if (snap.gradMagnitude() == 0.0) return snap.preview();
        return snap.preview() + "  ∇ " + formatGrad(snap.gradMagnitude());
    }

    private static String formatGrad(double mag) {
        if (mag == 0.0) return "0";
        if (mag < 1e-4) return String.format("%.2e", mag);
        if (mag < 10.0) return String.format("%.4f", mag);
        return String.format("%.2f", mag);
    }
}
