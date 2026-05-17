package sibarum.ternion.data;

import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.dasum.gui.core.status.Status;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.SlotSource;
import sibarum.mcc.training.Corpus;
import sibarum.mcc.training.Example;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.StringValue;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.designer.NodeSpec;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory mutable corpus for the Data tab. Holds a row list and a
 * declared input/target schema. Mutations bump {@link #version} so the
 * UI re-renders.
 *
 * <p>Schema is derived by {@link #detectFromGraph(GraphSync, CompGraphNode)}:
 * walks the live nodes, finds every unwired input slot, and emits one
 * {@link InputSchema} per slot keyed by {@code "nodeId#slotIndex"}.
 * The target type is taken from the supplied terminal node's primitive.
 *
 * <p>{@link #toCorpus()} produces an mcc-core {@link Corpus} suitable
 * for {@link sibarum.mcc.training.GraphTrainer}. Rows that fail to
 * parse (any cell fails its type parser) are skipped with a console
 * warning rather than aborting — partial corpora are useful during
 * iterative editing.
 */
public final class CorpusModel {

    public static final class Row {
        public String label;
        public final Map<String, String> inputStrings = new LinkedHashMap<>();
        public String targetString;

        Row(String label, String targetString) {
            this.label = label;
            this.targetString = targetString;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private List<InputSchema> inputSchema = List.of();
    private TargetSchema targetSchema = new TargetSchema(ValueType.NUMBER);
    private String name = "corpus";
    private final Property<Integer> version = new Property<>(0);

    public Property<Integer> version() { return version; }
    public List<Row> rows()             { return rows; }
    public List<InputSchema> inputSchema() { return inputSchema; }
    public TargetSchema targetSchema()  { return targetSchema; }
    public String name()                { return name; }

    public void setName(String n) {
        this.name = n;
        bumpStructure();
    }

    /**
     * Re-derive the input/target schema from the current graph state.
     * Walks every live node; for each unwired input slot, emits an
     * {@link InputSchema}. The target type is the terminal node's
     * primitive output type.
     *
     * <p>Existing row data is preserved keyed by input name; cells whose
     * keys disappeared from the schema are silently dropped from the
     * inputs map.
     */
    public void detectFromGraph(GraphSync sync, CompGraphNode terminalNode) {
        List<InputSchema> next = new ArrayList<>();
        for (NodeSpec spec : sync.liveNodes()) {
            CompGraphNode cg = spec.cgNode();
            for (int i = 0; i < cg.slotCount(); i++) {
                SlotSource s = cg.slot(i);
                if (s != null) continue;
                ValueType t = cg.tNode().primitive().inputTypes().get(i);
                String key = InputSchema.makeKey(cg, i);
                String displayLabel = cg.tNode().primitive().name() + " · slot " + i + " (" + t + ")";
                next.add(new InputSchema(key, cg, i, t, displayLabel));
            }
        }
        this.inputSchema = List.copyOf(next);
        this.targetSchema = new TargetSchema(
            terminalNode == null ? ValueType.NUMBER : terminalNode.tNode().primitive().outputType());

        // Reconcile existing rows.
        for (Row r : rows) {
            Map<String, String> kept = new LinkedHashMap<>();
            for (InputSchema in : inputSchema) {
                kept.put(in.key(), r.inputStrings.getOrDefault(in.key(), defaultStringFor(in.type())));
            }
            r.inputStrings.clear();
            r.inputStrings.putAll(kept);
            if (r.targetString == null) {
                r.targetString = defaultStringFor(targetSchema.type());
            }
        }
        bumpStructure();
    }

    public Row addRow() {
        Row r = new Row("ex" + (rows.size() + 1), defaultStringFor(targetSchema.type()));
        for (InputSchema in : inputSchema) {
            r.inputStrings.put(in.key(), defaultStringFor(in.type()));
        }
        rows.add(r);
        bumpStructure();
        return r;
    }

    /** Build a Row populated from save-file fields, without registering it. */
    public Row newRow(String label, Map<String, String> inputs, String target) {
        Row r = new Row(label == null ? "" : label, target == null ? "" : target);
        if (inputs != null) r.inputStrings.putAll(inputs);
        return r;
    }

    /** Replace the entire row list in one structural bump. Used by load. */
    public void replaceAll(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        bumpStructure();
    }

    /** Clear rows + schema. Used by New project. */
    public void clearAll() {
        rows.clear();
        inputSchema = List.of();
        targetSchema = new TargetSchema(ValueType.NUMBER);
        name = "corpus";
        bumpStructure();
    }

    public void removeRow(int index) {
        if (index < 0 || index >= rows.size()) return;
        rows.remove(index);
        bumpStructure();
    }

    /**
     * Cell-edit mutators below do NOT bump structure. The UI's cell
     * Components already own the displayed text via
     * {@link sibarum.dasum.gui.core.input.TextStates}; the model is just
     * the durable mirror used by {@link #toCorpus()}. Bumping structure
     * here would trigger a rebuild on every keystroke and wipe caret /
     * focus state.
     */
    public void setLabel(int rowIndex, String label) {
        if (rowIndex < 0 || rowIndex >= rows.size()) return;
        rows.get(rowIndex).label = label;
    }

    public void setInputCell(int rowIndex, String inputKey, String value) {
        if (rowIndex < 0 || rowIndex >= rows.size()) return;
        rows.get(rowIndex).inputStrings.put(inputKey, value);
    }

    public void setTargetCell(int rowIndex, String value) {
        if (rowIndex < 0 || rowIndex >= rows.size()) return;
        rows.get(rowIndex).targetString = value;
    }

    /**
     * Snapshot the current rows as an mcc-core {@link Corpus}. Rows
     * whose cells fail to parse are dropped from the snapshot. The
     * returned Corpus is independent of further edits.
     */
    public Corpus toCorpus() {
        List<Example> examples = new ArrayList<>(rows.size());
        int dropped = 0;
        String firstError = null;
        for (Row r : rows) {
            try {
                Map<String, Value> inputs = new LinkedHashMap<>();
                for (InputSchema in : inputSchema) {
                    String raw = r.inputStrings.getOrDefault(in.key(), defaultStringFor(in.type()));
                    inputs.put(in.key(), parse(in.type(), raw));
                }
                Value target = parse(targetSchema.type(), r.targetString);
                examples.add(new Example(r.label, inputs, target));
            } catch (RuntimeException ex) {
                dropped++;
                if (firstError == null) {
                    firstError = "row '" + r.label + "': " + ex.getMessage();
                }
                System.out.println("[corpus] skipped row '" + r.label + "': " + ex.getMessage());
            }
        }
        if (dropped > 0) {
            String summary = dropped == 1
                ? "Corpus: skipped 1 row — " + firstError
                : "Corpus: skipped " + dropped + " rows; first was " + firstError;
            Status.warn(summary);
        }
        String snapName = name;
        long snapSize = examples.size();
        return new Corpus() {
            @Override public String name() { return snapName; }
            @Override public long size()   { return snapSize; }
            @Override public Iterator<Example> stream() { return examples.iterator(); }
        };
    }

    private void bumpStructure() {
        version.set(version.get() + 1);
        Invalidator.invalidate();
    }

    private static String defaultStringFor(ValueType t) {
        return switch (t) {
            case STRING     -> "";
            case NUMBER     -> "0";
            case MATRIX     -> "0, 0, 0, 0";  // dim 4 — matches the palette's default Linear / Parameter
            case TERNION    -> "0, 0, 0";
            case QUATERNION -> "0, 0, 0, 0";
            default         -> "";
        };
    }

    /**
     * Parse a cell string into a Value of the given type. Throws
     * {@link IllegalArgumentException} on parse failure. Only STRING
     * and NUMBER are supported by the MVP editor; other types fall back
     * to a deferred-parse error so the toCorpus snapshot drops the row
     * cleanly instead of building an invalid Example.
     */
    private static Value parse(ValueType t, String raw) {
        return switch (t) {
            case STRING -> new StringValue(raw == null ? "" : raw);
            case NUMBER -> {
                if (raw == null || raw.isBlank()) yield new NumberValue(0.0);
                try {
                    yield new NumberValue(Double.parseDouble(raw.trim()));
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException("not a NUMBER: '" + raw + "'");
                }
            }
            case MATRIX, TERNION, QUATERNION -> new MatrixValue(parseFloats(raw));
            default -> throw new IllegalArgumentException(
                "data editor doesn't yet parse " + t + " — supported: STRING, NUMBER, MATRIX, TERNION, QUATERNION");
        };
    }

    /**
     * Parse a comma-separated list of doubles. Whitespace and surrounding
     * brackets / parens are tolerated. Empty or all-whitespace input
     * yields an empty array (caller may decide if that's valid).
     */
    private static double[] parseFloats(String raw) {
        if (raw == null) return new double[0];
        String s = raw.trim();
        if (s.isEmpty()) return new double[0];
        // Tolerate "[1, 2, 3]" or "(1 2 3)" — strip surrounding bracket pairs.
        if ((s.startsWith("[") && s.endsWith("]"))
                || (s.startsWith("(") && s.endsWith(")"))
                || (s.startsWith("{") && s.endsWith("}"))) {
            s = s.substring(1, s.length() - 1).trim();
            if (s.isEmpty()) return new double[0];
        }
        String[] parts = s.split("[\\s,]+");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(
                    "matrix entry " + i + " not a number: '" + parts[i] + "' (use comma- or space-separated numbers, e.g. \"1, 2, 3, 4\")");
            }
        }
        return out;
    }
}
