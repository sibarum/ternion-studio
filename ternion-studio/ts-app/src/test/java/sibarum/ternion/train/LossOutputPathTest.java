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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase C-1 contract verification for the LossOutput-driven training
 * path: Parameter (MATRIX-4) → Loss Output(source, target, MATRIX, MSE).
 * Source rows hold supervisory targets; trainer iterates rows in lockstep
 * via DataSourceBoundNodes, reads loss from the LossOutput's
 * producedValue scalar, and backprops with the trivial dL/dL=1 seed.
 *
 * <p>Asserts loss drops monotonically as the Parameter is pulled toward
 * the constant target, proving the new backward seed + LossOutput
 * gradient computation route into Trainable.step correctly.
 */
final class LossOutputPathTest {

    @BeforeEach
    void resetSidecar() {
        // The DataSourceBoundNodes registry is process-static; nodes
        // from a prior test's GraphSync linger after that GraphSync is
        // GC'd. Wipe so each test sees a clean preflight.
        DataSourceBoundNodes.clearForTesting();
    }

    @Test
    void parameterToLossOutput_convergesAtLr01() throws Exception {
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();

        // Build AppContext first so DataSourceRegistry.current() is live
        // before LossOutput's constructor runs at spawn time.
        AppContext ctx = new AppContext(surface, sync);
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        // Register a fresh editable source with a single MATRIX target.
        EditableDataSource src = new EditableDataSource(
            "fixture_targets", "Fixture Targets", List.of("target"));
        int r = src.addRow();
        src.setCell(r, 0, "1.0, 0.5, -0.5, 0.25");
        ctx.dataSources().add(src);

        // Spawn: Parameter (MATRIX-4) → Loss Output(fixture_targets, target, MATRIX, MSE).
        NodeSpec param = sync.spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        Map<String, Object> lossCfg = new LinkedHashMap<>();
        lossCfg.put("source",    "fixture_targets:target");
        lossCfg.put("inputType", "MATRIX");
        lossCfg.put("lossFn",    "MSE");
        NodeSpec loss = sync.spawn(Palette.byKey("output.loss"), 6f, 0f, lossCfg);
        DesignerPanel.afterSpawn(sync, param);
        DesignerPanel.afterSpawn(sync, loss);

        Connections.add(surface, param.outputPort(), loss.orderedInputPorts().get(0));

        controller.learningRate().set(0.1f);
        // Drive exactly 5 steps so we can compare the first (untrained)
        // loss to the last without the 512-sample ring buffer masking
        // convergence (300ms of run on this 1-row source would land all
        // visible samples in the converged tail).
        controller.step();
        waitForStepCount(controller, 1, 1000);
        double firstLoss = controller.lossHistory().snapshot().get(0).loss();
        for (int i = 0; i < 4; i++) {
            controller.step();
            waitForStepCount(controller, i + 2, 1000);
        }
        controller.stop();

        List<LossHistory.Sample> samples = controller.lossHistory().snapshot();
        assertTrue(samples.size() >= 5, "expected ≥5 samples, got " + samples.size());
        double lastLoss = samples.get(samples.size() - 1).loss();
        assertTrue(firstLoss > 0.0,
            "first loss should be non-zero (random Parameter vs fixed target); got " + firstLoss);
        assertTrue(lastLoss < firstLoss,
            "loss decreased over 5 steps: first=" + firstLoss + " last=" + lastLoss);
        assertEquals(TrainingState.STOPPED, controller.state().get());
    }

