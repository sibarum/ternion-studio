package sibarum.ternion.designer;

import sibarum.mcc.primitive.Configurable;
import sibarum.mcc.primitive.Primitive;
import sibarum.mcc.value.StringValue;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.data.source.Cells;
import sibarum.ternion.data.source.DataSource;
import sibarum.ternion.data.source.DataSourceRegistry;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Key-indexed lookup against a {@link DataSource}. Takes a
 * {@link ValueType#STRING} input (the key), emits the corresponding
 * cell from {@code valueColumn} as the configured
 * {@link ValueType#NUMBER}/{@link ValueType#MATRIX}/{@link ValueType#STRING}.
 *
 * <p>The natural use: a bundled embedding table
 * ({@code word_embed_k16}) where {@code keyColumn = "surface"} and
 * {@code valueColumn = "embedding"} — wire a STRING token in and
 * receive its pretrained 16-vector. Out-of-vocab keys map to a zero
 * value (zero {@link MatrixValue} for MATRIX, {@code 0.0} for NUMBER,
 * empty string for STRING) so the model survives unknown inputs
 * without an exception that would unwind the training step.
 *
 * <h2>Iteration</h2>
 * Marked {@link #iterated()} = {@code false}: the trainer's row
 * driver leaves this primitive's row pointer alone (there isn't one
 * — every {@code apply} looks up by key). The single-source
 * preflight only counts iterated bindings, so a graph can pair an
 * iterated source (e.g. {@code pos_train}'s {@code token} column
 * driving examples) with one or more lookup sources (e.g.
 * {@code word_embed_k16} feeding pretrained embeddings) without
 * tripping the multi-source error.
 *
 * <h2>Performance</h2>
 * The key→value cache is built once at construction by walking the
 * source's rows. For a 4000-row source that's a few ms; the savings
 * over a per-apply linear scan are significant during training.
 * {@link DataSource#structureVersion()} mutations don't invalidate
 * the cache today — bundled sources never mutate, and editable
 * sources mid-training is an explicit V1 non-goal.
 */
public final class LookupColumnPrimitive
        implements Primitive, Configurable, SourceBound {

    private final String sourceId;
    private final String keyColumn;
    private final String valueColumn;
    private final ValueType outputType;
    private final DataSource source;
    private final Map<String, String> index;

    public LookupColumnPrimitive(String sourceId, String keyColumn,
                                 String valueColumn, ValueType outputType) {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId");
        if (keyColumn == null || keyColumn.isBlank()) throw new IllegalArgumentException("keyColumn");
        if (valueColumn == null || valueColumn.isBlank()) throw new IllegalArgumentException("valueColumn");
        DataSourceRegistry reg = DataSourceRegistry.current();
        if (reg == null) {
            throw new IllegalStateException(
                "DataSourceRegistry.current() is null — Lookup Column requires an active AppContext.");
        }
        DataSource src = reg.byId(sourceId);
        if (src == null) {
            throw new IllegalArgumentException("Unknown source id: '" + sourceId + "'");
        }
        int keyIdx = src.columns().indexOf(keyColumn);
        if (keyIdx < 0) {
            throw new IllegalArgumentException(
                "Source '" + sourceId + "' has no key column '" + keyColumn
                + "'; have " + src.columns());
        }
        int valIdx = src.columns().indexOf(valueColumn);
        if (valIdx < 0) {
            throw new IllegalArgumentException(
                "Source '" + sourceId + "' has no value column '" + valueColumn
                + "'; have " + src.columns());
        }
        // outputType=null → AUTO from the value column's declared type.
        ValueType resolved = (outputType == null) ? src.columnType(valueColumn) : outputType;
        this.sourceId    = sourceId;
        this.keyColumn   = keyColumn;
        this.valueColumn = valueColumn;
        this.outputType  = resolved;
        this.source      = src;
        // Build the index once. Duplicate keys: last wins (matches the
        // intuition that later rows in a curated table override earlier
        // ones — the embedding tables are deduped at curation time so
        // this only matters if the user wires up a non-unique key).
        Map<String, String> idx = new HashMap<>(src.rowCount() * 2);
        for (int r = 0; r < src.rowCount(); r++) {
            String k = src.get(r, keyIdx);
            String v = src.get(r, valIdx);
            idx.put(k == null ? "" : k, v == null ? "" : v);
        }
        this.index = idx;
    }

    public DataSource source()       { return source; }
    public String keyColumn()        { return keyColumn; }
    public String valueColumn()      { return valueColumn; }
    public ValueType outputTypeRaw() { return outputType; }
    public int cachedKeyCount()      { return index.size(); }

    // ---- SourceBound ----

    @Override public String sourceId()    { return sourceId; }
    /** "Bound column" surfaces as the value column for source-removal
     *  diagnostics; key column is implicit. */
    @Override public String columnName()  { return valueColumn; }
    @Override public int rowCount()       { return source.rowCount(); }
    /** Lookup is random-access; the trainer's row driver doesn't apply. */
    @Override public void setCurrentRow(int row) { /* no-op */ }
    @Override public boolean iterated()   { return false; }

    // ---- Primitive ----

    @Override
    public String name() {
        return "lookup-column:" + sourceId + ":" + keyColumn + "→" + valueColumn;
    }

    @Override public List<ValueType> inputTypes() { return List.of(ValueType.STRING); }
    @Override public ValueType outputType()       { return outputType; }

    @Override
    public Value apply(List<Value> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("Lookup Column requires a STRING 'key' input");
        }
        Value v = inputs.getFirst();
        if (!(v instanceof StringValue s)) {
            throw new IllegalArgumentException(
                "Lookup Column expects STRING input, got " + v.type());
        }
        String cell = index.get(s.s());
        if (cell == null) return Cells.zeroFor(outputType);
        return Cells.parse(outputType, cell);
    }

    @Override
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source",      sourceId + ":" + keyColumn + ":" + valueColumn);
        m.put("outputType",  outputType.name());
        return m;
    }

}
