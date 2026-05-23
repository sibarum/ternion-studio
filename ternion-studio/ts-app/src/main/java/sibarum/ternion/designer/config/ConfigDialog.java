package sibarum.ternion.designer.config;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.overlay.Anchor;
import sibarum.dasum.gui.core.overlay.OverlayStack;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.status.Status;
import sibarum.dasum.gui.core.text.FontGroups;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.ternion.designer.config.ConfigField.BoolField;
import sibarum.ternion.designer.config.ConfigField.DoubleField;
import sibarum.ternion.designer.config.ConfigField.EnumField;
import sibarum.ternion.designer.config.ConfigField.IntArrayField;
import sibarum.ternion.designer.config.ConfigField.IntField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Modal overlay that renders one widget per {@link ConfigField} and
 * returns the user's values via a callback. Used at spawn time
 * (editable) and from a node's right-click "Properties" (read-only when
 * the node is frozen by training).
 */
public final class ConfigDialog {

    private static final Color DIALOG_BG     = new Color(0.10f, 0.12f, 0.16f, 1f);
    private static final Color DIALOG_BORDER = new Color(0.30f, 0.55f, 0.85f, 0.9f);
    private static final Color INPUT_BG      = new Color(0.13f, 0.16f, 0.21f, 1f);
    private static final Color LABEL_FG      = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color HINT_FG       = new Color(0.65f, 0.70f, 0.78f, 0.85f);
    private static final Color LOCK_FG       = new Color(0.95f, 0.75f, 0.35f, 1f);
    private static final Color BOX           = new Color(0.18f, 0.20f, 0.25f, 1f);
    private static final Color CHECK         = new Color(0.30f, 0.80f, 0.50f, 1f);
    private static final Color RADIO_DOT     = new Color(0.60f, 0.65f, 0.95f, 1f);
    private static final Color TRANSPARENT   = new Color(0f, 0f, 0f, 0f);

    private static final Em FIELD_WIDTH = Em.of(14f);
    private static final Em LABEL_WIDTH = Em.of(10f);

    private ConfigDialog() {}

    /**
     * Open the dialog in editable mode. {@code onAccept} fires with the
     * parsed value map on Confirm.
     */
    public static void show(String title,
                            List<ConfigField> schema,
                            Map<String, Object> initialValues,
                            Consumer<Map<String, Object>> onAccept) {
        showInternal(title, schema, initialValues, false, null, onAccept);
    }

    /**
     * Open the dialog in read-only mode with an explanatory line above
     * the fields ({@code readOnlyReason}). Confirm becomes a single
     * "Close" button.
     */
    public static void showReadOnly(String title,
                                    List<ConfigField> schema,
                                    Map<String, Object> initialValues,
                                    String readOnlyReason) {
        showInternal(title, schema, initialValues, true, readOnlyReason, v -> {});
    }

    private static void showInternal(String title,
                                     List<ConfigField> schema,
                                     Map<String, Object> initialValues,
                                     boolean readOnly,
                                     String readOnlyReason,
                                     Consumer<Map<String, Object>> onAccept) {
        Map<String, Supplier<Object>> readers = new LinkedHashMap<>();
        List<Component> rows = new ArrayList<>();
        for (ConfigField field : schema) {
            Object initial = initialValues.getOrDefault(field.key(), null);
            rows.add(switch (field) {
                case IntField f         -> intRow(f, initial, readOnly, readers);
                case DoubleField f      -> doubleRow(f, initial, readOnly, readers);
                case BoolField f        -> boolRow(f, initial, readOnly, readers);
                case EnumField f        -> enumRow(f, initial, readOnly, readers);
                case ConfigField.DynamicEnumField f -> enumRow(f.materialize(), initial, readOnly, readers);
                case IntArrayField f    -> intArrayRow(f, initial, readOnly, readers);
            });
        }

        Component titleText = new Component.Text(title, Em.of(1.15f), LABEL_FG);

        List<Component> dialogChildren = new ArrayList<>();
        dialogChildren.add(titleText);
        if (readOnly && readOnlyReason != null && !readOnlyReason.isBlank()) {
            dialogChildren.add(new Component.Text(readOnlyReason, Em.of(0.85f), LOCK_FG));
        }
        dialogChildren.addAll(rows);
        dialogChildren.add(buttonRow(readOnly, readers, onAccept));

        Component dialog = new Component.Flex(
            Em.of(30f), Em.AUTO, Em.of(1.0f), DIALOG_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.5f),
            dialogChildren, false, 0);

