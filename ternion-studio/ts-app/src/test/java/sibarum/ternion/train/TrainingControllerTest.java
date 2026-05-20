package sibarum.ternion.train;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.render.Color;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.source.EditableDataSource;
import sibarum.ternion.designer.DataSourceBoundNodes;
import sibarum.ternion.designer.DesignerPanel;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.designer.NodeSpec;
import sibarum.ternion.designer.Palette;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase C training-controller contract on the LossOutput-driven path:
 * empty-source preflight, identity-regression plumbing run, and
 * pause/step/resume/stop state machine. All three exercise the new
 * single-source iteration loop and the {@link DataSourceBoundNodes}
 * sidecar; the legacy Terminal + corpus path is gone.
 */
final class TrainingControllerTest {

    @BeforeEach
    void resetSidecar() {
        DataSourceBoundNodes.clearForTesting();
    }

    @Test
    void emptySource_preflightBlocks() {
        // LossOutput bound to a source with zero rows → preflight refuses
        // to start; state stays IDLE; lastError mentions the empty source.
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        EditableDataSource src = new EditableDataSource(
            "empty_fixture", "Empty", List.of("target"));
        ctx.dataSources().add(src);

        NodeSpec param = sync.spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        NodeSpec loss = sync.spawn(Palette.byKey("output.loss"), 6f, 0f, lossCfg("empty_fixture:target"));
        DesignerPanel.afterSpawn(sync, param);
        DesignerPanel.afterSpawn(sync, loss);
        Connections.add(surface, param.outputPort(), loss.orderedInputPorts().get(0));

        controller.start();
        assertEquals(TrainingState.IDLE, controller.state().get(),
            "preflight blocks start when source is empty");
        assertTrue(controller.lastError().get().contains("no rows"),
            "lastError mentions empty source, got: " + controller.lastError().get());
    }

    @Test
    void identityRegression_runsAndAppendsLossHistory() throws Exception {
        // The simplest training task that exercises the LossOutput
        // backward seed + reverse-topo walk + lossHistory append:
        // Parameter (MATRIX-4) → Loss Output(source, target, MATRIX, MSE)
        // with a single source row. No graph Trainables move (Parameter
        // IS the trainable; loss > 0 → step → param drifts toward target).
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        EditableDataSource src = new EditableDataSource(
            "identity_fixture", "Identity", List.of("target"));
        for (int i = 0; i < 3; i++) {
            int r = src.addRow();
            src.setCell(r, 0, "0.7, 0.7, 0.7, 0.7");
        }
        ctx.dataSources().add(src);

        NodeSpec param = sync.spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        NodeSpec loss = sync.spawn(Palette.byKey("output.loss"), 6f, 0f, lossCfg("identity_fixture:target"));
        DesignerPanel.afterSpawn(sync, param);
        DesignerPanel.afterSpawn(sync, loss);
        Connections.add(surface, param.outputPort(), loss.orderedInputPorts().get(0));

        controller.start();
        // Let it run several examples.
        Thread.sleep(150);
        controller.stop();

        assertTrue(controller.lossHistory().size() > 0,
            "loss history populated after run");
        assertEquals(TrainingState.STOPPED, controller.state().get());
    }

    @Test
    void pauseStepResumeStop_obeysStateMachine() throws Exception {
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        EditableDataSource src = new EditableDataSource(
            "state_fixture", "State", List.of("target"));
        for (int i = 0; i < 50; i++) {
            int r = src.addRow();
            src.setCell(r, 0, "0.5, 0.5, 0.5, 0.5");
        }
        ctx.dataSources().add(src);

        NodeSpec param = sync.spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        NodeSpec loss = sync.spawn(Palette.byKey("output.loss"), 6f, 0f, lossCfg("state_fixture:target"));
        DesignerPanel.afterSpawn(sync, param);
        DesignerPanel.afterSpawn(sync, loss);
        Connections.add(surface, param.outputPort(), loss.orderedInputPorts().get(0));

        controller.start();
        Thread.sleep(50);
        assertEquals(TrainingState.RUNNING, controller.state().get());

        controller.pause();
        // Wait for the worker to actually finish its in-flight example
        // and park. state.set(PAUSED) returns synchronously from pause()
        // but atRest only flips true once the worker is on the wait queue.
        waitForAtRest(controller, true, 1000);
        assertEquals(TrainingState.PAUSED, controller.state().get(), "pause took effect");

        long beforeStep = controller.lossHistory().stepCount();
        controller.step();
        // Wait for the loss-history count to advance (worker processed
        // the step) and then for atRest to return true (worker parked
        // back).
        long deadline = System.currentTimeMillis() + 1000;
        while (controller.lossHistory().stepCount() == beforeStep && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        waitForAtRest(controller, true, 500);
        assertEquals(beforeStep + 1, controller.lossHistory().stepCount(),
            "step advanced exactly one example");
        if (controller.state().get() != TrainingState.PAUSED) {
            fail("expected PAUSED after step, got " + controller.state().get());
        }

        controller.resume();
        Thread.sleep(50);
        assertTrue(
            controller.state().get() == TrainingState.RUNNING
            || controller.state().get() == TrainingState.STOPPED,
            "resume left PAUSED");

        controller.stop();
        assertEquals(TrainingState.STOPPED, controller.state().get());
    }

    // ---------- helpers ----------

    private static Component.GraphSurface newSurface() {
        return new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
    }

    private static GraphSync newSync(Component.GraphSurface s) {
        GraphSync sync = new GraphSync(s);
        sync.install();
        return sync;
    }

    private static Map<String, Object> lossCfg(String source) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("source",    source);
        cfg.put("inputType", "MATRIX");
        cfg.put("lossFn",    "MSE");
        return cfg;
    }

    private static void waitForAtRest(TrainingController c, boolean target, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (c.atRest().get() != target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }
}
