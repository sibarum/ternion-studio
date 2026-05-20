package sibarum.ternion.data.source;

import sibarum.dasum.gui.core.reactive.Property;

import java.util.List;

/**
 * Uniform table abstraction over every kind of training data the project
 * can hold — bundled curated datasets, hand-edited tables, future
 * CSV/parquet imports. Graph primitives address cells by
 * {@code (sourceId, columnName)} and resolve through the project's
 * {@link DataSourceRegistry}, so a downstream node never has to care
 * about a source's origin.
 *
 * <p>Cells are raw {@link String}; parsing into typed
 * {@link sibarum.mcc.value.Value Value}s happens at the primitive
 * (with its {@code outputType} / {@code inputType} config) so a single
 * source can feed different downstream types from different columns.
 *
 * <p>{@link #structureVersion()} is bumped whenever rows or columns
 * change shape; the Data tab and any other subscribers re-render off
 * it. Cell-level edits also bump it for V1 simplicity — finer-grained
 * notification is a future optimisation.
 *
 * <h2>Single-source-per-graph invariant (V1)</h2>
 * The training controller enforces that a single graph snapshot
 * references at most one source — every {@code DatasetColumn} +
 * {@code LossOutput} in the graph must agree on which source they read
 * from. Cross-source joins require explicit join primitives that V1
 * doesn't have. This contract lives in the trainer's preflight, not
 * here, but {@link DataSourceRegistry#referencingNodeIds} is the
 * facility that supports enforcing it.
 */
public sealed interface DataSource
    permits BundledDataSource, EditableDataSource, ImportedDataSource {

    /** Stable identifier within the owning project. */
    String id();

    /** Human-readable label for UI surfaces (dropdowns, pickers). */
    String displayLabel();

    /** Origin (read-only metadata). Drives UI icons + persistence shape. */
    Origin origin();

    /** Whether {@link EditableDataSource} mutators are accepted. False for bundled/imported. */
    boolean isEditable();

    /** Header row, in column order. */
    List<String> columns();

    /** Total row count. */
    int rowCount();

    /** Cell at ({@code row}, {@code col}); throws on out-of-range. */
    String get(int row, int col);

    /** Cell at ({@code row}, named column); throws if column unknown. */
    String get(int row, String columnName);

    /** Resolve a column's index, or {@code -1} if not present. */
    default int indexOf(String columnName) {
        return columns().indexOf(columnName);
    }

    /**
     * Optional UI hint: which column the source's author considered the
     * supervisory target. The trainer ignores this — {@code LossOutput}
     * configures its own target column explicitly. {@code -1} when no
     * hint is available.
     */
    int suggestedTargetColumnIndex();

    /** Bumps on row or column structural changes. UI subscribes for refresh. */
    Property<Integer> structureVersion();
}
