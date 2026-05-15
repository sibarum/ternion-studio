package sibarum.ternion.designer;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.input.ContextMenuItem;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.PortContextMenu;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.ComputationGraph;
import sibarum.mcc.op.Terminal;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.app.AppContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Designer tab. Hosts a {@link Component.GraphSurface} that fills the
 * pane, with a thin top toolbar for a "Run forward" smoke action.
 * Right-click on empty surface area opens the spawn menu; right-click
 * on a node opens its node menu (Delete Node).
 */
public final class DesignerPanel {

    private static final Color SURFACE_BG = new Color(0.05f, 0.07f, 0.10f, 1f);
    private static final Color TOOLBAR_BG = new Color(0.10f, 0.12f, 0.15f, 1f);
    private static final Color LABEL_FG   = new Color(0.92f, 0.94f, 0.97f, 1.00f);

    public static Component build(AppContext ctx) {
        Component.GraphSurface surface = ctx.designerSurface();
        GraphSync sync = ctx.graphSync();

        wireSurfaceContextMenu(surface, sync);
        registerPaletteCommands(sync);

        Component toolbar = buildToolbar(sync);

        // Surface was constructed with flexGrow=1 in MainShell so we don't
        // need (and must not) transform it here. Calling .withFlexGrow would
        // return a new record instance and orphan every sidecar registration
        // (right-click, dynamic children) keyed to the original.
        return new Component.Flex(
            null, null, Em.ZERO, TOOLBAR_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(toolbar, surface),
            false, 1
        );
    }

    private static Component buildToolbar(GraphSync sync) {
        Component hint = new Component.Text(
            "Right-click surface to add a node · right-click a node to delete · drag port-to-port to wire",
            Em.of(0.85f), LABEL_FG);
        Component spacer = new Component.Flex(
            null, Em.of(2f), Em.ZERO, new Color(0f, 0f, 0f, 0f),
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.ZERO,
            List.of(), false, 1);
        Component runBtn = Themed.button("Run Forward", Em.of(8f), Variant.SUCCESS, 0);
        Handlers.onClick(runBtn, () -> runForward(sync));

        return new Component.Flex(
            null, Em.of(2.5f), Em.of(0.4f), TOOLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.5f),
            List.of(hint, spacer, runBtn),
            false, 0
        );
    }

    /**
     * Register one {@code node.spawn.<key>} command per palette item, so
     * Ctrl+Space's command palette can spawn nodes even when surface
     * right-click isn't reachable. Each command spawns at (3, 3) em.
     */
    private static void registerPaletteCommands(GraphSync sync) {
        for (PaletteItem item : Palette.items()) {
            sibarum.dasum.gui.core.command.CommandRegistry.register(
                "node.spawn." + item.key(),
                "Spawn: " + item.label(),
                () -> spawnAndWire(sync, item, 3f, 3f));
        }
    }

    private static void wireSurfaceContextMenu(Component.GraphSurface surface, GraphSync sync) {
        Handlers.onContextMenu(surface, event -> {
            float emX = event.localEmX(surface);
            float emY = event.localEmY(surface);
            List<ContextMenuItem> items = new ArrayList<>();
            for (PaletteItem palette : Palette.items()) {
                items.add(new ContextMenuItem(
                    "Add: " + palette.label(),
                    () -> spawnAndWire(sync, palette, emX, emY)));
            }
            return items;
        });
    }

    private static void spawnAndWire(GraphSync sync, PaletteItem palette, float emX, float emY) {
        NodeSpec spec = sync.spawn(palette, emX, emY);
        wireNodeContextMenu(sync, spec);
        PortContextMenu.registerDefaults(spec.visual());
        Invalidator.invalidate();
    }

    private static void wireNodeContextMenu(GraphSync sync, NodeSpec spec) {
        Handlers.onContextMenu(spec.visual(), event -> List.of(
            new ContextMenuItem("Delete Node",
                () -> sync.removeNode(spec.visual()))
        ));
    }

    /**
     * Phase 3 smoke-test action. Finds a Terminal node, snapshots the
     * graph, binds NUMBER/MATRIX root values for any unwired slots from
     * fixed defaults, and runs {@link ComputationGraph#execute}.
     * Prints to stdout (the Train tab will replace this in Phase 6).
     */
    private static void runForward(GraphSync sync) {
        NodeSpec terminalSpec = findTerminal(sync);
        if (terminalSpec == null) {
            System.out.println("[run] no Terminal node in graph");
            return;
        }
        try {
            ComputationGraph graph = sync.snapshot(terminalSpec.cgNode());
            bindUnwiredSlots(sync, graph);
            Value out = graph.execute();
            System.out.println("[run] output = " + formatValue(out));
        } catch (Exception e) {
            System.out.println("[run] failed: " + e.getMessage());
        }
    }

    private static NodeSpec findTerminal(GraphSync sync) {
        for (NodeSpec spec : sync.liveNodes()) {
            if (spec.tNode().primitive() instanceof Terminal) return spec;
        }
        return null;
    }

    /** Bind zero defaults to any unwired input slot — placeholder for Phase 5's corpus binding. */
    private static void bindUnwiredSlots(GraphSync sync, ComputationGraph graph) {
        for (CompGraphNode n : graph.nodes()) {
            for (int i = 0; i < n.slotCount(); i++) {
                if (n.slot(i) != null) continue;
                ValueType t = n.tNode().primitive().inputTypes().get(i);
                graph.bindRoot(n, i, zeroFor(t));
            }
        }
    }

    private static Value zeroFor(ValueType t) {
        return switch (t) {
            case NUMBER -> new NumberValue(0.0);
            case MATRIX -> new MatrixValue(new double[] { 0.0, 0.0, 0.0, 0.0 });
            default -> throw new IllegalStateException("Phase 3 demo only binds NUMBER/MATRIX defaults; got " + t);
        };
    }

    private static String formatValue(Value v) {
        return switch (v) {
            case NumberValue n -> "Number(" + n.n() + ")";
            case MatrixValue m -> "Matrix[" + m.data().length + "]" + java.util.Arrays.toString(m.data());
            case null -> "null";
            default -> v.toString();
        };
    }

    private DesignerPanel() {}
}
