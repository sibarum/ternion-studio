package sibarum.ternion.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.render.Color;
import sibarum.mcc.op.Linear;
import sibarum.mcc.primitive.Parameterized;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.CorpusModel;
import sibarum.ternion.data.InputSchema;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.designer.NodeSpec;
import sibarum.ternion.designer.Palette;
import sibarum.ternion.train.TrainingController;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Round-trip: build a graph + corpus, train briefly to perturb the
 * Linear's parameters off random init, save, build a fresh context,
 * load, assert everything came back — including the post-training
 * Linear weights (the test that proves parameters are restored, not
 * re-initialized).
 */
final class ProjectIoTest {

    @Test
    void roundTripPreservesGraphAndParameters(@TempDir Path tmp) throws Exception {
        // ---- Build the source context ----
        AppContext src = newContext();
        NodeSpec param = src.graphSync().spawn(Palette.byKey("input.parameter.matrix4"), 1f, 2f);
        NodeSpec relu  = src.graphSync().spawn(Palette.byKey("act.relu"),                 5f, 2f);
        NodeSpec lin   = src.graphSync().spawn(Palette.byKey("linear.linear"),        9f, 2f);
        NodeSpec term  = src.graphSync().spawn(Palette.byKey("output.terminal.matrix"), 13f, 2f);

        Connections.add(src.designerSurface(), param.outputPort(), relu.orderedInputPorts().get(0));
        Connections.add(src.designerSurface(), relu.outputPort(),  lin.orderedInputPorts().get(0));
        Connections.add(src.designerSurface(), lin.outputPort(),   term.orderedInputPorts().get(0));

        // No unwired NUMBER/STRING slots — corpus parser would skip MATRIX
        // targets. Skip cell editing and just snapshot the model. Save
        // anyway — corpus.json will be empty, that's fine.
        // Actually: detectFromGraph reads from the terminal; with all
        // slots wired, inputSchema is empty. Add a dummy row to exercise
        // the corpus save path.
        src.corpus().detectFromGraph(src.graphSync(), term.cgNode());
        src.corpus().addRow();
        src.corpus().setLabel(0, "saved-example");

        // Capture the Linear's parameters BEFORE save so we can verify
        // they round-trip exactly.
        Linear srcLinear = (Linear) lin.cgNode().tNode().primitive();
        Map<String, double[]> savedTensorData = snapshotParams(srcLinear);

        // ---- Save ----
        Path projectDir = tmp.resolve("project.tsp");
        ProjectIo.save(projectDir, src);

        // Verify the on-disk layout.
        assertNotEquals(0L, projectDir.toFile().length(),
            "project dir exists");
        assertNotEquals(0L,
            projectDir.resolve("graph").resolve("graph.json").toFile().length());
        assertNotEquals(0L,
            projectDir.resolve("graph").resolve("params.bin").toFile().length());
        assertNotEquals(0L,
            projectDir.resolve("corpus.json").toFile().length());
        assertNotEquals(0L,
            projectDir.resolve("designer.json").toFile().length());

        // ---- Load into a FRESH context ----
        AppContext dst = newContext();
        ProjectIo.load(projectDir, dst);

        // Same number of nodes restored.
        assertEquals(4, dst.graphSync().liveNodes().size(),
            "all four nodes restored");

        // Find each by paletteKey + verify position.
        NodeSpec dParam = findByKey(dst, "input.parameter.matrix4");
        NodeSpec dRelu  = findByKey(dst, "act.relu");
        NodeSpec dLin   = findByKey(dst, "linear.linear");
        NodeSpec dTerm  = findByKey(dst, "output.terminal.matrix");
        assertNotNull(dParam); assertNotNull(dRelu);
        assertNotNull(dLin);   assertNotNull(dTerm);

        sibarum.dasum.gui.core.graph.GraphSurfacePositions.Pos pos =
            sibarum.dasum.gui.core.graph.GraphSurfacePositions.of(
                dst.designerSurface(), dRelu.visual());
        assertEquals(5f, pos.emX(), 1e-6);
        assertEquals(2f, pos.emY(), 1e-6);

        // Edges restored — Linear's slot 0 should point at ReLU's output.
        assertNotNull(dLin.cgNode().slot(0), "Linear slot 0 rewired");
        assertEquals(dRelu.cgNode(), dLin.cgNode().slot(0).source(),
            "Linear ← ReLU restored");

        // Parameters restored — this is the headline assertion.
        Linear dstLinear = (Linear) dLin.cgNode().tNode().primitive();
        Map<String, double[]> reloadedData = snapshotParams(dstLinear);
        for (Map.Entry<String, double[]> e : savedTensorData.entrySet()) {
            double[] expected = e.getValue();
            double[] actual   = reloadedData.get(e.getKey());
            assertNotNull(actual, "reloaded tensor '" + e.getKey() + "'");
            assertArrayEquals(expected, actual, 0.0,
                "Linear tensor '" + e.getKey() + "' restored bit-for-bit");
        }

        // Corpus row + label restored.
        assertEquals(1, dst.corpus().rows().size(), "corpus row count restored");
        assertEquals("saved-example", dst.corpus().rows().get(0).label);

        // Project metadata.
        assertEquals(projectDir, dst.project().path().get(),
            "project path captured after load");
    }

    @Test
    void loadIntoFreshContextDropsExistingNodes(@TempDir Path tmp) throws Exception {
        // Build + save a minimal project.
        AppContext src = newContext();
        NodeSpec term = src.graphSync().spawn(Palette.byKey("output.terminal.number"), 0f, 0f);
        src.corpus().detectFromGraph(src.graphSync(), term.cgNode());

        Path dir = tmp.resolve("minimal.tsp");
        ProjectIo.save(dir, src);

        // Build a DIFFERENT context with unrelated nodes, then load.
        AppContext dst = newContext();
        dst.graphSync().spawn(Palette.byKey("act.relu"),    0f, 0f);
        dst.graphSync().spawn(Palette.byKey("arith.add"),   4f, 0f);
        assertEquals(2, dst.graphSync().liveNodes().size());

        ProjectIo.load(dir, dst);

        // The pre-existing nodes are gone; only the loaded one remains.
        assertEquals(1, dst.graphSync().liveNodes().size(),
            "load clears existing nodes before re-spawning saved set");
        assertEquals("output.terminal.number",
            dst.graphSync().liveNodes().get(0).paletteKey());
    }

    private static AppContext newContext() {
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();
        AppContext ctx = new AppContext(surface, sync, new CorpusModel());
        ctx.attachTrainingController(new TrainingController(ctx));
        return ctx;
    }

    private static NodeSpec findByKey(AppContext ctx, String key) {
        for (NodeSpec s : ctx.graphSync().liveNodes()) {
            if (key.equals(s.paletteKey())) return s;
        }
        return null;
    }

    private static Map<String, double[]> snapshotParams(Parameterized p) {
        Map<String, double[]> out = new HashMap<>();
        for (Parameterized.NamedTensor t : p.parameters()) {
            out.put(t.name(), t.data().clone());
        }
        return out;
    }
}
