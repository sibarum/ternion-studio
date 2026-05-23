package sibarum.ternion.train;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.render.Color;
import sibarum.mcc.graph.ComputationGraph;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.StringValue;
import sibarum.mcc.value.Value;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.source.EditableDataSource;
import sibarum.ternion.designer.DataSourceBoundNodes;
import sibarum.ternion.designer.DesignerPanel;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.designer.LookupColumnPrimitive;
import sibarum.ternion.designer.NodeSpec;
import sibarum.ternion.designer.Palette;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LookupColumn contract: STRING input → value cell parsed as the
 * configured output type. Out-of-vocab keys map to a zero value
 * rather than throwing.
 *
 * <p>Multi-source flow: a graph can iterate one source (an
 * {@code EditableDataSource} driving the training step) while a
 * second {@link LookupColumnPrimitive} pulls vectors from a
 * different (read-only) source — the preflight no longer rejects
 * the multi-source case because the lookup is non-iterated.
 */
final class LookupColumnTest {

    @BeforeEach
    void resetSidecar() {
        DataSourceBoundNodes.clearForTesting();
    }

    @Test
    void lookupColumn_returnsValueForKnownKey() {
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new TrainingController(ctx));

        EditableDataSource src = new EditableDataSource(
            "lookup_fixture", "Lookup Fixture", List.of("surface", "embedding"));
        int r = src.addRow();
        src.setCell(r, 0, "the");
        src.setCell(r, 1, "1.0 2.0 3.0 4.0");
        r = src.addRow();
        src.setCell(r, 0, "and");
        src.setCell(r, 1, "5.0 6.0 7.0 8.0");
        ctx.dataSources().add(src);

        LookupColumnPrimitive lookup = new LookupColumnPrimitive(
            "lookup_fixture", "surface", "embedding", sibarum.mcc.value.ValueType.MATRIX);

        Value out = lookup.apply(List.of(new StringValue("the")));
        MatrixValue mv = assertInstanceOf(MatrixValue.class, out);
        assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0 }, mv.data(), 1e-12);

        Value out2 = lookup.apply(List.of(new StringValue("and")));
        MatrixValue mv2 = assertInstanceOf(MatrixValue.class, out2);
        assertArrayEquals(new double[] { 5.0, 6.0, 7.0, 8.0 }, mv2.data(), 1e-12);
    }

    @Test
    void lookupColumn_outOfVocabReturnsZero() {
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new TrainingController(ctx));

        EditableDataSource src = new EditableDataSource(
            "oov_fixture", "OOV", List.of("surface", "embedding"));
        int r = src.addRow();
        src.setCell(r, 0, "the");
        src.setCell(r, 1, "1.0 2.0 3.0");
        ctx.dataSources().add(src);

        LookupColumnPrimitive lookup = new LookupColumnPrimitive(
            "oov_fixture", "surface", "embedding", sibarum.mcc.value.ValueType.MATRIX);

        // OOV key → zero MatrixValue (length 0 since we don't know
        // the embedding dim from a missing cell). The downstream
        // primitive will surface a dim mismatch only if it relies on
        // the lookup being non-empty; for V1 that's acceptable, and
        // the user's pipeline can guard via a known-keys
        // pre-filter.
        Value out = lookup.apply(List.of(new StringValue("XXXXXXX_NOT_IN_VOCAB")));
        MatrixValue mv = assertInstanceOf(MatrixValue.class, out);
        assertEquals(0, mv.data().length);
    }

    @Test
    void multiSourceWithLookupBinding_preflightAllowsTraining() throws Exception {
        // Two sources: one drives iteration (a fixture target column),
        // the other is a pretrained lookup table. Pre-Phase-1 the
        // trainer rejected any multi-source graph; with the iterated /
        // lookup split, this should now train.
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        EditableDataSource iterated = new EditableDataSource(
            "iter_fixture", "Iter", List.of("target"));
        int r = iterated.addRow();
        iterated.setCell(r, 0, "0.1 0.2 0.3 0.4");
        ctx.dataSources().add(iterated);

        EditableDataSource lookupTbl = new EditableDataSource(
            "embed_fixture", "Embed", List.of("surface", "embedding"));
        r = lookupTbl.addRow();
        lookupTbl.setCell(r, 0, "the");
        lookupTbl.setCell(r, 1, "1.0 2.0 3.0 4.0");
        ctx.dataSources().add(lookupTbl);

        // Param → Loss Output (iter_fixture). And in parallel a
        // LookupColumn against embed_fixture that's wired but its
        // output ignored — the preflight just needs to see it as a
        // bound source that doesn't count for iteration.
        NodeSpec param = sync.spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        Map<String, Object> lossCfg = new LinkedHashMap<>();
        lossCfg.put("source",    "iter_fixture:target");
        lossCfg.put("inputType", "MATRIX");
        lossCfg.put("lossFn",    "MSE");
        NodeSpec loss = sync.spawn(Palette.byKey("output.loss"), 6f, 0f, lossCfg);

        Map<String, Object> lookupCfg = new LinkedHashMap<>();
        lookupCfg.put("source",     "embed_fixture:surface:embedding");
        lookupCfg.put("outputType", "MATRIX");
        NodeSpec lookup = sync.spawn(Palette.byKey("input.lookup.column"), 3f, 3f, lookupCfg);

        DesignerPanel.afterSpawn(sync, param);
        DesignerPanel.afterSpawn(sync, loss);
        DesignerPanel.afterSpawn(sync, lookup);
        Connections.add(surface, param.outputPort(), loss.orderedInputPorts().get(0));
        // lookup dangling — that's fine; its presence shouldn't gate the preflight.

        controller.step();
        long deadline = System.currentTimeMillis() + 1000;
        while (controller.lossHistory().stepCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        controller.stop();

        assertEquals("", controller.lastError().get(),
            "preflight passed (no multi-source error), got: " + controller.lastError().get());
        assertTrue(controller.lossHistory().size() >= 1,
            "training step completed");
    }

    @Test
    void lookupColumnViaGraphExecute_roundTrip() {
        // End-to-end via ComputationGraph.execute: bind a STRING root
        // to the lookup's key port, snapshot, execute, assert the
        // lookup's parsed embedding flows out.
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new TrainingController(ctx));

        EditableDataSource src = new EditableDataSource(
            "graph_fixture", "Graph", List.of("surface", "embedding"));
        int r = src.addRow();
        src.setCell(r, 0, "hello");
        src.setCell(r, 1, "0.5 1.5 2.5");
        ctx.dataSources().add(src);

        Map<String, Object> lookupCfg = new LinkedHashMap<>();
        lookupCfg.put("source",     "graph_fixture:surface:embedding");
        lookupCfg.put("outputType", "MATRIX");
        NodeSpec lookup = sync.spawn(Palette.byKey("input.lookup.column"), 0f, 0f, lookupCfg);
        DesignerPanel.afterSpawn(sync, lookup);

        ComputationGraph graph = sync.snapshot(lookup.cgNode());
        graph.bindRoot(lookup.cgNode(), 0, new StringValue("hello"));
        Value out = graph.execute();
        MatrixValue mv = assertInstanceOf(MatrixValue.class, out);
        assertArrayEquals(new double[] { 0.5, 1.5, 2.5 }, mv.data(), 1e-12);
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
}
