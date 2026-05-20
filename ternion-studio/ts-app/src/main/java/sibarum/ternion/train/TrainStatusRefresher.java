package sibarum.ternion.train;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.reactive.Property;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Marshals the Train tab's status / parameter text updates from the
 * training worker thread onto the main render thread. Same trick as
 * {@link sibarum.ternion.designer.NodePreviewRefresher}: subscribers
 * to the worker-mutated {@link Property} fields only flip a dirty
 * flag here, and the actual {@link TextStates#setContent} writes
 * happen on the next main-thread render tick.
 *
 * <p>Background: {@link TextStates} is backed by an unsynchronized
 * {@link java.util.IdentityHashMap}. A single concurrent write from the
 * training worker while the renderer is mid-{@code contentOf} corrupts
 * the open-addressed table — subsequent reads can hang in the rehash
 * loop or see ghost entries, which surfaces as renderer freezes, missing
 * connectors, and nodes phasing in/out of existence.
 *
 * <p>The previous wiring subscribed
 * {@code controller.currentLoss().subscribe(v -> TextStates.setContent(..., v))}
 * etc. — that runs the {@code setContent} on whatever thread called
 * {@code Property.set}, which for the loss / example / epoch /
 * lastError / state-on-divergence properties is the training worker.
 * The visualizer's per-step renderer activity widened the race window
 * enough that the corruption became reliable; this class closes it.
 */
public final class TrainStatusRefresher {

    private static final long MIN_INTERVAL_NS = 16_000_000L;  // ~60Hz

    private static final AtomicBoolean DIRTY = new AtomicBoolean(true);
    private static long lastNs = 0L;

    // Set once by install(); subsequent installs replace.
    private static volatile Binding binding;

    public record Binding(
        TrainingController controller,
        Component.Text stateText,
        Component.Text epochText,
        Component.Text exampleText,
        Component.Text lossText,
        Component.Text errorText,
        Component.Text lrEdit,
        Component.Text toggleLabel,
        Function<Float, String> lrFormatter,
        Function<Double, String> lossFormatter,
        Function<TrainingState, String> toggleLabelFormatter,
        Function<String, String> errorFormatter
    ) {}

    private TrainStatusRefresher() {}

    /**
     * Wire up the refresher: subscribe to each observed property so any
     * change (from any thread) marks the refresher dirty, and remember
     * the target Text components for the next refresh.
     */
    public static void install(Binding b) {
        binding = b;
        b.controller().state().subscribe(v -> DIRTY.set(true));
        b.controller().currentEpoch().subscribe(v -> DIRTY.set(true));
        b.controller().currentExample().subscribe(v -> DIRTY.set(true));
        b.controller().currentLoss().subscribe(v -> DIRTY.set(true));
        b.controller().lastError().subscribe(v -> DIRTY.set(true));
        b.controller().learningRate().subscribe(v -> DIRTY.set(true));
        DIRTY.set(true);
    }

    /** Force the next refresh to ignore the throttle. Safe from any thread. */
    public static void forceRefresh() {
        lastNs = 0L;
        DIRTY.set(true);
    }

    /**
     * Main render-thread tick. Throttled to ~60Hz; idempotent. Pulls
     * each {@link Property#get current value} and writes it through
     * {@link TextStates#setContent} on the calling (render) thread.
     */
    public static void refresh() {
        Binding b = binding;
        if (b == null) return;
        if (!DIRTY.get()) return;
        long now = System.nanoTime();
        if (now - lastNs < MIN_INTERVAL_NS) return;
        lastNs = now;
        DIRTY.set(false);

        TrainingState st = b.controller().state().get();
        TextStates.setContent(b.stateText(),   "state: " + st);
        TextStates.setContent(b.toggleLabel(), b.toggleLabelFormatter().apply(st));

        TextStates.setContent(b.epochText(),
            "epoch: "   + b.controller().currentEpoch().get());
        TextStates.setContent(b.exampleText(),
            "example: " + b.controller().currentExample().get());
        TextStates.setContent(b.lossText(),
            "loss: "    + b.lossFormatter().apply(b.controller().currentLoss().get()));
        TextStates.setContent(b.errorText(),
            b.errorFormatter().apply(b.controller().lastError().get()));
        TextStates.setContent(b.lrEdit(),
            b.lrFormatter().apply(b.controller().learningRate().get()));
    }
}
