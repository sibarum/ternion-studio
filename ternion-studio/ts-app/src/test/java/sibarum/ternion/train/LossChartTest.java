package sibarum.ternion.train;

import org.junit.jupiter.api.Test;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.DynamicChildren;
import sibarum.dasum.gui.core.render.Color;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.CorpusModel;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.designer.Palette;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the chart's build path + bar-count update on appended
 * samples. Bypasses the 60Hz throttle via {@link LossChart#resetThrottle}
 * + the state-change force path (state subscriber triggers force=true
 * on the controller's initial state).
 */
final class LossChartTest {

    @Test
    void chartBuildsAndUpdatesWithSamples() {
        // Minimal AppContext — a NUMBER Terminal so the controller's
        // initial findTerminal returns something (not strictly needed for
        // the chart but keeps the controller's nextExample sane).
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();
        sync.spawn(Palette.byKey("output.terminal.number"), 0f, 0f);

        AppContext ctx = new AppContext(surface, sync, new CorpusModel());
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        LossChart.resetThrottle();
        Component chart = LossChart.build(controller);

        // The chart is a Flex(COLUMN) [header Text, chartArea Flex].
        assertTrue(chart instanceof Component.Flex);
        Component.Flex outer = (Component.Flex) chart;
        assertEquals(2, outer.children().size(), "chart has header + area");
        Component chartArea = outer.children().get(1);
        assertTrue(chartArea instanceof Component.Flex);

        // Initial state: no bars.
        assertEquals(0, DynamicChildren.added(chartArea).size(),
            "no bars before any samples");

        // Append samples. The subscriber fires on the same thread,
        // throttled to ~60Hz; reset throttle before each append for
        // deterministic behavior in tests.
        LossChart.resetThrottle();
        controller.lossHistory().append(0.5);
        LossChart.resetThrottle();
        controller.lossHistory().append(0.3);
        LossChart.resetThrottle();
        controller.lossHistory().append(0.1);

        // After 3 appends, expect 3 bars.
        assertEquals(3, DynamicChildren.added(chartArea).size(),
            "one bar per appended sample");

        // Bars are Boxes — sanity-check the layout type.
        for (Component bar : DynamicChildren.added(chartArea)) {
            assertNotNull(bar);
            assertTrue(bar instanceof Component.Box);
        }
    }
}
