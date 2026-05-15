package sibarum.ternion.data;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Components;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.graph.GraphSurfaceChildren;
import sibarum.dasum.gui.core.graph.GraphSurfacePositions;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.op.Terminal;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.designer.NodeSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Data tab — corpus editor backed by {@link CorpusModel}. The row table
 * uses {@link Component.GraphSurface} as its host because dasum's Flex
 * record's child list is immutable, while {@link GraphSurfaceChildren}
 * already supports dynamic add/remove. Each row is absolutely-positioned
 * at {@code y = rowIndex * ROW_HEIGHT_EM}; rebuilds happen on schema
 * changes ("Detect Inputs"), add row, and delete row.
 */
public final class DataPanel {

    private static final Color SURFACE_BG = new Color(0.07f, 0.09f, 0.13f, 1f);
    private static final Color TOOLBAR_BG = new Color(0.10f, 0.12f, 0.15f, 1f);
    private static final Color LABEL_FG   = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color HINT_FG    = new Color(0.70f, 0.75f, 0.82f, 0.85f);

    public static Component build(AppContext ctx) {
        Component.GraphSurface table = new Component.GraphSurface(
            null, null, SURFACE_BG, List.of(), true, 0);

        Component toolbar = buildToolbar(ctx, table);
        Component scroll  = new Component.Scroll(
            null, null, Em.of(0.5f), SURFACE_BG, table, false, 1);

        // Render initial state. No schema yet, so table starts empty.
        rebuildTable(ctx, table);

        // Subscribe to corpus mutations — re-render the table when rows
        // change (add/delete/schema). Cell-content edits do NOT need
        // re-render: TextStates already owns the override.
        int[] lastVersion = { ctx.corpus().version().get() };
        ctx.corpus().version().subscribe(v -> {
            if (v == lastVersion[0]) return;
            // Schema or row-count change — rebuild. Per-cell text edits
            // also bump version but the rebuild path is idempotent for
            // them (no structural change → cheap).
            lastVersion[0] = v;
            rebuildTable(ctx, table);
        });

        return new Component.Flex(
            null, null, Em.ZERO, SURFACE_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(toolbar, scroll), false, 1
        );
    }

    private static Component buildToolbar(AppContext ctx, Component.GraphSurface table) {
        Component title = new Component.Text("Training Data", Em.of(1.0f), LABEL_FG);
        Component spacer = new Component.Flex(
            null, Em.of(1.5f), Em.ZERO, new Color(0f, 0f, 0f, 0f),
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.ZERO,
            List.of(), false, 1
        );
        Component hint = new Component.Text(
            "Detect schema from the current graph, then edit cells in place.",
            Em.of(0.85f), HINT_FG);

        Component detect = Themed.button("Detect Inputs", Em.of(8f), Variant.PRIMARY, 0);
        Component addRow = Themed.button("Add Row",       Em.of(6f), Variant.SUCCESS, 0);
        Handlers.onClick(detect, () -> {
            CompGraphNode terminal = findTerminal(ctx);
            if (terminal == null) {
                System.out.println("[data] no Terminal node found — schema unchanged");
                return;
            }
            ctx.corpus().detectFromGraph(ctx.graphSync(), terminal);
            System.out.println("[data] detected " + ctx.corpus().inputSchema().size()
                + " input(s); target=" + ctx.corpus().targetSchema().type());
        });
        Handlers.onClick(addRow, () -> ctx.corpus().addRow());

        return new Component.Flex(
            null, Em.of(2.5f), Em.of(0.5f), TOOLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.5f),
            List.of(title, hint, spacer, addRow, detect),
            false, 0
        );
    }

    /**
     * Tear down every dynamic child of {@code table} and rebuild from
     * the current {@link CorpusModel} state. Uses {@link Components#detach}
     * on each child so sidecars (Handlers, TextStates) are evicted —
     * essential because each row's cells carry edit-change listeners
     * that must not double-fire after a rebuild.
     */
    private static void rebuildTable(AppContext ctx, Component.GraphSurface table) {
        List<Component> existing = new ArrayList<>(GraphSurfaceChildren.added(table));
        for (Component child : existing) {
            Components.detach(child);
        }

        CorpusModel corpus = ctx.corpus();
        float y = 0f;

        // Header always present.
        Component header = ExampleRow.buildHeader(corpus);
        GraphSurfacePositions.set(table, header, 0f, y);
        GraphSurfaceChildren.add(table, header);
        y += ExampleRow.ROW_HEIGHT_EM;

        if (corpus.inputSchema().isEmpty()) {
            Component placeholder = new Component.Text(
                "Click \"Detect Inputs\" to derive column structure from the graph's unwired slots.",
                Em.of(0.95f), HINT_FG);
            // Wrap in a Flex so it positions cleanly within the GraphSurface.
            Component wrap = new Component.Flex(
                null, Em.of(ExampleRow.ROW_HEIGHT_EM), Em.of(0.4f), new Color(0f, 0f, 0f, 0f),
                Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.ZERO,
                List.of(placeholder), false, 0
            );
            GraphSurfacePositions.set(table, wrap, 0f, y);
            GraphSurfaceChildren.add(table, wrap);
        } else {
            for (int i = 0; i < corpus.rows().size(); i++) {
                Component row = ExampleRow.build(corpus, i);
                GraphSurfacePositions.set(table, row, 0f, y);
                GraphSurfaceChildren.add(table, row);
                y += ExampleRow.ROW_HEIGHT_EM;
            }
        }
        Invalidator.invalidate();
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
