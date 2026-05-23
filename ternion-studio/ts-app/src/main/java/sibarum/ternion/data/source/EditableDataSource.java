package sibarum.ternion.data.source;

import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.mcc.value.ValueType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Mutable {@link DataSource} — the hand-edited side of the project.
 * Owns its columns (mutable header) and rows (each row is a
 * {@code String[]} sized to match {@link #columns()} at the moment the
 * row was constructed; the source keeps row arrays in sync when
 * columns are added/removed).
 *
 * <p>Every mutator bumps {@link #structureVersion} and calls
 * {@link Invalidator#invalidate} so the Data tab re-renders. Cell-level
 * edits also bump for V1 simplicity — finer-grained signalling
 * (separate {@code rowsVersion}) is a future optimisation.
 *
 * <p>Thread-safety: all mutators must run on the GLFW main thread,
 * mirroring the existing {@link sibarum.ternion.data.CorpusModel}
 * convention. Cell reads are safe from any thread.
 */
public final class EditableDataSource implements DataSource {

    private final String id;
    private String displayLabel;
    private int suggestedTargetColumnIndex = -1;

    private final List<String> columns = new ArrayList<>();
    /** Per-column declared {@link ValueType}, parallel to {@link #columns}.
     *  Default STRING — caller can override via {@link #setColumnType}. */
    private final List<ValueType> columnTypes = new ArrayList<>();
    private final List<String[]> rows = new ArrayList<>();

    private final Property<Integer> structureVersion = new Property<>(0);

    /** Construct a fresh editable source with the given columns and zero rows. */
    public EditableDataSource(String id, String displayLabel, List<String> columns) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must be non-blank");
        if (columns == null) throw new IllegalArgumentException("columns");
        for (String c : columns) {
            if (c == null || c.isBlank()) {
                throw new IllegalArgumentException("column names must be non-blank: " + columns);
            }
        }
        if (hasDuplicates(columns)) {
            throw new IllegalArgumentException("duplicate column names: " + columns);
        }
        this.id = id;
        this.displayLabel = (displayLabel == null || displayLabel.isBlank()) ? id : displayLabel;
        this.columns.addAll(columns);
        for (int i = 0; i < columns.size(); i++) columnTypes.add(ValueType.STRING);
    }

    public EditableDataSource(String id, List<String> columns) {
        this(id, id, columns);
    }

    // ---------- DataSource API ----------

    @Override public String id()                        { return id; }
    @Override public String displayLabel()              { return displayLabel; }
    @Override public Origin origin()                    { return Origin.EDITABLE; }
    @Override public boolean isEditable()               { return true; }
    @Override public List<String> columns()             { return Collections.unmodifiableList(columns); }
    @Override public int rowCount()                     { return rows.size(); }
    @Override public Property<Integer> structureVersion() { return structureVersion; }
    @Override public int suggestedTargetColumnIndex()   { return suggestedTargetColumnIndex; }

    @Override
    public String get(int row, int col) {
        return rows.get(row)[col];
    }

    @Override
    public String get(int row, String columnName) {
        int idx = columns.indexOf(columnName);
        if (idx < 0) throw new IllegalArgumentException(
            "unknown column '" + columnName + "' (have " + columns + ")");
        return rows.get(row)[idx];
    }

    @Override
    public ValueType columnType(String columnName) {
        int idx = columns.indexOf(columnName);
        return idx < 0 ? ValueType.STRING : columnTypes.get(idx);
    }

    /** Set the declared type for an existing column. New columns
     *  default to {@link ValueType#STRING} when added via
     *  {@link #addColumn} / {@link #insertColumn}; use this to
     *  declare MATRIX / NUMBER columns. */
    public void setColumnType(String columnName, ValueType type) {
        int idx = columns.indexOf(columnName);
        if (idx < 0) throw new IllegalArgumentException(
            "unknown column '" + columnName + "' (have " + columns + ")");
        if (type == null) throw new IllegalArgumentException("type");
        columnTypes.set(idx, type);
        bump();
    }

    // ---------- metadata mutators ----------

    public void setDisplayLabel(String label) {
        this.displayLabel = (label == null || label.isBlank()) ? id : label;
        bump();
    }

    public void setSuggestedTargetColumnIndex(int idx) {
        if (idx < -1 || idx >= columns.size()) {
            throw new IllegalArgumentException("suggestedTargetColumnIndex out of range: " + idx);
        }
        this.suggestedTargetColumnIndex = idx;
        bump();
    }

    // ---------- row mutators ----------

    /** Append a fresh row; new cells are empty strings. Returns the new row index. */
    public int addRow() {
        rows.add(blankRow());
        bump();
        return rows.size() - 1;
    }

    /** Insert a row at the given index (0..rowCount). Returns the index. */
    public int insertRow(int index) {
        if (index < 0 || index > rows.size()) {
            throw new IllegalArgumentException("insertRow index out of range: " + index);
        }
        rows.add(index, blankRow());
        bump();
        return index;
    }

    /** Delete one row. No-op if out of range. */
    public void removeRow(int index) {
        if (index < 0 || index >= rows.size()) return;
        rows.remove(index);
        bump();
    }

    /** Delete the half-open range [from, toExclusive); clamped to bounds. */
    public void removeRows(int from, int toExclusive) {
        int lo = Math.max(0, from);
        int hi = Math.min(rows.size(), toExclusive);
        if (lo >= hi) return;
        rows.subList(lo, hi).clear();
        bump();
    }

    /** Replace every row with the given snapshot. Each entry must have
     *  exactly {@code columns().size()} cells. */
    public void replaceRows(List<String[]> snapshot) {
        rows.clear();
        for (int i = 0; i < snapshot.size(); i++) {
            String[] r = snapshot.get(i);
            if (r == null) throw new IllegalArgumentException("row " + i + " is null");
            if (r.length != columns.size()) {
                throw new IllegalArgumentException(
                    "row " + i + " has " + r.length + " cells; expected " + columns.size());
            }
            rows.add(r.clone());
        }
        bump();
    }

    // ---------- cell mutators ----------

    public void setCell(int row, int col, String value) {
        rows.get(row)[col] = value == null ? "" : value;
        bump();
    }

    public void setCell(int row, String columnName, String value) {
        int idx = columns.indexOf(columnName);
        if (idx < 0) throw new IllegalArgumentException(
            "unknown column '" + columnName + "'");
        setCell(row, idx, value);
    }

    // ---------- column mutators ----------

    /** Append a new column. New cells in every existing row are empty
     *  strings. Throws on duplicate name. */
    public void addColumn(String name) {
        insertColumn(columns.size(), name);
    }

    /** Insert a column at the given index. Existing rows grow by one
     *  cell at that position. */
    public void insertColumn(int index, String name) {
        if (index < 0 || index > columns.size()) {
            throw new IllegalArgumentException("insertColumn index out of range: " + index);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("column name must be non-blank");
        }
        if (columns.contains(name)) {
            throw new IllegalArgumentException("duplicate column name: " + name);
        }
        columns.add(index, name);
        columnTypes.add(index, ValueType.STRING);
        for (int r = 0; r < rows.size(); r++) {
            String[] old = rows.get(r);
            String[] grown = new String[old.length + 1];
            System.arraycopy(old, 0, grown, 0, index);
            grown[index] = "";
            System.arraycopy(old, index, grown, index + 1, old.length - index);
            rows.set(r, grown);
        }
        if (suggestedTargetColumnIndex >= index) suggestedTargetColumnIndex++;
        bump();
    }

    /** Rename a column. Throws if the new name collides. */
    public void renameColumn(String oldName, String newName) {
        int idx = columns.indexOf(oldName);
        if (idx < 0) throw new IllegalArgumentException("unknown column '" + oldName + "'");
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("new column name must be non-blank");
        }
        if (oldName.equals(newName)) return;
        if (columns.contains(newName)) {
            throw new IllegalArgumentException("duplicate column name: " + newName);
        }
        columns.set(idx, newName);
        bump();
    }

    /** Remove a column; every row loses its corresponding cell. */
    public void removeColumn(String name) {
        int idx = columns.indexOf(name);
        if (idx < 0) return;
        removeColumn(idx);
    }

    /** Remove the column at the given index. */
    public void removeColumn(int index) {
        if (index < 0 || index >= columns.size()) return;
        columns.remove(index);
        columnTypes.remove(index);
        for (int r = 0; r < rows.size(); r++) {
            String[] old = rows.get(r);
            String[] shrunk = new String[old.length - 1];
            System.arraycopy(old, 0, shrunk, 0, index);
            System.arraycopy(old, index + 1, shrunk, index, old.length - index - 1);
            rows.set(r, shrunk);
        }
        if (suggestedTargetColumnIndex == index) suggestedTargetColumnIndex = -1;
        else if (suggestedTargetColumnIndex > index) suggestedTargetColumnIndex--;
        bump();
    }

    /**
     * Replace the entire column schema. Cells whose column name exists
     * in both the old and new schema are preserved; new columns get
     * empty strings; removed columns get dropped. Use for bulk
     * "redefine the table" operations (CSV import, dataset load).
     */
    public void replaceColumns(List<String> newColumns) {
        if (newColumns == null) throw new IllegalArgumentException("newColumns");
        for (String c : newColumns) {
            if (c == null || c.isBlank()) {
                throw new IllegalArgumentException("column names must be non-blank: " + newColumns);
            }
        }
        if (hasDuplicates(newColumns)) {
            throw new IllegalArgumentException("duplicate column names: " + newColumns);
        }
        // Build an old-name → old-index map for value + type preservation.
        int[] keepFromOld = new int[newColumns.size()];
        for (int i = 0; i < newColumns.size(); i++) {
            keepFromOld[i] = columns.indexOf(newColumns.get(i));
        }
        // Reshape every row.
        for (int r = 0; r < rows.size(); r++) {
            String[] old = rows.get(r);
            String[] rebuilt = new String[newColumns.size()];
            for (int i = 0; i < rebuilt.length; i++) {
                int oldIdx = keepFromOld[i];
                rebuilt[i] = (oldIdx >= 0 && oldIdx < old.length) ? old[oldIdx] : "";
            }
            rows.set(r, rebuilt);
        }
        // Preserve types for columns whose names survived; new
        // columns default to STRING.
        ValueType[] newTypes = new ValueType[newColumns.size()];
        for (int i = 0; i < newTypes.length; i++) {
            int oldIdx = keepFromOld[i];
            newTypes[i] = (oldIdx >= 0) ? columnTypes.get(oldIdx) : ValueType.STRING;
        }
        columns.clear();
        columns.addAll(newColumns);
        columnTypes.clear();
        Collections.addAll(columnTypes, newTypes);
        suggestedTargetColumnIndex = -1;
        bump();
    }

    // ---------- helpers ----------

    private String[] blankRow() {
        String[] r = new String[columns.size()];
        Arrays.fill(r, "");
        return r;
    }

    private void bump() {
        structureVersion.set(structureVersion.get() + 1);
        Invalidator.invalidate();
    }

    private static boolean hasDuplicates(List<String> xs) {
        return xs.stream().distinct().count() != xs.size();
    }
}
