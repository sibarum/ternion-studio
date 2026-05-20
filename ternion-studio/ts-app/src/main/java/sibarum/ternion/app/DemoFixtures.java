package sibarum.ternion.app;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.graph.Ports;
import sibarum.dasum.gui.core.status.Status;
import sibarum.ternion.data.source.DataSourceRegistry;
import sibarum.ternion.data.source.EditableDataSource;
import sibarum.ternion.designer.DesignerPanel;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.designer.NodeSpec;
import sibarum.ternion.designer.Palette;
import sibarum.ternion.designer.PaletteItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-keypress demo scenarios for manual smoke-testing. Each scenario
 * wipes the current workspace (graph + corpus) and rebuilds a known-good
 * configuration so testing a feature doesn't require clicking through
 * three tabs to recreate the setup every iteration.
 *
 * <p>Wire each scenario as a {@code CommandRegistry} entry in
 * {@link TsApp#registerCommands} so it's reachable via Ctrl+Space.
 */
public final class DemoFixtures {

    private DemoFixtures() {}

    /**
     * Build the minimal visualizer-test graph: a MATRIX-4 Parameter feeds
     * a Visualizer (pass-through), which feeds a MATRIX Terminal. One
     * corpus row with a non-trivial target so the loss isn't immediately
     * zero. Training will start producing trajectory points on the
     * visualizer's cloud within the first few examples.
     *
     * <p>Idempotent — call it again to reset to a clean state.
     */
    public static void installVisualizerDemo(AppContext ctx) {
        GraphSync sync = ctx.graphSync();

        // 1. Wipe the slate — graph + any prior demo source so re-running
        //    is idempotent.
        sync.clearAll();
        DataSourceRegistry reg = ctx.dataSources();
        reg.removeForced(DEMO_SOURCE_ID);

        // 2. Build a fresh editable source carrying the supervisory
        //    targets. The trainer will iterate this source's rows in
        //    lockstep via the LossOutput sink's binding.
        String[] targets = {
            " 1.0,  0.5, -0.5,  0.25",
            " 0.8,  0.3, -0.7,  0.40",
            "-0.5,  1.0,  0.2, -0.30",
            " 0.2, -0.8,  0.6,  0.10",
            " 1.0,  1.0,  1.0,  1.00",
            "-1.0, -1.0, -1.0, -1.00",
            " 0.5, -0.5,  0.5, -0.50",
            "-0.3,  0.7, -0.4,  0.80",
        };
        EditableDataSource demoSrc = new EditableDataSource(
            DEMO_SOURCE_ID, "Visualizer Demo Targets", List.of("target"));
        for (String t : targets) {
            int r = demoSrc.addRow();
            demoSrc.setCell(r, 0, t);
        }
        reg.add(demoSrc);

        // 3. Spawn nodes by palette key. We go through GraphSync.spawn
        //    directly (not DesignerPanel.finalizeSpawn) so this works
        //    regardless of which tab is active — the finalize hooks
        //    still needed (context menu, visualizer decoration,
        //    DataSourceBoundNodes registration) get called manually
        //    below.
        PaletteItem paramItem = Palette.byKey("input.parameter.matrix4");
        PaletteItem visItem   = Palette.byKey("visualizer.matrix");
        PaletteItem lossItem  = Palette.byKey("output.loss");
        if (paramItem == null || visItem == null || lossItem == null) {
            Status.error("Demo fixture: missing palette key — check Palette.java.");
            return;
        }

        Map<String, Object> lossCfg = new LinkedHashMap<>();
        lossCfg.put("source",    DEMO_SOURCE_ID + ":target");
        lossCfg.put("inputType", "MATRIX");
        lossCfg.put("lossFn",    "MSE");

        NodeSpec paramSpec = sync.spawn(paramItem,  2f,  4f);
        NodeSpec visSpec   = sync.spawn(visItem,   18f,  4f);
        NodeSpec lossSpec  = sync.spawn(lossItem,  40f,  4f, lossCfg);
        DesignerPanel.afterSpawn(sync, paramSpec);
        DesignerPanel.afterSpawn(sync, visSpec);
        DesignerPanel.afterSpawn(sync, lossSpec);

        // 4. Wire Parameter.value → Visualizer.in → Loss Output.predicted.
        if (!wire(sync.surface(), paramSpec, "value", visSpec,  "in"))        return;
        if (!wire(sync.surface(), visSpec,   "out",   lossSpec, "predicted")) return;

        // 5. Sensible defaults for the training knob.
        ctx.trainingController().learningRate().set(0.05f);

        Invalidator.invalidate();
        Status.success(
            "Demo: Parameter → Visualizer → Loss Output · " + targets.length
            + " rows · LR 0.05. Switch to the Train tab and press Start (or Space).");
    }

    /** Source id the visualizer demo registers + binds Loss Output to. */
    private static final String DEMO_SOURCE_ID = "demo_targets";

    /**
     * Wire {@code fromNode.fromPort} → {@code toNode.toPort} via
     * {@link Connections#add}, which fires the same listener chain a
     * drag-and-drop would. Returns false (and prints status) if either
     * port name doesn't resolve.
     */
    private static boolean wire(Component surface,
                                NodeSpec fromNode, String fromPort,
                                NodeSpec toNode,   String toPort) {
        Ports.Port out = Ports.byName(fromNode.visual(), fromPort);
        Ports.Port in  = Ports.byName(toNode.visual(),   toPort);
        if (out == null || in == null) {
            Status.error("Demo fixture: missing port "
                + (out == null ? fromPort : toPort) + " on "
                + (out == null ? fromNode.cgNode().id() : toNode.cgNode().id()));
            return false;
        }
        try {
            Connections.add(surface, out.component(), in.component());
            return true;
        } catch (RuntimeException ex) {
            Status.error("Demo fixture: connect "
                + fromNode.cgNode().id() + "." + fromPort + " → "
                + toNode.cgNode().id() + "." + toPort + " failed: " + ex.getMessage());
            return false;
        }
    }

}
