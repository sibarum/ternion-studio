package sibarum.ternion.data.source;

import sibarum.dasum.gui.core.reactive.Property;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.data.curated.CuratedDataset;

import java.util.List;

/**
 * Read-only {@link DataSource} backed by a {@link CuratedDataset}
 * bundled with the executable. Persistence stores only the source id;
 * the rows + columns rehydrate on load from
 * {@link sibarum.ternion.data.curated.CuratedDatasets} via the
 * {@link DataSourceRegistry#seedBundled} step.
 *
 * <p>{@link #structureVersion} is a constant {@code 0} {@link Property}
 * — a bundled source never mutates, so subscribers never need to
 * re-render. Allocating it once at construction keeps the
 * {@code DataSource} contract symmetric across origins.
 */
public final class BundledDataSource implements DataSource {

    private final CuratedDataset dataset;
    private final Property<Integer> structureVersion = new Property<>(0);

    public BundledDataSource(CuratedDataset dataset) {
        if (dataset == null) throw new IllegalArgumentException("dataset");
        this.dataset = dataset;
    }

    /** Direct access to the wrapped curated record for places that
     *  legitimately need it (project save → emits id only; tests). */
    public CuratedDataset dataset() { return dataset; }

    @Override public String id()                       { return dataset.id(); }
    @Override public String displayLabel()             { return dataset.displayLabel(); }
    @Override public Origin origin()                   { return Origin.BUNDLED; }
    @Override public boolean isEditable()              { return false; }
    @Override public List<String> columns()            { return dataset.columns(); }
    @Override public int rowCount()                    { return dataset.rowCount(); }
    @Override public String get(int row, int col)      { return dataset.get(row, col); }
    @Override public String get(int row, String col)   { return dataset.get(row, col); }
    @Override public int suggestedTargetColumnIndex()  { return dataset.targetColumnIndex(); }
    @Override public Property<Integer> structureVersion() { return structureVersion; }
    @Override public ValueType columnType(String columnName) {
        return dataset.columnType(columnName);
    }
}
