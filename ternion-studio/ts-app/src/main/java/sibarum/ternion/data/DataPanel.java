package sibarum.ternion.data;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Components;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.DynamicChildren;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.status.Status;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.op.Terminal;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.designer.NodeSpec;

import java.util.List;

/**
 * Data tab — corpus editor backed by {@link CorpusModel}. The row table
 * is a vertical {@link Component.Flex} with rows added via
 * {@link DynamicChildren}.
 */
public final class DataPanel {

    private static final Color SURFACE_BG = new Color(0.07f, 0.09f, 0.13f, 1f);
    private static final Color TOOLBAR_BG = new Color(0.10f, 0.12f, 0.15f, 1f);
    private static final Color LABEL_FG   = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color HINT_FG    = new Color(0.70f, 0.75f, 0.82f, 0.85f);
    private static final Color TRANSPARENT = new Color(0f, 0f, 0f, 0f);

    public static Component build(AppContext ctx) {
        Component.Flex rowColumn = new Component.Flex(
            null, null, Em.ZERO, TRANSPARENT,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(), false, 1
        );

        Component toolbar = buildToolbar(ctx);
        Component scroll  = new Component.Scroll(
            null, null, Em.of(0.5f), SURFACE_BG, rowColumn, false, 1);

        rebuildRows(ctx, rowColumn);
        ctx.corpus().version().subscribe(v -> rebuildRows(ctx, rowColumn));

        return new Component.Flex(
            null, null, Em.ZERO, SURFACE_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(toolbar, scroll), false, 1
        );
    }

    private static Component buildToolbar(AppContext ctx) {
        Component title = new Component.Text("Training Data", Em.of(1.0f), LABEL_FG);
        Component hint  = new Component.Text(
            "Set up your graph in the Designer, then click \"Detect Inputs\".",
            Em.of(0.85f), HINT_FG);
        Component spacer = new Component.Flex(
            null, Em.of(1.5f), Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.ZERO,
            List.of(), false, 1);

        Component detect = Themed.button("Detect Inputs", Em.of(8f), Variant.PRIMARY, 0, () -> {
            CompGraphNode terminal = findTerminal(ctx);
            if (terminal == null) {
                Status.error("Detect Inputs: no Terminal node in the graph",
                    "Add a Terminal (MATRIX) or Terminal (NUMBER) node in the Designer first.");
                return;
            }
            ctx.corpus().detectFromGraph(ctx.graphSync(), terminal);
            Status.success("Detected " + ctx.corpus().inputSchema().size()
                + " input column(s); target = " + ctx.corpus().targetSchema().type());
        });
        Component addRow = Themed.button("Add Row", Em.of(6f), Variant.SUCCESS, 0,
            () -> ctx.corpus().addRow());

        return new Component.Flex(
            null, Em.of(2.5f), Em.of(0.5f), TOOLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.6f),
            List.of(title, hint, spacer, addRow, detect),
            false, 0
        );
    }

    private static void rebuildRows(AppContext ctx, Component.Flex rowColumn) {
        List<Component> existing = DynamicChildren.added(rowColumn);
        for (Component child : existing) {
            Components.detach(child);
        }

        CorpusModel corpus = ctx.corpus();
        DynamicChildren.add(rowColumn, ExampleRow.buildHeader(corpus));

        if (corpus.inputSchema().isEmpty() && corpus.rows().isEmpty()) {
            Component hintRow = new Component.Text(
                "Empty corpus. \"Add Row\" to start, or \"Detect Inputs\" to derive columns from the graph.",
                Em.of(0.95f), HINT_FG);
            DynamicChildren.add(rowColumn, padded(hintRow));
        } else {
            for (int i = 0; i < corpus.rows().size(); i++) {
                DynamicChildren.add(rowColumn, ExampleRow.build(corpus, i));
            }
        }
        Invalidator.invalidate();
    }

    private static Component padded(Component inner) {
        return new Component.Flex(
            null, Em.of(ExampleRow.ROW_HEIGHT_EM), Em.of(0.4f), TRANSPARENT,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.ZERO,
            List.of(inner), false, 0
        );
    }

    private static CompGraphNode findTerminal(AppContext ctx) {
        for (NodeSpec spec : ctx.graphSync().liveNodes()) {
            if (spec.tNode().primitive() instanceof Terminal) {
                return spec.cgNode();
            }
        }
        return null;
    }

    private DataPanel() {}
}
