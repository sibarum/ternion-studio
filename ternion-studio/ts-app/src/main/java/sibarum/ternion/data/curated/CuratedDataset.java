package sibarum.ternion.data.curated;

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
 * @param id                 stable identifier; also the classpath leaf
 *                           name minus {@code .csv}
 * @param displayLabel       human-readable label for the loader UI
 * @param columns            header row from the source CSV, in order
 * @param targetColumnIndex  column to bind as the training target,
 *                           or {@code -1} for unsupervised
 * @param rows               row-major cell data; lengths must match
 *                           {@code columns.size()} per row
 */
public record CuratedDataset(
    String id,
    String displayLabel,
    List<String> columns,
    int targetColumnIndex,
    String[][] rows
) {

    public CuratedDataset {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
        if (displayLabel == null) throw new IllegalArgumentException("displayLabel");
        columns = List.copyOf(columns);
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

    /** Name of the target column, or {@code null} if unsupervised. */
    public String targetColumnName() {
        return targetColumnIndex < 0 ? null : columns.get(targetColumnIndex);
    }
}
