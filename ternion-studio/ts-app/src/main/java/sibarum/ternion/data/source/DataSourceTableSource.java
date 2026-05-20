package sibarum.ternion.data.source;

import sibarum.dasum.gui.core.data.DataTableSource;
import sibarum.dasum.gui.core.em.Em;

import java.util.OptionalLong;

/**
 * Adapter from any {@link DataSource} to dasum's
 * {@link DataTableSource} contract — the spreadsheet widget's backing
 * provider. One adapter wraps one source; switching the Data tab's
 * displayed source is done by rebinding the table to a new adapter.
 *
 * <p>Behaviour split by {@link DataSource#isEditable()}:
 * <ul>
 *   <li><b>Bundled / Imported</b> — read-only. {@link #canEdit} returns
 *       {@code false}, and {@code canInsert*} / {@code canDelete*} all
 *       report no. The widget greys edits, hides the row context-menu
 *       insert/delete entries.</li>
 *   <li><b>Editable</b> — full CRUD: row insert/delete, column
 *       insert/delete (via this adapter), cell edit (via
 *       {@link #trySet}). All mutations route through
 *       {@link EditableDataSource}'s thread-affine mutators, which
 *       bump {@code structureVersion} + {@code Invalidator.invalidate}
 *       so the next frame re-reads.</li>
 * </ul>
 *
 * <p>Column widths default to a single uniform {@code 16em}. Per-column
 * width hints are a future addition (e.g. derive from observed content
 * width) — kept simple for V1.
 */
public final class DataSourceTableSource implements DataTableSource {

    private static final Em DEFAULT_COL_WIDTH = Em.of(16f);

    private final DataSource source;

    public DataSourceTableSource(DataSource source) {
        if (source == null) throw new IllegalArgumentException("source");
        this.source = source;
    }

    /** The underlying source, useful for callers that need typed access
     *  (e.g. casting to {@link EditableDataSource} for schema changes). */
    public DataSource source() { return source; }

    // ---------- columns ----------

    @Override public int columnCount()           { return source.columns().size(); }
    @Override public String columnHeader(int col) {
        return col >= 0 && col < source.columns().size() ? source.columns().get(col) : "";
    }
    @Override public Em columnWidth(int col)     { return DEFAULT_COL_WIDTH; }

    // ---------- rows ----------

    @Override public OptionalLong rowCount()     { return OptionalLong.of(source.rowCount()); }

    @Override
    public String get(int row, int col) {
        if (row < 0 || row >= source.rowCount()) return null;
        if (col < 0 || col >= source.columns().size()) return null;
        return source.get(row, col);
    }

    // ---------- mutation (editable only) ----------

    @Override
    public boolean trySet(int row, int col, String value) {
        if (!source.isEditable()) return false;
        if (row < 0 || row >= source.rowCount()) return false;
        if (col < 0 || col >= source.columns().size()) return false;
        ((EditableDataSource) source).setCell(row, col, value == null ? "" : value);
        return true;
    }

    @Override
    public void insertRowAbove(int row) {
        if (!source.isEditable()) {
            throw new UnsupportedOperationException("Source '" + source.id() + "' is read-only");
        }
        EditableDataSource eds = (EditableDataSource) source;
        eds.insertRow(Math.max(0, Math.min(eds.rowCount(), row)));
    }

    @Override
    public void deleteRows(int from, int toExclusive) {
        if (!source.isEditable()) {
            throw new UnsupportedOperationException("Source '" + source.id() + "' is read-only");
        }
        ((EditableDataSource) source).removeRows(from, toExclusive);
    }

    @Override
    public void insertColumnLeft(int col) {
        if (!source.isEditable()) {
            throw new UnsupportedOperationException("Source '" + source.id() + "' is read-only");
        }
        EditableDataSource eds = (EditableDataSource) source;
        // Auto-generate a fresh column name; the user can rename via the
        // column-header context menu. Names like "col2", "col3", … picked
        // to be deterministic and avoid collisions with existing headers.
        eds.insertColumn(Math.max(0, Math.min(eds.columns().size(), col)),
                         freshColumnName(eds));
    }

    @Override
    public void deleteColumns(int from, int toExclusive) {
        if (!source.isEditable()) {
            throw new UnsupportedOperationException("Source '" + source.id() + "' is read-only");
        }
        EditableDataSource eds = (EditableDataSource) source;
        int hi = Math.min(toExclusive, eds.columns().size());
        int lo = Math.max(0, from);
        // Reverse-order remove so indices stay valid.
        for (int i = hi - 1; i >= lo; i--) eds.removeColumn(i);
    }

    // ---------- capability flags ----------

    @Override public boolean canEdit(int row, int col) { return source.isEditable(); }
    @Override public boolean canInsertRows()           { return source.isEditable(); }
    @Override public boolean canDeleteRows()           { return source.isEditable(); }
    @Override public boolean canInsertColumns()        { return source.isEditable(); }
    @Override public boolean canDeleteColumns()        { return source.isEditable(); }

    // ---------- helpers ----------

    private static String freshColumnName(EditableDataSource eds) {
        int n = eds.columns().size() + 1;
        while (eds.columns().contains("col" + n)) n++;
        return "col" + n;
    }
}
