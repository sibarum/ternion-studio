package sibarum.ternion.data.source;

import sibarum.dasum.gui.core.reactive.Property;

import java.nio.file.Path;
import java.util.List;

/**
 * V1 placeholder for an externally-imported {@link DataSource}
 * (CSV / parquet / etc.). The actual loader, schema inference, and
 * Data tab "Import…" button are deferred — this class exists so the
 * sealed {@link DataSource} hierarchy is complete and downstream code
 * (registry, table adapter, save/load) can handle the case
 * structurally now without a follow-up refactor.
 *
 * <p>Behaves as a read-only snapshot — columns + rows passed at
 * construction, cell mutations rejected. {@link #sourcePath} records
 * where the rows originated so a future "reload" action knows where
 * to look.
 */
public final class ImportedDataSource implements DataSource {

    private final String id;
    private final String displayLabel;
    private final List<String> columns;
    private final String[][] rows;
    private final Path sourcePath;
    private final Property<Integer> structureVersion = new Property<>(0);

    public ImportedDataSource(String id, String displayLabel,
                              List<String> columns, String[][] rows,
                              Path sourcePath) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
        if (columns == null) throw new IllegalArgumentException("columns");
        if (rows == null) throw new IllegalArgumentException("rows");
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == null || rows[i].length != columns.size()) {
                throw new IllegalArgumentException(
                    "row " + i + " has " + (rows[i] == null ? 0 : rows[i].length)
                    + " cells; expected " + columns.size());
            }
        }
        this.id = id;
        this.displayLabel = (displayLabel == null || displayLabel.isBlank()) ? id : displayLabel;
        this.columns = List.copyOf(columns);
        this.rows = rows;
        this.sourcePath = sourcePath;
    }

    /** Where the rows came from on disk; may be {@code null} if synthetic. */
    public Path sourcePath() { return sourcePath; }

    @Override public String id()                       { return id; }
    @Override public String displayLabel()             { return displayLabel; }
    @Override public Origin origin()                   { return Origin.IMPORTED; }
    @Override public boolean isEditable()              { return false; }
    @Override public List<String> columns()            { return columns; }
    @Override public int rowCount()                    { return rows.length; }
    @Override public String get(int row, int col)      { return rows[row][col]; }

    @Override
    public String get(int row, String columnName) {
        int idx = columns.indexOf(columnName);
        if (idx < 0) throw new IllegalArgumentException(
            "unknown column '" + columnName + "'");
        return rows[row][idx];
    }

    @Override public int suggestedTargetColumnIndex()  { return -1; }
    @Override public Property<Integer> structureVersion() { return structureVersion; }
}
