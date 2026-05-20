package sibarum.ternion.data;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.data.DataTableSource;
import sibarum.dasum.gui.core.data.DataTableStates;
import sibarum.dasum.gui.core.data.TableSelection;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.overlay.Tooltips;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.status.Status;
import sibarum.dasum.gui.core.text.FontGroups;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.app.Toolbars;
import sibarum.ternion.app.generated.Icons;
import sibarum.ternion.data.source.DataSource;
import sibarum.ternion.data.source.DataSourceTableSource;
import sibarum.ternion.data.source.EditableDataSource;
import sibarum.ternion.data.source.SwitchableDataTableSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Data tab — multi-source editor backed by {@link AppContext#dataSources()},
 * rendered via dasum's {@link Component.DataTable} grid widget.
 *
 * <p>Layout:
 * <ul>
 *   <li><b>Pill row</b> (top) — one clickable label per registered
 *       source. Clicking a pill rebinds the table's source via a
 *       {@link SwitchableDataTableSource} so the {@code Component.DataTable}
 *       itself never needs rebuilding.</li>
 *   <li><b>Toolbar</b> (middle) — Add Row / Delete Row. Actions that
 *       don't apply to the current source (read-only bundled / imported)
 *       surface a {@link Status} hint rather than disabling.</li>
 *   <li><b>Table</b> (rest) — the grid. Read-only sources grey edits
 *       via {@link DataTableSource#canEdit}.</li>
 * </ul>
 */
public final class DataPanel {

    private static final Color SURFACE_BG = new Color(0.07f, 0.09f, 0.13f, 1f);
    private static final Color TOOLBAR_BG = new Color(0.10f, 0.12f, 0.15f, 1f);
    private static final Color PILLBAR_BG = new Color(0.09f, 0.11f, 0.14f, 1f);
    private static final Color PILL_BG    = new Color(0.16f, 0.19f, 0.24f, 1f);
    private static final Color LABEL_FG   = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color TRANSPARENT = new Color(0f, 0f, 0f, 0f);

    public static Component build(AppContext ctx) {
        // Start on the first registered source (typically the first
        // bundled dataset) — the user switches via the pill row.
        DataSource firstSource = ctx.dataSources().all().isEmpty()
            ? null : ctx.dataSources().all().get(0);
        DataTableSource initial = firstSource == null
            ? new EmptyDataTableSource()
            : new DataSourceTableSource(firstSource);
        Property<DataTableSource> currentDelegate = new Property<>(initial);
        Property<String> currentId = new Property<>(
            firstSource == null ? "" : firstSource.id());

        Property<TableSelection> selection = new Property<>(null);
        SwitchableDataTableSource adapter = new SwitchableDataTableSource(currentDelegate);

        Component.DataTable table = new Component.DataTable(
            null, null,
            Em.of(1.9f), Em.of(1.7f), Em.of(3.8f),
            adapter,
            new Color(0.16f, 0.18f, 0.22f, 1f),
            new Color(0.10f, 0.12f, 0.15f, 1f),
            new Color(0.08f, 0.10f, 0.13f, 1f),
            new Color(0.22f, 0.24f, 0.30f, 1f),
            new Color(0.30f, 0.55f, 0.85f, 0.35f),
            new Color(0.92f, 0.94f, 0.97f, 1f),
            FontGroups.DEFAULT, Em.of(0.95f),
            selection,
            true, 1
        );

        Component.Text activeLabel = new Component.Text(
            "Active: " + (firstSource == null ? "(no sources)" : firstSource.displayLabel()),
            Em.of(1.0f), LABEL_FG);

        Component pillRow = buildPillRow(ctx, currentDelegate, currentId, selection,
                                         table, activeLabel);
        Component toolbar = buildToolbar(currentDelegate, selection, table, activeLabel);

        return new Component.Flex(
            null, null, Em.ZERO, SURFACE_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(pillRow, toolbar, table), false, 1
        );
    }

    // ---------- pill row ----------

    private static Component buildPillRow(AppContext ctx,
                                          Property<DataTableSource> currentDelegate,
                                          Property<String> currentId,
                                          Property<TableSelection> selection,
                                          Component.DataTable table,
                                          Component.Text activeLabel) {
        List<Component> pills = new ArrayList<>();
        for (DataSource ds : ctx.dataSources().all()) {
            String tip = ds.origin() + " · " + ds.rowCount() + " rows · "
                       + ds.columns().size() + " cols";
            pills.add(pill(ds.displayLabel(), tip,
                () -> switchTo(ds.id(), ds.displayLabel(),
                    new DataSourceTableSource(ds),
                    currentDelegate, currentId, selection, table, activeLabel)));
        }
        return new Component.Flex(
            null, Em.of(2.2f), Em.of(0.5f), PILLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.4f),
            pills, false, 0
        );
    }

    private static Component pill(String label, String tooltip, Runnable onClick) {
        Component.Text text = new Component.Text(label, Em.of(0.95f), LABEL_FG);
        Component.Flex p = new Component.Flex(
            null, Em.of(1.7f), Em.of(0.7f), PILL_BG,
            Direction.ROW, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
            List.of(text), true, 0
        );
        Handlers.onClick(p, onClick);
        if (tooltip != null && !tooltip.isEmpty()) Tooltips.set(p, tooltip);
        return p;
    }

    private static void switchTo(String id, String displayLabel,
                                 DataTableSource newDelegate,
                                 Property<DataTableSource> currentDelegate,
                                 Property<String> currentId,
                                 Property<TableSelection> selection,
                                 Component.DataTable table,
                                 Component.Text activeLabel) {
        currentDelegate.set(newDelegate);
        currentId.set(id);
        // Stale selection (column index past new schema) — clear so
        // the renderer doesn't paint an out-of-range highlight.
        DataTableStates.setSelection(table, null);
        selection.set(null);
        TextStates.setContent(activeLabel, "Active: " + displayLabel);
        Invalidator.invalidate();
    }

    // ---------- toolbar ----------

    private static Component buildToolbar(Property<DataTableSource> currentDelegate,
                                          Property<TableSelection> selection,
                                          Component.DataTable table,
                                          Component.Text activeLabel) {
        Component spacer = new Component.Flex(
            null, Em.of(1.5f), Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.ZERO,
            List.of(), false, 1);

        Component addRow = Toolbars.iconButton(
            Icons.PLUS, Variant.SUCCESS,
            "Add Row — append a new row to the active source.",
            () -> addRow(currentDelegate));
        Component deleteRows = Toolbars.iconButton(
            Icons.TRASH_2, Variant.ERROR,
            "Delete Row(s) — remove the rows currently selected in the "
            + "table. Click a row gutter (or shift-click for a range) "
            + "first.",
            () -> deleteSelectedRows(table, selection, currentDelegate));

        return new Component.Flex(
            null, Em.of(2.5f), Em.of(0.5f), TOOLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.6f),
            List.of(activeLabel, spacer, addRow, deleteRows),
            false, 0
        );
    }

    private static void addRow(Property<DataTableSource> currentDelegate) {
        DataTableSource cur = currentDelegate.get();
        if (cur instanceof DataSourceTableSource dsts) {
            if (!dsts.source().isEditable()) {
                Status.warn("Cannot add row — source '" + dsts.source().id() + "' is read-only.");
                return;
            }
            ((EditableDataSource) dsts.source()).addRow();
            return;
        }
        Status.warn("Add Row: active source has no row-add affordance.");
    }

    private static void deleteSelectedRows(Component.DataTable table,
                                           Property<TableSelection> selection,
                                           Property<DataTableSource> currentDelegate) {
        TableSelection sel = selection.get();
        if (sel == null) {
            Status.warn("Delete Row(s): no selection — click a row gutter first.");
            return;
        }
        DataTableSource cur = currentDelegate.get();
        if (!cur.canDeleteRows()) {
            Status.warn("Delete Row(s): active source is read-only.");
            return;
        }
        int from = Math.max(0, sel.rowStart());
        int to   = sel.rowEnd();
        if (from > to) return;
        cur.deleteRows(from, to + 1);
        DataTableStates.setSelection(table, null);
        selection.set(null);
        Invalidator.invalidate();
        int removed = to - from + 1;
        Status.success("Deleted " + removed + " row" + (removed == 1 ? "" : "s") + ".");
    }

    /** Empty placeholder source used when no DataSource is registered.
     *  Shows a zero-column, zero-row table; the user sees an empty grid
     *  rather than an NPE on the first frame. */
    private static final class EmptyDataTableSource implements DataTableSource {
        @Override public int columnCount() { return 0; }
        @Override public String columnHeader(int col) { return ""; }
        @Override public Em columnWidth(int col) { return Em.of(8f); }
        @Override public java.util.OptionalLong rowCount() { return java.util.OptionalLong.of(0L); }
        @Override public String get(int row, int col) { return null; }
    }

    private DataPanel() {}
}
