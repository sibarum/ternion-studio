package sibarum.ternion.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.render.Color;
import sibarum.mcc.op.Linear;
import sibarum.mcc.primitive.Parameterized;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.source.DataSource;
import sibarum.ternion.data.source.EditableDataSource;
import sibarum.ternion.designer.DataSourceBoundNodes;
import sibarum.ternion.designer.DesignerPanel;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.designer.NodeSpec;
import sibarum.ternion.designer.Palette;
import sibarum.ternion.train.TrainingController;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Round-trip: build a Parameter → Linear → Loss Output graph backed
 * by an editable {@link DataSource}, save, build a fresh context,
 * load, and confirm everything came back — including the Linear's
 * parameters (bit-for-bit) and the source's rows + columns (so
 * {@link sibarum.ternion.designer.LossOutputPrimitive} re-resolves
 * its column at construction).
 */
final class ProjectIoTest {

    @BeforeEach
    void resetSidecar() {
        DataSourceBoundNodes.clearForTesting();
    }

    @Test
    void roundTripPreservesGraphParametersAndSource(@TempDir Path tmp) throws Exception {
        // ---- Build the source context ----
        AppContext src = newContext();
        EditableDataSource srcDs = new EditableDataSource(
            "fixture", "Fixture", List.of("target"));
        int r = srcDs.addRow();
        srcDs.setCell(r, 0, "0.1, 0.2, 0.3, 0.4");
        src.dataSources().add(srcDs);

        NodeSpec param = src.graphSync().spawn(Palette.byKey("input.parameter.matrix4"), 1f, 2f);
        NodeSpec lin   = src.graphSync().spawn(Palette.byKey("linear.linear"),           5f, 2f);
        NodeSpec loss  = src.graphSync().spawn(Palette.byKey("output.loss"), 9f, 2f, lossCfg("fixture:target"));
        DesignerPanel.afterSpawn(src.graphSync(), param);
        DesignerPanel.afterSpawn(src.graphSync(), lin);
        DesignerPanel.afterSpawn(src.graphSync(), loss);

        Connections.add(src.designerSurface(), param.outputPort(), lin.orderedInputPorts().get(0));
        Connections.add(src.designerSurface(), lin.outputPort(),   loss.orderedInputPorts().get(0));

        // Capture the Linear's parameters BEFORE save so we can verify
        // they round-trip exactly.
        Linear srcLinear = (Linear) lin.cgNode().tNode().primitive();
        Map<String, double[]> savedTensorData = snapshotParams(srcLinear);

        // ---- Save ----
        Path projectDir = tmp.resolve("project.tsp");
        ProjectIo.save(projectDir, src);

        assertNotEquals(0L, projectDir.toFile().length(), "project dir exists");
        assertNotEquals(0L, projectDir.resolve("graph").resolve("graph.json").toFile().length());
        assertNotEquals(0L, projectDir.resolve("graph").resolve("params.bin").toFile().length());
        assertNotEquals(0L, projectDir.resolve("designer.json").toFile().length());
        assertNotEquals(0L, projectDir.resolve("sources.json").toFile().length());

        // ---- Load into a FRESH context ----
        AppContext dst = newContext();
        ProjectIo.load(projectDir, dst);

        // All three nodes restored.
        assertEquals(3, dst.graphSync().liveNodes().size(),
            "all three nodes restored");

        // Find each by paletteKey.
        NodeSpec dParam = findByKey(dst, "input.parameter.matrix4");
        NodeSpec dLin   = findByKey(dst, "linear.linear");
        NodeSpec dLoss  = findByKey(dst, "output.loss");
        assertNotNull(dParam);
        assertNotNull(dLin);
        assertNotNull(dLoss);

        // Designer position preserved.
        sibarum.dasum.gui.core.graph.GraphSurfacePositions.Pos pos =
            sibarum.dasum.gui.core.graph.GraphSurfacePositions.of(
                dst.designerSurface(), dLin.visual());
        assertEquals(5f, pos.emX(), 1e-6);
        assertEquals(2f, pos.emY(), 1e-6);

        // Edges restored.
        assertNotNull(dLin.cgNode().slot(0), "Linear slot 0 rewired");
        assertEquals(dParam.cgNode(), dLin.cgNode().slot(0).source(),
            "Linear ← Parameter restored");
        assertNotNull(dLoss.cgNode().slot(0), "Loss Output slot 0 rewired");
        assertEquals(dLin.cgNode(), dLoss.cgNode().slot(0).source(),
            "Loss Output ← Linear restored");

        // Parameters restored bit-for-bit — the headline assertion.
        Linear dstLinear = (Linear) dLin.cgNode().tNode().primitive();
        Map<String, double[]> reloadedData = snapshotParams(dstLinear);
        for (Map.Entry<String, double[]> e : savedTensorData.entrySet()) {
            double[] expected = e.getValue();
            double[] actual   = reloadedData.get(e.getKey());
            assertNotNull(actual, "reloaded tensor '" + e.getKey() + "'");
            assertArrayEquals(expected, actual, 0.0,
                "Linear tensor '" + e.getKey() + "' restored bit-for-bit");
        }

        // Source rows + columns restored.
        DataSource reloadedSrc = dst.dataSources().byId("fixture");
        assertNotNull(reloadedSrc, "fixture source registered after load");
        assertEquals(1, reloadedSrc.rowCount(), "fixture row count preserved");
        assertEquals(List.of("target"), reloadedSrc.columns(), "fixture column preserved");
        assertEquals("0.1, 0.2, 0.3, 0.4", reloadedSrc.get(0, 0), "fixture cell preserved");

        // Project metadata.
        assertEquals(projectDir, dst.project().path().get(),
            "project path captured after load");
    }

