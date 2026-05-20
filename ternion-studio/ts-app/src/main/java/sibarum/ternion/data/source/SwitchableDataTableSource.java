package sibarum.ternion.data.source;

import sibarum.dasum.gui.core.data.DataTableSource;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.reactive.Property;

import java.util.OptionalLong;

/**
 * Indirection layer over a {@link Property} of {@link DataTableSource}
 * so the dasum {@code Component.DataTable} (which holds its source as
 * a final record field) doesn't need rebuilding when the user picks a
 * different source. Every call routes through the current delegate.
 *
 * <p>The {@code DataTable} renderer re-queries shape + cell methods on
 * every visible frame, so swapping the delegate via
 * {@link Property#set} is immediately reflected — no extra invalidate
 * needed beyond what {@code Property} itself fires.
 *
 * <p>Read-only and editable delegates are both supported; capability
 * predicates and the mutation methods route through unchanged, so the
 * table widget greys edit affordances when an immutable delegate is
 * current.
 */
public final class SwitchableDataTableSource implements DataTableSource {

    private final Property<DataTableSource> delegate;

    public SwitchableDataTableSource(Property<DataTableSource> delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate");
        this.delegate = delegate;
    }

    public Property<DataTableSource> delegate() { return delegate; }

    private DataTableSource d() {
        DataTableSource cur = delegate.get();
        if (cur == null) {
            throw new IllegalStateException("SwitchableDataTableSource has no delegate set");
        }
        return cur;
    }

    @Override public int columnCount()                       { return d().columnCount(); }
    @Override public String columnHeader(int col)            { return d().columnHeader(col); }
    @Override public Em columnWidth(int col)                 { return d().columnWidth(col); }
    @Override public OptionalLong rowCount()                 { return d().rowCount(); }
    @Override public String get(int row, int col)            { return d().get(row, col); }

    @Override public boolean trySet(int row, int col, String value) { return d().trySet(row, col, value); }
    @Override public void insertRowAbove(int row)            { d().insertRowAbove(row); }
    @Override public void insertRowBelow(int row)            { d().insertRowBelow(row); }
    @Override public void deleteRows(int from, int toExclusive) { d().deleteRows(from, toExclusive); }
    @Override public void insertColumnLeft(int col)          { d().insertColumnLeft(col); }
    @Override public void insertColumnRight(int col)         { d().insertColumnRight(col); }
    @Override public void deleteColumns(int from, int toExclusive) { d().deleteColumns(from, toExclusive); }

    @Override public boolean canEdit(int row, int col)       { return d().canEdit(row, col); }
    @Override public boolean canInsertRows()                 { return d().canInsertRows(); }
    @Override public boolean canDeleteRows()                 { return d().canDeleteRows(); }
    @Override public boolean canInsertColumns()              { return d().canInsertColumns(); }
    @Override public boolean canDeleteColumns()              { return d().canDeleteColumns(); }
}