        Component framed = new Component.Flex(
            Em.AUTO, Em.AUTO, Em.of(0.125f), DIALOG_BORDER,
            Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
            List.of(dialog), false, 0);

        OverlayStack.push(new OverlayStack.Overlay(framed, Anchor.CENTER, true, () -> {}));
    }

    // ---------- per-field rows ----------

    private static Component intRow(IntField f, Object initial, boolean readOnly,
                                    Map<String, Supplier<Object>> readers) {
        int value = (initial instanceof Number n) ? n.intValue() : f.defaultValue();
        Component.Text input = editableField(String.valueOf(value), !readOnly);
        readers.put(f.key(), () -> {
            String s = TextStates.contentOf(input).trim();
            int v;
            try { v = Integer.parseInt(s); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("must be an integer: '" + s + "'"); }
            if (v < f.min() || v > f.max()) {
                throw new IllegalArgumentException("must be in [" + f.min() + ", " + f.max() + "]");
            }
            return v;
        });
        return labeledRow(f.label(), wrapInput(input));
    }

    private static Component doubleRow(DoubleField f, Object initial, boolean readOnly,
                                       Map<String, Supplier<Object>> readers) {
        double value = (initial instanceof Number n) ? n.doubleValue() : f.defaultValue();
        Component.Text input = editableField(String.valueOf(value), !readOnly);
        readers.put(f.key(), () -> {
            String s = TextStates.contentOf(input).trim();
            double v;
            try { v = Double.parseDouble(s); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("must be a number: '" + s + "'"); }
            if (v < f.min() || v > f.max()) {
                throw new IllegalArgumentException("must be in [" + f.min() + ", " + f.max() + "]");
            }
            return v;
        });
        return labeledRow(f.label(), wrapInput(input));
    }

    private static Component boolRow(BoolField f, Object initial, boolean readOnly,
                                     Map<String, Supplier<Object>> readers) {
        boolean value = (initial instanceof Boolean b) ? b : f.defaultValue();
        Property<Boolean> cell = new Property<>(value);
        Component checkbox = new Component.Checkbox(
            Em.of(1.0f), BOX, CHECK, cell, !readOnly, 0);
        readers.put(f.key(), cell::get);
        return labeledRow(f.label(), checkbox);
    }

    private static Component enumRow(EnumField f, Object initial, boolean readOnly,
                                     Map<String, Supplier<Object>> readers) {
        String value = (initial instanceof String s && f.choices().contains(s)) ? s : f.defaultValue();
        Property<String> group = new Property<>(value);
        List<Component> children = new ArrayList<>();
        for (String choice : f.choices()) {
            Component radio = new Component.Radio<>(
                Em.of(0.9f), BOX, RADIO_DOT, group, choice, !readOnly, 0);
            Component label = new Component.Text(choice, Em.of(0.9f), LABEL_FG);
            // Wrap each radio+label pair so the radio click area sits
            // tight to its own label and the inter-pair gap is preserved.
            children.add(new Component.Flex(
                Em.AUTO, Em.AUTO, Em.ZERO, TRANSPARENT,
                Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.4f),
                List.of(radio, label), false, 0));
        }
        // Stack vertically when a horizontal row would overflow the
        // ~22em input column on a 30em dialog. Cheap heuristic: more
        // than 3 choices OR any choice longer than 16 chars. Keeps the
        // common case (DELTA/BOX/TENT, SEQUENTIAL/RANDOM/DISTRIBUTED)
        // horizontal; switches multi-column / long-label enums (Dataset
        // Column sources, AutoTokenizer2D mode incl. GAUSSIAN_PRIME) to
        // a scannable column.
        boolean stacked = f.choices().size() > 3
            || f.choices().stream().anyMatch(c -> c.length() > 16);
        Component groupFlex = new Component.Flex(
            null, Em.AUTO, Em.ZERO, TRANSPARENT,
            stacked ? Direction.COLUMN : Direction.ROW,
            JustifyContent.START,
            stacked ? AlignItems.START : AlignItems.CENTER,
            stacked ? Em.of(0.25f) : Em.of(1.2f),
            children, false, 1);
        readers.put(f.key(), group::get);
        return labeledRow(f.label(), groupFlex);
    }

    private static Component intArrayRow(IntArrayField f, Object initial, boolean readOnly,
                                         Map<String, Supplier<Object>> readers) {
        int[] value;
        if (initial instanceof int[] arr) value = arr;
        else if (initial instanceof List<?> list) {
            value = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                value[i] = list.get(i) instanceof Number n ? n.intValue() : 0;
            }
        } else {
            value = f.defaultValue();
        }
        Component.Text input = editableField(joinInts(value), !readOnly);
        readers.put(f.key(), () -> {
            String s = TextStates.contentOf(input).trim();
            String[] parts = s.isEmpty() ? new String[0] : s.split("\\s*,\\s*");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try { out[i] = Integer.parseInt(parts[i]); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("entry " + i + " not an integer: '" + parts[i] + "'"); }
            }
            if (out.length < f.minLen() || out.length > f.maxLen()) {
                throw new IllegalArgumentException(
                    "length " + out.length + " outside [" + f.minLen() + ", " + f.maxLen() + "]");
            }
            return out;
        });
        return labeledRow(f.label(), wrapInput(input));
    }

    // ---------- helpers ----------

    private static Component.Text editableField(String content, boolean editable) {
        return new Component.Text(
            content, FontGroups.DEFAULT, Em.of(0.95f), LABEL_FG,
            FIELD_WIDTH, null, Em.of(0.2f),
            FIELD_WIDTH, true,
            editable, editable, editable, false, 0);
    }

    private static Component wrapInput(Component.Text field) {
        return new Component.Flex(
            FIELD_WIDTH, Em.of(1.8f), Em.ZERO, INPUT_BG,
            Direction.ROW, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(field), false, 0);
    }

    private static Component labeledRow(String label, Component input) {
        Component labelText = new Component.Text(label, Em.of(0.9f), HINT_FG)
            .withWidth(LABEL_WIDTH);
        return new Component.Flex(
            null, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.5f),
            List.of(labelText, input), false, 0);
    }

    private static Component buttonRow(boolean readOnly,
                                       Map<String, Supplier<Object>> readers,
                                       Consumer<Map<String, Object>> onAccept) {
        List<Component> buttons = new ArrayList<>();
        if (readOnly) {
            buttons.add(Themed.button("Close", Em.of(6f), Variant.PRIMARY, 0, OverlayStack::pop));
        } else {
            buttons.add(Themed.button("Cancel", Em.of(6f), Variant.DEFAULT, 0, OverlayStack::pop));
            buttons.add(Themed.button("Confirm", Em.of(6f), Variant.PRIMARY, 0, () -> {
                Map<String, Object> parsed = new LinkedHashMap<>();
                for (Map.Entry<String, Supplier<Object>> e : readers.entrySet()) {
                    try {
                        parsed.put(e.getKey(), e.getValue().get());
                    } catch (RuntimeException ex) {
                        Status.warn("Invalid value for '" + e.getKey() + "': " + ex.getMessage());
                        return;
                    }
                }
                OverlayStack.pop();
                onAccept.accept(parsed);
            }));
        }
        return new Component.Flex(
            null, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.END, AlignItems.CENTER, Em.of(0.5f),
            buttons, false, 0);
    }

    private static String joinInts(int[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
