package sibarum.ternion.data.curated;

import sibarum.mcc.value.ValueType;

import java.util.Collections;
import java.util.List;

/**
 * Immutable view of one curated dataset bundled with the executable.
 * Cells are stored as raw strings — same shape the source CSV had — so
 * downstream parse/tokenize/embed steps can run inside the computation
 * graph rather than at load time.
 *
 * <p>Row-major storage ({@code rows[row][col]}) keeps reads cheap and
 * keeps the in-memory layout close to what the source file looked like;
 * a future native-image build-time initialization step can freeze this
 * record straight into the executable's image without re-parsing.
 *
 * <p>{@link #targetColumnIndex} marks which column the source treats as
 * the supervisory target (e.g. {@code upos} for the POS dataset). The
 * {@code Data} tab's loader uses it to decide which column maps to the
 * corpus's target schema vs the input schemas. {@code -1} means the
 * dataset is unsupervised — all columns become inputs.
 *
 * <p>{@link #columnTypes} declares each column's parse target —
 * {@link ValueType#STRING}, {@link ValueType#NUMBER}, or
 * {@link ValueType#MATRIX}. Defaults to all-STRING when not supplied,
 * so older registrations (no type hints) still load. Primitives that
 * support {@code AUTO} output-type resolution read these via the
 * {@link sibarum.ternion.data.source.DataSource#columnType DataSource}
 * facade.
 *
 * @param id                 stable identifier; also the classpath leaf
 *                           name minus {@code .csv}
 * @param displayLabel       human-readable label for the loader UI
 * @param columns            header row from the source CSV, in order
 * @param columnTypes        per-column declared {@link ValueType}, parallel
 *                           to {@code columns}; same length
 * @param targetColumnIndex  column to bind as the training target,
 *                           or {@code -1} for unsupervised
 * @param rows               row-major cell data; lengths must match
 *                           {@code columns.size()} per row
 */
public record CuratedDataset(
    String id,
    String displayLabel,
    List<String> columns,
    List<ValueType> columnTypes,
    int targetColumnIndex,
    String[][] rows
) {

    public CuratedDataset {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
        if (displayLabel == null) throw new IllegalArgumentException("displayLabel");
        columns = List.copyOf(columns);
        if (columnTypes == null) {
            // Default: every column STRING. Preserves the original
            // 5-arg constructor's semantics for callers that don't
            // know about typed columns yet.
            ValueType[] all = new ValueType[columns.size()];
            for (int i = 0; i < all.length; i++) all[i] = ValueType.STRING;
            columnTypes = List.of(all);
        } else {
            columnTypes = List.copyOf(columnTypes);
            if (columnTypes.size() != columns.size()) {
                throw new IllegalArgumentException(
                    "columnTypes size " + columnTypes.size()
                    + " ≠ columns size " + columns.size());
            }
        }
        if (targetColumnIndex < -1 || targetColumnIndex >= columns.size()) {
            throw new IllegalArgumentException(
                "targetColumnIndex out of range: " + targetColumnIndex);
        }
        if (rows == null) throw new IllegalArgumentException("rows");
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == null || rows[i].length != columns.size()) {
                throw new IllegalArgumentException(
                    "row " + i + " has " + (rows[i] == null ? 0 : rows[i].length)
                    + " cells; expected " + columns.size());
            }
        }
    }

    /** Back-compat constructor — all columns default to STRING. */
    public CuratedDataset(String id, String displayLabel,
                          List<String> columns,
                          int targetColumnIndex,
                          String[][] rows) {
        this(id, displayLabel, columns, null, targetColumnIndex, rows);
    }

    public int rowCount()    { return rows.length; }
    public int columnCount() { return columns.size(); }

    /** Cell at ({@code row}, {@code col}). */
    public String get(int row, int col) {
        return rows[row][col];
    }

    /** Cell at ({@code row}, named column). Throws if {@code columnName} is unknown. */
    public String get(int row, String columnName) {
        int idx = columns.indexOf(columnName);
        if (idx < 0) {
            throw new IllegalArgumentException(
                "unknown column '" + columnName + "'; have " + columns);
        }
        return rows[row][idx];
    }

    /** Declared type for {@code columnName}, defaulting to STRING if unknown. */
    public ValueType columnType(String columnName) {
        int idx = columns.indexOf(columnName);
        return idx < 0 ? ValueType.STRING : columnTypes.get(idx);
    }

    /** Name of the target column, or {@code null} if unsupervised. */
    public String targetColumnName() {
        return targetColumnIndex < 0 ? null : columns.get(targetColumnIndex);
    }
}
