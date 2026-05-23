package sibarum.ternion.designer;

import sibarum.mcc.embedding.AutoTokenizer;
import sibarum.mcc.primitive.Configurable;
import sibarum.mcc.primitive.Differentiable;
import sibarum.mcc.primitive.Primitive;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.data.source.Cells;
import sibarum.ternion.data.source.DataSource;
import sibarum.ternion.data.source.DataSourceRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified output + loss sink. Replaces the {@code Terminal} +
 * {@code ExpectedOutputPrimitive} pair that V1 carried as separate
 * graph nodes — one configurable Loss Output reads the supervisory
 * cell from a {@link DataSource} column and emits the scalar loss as
 * its produced value, so the trainer's backward seed becomes the
 * trivial {@code dL/dL = 1} regardless of {@link #lossFn}.
 *
 * <h2>Ports</h2>
 * One input port labelled {@code predicted} (typed to {@link #inputType});
 * no output port (this is a sink — its produced scalar feeds only the
 * trainer's loss history).
 *
 * <h2>Differentiability</h2>
 * For {@link LossFn#MSE} over {@link #inputType}:
 * <ul>
 *   <li>{@code apply}: returns {@code 0.5 · ||predicted − expected||²}
 *       as a {@link NumberValue}.</li>
 *   <li>{@code backward(gradOutput)}: returns one entry — the
 *       per-component gradient {@code clip(predicted − expected) ·
 *       gradOutput} for the {@code predicted} slot. {@code gradOutput}
 *       is the upstream loss gradient (the trainer seeds {@code 1.0}
 *       at this node, so {@code gradOutput} is typically a scalar).
 *       Clipping uses the same {@link #GRAD_CLIP} as the old
 *       {@code TrainingController.mseGrad} helper.</li>
 * </ul>
 *
 * <h2>Source resolution</h2>
 * Resolves its {@link DataSource} at construction via
 * {@link DataSourceRegistry#current()} — the same singleton-style
 * lookup {@link DatasetColumnPrimitive} uses for bundled datasets.
 * Throws if the registry isn't initialised or the source / column
 * are missing.
 *
 * <h2>Iteration</h2>
 * Implements {@link SourceBound} so the trainer can drive
 * {@link #setCurrentRow} in lockstep with {@code DatasetColumn} nodes.
 * {@code currentRow} defaults to 0; the trainer advances it once per
 * step before {@code apply}.
 */
public final class LossOutputPrimitive
        implements Primitive, Configurable, Differentiable, SourceBound {

    public enum LossFn {
        /** Half mean-squared-error: {@code L = 0.5 · ||p − e||²} between
         *  predicted and the target cell parsed as {@link #inputType}.
         *  The classic regression loss; gradient is {@code (p − e)}. */
        MSE,

        /**
         * Class-label target. The target cell is read as a STRING and
         * tokenized to a class id in {@code [0, numClasses)}; the
         * supervisory vector is the one-hot encoding of that id.
         * {@link #inputType} must be {@link ValueType#MATRIX} with
         * length {@code numClasses}, i.e. the model emits one score per
         * class (typically after a Softmax). Loss + gradient math is
         * the same as {@link #MSE} — same backward shape, same clip —
         * but applied against the one-hot target. Suitable for
         * classification tasks like POS tagging where labels are
         * strings ({@code "NOUN"}, {@code "ADJ"}, …) rather than
         * numeric vectors.
         */
        CATEGORICAL
    }

    /** Per-component clip threshold on the gradient. Mirrors the
     *  conservative default the old {@code TrainingController.mseGrad}
     *  applied to keep a bad step from cascading into runaway weights. */
    private static final double GRAD_CLIP = 1.0;

    /** Fallback class count when a {@link LossFn#CATEGORICAL} config
     *  arrives with {@code numClasses <= 0} (e.g. an old project that
     *  pre-dates the field). Matches the palette default. */
    private static final int DEFAULT_NUM_CLASSES = 16;

    private final String sourceId;
    private final String columnName;
    private final ValueType inputType;
    private final LossFn lossFn;
    private final int numClasses;
    private final DataSource source;
    private final int columnIndex;
    /** Maps target STRINGs to class ids when {@link #lossFn} is
     *  {@link LossFn#CATEGORICAL}. Recreated lazily on first
     *  {@link #apply} once we know the predicted vector's length —
     *  the actual one-hot dimension comes from upstream (typically a
     *  Linear's {@code outDim}), not from the user's {@code numClasses}
     *  config, so a Properties tweak that misses by a couple of
     *  classes still trains rather than throwing. The
     *  {@link AutoTokenizer.Mode#DISTRIBUTED} hashing keeps the same
     *  label → same id across save/load even though the vocab itself
     *  isn't persisted. {@code null} when {@link #lossFn} is
     *  {@link LossFn#MSE}. */
    private volatile AutoTokenizer classTokenizer;
    private volatile int classTokenizerRange;

    private volatile int currentRow = 0;

    // Captured at the most recent apply() for backward to reuse without
    // re-parsing the source cell.
    private volatile Value lastPredicted;
    private volatile Value lastExpected;

    public LossOutputPrimitive(String sourceId, String columnName,
                               ValueType inputType, LossFn lossFn, int numClasses) {
        if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("sourceId");
        if (columnName == null || columnName.isBlank()) throw new IllegalArgumentException("columnName");
        if (inputType == null) throw new IllegalArgumentException("inputType");
        if (lossFn == null) throw new IllegalArgumentException("lossFn");
        // CATEGORICAL requires MATRIX predicted + numClasses > 0.
        // Coerce silently rather than throw — the Properties dialog
        // route can't gracefully recover from a mid-spawn throw and
        // these defaults are always safe.
        if (lossFn == LossFn.CATEGORICAL) {
            if (inputType != ValueType.MATRIX) inputType = ValueType.MATRIX;
            if (numClasses <= 0) numClasses = DEFAULT_NUM_CLASSES;
        }
        DataSourceRegistry reg = DataSourceRegistry.current();
        if (reg == null) {
            throw new IllegalStateException(
                "DataSourceRegistry.current() is null — Loss Output requires an active AppContext.");
        }
        DataSource src = reg.byId(sourceId);
        if (src == null) {
            throw new IllegalArgumentException("Unknown source id: '" + sourceId + "'");
        }
        int idx = src.columns().indexOf(columnName);
        if (idx < 0) {
            throw new IllegalArgumentException(
                "Source '" + sourceId + "' has no column '" + columnName
                + "'; have " + src.columns());
        }
        this.sourceId   = sourceId;
        this.columnName = columnName;
        this.inputType  = inputType;
        this.lossFn     = lossFn;
        this.numClasses = numClasses;
        this.source     = src;
        this.columnIndex = idx;
        // Tokenizer is created lazily once we know the predicted dim
        // — see classTokenizer's javadoc.
        this.classTokenizer = null;
        this.classTokenizerRange = 0;
    }

    /** Back-compat overload for the (sourceId, columnName, inputType, lossFn) shape used by
     *  tests + non-CATEGORICAL spawn paths. Equivalent to passing
     *  {@code numClasses = 0}. */
    public LossOutputPrimitive(String sourceId, String columnName,
                               ValueType inputType, LossFn lossFn) {
        this(sourceId, columnName, inputType, lossFn, 0);
    }

    public LossFn lossFn()        { return lossFn; }
    public ValueType inputType()  { return inputType; }
    public int numClasses()       { return numClasses; }
    public DataSource source()    { return source; }
    public int currentRow()       { return currentRow; }

    // ---- SourceBound ----

    @Override public String sourceId()    { return sourceId; }
    @Override public String columnName()  { return columnName; }
    @Override public int rowCount()       { return source.rowCount(); }

    @Override
    public void setCurrentRow(int row) {
        if (row < 0 || row >= source.rowCount()) {
            throw new IllegalArgumentException(
                "row " + row + " out of range [0, " + source.rowCount() + ")");
        }
        this.currentRow = row;
    }

    // ---- Primitive ----

    @Override public String name() {
        return "loss-output:" + sourceId + ":" + columnName + ":" + lossFn;
    }

    @Override public List<ValueType> inputTypes() { return List.of(inputType); }

    /** Loss Output produces a scalar regardless of {@link #inputType}. */
    @Override public ValueType outputType() { return ValueType.NUMBER; }

    @Override
    public Value apply(List<Value> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("Loss Output requires a wired 'predicted' input");
        }
        Value predicted = inputs.getFirst();
        String cell = source.get(currentRow, columnIndex);
        Value expected = (lossFn == LossFn.CATEGORICAL)
            ? oneHotFor(cell, predicted)
            : Cells.parse(inputType, cell);
        this.lastPredicted = predicted;
        this.lastExpected  = expected;
        return new NumberValue(halfMse(predicted, expected));
    }

    /**
     * Convert a label string into a one-hot {@link MatrixValue} sized
     * to match the predicted vector's length. The class count is read
     * from the predicted vector — not the user's {@code numClasses}
     * config — so the user can size the model's final Linear (or
     * whatever feeds {@code predicted}) freely without an exact match
     * to the LossOutput config. The tokenizer is created lazily on
     * first sight, then reused; if predicted's length ever changes
     * (rare — would require a re-spawn), the tokenizer is rebuilt to
     * the new range so labels keep hashing into the right space.
     *
     * <p>Empty / null cells map to class 0 — lets a CATEGORICAL graph
     * survive sparse target data without exploding loss on the empty
     * rows.
     */
    private Value oneHotFor(String cell, Value predicted) {
        if (!(predicted instanceof MatrixValue mp)) {
            throw new IllegalArgumentException(
                "CATEGORICAL Loss Output requires MATRIX predicted; got " + predicted.type()
                + " — wire a vector-emitting node (e.g. Linear) to 'predicted'");
        }
        int classCount = mp.data().length;
        if (classCount <= 0) {
            throw new IllegalArgumentException(
                "CATEGORICAL Loss Output: predicted vector has length 0");
        }
        AutoTokenizer t = classTokenizer;
        if (t == null || classTokenizerRange != classCount) {
            t = new AutoTokenizer(AutoTokenizer.Mode.DISTRIBUTED, classCount, 0L);
            classTokenizer = t;
            classTokenizerRange = classCount;
        }
        int id = t.idFor(cell == null ? "" : cell);
        if (id < 0 || id >= classCount) id = Math.floorMod(id, classCount);
        double[] data = new double[classCount];
        data[id] = 1.0;
        return new MatrixValue(data);
    }

    @Override
    public List<Value> backward(Value gradOutput) {
        Value p = lastPredicted;
        Value e = lastExpected;
        if (p == null || e == null) {
            throw new IllegalStateException("backward() called before apply() on Loss Output");
        }
        double upstream = scalarOf(gradOutput);
        return List.of(gradPredicted(p, e, upstream));
    }

    @Override
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source",     sourceId + ":" + columnName);
        m.put("inputType",  inputType.name());
        m.put("lossFn",     lossFn.name());
        m.put("numClasses", numClasses);
        return m;
    }

    // ---- math helpers ----

    private static double halfMse(Value out, Value target) {
        if (out instanceof MatrixValue mo && target instanceof MatrixValue mt) {
            if (mo.data().length != mt.data().length) {
                throw new IllegalArgumentException(
                    "MSE loss: predicted is MATRIX[" + mo.data().length
                    + "] but target is MATRIX[" + mt.data().length
                    + "] — set Loss Output's source column to a vector of matching length.");
            }
            double s = 0.0;
            for (int i = 0; i < mo.data().length; i++) {
                double d = mo.data()[i] - mt.data()[i];
                s += d * d;
            }
            return 0.5 * s;
        }
        if (out instanceof NumberValue no && target instanceof NumberValue nt) {
            double d = no.n() - nt.n();
            return 0.5 * d * d;
        }
        throw new IllegalArgumentException(
            "MSE loss: predicted type " + out.type() + " ≠ target type " + target.type());
    }

    private static Value gradPredicted(Value p, Value e, double upstream) {
        if (p instanceof MatrixValue mp && e instanceof MatrixValue me) {
            double[] g = new double[mp.data().length];
            for (int i = 0; i < g.length; i++) {
                g[i] = clip(mp.data()[i] - me.data()[i]) * upstream;
            }
            return new MatrixValue(g);
        }
        if (p instanceof NumberValue np && e instanceof NumberValue ne) {
            return new NumberValue(clip(np.n() - ne.n()) * upstream);
        }
        throw new IllegalArgumentException(
            "MSE grad: predicted type " + p.type() + " ≠ target type " + e.type());
    }

    private static double clip(double v) {
        if (v >  GRAD_CLIP) return  GRAD_CLIP;
        if (v < -GRAD_CLIP) return -GRAD_CLIP;
        return v;
    }

    private static double scalarOf(Value v) {
        if (v instanceof NumberValue n) return n.n();
        if (v instanceof MatrixValue m && m.data().length == 1) return m.data()[0];
        throw new IllegalArgumentException(
            "Loss Output backward expected a scalar gradOutput; got " + v.type());
    }

}
