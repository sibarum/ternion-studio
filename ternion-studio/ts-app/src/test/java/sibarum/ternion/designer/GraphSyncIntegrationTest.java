package sibarum.ternion.designer;

import org.junit.jupiter.api.Test;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.graph.Connection;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.graph.GraphSurfaceChildren;
import sibarum.dasum.gui.core.render.Color;
import sibarum.mcc.graph.ComputationGraph;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.Value;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end check for Phase 3: spawn nodes via {@link GraphSync}, wire
 * them programmatically through dasum's {@link Connections#add} (which
 * fires our listener), snapshot a {@link ComputationGraph}, execute, and
 * verify the output matches the Parameter's data. Plus a delete-cascade
 * check exercising {@link sibarum.dasum.gui.core.component.Components#detach}.
 */
final class GraphSyncIntegrationTest {

    @Test
    void wireExecuteDelete_roundTrip() {
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();

        // Spawn Parameter (MATRIX-4) → ReLU as the downstream sink for
        // the test. (ReLU is a 1-input MATRIX primitive; using it
        // avoids needing a Terminal entry in the palette, which Phase
        // C-2 removed.)
        PaletteItem paramItem = Palette.byKey("input.parameter.matrix4");
        PaletteItem sinkItem  = Palette.byKey("act.relu");
        assertNotNull(paramItem, "param palette item present");
        assertNotNull(sinkItem,  "sink palette item present");

        NodeSpec paramSpec = sync.spawn(paramItem, 2f, 2f);
        NodeSpec sinkSpec  = sync.spawn(sinkItem,  8f, 2f);
        assertEquals(2, sync.liveNodes().size(), "two nodes after spawn");
        assertTrue(GraphSurfaceChildren.added(surface).contains(paramSpec.visual()),
            "paramNode added to GraphSurfaceChildren");

        // Programmatic wire: paramOutput → sinkInput.
        Component paramOut = paramSpec.outputPort();
        Component sinkIn   = sinkSpec.orderedInputPorts().get(0);
        Connection conn = Connections.add(surface, paramOut, sinkIn);
        assertNotNull(conn, "Connection created");
        // The listener should have wired the slot.
        assertNotNull(sinkSpec.cgNode().slot(0),
            "Sink slot 0 wired post-connect");
        assertEquals(paramSpec.cgNode(), sinkSpec.cgNode().slot(0).source(),
            "Sink slot 0 sources Parameter");

        // Execute the graph — Parameter outputs its random init, ReLU
        // clamps to max(0, x) element-wise. We just verify that
        // execute() succeeds and returns a MATRIX of matching arity.
        ComputationGraph graph = sync.snapshot(sinkSpec.cgNode());
        Value out = graph.execute();
        MatrixValue m = assertInstanceOf(MatrixValue.class, out);
        Value paramVal = paramSpec.tNode().primitive().apply(List.of());
        MatrixValue paramMat = assertInstanceOf(MatrixValue.class, paramVal);
        assertEquals(paramMat.data().length, m.data().length,
            "Sink output arity matches Parameter output arity");
        for (int i = 0; i < m.data().length; i++) {
            assertEquals(Math.max(0.0, paramMat.data()[i]), m.data()[i], 1e-12,
                "ReLU sink output[" + i + "] = max(0, param[" + i + "])");
        }

        // Remove the Parameter: cascade-fires REMOVED, which should clear
        // Sink's slot binding.
        sync.removeNode(paramSpec.visual());
        assertNull(sinkSpec.cgNode().slot(0),
            "Sink slot 0 cleared after upstream Parameter detached");
        assertEquals(1, sync.liveNodes().size(), "one node after delete");
        assertFalse(GraphSurfaceChildren.added(surface).contains(paramSpec.visual()),
            "paramNode removed from GraphSurfaceChildren");
        // Trying to execute now should fail cleanly (Sink slot has no source AND no root binding).
        try {
            sync.snapshot(sinkSpec.cgNode()).execute();
            fail("execute should fail with no source on Sink");
        } catch (IllegalStateException ignored) {
            // expected
        }
    }

    @Test
    void cycleAttempt_rejectedByConnectionRule() {
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();

        // Pattern: A → B → C. Then try C → A which would close the loop.
        NodeSpec a = sync.spawn(Palette.byKey("act.relu"), 0f, 0f);
        NodeSpec b = sync.spawn(Palette.byKey("act.relu"), 4f, 0f);
        NodeSpec c = sync.spawn(Palette.byKey("act.relu"), 8f, 0f);

        Connections.add(surface, a.outputPort(), b.orderedInputPorts().get(0));
        Connections.add(surface, b.outputPort(), c.orderedInputPorts().get(0));

        // Now C → A would be a cycle. canConnect should refuse.
        try {
            Connections.add(surface, c.outputPort(), a.orderedInputPorts().get(0));
            fail("Connections.add should throw on cycle");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