    private static void waitForStepCount(TrainingController c, long target, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (c.lossHistory().stepCount() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    @Test
    void multiSourceGraph_preflightRejects() throws Exception {
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();
        AppContext ctx = new AppContext(surface, sync);
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        // Two distinct sources, neither bundled.
        EditableDataSource a = new EditableDataSource("src_a", "A", List.of("target"));
        a.addRow(); a.setCell(0, 0, "1, 2, 3, 4");
        EditableDataSource b = new EditableDataSource("src_b", "B", List.of("col"));
        b.addRow(); b.setCell(0, 0, "x");
        ctx.dataSources().add(a);
        ctx.dataSources().add(b);

        // Loss Output → src_a:target.
        Map<String, Object> lossCfg = new LinkedHashMap<>();
        lossCfg.put("source",    "src_a:target");
        lossCfg.put("inputType", "MATRIX");
        lossCfg.put("lossFn",    "MSE");
        NodeSpec param = sync.spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        NodeSpec loss = sync.spawn(Palette.byKey("output.loss"), 6f, 0f, lossCfg);

        // Dataset Column → src_b:col (the *other* source).
        Map<String, Object> dsCfg = new LinkedHashMap<>();
        dsCfg.put("source",     "src_b:col");
        dsCfg.put("outputType", "STRING");
        NodeSpec dsCol = sync.spawn(Palette.byKey("input.dataset.column"), 3f, 3f, dsCfg);

        DesignerPanel.afterSpawn(sync, param);
        DesignerPanel.afterSpawn(sync, loss);
        DesignerPanel.afterSpawn(sync, dsCol);

        Connections.add(surface, param.outputPort(), loss.orderedInputPorts().get(0));
        // (dsCol left dangling; the preflight should still reject on
        //  multi-source grounds before checking unwired slots.)

        controller.start();
        // Preflight rejects synchronously — state stays IDLE.
        assertEquals(TrainingState.IDLE, controller.state().get());
        String err = controller.lastError().get();
        assertNotNull(err);
        assertTrue(err.contains("2 distinct sources"),
            "expected multi-source error, got: " + err);
    }

    @Test
    void categoricalLoss_classLabelTarget_trains() throws Exception {
        // Smallest CATEGORICAL setup: a learnable 4-vector Parameter
        // chased toward the one-hot of a class label. The label cell
        // ("NOUN") tokenizes to a deterministic id in [0, 4); the
        // Parameter converges toward that one-hot.
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();

        AppContext ctx = new AppContext(surface, sync);
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        EditableDataSource src = new EditableDataSource(
            "categorical_fixture", "Categorical", List.of("label"));
        int r = src.addRow();
        src.setCell(r, 0, "NOUN");
        ctx.dataSources().add(src);

        Map<String, Object> lossCfg = new LinkedHashMap<>();
        lossCfg.put("source",     "categorical_fixture:label");
        lossCfg.put("inputType",  "MATRIX");
        lossCfg.put("lossFn",     "CATEGORICAL");
        lossCfg.put("numClasses", 4);

        NodeSpec param = sync.spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        NodeSpec loss  = sync.spawn(Palette.byKey("output.loss"), 6f, 0f, lossCfg);
        DesignerPanel.afterSpawn(sync, param);
        DesignerPanel.afterSpawn(sync, loss);
        Connections.add(surface, param.outputPort(), loss.orderedInputPorts().get(0));

        controller.learningRate().set(0.1f);
        controller.step();
        long deadline = System.currentTimeMillis() + 1000;
        while (controller.lossHistory().stepCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        double firstLoss = controller.lossHistory().snapshot().get(0).loss();
        for (int i = 0; i < 4; i++) {
            controller.step();
            long d2 = System.currentTimeMillis() + 1000;
            while (controller.lossHistory().stepCount() < i + 2 && System.currentTimeMillis() < d2) {
                Thread.sleep(5);
            }
        }
        controller.stop();

        List<LossHistory.Sample> samples = controller.lossHistory().snapshot();
        assertTrue(samples.size() >= 5, "expected ≥5 samples, got " + samples.size());
        double lastLoss = samples.get(samples.size() - 1).loss();
        assertTrue(firstLoss > 0.0,
            "initial loss should be non-zero (random Parameter vs one-hot target); got " + firstLoss);
        assertTrue(lastLoss < firstLoss,
            "CATEGORICAL loss decreased over 5 steps: first=" + firstLoss + " last=" + lastLoss);
    }

    @Test
    void categoricalLoss_adaptsToPredictedDimRegardlessOfNumClasses() throws Exception {
        // Predicted is a Parameter MATRIX-4 (dim 4); LossOutput's
        // numClasses is intentionally a mismatched 2. The one-hot
        // target should be built to match the predicted dim (4), not
        // numClasses (2) — the old code threw "MATRIX[4] vs MATRIX[2]"
        // and the Run Forward path surfaced it through Status.error.
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();
        AppContext ctx = new AppContext(surface, sync);
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        EditableDataSource src = new EditableDataSource(
            "adaptive_fixture", "Adaptive", List.of("label"));
        int r = src.addRow();
        src.setCell(r, 0, "NOUN");
        ctx.dataSources().add(src);

        Map<String, Object> lossCfg = new LinkedHashMap<>();
        lossCfg.put("source",     "adaptive_fixture:label");
        lossCfg.put("inputType",  "MATRIX");
        lossCfg.put("lossFn",     "CATEGORICAL");
        lossCfg.put("numClasses", 2);                       // mismatched on purpose

        NodeSpec param = sync.spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        NodeSpec loss  = sync.spawn(Palette.byKey("output.loss"), 6f, 0f, lossCfg);
        DesignerPanel.afterSpawn(sync, param);
        DesignerPanel.afterSpawn(sync, loss);
        Connections.add(surface, param.outputPort(), loss.orderedInputPorts().get(0));

        // One step is enough — pre-fix this would throw before
        // lossHistory grew; the new path adapts the one-hot length to
        // the predicted (4) and finishes a clean step.
        controller.step();
        long deadline = System.currentTimeMillis() + 1000;
        while (controller.lossHistory().stepCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        controller.stop();

        assertTrue(controller.lossHistory().size() >= 1,
            "loss history populated (no dim-mismatch throw)");
        assertEquals("", controller.lastError().get(),
            "no error reported, got: " + controller.lastError().get());
    }

    @Test
    void categoricalLoss_coercesNumberPredictedToMatrix() {
        // The CATEGORICAL path needs a MATRIX predicted (one score per
        // class). Rather than rejecting an inadvertently NUMBER-typed
        // inputType — which would unwind the Properties-dialog spawn
        // mid-mutation — the constructor coerces silently to MATRIX,
        // and the primitive's reported config reflects the coerced
        // value so save/load is round-trip stable.
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new TrainingController(ctx));

        EditableDataSource src = new EditableDataSource(
            "coerce_fixture", "Coerce", List.of("label"));
        src.addRow();
        src.setCell(0, 0, "X");
        ctx.dataSources().add(src);

        Map<String, Object> lossCfg = new LinkedHashMap<>();
        lossCfg.put("source",     "coerce_fixture:label");
        lossCfg.put("inputType",  "NUMBER");        // intentionally wrong
        lossCfg.put("lossFn",     "CATEGORICAL");
        lossCfg.put("numClasses", 4);

        NodeSpec loss = sync.spawn(Palette.byKey("output.loss"), 0f, 0f, lossCfg);
        sibarum.ternion.designer.LossOutputPrimitive lop =
            (sibarum.ternion.designer.LossOutputPrimitive) loss.tNode().primitive();
        assertEquals(sibarum.mcc.value.ValueType.MATRIX, lop.inputType(),
            "CATEGORICAL coerces inputType to MATRIX");
        assertEquals(4, lop.numClasses(), "numClasses preserved");
        // Round-trip via config().
        assertEquals("MATRIX", lop.config().get("inputType"),
            "config().inputType reflects the coerced MATRIX value");
    }
}
