package sibarum.ternion.designer;

import org.junit.jupiter.api.Test;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.render.Color;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.CorpusModel;
import sibarum.ternion.data.InputSchema;
import sibarum.ternion.train.TrainingController;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verify the Phase 8 wiring: each spawned node carries a stable
 * preview Text that gets populated by the training worker's
 * {@code NodeRuntime} updates and stays in sync via TextStates.
 */
final class NodePreviewTest {

    @Test
    void spawnedNodeHasEmptyPreview() {
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();

        NodeSpec spec = sync.spawn(Palette.byKey("act.relu"), 0f, 0f);
        assertNotNull(spec.previewText(),
            "spawned NodeSpec carries a stable preview Text");
        assertEquals("", TextStates.contentOf(spec.previewText()),
            "preview starts empty");
    }

    @Test
    void runtimeUpdatesPopulatePreviewAfterTraining() throws Exception {
        // Smallest training graph that exercises the NodeRuntime path:
        // Terminal(NUMBER) with one unwired NUMBER input → an identity
        // regression from supplied input to target.
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();

        NodeSpec term = sync.spawn(Palette.byKey("output.terminal.number"), 0f, 0f);

        CorpusModel corpus = new CorpusModel();
        corpus.detectFromGraph(sync, term.cgNode());
        InputSchema in = corpus.inputSchema().get(0);
        for (int i = 0; i < 5; i++) {
            corpus.addRow();
            corpus.setInputCell(i, in.key(), Double.toString(0.5 + i * 0.1));
            corpus.setTargetCell(i, "0.42");
        }

        AppContext ctx = new AppContext(surface, sync, corpus);
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        // Build the Designer panel — this is what wires the NodeRuntime
        // subscriber onto the spawned nodes' preview Texts.
        DesignerPanel.build(ctx);

        // Preview is empty before training.
        assertEquals("", TextStates.contentOf(term.previewText()));

        controller.start();
        Thread.sleep(200);
        controller.stop();

        // Preview updates now happen on the main render thread via
        // NodePreviewRefresher (so they don't race the renderer against
        // TextStates's unsynchronized map). In production that runs from
        // the event-loop closure; in tests we call it manually.
        NodePreviewRefresher.refresh();

        // The state subscriber forces a refresh on STOPPED so by the
        // time stop() returns the preview must be populated. NumberValue
        // previews start with "num " per NodeRuntime.formatValue.
        String preview = TextStates.contentOf(term.previewText());
        assertNotEquals("", preview, "preview populated after training");
        assertTrue(preview.startsWith("num "),
            "NUMBER terminal preview starts with NodeRuntime.formatValue's 'num ' tag, got: " + preview);
    }
}
