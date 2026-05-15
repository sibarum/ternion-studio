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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

        // Spawn Parameter (MATRIX-4) and Terminal (MATRIX).
        PaletteItem paramItem    = Palette.byKey("input.parameter.matrix4");
        PaletteItem terminalItem = Palette.byKey("output.terminal.matrix");
        assertNotNull(paramItem,    "param palette item present");
        assertNotNull(terminalItem, "terminal palette item present");

        NodeSpec paramSpec    = sync.spawn(paramItem,    2f, 2f);
        NodeSpec terminalSpec = sync.spawn(terminalItem, 8f, 2f);
        assertEquals(2, sync.liveNodes().size(), "two nodes after spawn");
        assertTrue(GraphSurfaceChildren.added(surface).contains(paramSpec.visual()),
            "paramNode added to GraphSurfaceChildren");

        // Programmatic wire: paramOutput → terminalInput.
        Component paramOut = paramSpec.outputPort();
        Component termIn   = terminalSpec.orderedInputPorts().get(0);
        Connection conn = Connections.add(surface, paramOut, termIn);
        assertNotNull(conn, "Connection created");
        // The listener should have wired the slot.
        assertNotNull(terminalSpec.cgNode().slot(0),
            "Terminal slot 0 wired post-connect");
        assertEquals(paramSpec.cgNode(), terminalSpec.cgNode().slot(0).source(),
            "Terminal slot 0 sources Parameter");

        // Execute the graph — Parameter outputs its random init.
        ComputationGraph graph = sync.snapshot(terminalSpec.cgNode());
        Value out = graph.execute();
        MatrixValue m = assertInstanceOf(MatrixValue.class, out);
        // Parameter's initial data is independent of slot wiring; just check
        // that the Terminal forwards it verbatim. (Read it from the Parameter
        // primitive directly via apply with no inputs.)
        Value paramVal = paramSpec.tNode().primitive().apply(List.of());
        MatrixValue expected = assertInstanceOf(MatrixValue.class, paramVal);
        assertArrayEquals(expected.data(), m.data(), 1e-12,
            "Terminal output equals Parameter output");

        // Remove the Parameter: cascade-fires REMOVED, which should clear
        // Terminal's slot binding.
        sync.removeNode(paramSpec.visual());
        assertNull(terminalSpec.cgNode().slot(0),
            "Terminal slot 0 cleared after upstream Parameter detached");
        assertEquals(1, sync.liveNodes().size(), "one node after delete");
        assertFalse(GraphSurfaceChildren.added(surface).contains(paramSpec.visual()),
            "paramNode removed from GraphSurfaceChildren");
        // Trying to execute now should fail cleanly (Terminal slot has no source AND no root binding).
        try {
            sync.snapshot(terminalSpec.cgNode()).execute();
            fail("execute should fail with no source on Terminal");
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
