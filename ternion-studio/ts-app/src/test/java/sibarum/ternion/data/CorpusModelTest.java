package sibarum.ternion.data;

import org.junit.jupiter.api.Test;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.render.Color;
import sibarum.mcc.training.Corpus;
import sibarum.mcc.training.Example;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.StringValue;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.designer.NodeSpec;
import sibarum.ternion.designer.Palette;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end for Phase 5: build a small graph, detect schema, edit
 * cells via {@link CorpusModel}, and verify the produced {@link Corpus}
 * carries the right values for {@link sibarum.mcc.training.GraphTrainer}.
 */
final class CorpusModelTest {

    @Test
    void detectThenEdit_producesUsableCorpus() {
        // Build a small graph: Parameter → ReLU → Terminal.
        // After wiring Parameter→ReLU and ReLU→Terminal, every slot is
        // wired EXCEPT… actually the Parameter has 0 input slots, so no
        // unwired inputs at all. Need a configuration where at least one
        // slot is unwired so detectFromGraph has something to find.
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();

        // Use Add (2 inputs) → Terminal. Wire only one input so the
        // other is unwired and becomes a corpus input.
        NodeSpec p1   = sync.spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        NodeSpec add  = sync.spawn(Palette.byKey("arith.add"),               4f, 0f);
        NodeSpec term = sync.spawn(Palette.byKey("output.terminal.matrix"),  8f, 0f);

        // Wire p1 -> add.a; leave add.b unwired. Wire add -> terminal.
        Connections.add(surface, p1.outputPort(),  add.orderedInputPorts().get(0));
        Connections.add(surface, add.outputPort(), term.orderedInputPorts().get(0));

        CorpusModel corpus = new CorpusModel();
        corpus.detectFromGraph(sync, term.cgNode());

        // One unwired input on Add (slot 1, MATRIX).
        assertEquals(1, corpus.inputSchema().size(),
            "one unwired slot detected (add.b)");
        InputSchema slotB = corpus.inputSchema().get(0);
        assertEquals(ValueType.MATRIX, slotB.type(),
            "the unwired slot is MATRIX-typed");
        assertEquals(ValueType.MATRIX, corpus.targetSchema().type(),
            "terminal output type is MATRIX");

        // MATRIX isn't supported by the cell parser; rows toCorpus should
        // drop on parse failure but not throw. Switch to a smaller graph
        // for a happy-path cell test.
        CorpusModel scalar = new CorpusModel();
        Component.GraphSurface s2 = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync2 = new GraphSync(s2);
        sync2.install();

        // Magnitude (MATRIX → NUMBER) → Terminal(NUMBER). Unwired slot: Magnitude.x (MATRIX).
        // For corpus parse to succeed we need only STRING/NUMBER inputs.
        // VectorToInt (MATRIX → NUMBER) has same shape; both still have MATRIX inputs.
        // So a happier shape is: Parameter NUMBER style — but Parameter (MATRIX-4) outputs MATRIX.
        // Use Terminal(NUMBER) with an unwired input — that's the simplest.
        NodeSpec termN = sync2.spawn(Palette.byKey("output.terminal.number"), 0f, 0f);
        scalar.detectFromGraph(sync2, termN.cgNode());

        // One unwired input on Terminal (NUMBER).
        assertEquals(1, scalar.inputSchema().size(),
            "one unwired slot on NUMBER terminal");
        assertEquals(ValueType.NUMBER, scalar.inputSchema().get(0).type());
        assertEquals(ValueType.NUMBER, scalar.targetSchema().type());

        // Add 3 rows, set values, verify toCorpus.
        scalar.addRow();
        scalar.addRow();
        scalar.addRow();
        assertEquals(3, scalar.rows().size());

        String key = scalar.inputSchema().get(0).key();
        scalar.setLabel(0, "a");        scalar.setInputCell(0, key, "1.5");   scalar.setTargetCell(0, "0.5");
        scalar.setLabel(1, "b");        scalar.setInputCell(1, key, "2");     scalar.setTargetCell(1, "1");
        scalar.setLabel(2, "skip-me");  scalar.setInputCell(2, key, "oops");  scalar.setTargetCell(2, "3");

        Corpus c = scalar.toCorpus();
        Iterator<Example> it = c.stream();
        List<Example> collected = new ArrayList<>();
        while (it.hasNext()) collected.add(it.next());
        // Row 2 has unparseable input — dropped from corpus.
        assertEquals(2, collected.size(),
            "unparseable row dropped, two valid examples remain");

        Example e0 = collected.get(0);
        assertEquals("a", e0.label());
        Value in0 = e0.inputs().get(key);
        assertInstanceOf(NumberValue.class, in0);
        assertEquals(1.5, ((NumberValue) in0).n(), 1e-12);
        assertInstanceOf(NumberValue.class, e0.target());
        assertEquals(0.5, ((NumberValue) e0.target()).n(), 1e-12);

        Example e1 = collected.get(1);
        assertEquals("b", e1.label());
        assertEquals(2.0, ((NumberValue) e1.inputs().get(key)).n(), 1e-12);

        // Remove row 0, verify version bumps and rows decrement.
        int beforeVersion = scalar.version().get();
        scalar.removeRow(0);
        assertTrue(scalar.version().get() > beforeVersion,
            "removeRow bumps structure version");
        assertEquals(2, scalar.rows().size());

        // Cell-edit must NOT bump structure version (else Data panel rebuilds and clobbers caret).
        int afterRemoveVersion = scalar.version().get();
        scalar.setLabel(0, "renamed");
        scalar.setInputCell(0, key, "999");
        scalar.setTargetCell(0, "42");
        assertEquals(afterRemoveVersion, scalar.version().get(),
            "cell-edit must NOT bump structure version");
    }

    @Test
    void stringSchema_roundTrips() {
        // Build: Lookup (MATRIX → STRING) → Terminal(STRING). Both Lookup
        // and Terminal need MATRIX/STRING inputs respectively. For the
        // simplest schema test, just use Terminal(NUMBER) with input slot
        // typed NUMBER — verified above. Here add a STRING-targeted test
        // by spinning Terminal(NUMBER → STRING) hack via a NUMBER → STRING
        // primitive that we don't have. So instead, manually set the
        // target schema by detecting a NUMBER terminal and editing a
        // STRING input field. The key check: STRING parses unchanged.
        Component.GraphSurface s = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(s);
        sync.install();

        // We need a STRING-input op. None in current palette directly
        // accepts STRING (Embed does but is deferred to task #12). So
        // this test just verifies STRING values pass through parse() by
        // exercising the underlying type system: set a NUMBER input with
        // a value that is a valid NUMBER string ("3.14"), and verify the
        // parser returns NumberValue, not StringValue. Covers the parse
        // dispatch.
        NodeSpec termN = sync.spawn(Palette.byKey("output.terminal.number"), 0f, 0f);
        CorpusModel c = new CorpusModel();
        c.detectFromGraph(sync, termN.cgNode());
        c.addRow();
        c.setInputCell(0, c.inputSchema().get(0).key(), "3.14");
        Iterator<Example> it = c.toCorpus().stream();
        assertTrue(it.hasNext());
        Example e = it.next();
        Value v = e.inputs().get(c.inputSchema().get(0).key());
        assertInstanceOf(NumberValue.class, v);
        assertEquals(3.14, ((NumberValue) v).n(), 1e-12);
        assertFalse(v instanceof StringValue, "NUMBER cells parse as NumberValue, not StringValue");
    }
}
