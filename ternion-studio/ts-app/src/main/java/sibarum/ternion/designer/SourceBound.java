package sibarum.ternion.designer;

import sibarum.ternion.data.source.DataSource;

/**
 * Marker for graph primitives that resolve a cell from a
 * {@link DataSource} at each training step. The trainer iterates rows
 * by calling {@link #setCurrentRow} on every registered binding, then
 * runs forward + backward.
 *
 * <p>Implemented by {@link DatasetColumnPrimitive} (role: graph input)
 * and {@link LossOutputPrimitive} (role: loss target). Recognised
 * by {@link DataSourceBoundNodes} which captures the binding info
 * for the trainer's single-source preflight and iteration loop.
 */
public interface SourceBound {

    /** Identifier of the {@link DataSource} this primitive reads from. */
    String sourceId();

    /** Column name within {@link #sourceId()}'s schema. */
    String columnName();

    /** Set the row to read on the next {@code apply()}. */
    void setCurrentRow(int row);

    /** The number of rows available in the resolved source. */
    int rowCount();

    /**
     * Whether the trainer drives this binding's {@link #setCurrentRow}
     * each step. {@code true} for nodes that consume cells in
     * row-by-row lockstep (DatasetColumn, LossOutput); {@code false}
     * for nodes that random-access the source by key (LookupColumn),
     * which the trainer leaves alone.
     *
     * <p>Used by the preflight to count "iterated" sources separately
     * from "referenced" sources, so a graph can pull from one source
     * for its example stream and a different source as a lookup
     * table without tripping the single-source rule.
     */
    default boolean iterated() { return true; }
}
