package sibarum.ternion.designer;

import org.junit.jupiter.api.Test;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.render.Color;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.source.EditableDataSource;
import sibarum.ternion.train.TrainingController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        // Smallest training graph on the LossOutput path that exercises
        // the NodeRuntime tap: Parameter (MATRIX-4) → Loss Output. A
        // single source row supplies the supervisory target; the
        // trainer iterates one row and reads the scalar loss from the
        // sink's producedValue. The sink's preview must populate.
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();

        AppContext ctx = new AppContext(surface, sync);
        TrainingController controller = new TrainingController(ctx);
        ctx.attachTrainingController(controller);

        EditableDataSource src = new EditableDataSource(
            "fixture_preview", "Fixture", List.of("target"));
        int r = src.addRow();
        src.setCell(r, 0, "0.42, 0.42, 0.42, 0.42");
        ctx.dataSources().add(src);

        NodeSpec param = sync.spawn(Palette.byKey("input.parameter.matrix4"), 0f, 0f);
        Map<String, Object> lossCfg = new LinkedHashMap<>();
        lossCfg.put("source",    "fixture_preview:target");
        lossCfg.put("inputType", "MATRIX");
        lossCfg.put("lossFn",    "MSE");
        NodeSpec loss = sync.spawn(Palette.byKey("output.loss"), 6f, 0f, lossCfg);
        DesignerPanel.afterSpawn(sync, param);
        DesignerPanel.afterSpawn(sync, loss);
        Connections.add(surface, param.outputPort(), loss.orderedInputPorts().get(0));

        // Build the Designer panel — this is what wires the NodeRuntime
        // subscriber onto the spawned nodes' preview Texts.
        DesignerPanel.build(ctx);

        // Previews empty before training.
        assertEquals("", TextStates.contentOf(param.previewText()));
        assertEquals("", TextStates.contentOf(loss.previewText()));

        controller.start();
        Thread.sleep(200);
        controller.stop();

        // Preview updates run on the main render thread via
        // NodePreviewRefresher (so they don't race the renderer against
        // TextStates's unsynchronized map). In production that runs from
        // the event-loop closure; in tests we call it manually.
        NodePreviewRefresher.refresh();

        // Loss Output produces a NUMBER (scalar loss) regardless of its
        // input type, so the preview tag is "num ".
        String preview = TextStates.contentOf(loss.previewText());
        assertNotEquals("", preview, "preview populated after training");
        assertTrue(preview.startsWith("num "),
            "Loss Output preview starts with NodeRuntime.formatValue's 'num ' tag, got: " + preview);
    }
}