    @Test
    void loadIntoFreshContextDropsExistingNodes(@TempDir Path tmp) throws Exception {
        // Build + save a minimal project.
        AppContext src = newContext();
        EditableDataSource srcDs = new EditableDataSource(
            "minimal", "Minimal", List.of("target"));
        int r = srcDs.addRow();
        srcDs.setCell(r, 0, "0.0, 0.0, 0.0, 0.0");
        src.dataSources().add(srcDs);

        NodeSpec param = src.graphSync().spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        NodeSpec loss  = src.graphSync().spawn(Palette.byKey("output.loss"), 6f, 0f, lossCfg("minimal:target"));
        DesignerPanel.afterSpawn(src.graphSync(), param);
        DesignerPanel.afterSpawn(src.graphSync(), loss);
        Connections.add(src.designerSurface(), param.outputPort(), loss.orderedInputPorts().get(0));

        Path dir = tmp.resolve("minimal.tsp");
        ProjectIo.save(dir, src);

        // Build a DIFFERENT context with unrelated nodes, then load.
        AppContext dst = newContext();
        dst.graphSync().spawn(Palette.byKey("act.relu"),  0f, 0f);
        dst.graphSync().spawn(Palette.byKey("arith.add"), 4f, 0f);
        assertEquals(2, dst.graphSync().liveNodes().size());

        ProjectIo.load(dir, dst);

        // Pre-existing nodes are gone; only the loaded set remains.
        assertEquals(2, dst.graphSync().liveNodes().size(),
            "load clears existing nodes before re-spawning saved set");
    }

    private static AppContext newContext() {
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new TrainingController(ctx));
        return ctx;
    }

    private static NodeSpec findByKey(AppContext ctx, String key) {
        for (NodeSpec s : ctx.graphSync().liveNodes()) {
            if (key.equals(s.paletteKey())) return s;
        }
        return null;
    }

    private static Map<String, Object> lossCfg(String source) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("source",    source);
        cfg.put("inputType", "MATRIX");
        cfg.put("lossFn",    "MSE");
        return cfg;
    }

    private static Map<String, double[]> snapshotParams(Parameterized p) {
        Map<String, double[]> out = new HashMap<>();
        for (Parameterized.NamedTensor t : p.parameters()) {
            out.put(t.name(), t.data().clone());
        }
        return out;
    }
}
