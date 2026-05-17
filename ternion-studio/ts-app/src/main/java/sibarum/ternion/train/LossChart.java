package sibarum.ternion.train;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.DynamicChildren;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.text.FontGroups;

import java.util.List;

/**
 * Live loss chart bound to a {@link LossHistory}. Visual: a row of
 * fixed-width vertical bars (one per sample), aligned to the bottom of
 * the chart area, height proportional to the sample's loss relative to
 * the rolling max. The header text shows the current loss + sample
 * count and updates via {@link TextStates#setContent} so its identity
 * stays stable across rebuilds.
 *
 * <p>Bars are managed via {@link DynamicChildren} so the chart's outer
 * Components keep stable identity — no risk of orphaning state on
 * rebuild and re-render is cheap.
 *
 * <p>Rebuilds are throttled to ~60Hz so worker-thread training (which
 * can fire {@code LossHistory.append} thousands of times per second on
 * small graphs) doesn't bottleneck on chart-DOM work. The chart also
 * rebuilds on training-state changes so the final post-stop frame is
 * always accurate.
 */
public final class LossChart {

    private static final Em CHART_HEIGHT       = Em.of(8.5f);
    private static final Em CHART_PADDING      = Em.of(0.4f);
    /** Width of one bar at the smallest history size we render. Adaptive
     *  below this when the bar count is large enough that fitting them
     *  all in the chart's interior matters. */
    private static final float BAR_WIDTH_EM_MAX = 0.5f;
    private static final float BAR_WIDTH_EM_MIN = 0.06f;

    private static final Color CHART_BG   = new Color(0.05f, 0.07f, 0.10f, 1f);
    private static final Color BAR_COLOR  = new Color(0.30f, 0.65f, 0.85f, 1f);
    private static final Color LABEL_FG   = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color HINT_FG    = new Color(0.65f, 0.70f, 0.78f, 0.85f);
    private static final Color TRANSPARENT = new Color(0f, 0f, 0f, 0f);

    private static final long MIN_REBUILD_INTERVAL_NS = 16_000_000L; // ~60Hz

    /** Per-chart throttle state. Tracked separately for each chart since
     *  in principle multiple charts could coexist. Stored on the
     *  controller-history pair via the LossHistory identity. */
    private static final java.util.IdentityHashMap<LossHistory, long[]> LAST_REBUILD = new java.util.IdentityHashMap<>();

    private LossChart() {}

    public static Component build(TrainingController controller) {
        LossHistory history = controller.lossHistory();

        Component.Text header = new Component.Text(
            "Loss — no data yet",
            FontGroups.DEFAULT, Em.of(0.95f), LABEL_FG,
            null, null, Em.ZERO, null, false,
            false, false, false, false, 0);

        // Chart area — DynamicChildren holds the bars; declared empty.
        // AlignItems.END so bars hang from the bottom.
        Component.Flex chartArea = new Component.Flex(
            null, CHART_HEIGHT, CHART_PADDING, CHART_BG,
            Direction.ROW, JustifyContent.START, AlignItems.END, Em.ZERO,
            List.of(), false, 0);

        // Initial render — covers the case where history already has data
        // (e.g. chart rebuilt after panel switch).
        rebuild(history, chartArea, header, /*force*/ true);

        history.version().subscribe(v ->
            rebuild(history, chartArea, header, /*force*/ false));
        // Force-refresh on training-state transitions so post-stop frame
        // reflects the final loss state (no missed last-bump from throttle).
        controller.state().subscribe(s ->
            rebuild(history, chartArea, header, /*force*/ true));

        return new Component.Flex(
            null, null, Em.of(0.3f), TRANSPARENT,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.4f),
            List.of(header, chartArea),
            false, 1);
    }

    private static void rebuild(LossHistory history, Component.Flex chartArea,
                                Component.Text header, boolean force) {
        long now = System.nanoTime();
        long[] last = LAST_REBUILD.computeIfAbsent(history, k -> new long[1]);
        if (!force && now - last[0] < MIN_REBUILD_INTERVAL_NS) return;
        last[0] = now;

        List<LossHistory.Sample> samples = history.snapshot();

        if (samples.isEmpty()) {
            TextStates.setContent(header, "Loss — no data yet");
            DynamicChildren.clearChildren(chartArea);
            Invalidator.invalidate();
            return;
        }

        double maxLoss = history.rollingMax();
        if (maxLoss <= 0.0) maxLoss = 1.0;  // all-zero loss → flat bars at 0 height
        double lastLoss = samples.get(samples.size() - 1).loss();

        TextStates.setContent(header,
            String.format("Loss — current: %.5f · max in window: %.5f · samples: %d",
                lastLoss, maxLoss, samples.size()));

        float barWidthEm = pickBarWidth(samples.size());
        float chartHeightEm = CHART_HEIGHT.value() - 2f * CHART_PADDING.value();

        DynamicChildren.clearChildren(chartArea);
        for (LossHistory.Sample s : samples) {
            float frac = (float) Math.min(1.0, Math.max(0.0, s.loss() / maxLoss));
            float heightEm = Math.max(0.02f, chartHeightEm * frac);
            DynamicChildren.add(chartArea, new Component.Box(
                Em.of(barWidthEm), Em.of(heightEm), Em.ZERO, BAR_COLOR));
        }
        Invalidator.invalidate();
    }

    private static float pickBarWidth(int sampleCount) {
        // Aim to fit visibly within roughly a 36em-wide chart area without
        // overflowing. Clamp to a readable minimum.
        float chartWidthEm = 36f;
        float fair = chartWidthEm / Math.max(1, sampleCount);
        return Math.min(BAR_WIDTH_EM_MAX, Math.max(BAR_WIDTH_EM_MIN, fair));
    }

    /** Test/dev helper — drop cached throttle state. Not called by
     *  production code. */
    static void resetThrottle() {
        LAST_REBUILD.clear();
    }
}
