package sibarum.ternion.designer;

import sibarum.mcc.primitive.Configurable;
import sibarum.mcc.primitive.Primitive;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.StringValue;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.data.source.DataSource;
import sibarum.ternion.data.source.DataSourceRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source primitive that emits one column's value from any registered
 * {@link DataSource} — bundled curated, hand-edited, or imported.
 * No inputs; one output of the configured {@link ValueType}. The
 * current row is mutable per-step: the training controller iterates
 * rows and calls {@link #setCurrentRow} on every {@link SourceBound}
 * binding before each {@code graph.execute()}.
 *
 * <h2>State + lifecycle</h2>
 * <ul>
 *   <li>The primitive holds a reference to its {@link DataSource}
 *       (resolved at construction from {@link DataSourceRegistry#current()})
 *       and the column's index in that source's schema.</li>
 *   <li>{@code currentRow} is mutable and bounded; {@link #setCurrentRow}
 *       validates. Defaults to 0.</li>
 *   <li>{@link #apply} returns the parsed cell value at the current row.
 *       Parse failures (e.g. asking for NUMBER on a non-numeric cell)
 *       throw — the training preflight should have surfaced this earlier
 *       via type compatibility checks.</li>
 * </ul>
 *
 * <p>The primitive is non-{@code Differentiable}; gradients stop here.
 * Downstream learnable primitives (e.g. {@code IntToVector}) still
 * accumulate their own grads as usual.
 */
public final class DatasetColumnPrimitive
        implements Primitive, Configurable, SourceBound {

    private final String sourceId;
    private final String columnName;
    private final ValueType outputType;
    private final DataSource source;
    private final int columnIndex;
    private volatile int currentRow = 0;

    public DatasetColumnPrimitive(String sourceId, String columnName, ValueType outputType) {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId");
        if (columnName == null || columnName.isBlank()) throw new IllegalArgumentException("columnName");
        if (outputType == null) throw new IllegalArgumentException("outputType");
        DataSourceRegistry reg = DataSourceRegistry.current();
        if (reg == null) {
            throw new IllegalStateException(
                "DataSourceRegistry.current() is null — Dataset Column requires an active AppContext.");
        }
        DataSource src = reg.byId(sourceId);
        if (src == null) {
            throw new IllegalArgumentException("Unknown source id: '" + sourceId + "'");
        }
        int idx = src.columns().indexOf(columnName);
        if (idx < 0) {
            throw new IllegalArgumentException("Unknown column '" + columnName
                + "' in source '" + sourceId + "'; have " + src.columns());
        }
        this.sourceId   = sourceId;
        this.columnName = columnName;
        this.outputType = outputType;
        this.source = src;
        this.columnIndex = idx;
    }

    public DataSource source()      { return source; }
    public int currentRow()         { return currentRow; }
    public ValueType outputTypeRaw(){ return outputType; }

    // ---- SourceBound ----

    @Override public String sourceId()   { return sourceId; }
    @Override public String columnName() { return columnName; }
    @Override public int    rowCount()   { return source.rowCount(); }

    /** Trainer hook — set the row to emit on the next {@link #apply}. */
    @Override
    public void setCurrentRow(int row) {
        if (row < 0 || row >= source.rowCount()) {
            throw new IllegalArgumentException(
                "row " + row + " out of range [0, " + source.rowCount() + ")");
        }
        this.currentRow = row;
    }

    // ---- Primitive ----

    @Override
    public String name() {
        return "dataset:" + sourceId + ":" + columnName;
    }

    @Override public List<ValueType> inputTypes() { return List.of(); }
    @Override public ValueType outputType()       { return outputType; }

    @Override
    public Value apply(List<Value> inputs) {
        String cell = source.get(currentRow, columnIndex);
        return parse(cell, outputType);
    }

    @Override
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source",     sourceId + ":" + columnName);
        m.put("outputType", outputType.name());
        return m;
    }

    private static Value parse(String cell, ValueType type) {
        if (cell == null) cell = "";
        return switch (type) {
            case STRING -> new StringValue(cell);
            case NUMBER -> new NumberValue(
                cell.isBlank() ? 0.0 : Double.parseDouble(cell.trim()));
            case MATRIX -> new MatrixValue(parseFloats(cell));
            default -> throw new IllegalArgumentException(
                "DatasetColumn doesn't yet support " + type
                + " (supported: STRING, NUMBER, MATRIX)");
        };
    }

    private static double[] parseFloats(String raw) {
        if (raw == null || raw.isBlank()) return new double[0];
        String[] parts = raw.trim().split("[\\s,]+");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i]);
        }
        return out;
    }
}
