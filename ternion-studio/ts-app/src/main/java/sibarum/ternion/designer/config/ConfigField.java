package sibarum.ternion.designer.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Schema descriptor for one configurable field of a primitive. A
 * {@link sibarum.ternion.designer.PaletteItem} declares a list of these
 * for the structural parameters it accepts (e.g. {@code inDim}, {@code K},
 * {@code kernel}). The {@link ConfigDialog} renders one widget per kind,
 * collects user input, and hands the resulting {@code Map<String,Object>}
 * to the primitive factory.
 *
 * <p>Field {@code key}s must match the corresponding
 * {@link sibarum.mcc.primitive.Configurable#config} key — schema + values
 * is the same data that round-trips through serialization.
 */
public sealed interface ConfigField {

    String key();
    String label();

    record IntField(String key, String label, int defaultValue, int min, int max) implements ConfigField {}

    record DoubleField(String key, String label, double defaultValue, double min, double max) implements ConfigField {}

    record BoolField(String key, String label, boolean defaultValue) implements ConfigField {}

    record EnumField(String key, String label, String defaultValue, List<String> choices) implements ConfigField {
        public EnumField {
            choices = List.copyOf(choices);
            if (!choices.contains(defaultValue)) {
                throw new IllegalArgumentException(
                    "EnumField '" + key + "' default '" + defaultValue + "' not in choices " + choices);
            }
        }
    }

    record IntArrayField(String key, String label, int[] defaultValue, int minLen, int maxLen) implements ConfigField {
        public IntArrayField {
            defaultValue = defaultValue.clone();
            if (defaultValue.length < minLen || defaultValue.length > maxLen) {
                throw new IllegalArgumentException(
                    "IntArrayField '" + key + "' default length " + defaultValue.length
                        + " outside [" + minLen + ", " + maxLen + "]");
            }
        }
    }

    /**
     * Enum-style field whose choices + default are resolved lazily
     * each time the {@link ConfigDialog} opens — so a source picker
     * shows editable sources that were registered after JVM startup,
     * not just the bundled ones baked in at palette init.
     *
     * <p>{@link #materialize()} snapshots the supplier into a regular
     * {@link EnumField} for rendering; if the supplied default isn't
     * in the supplied choices (registry drift, OOV default), it falls
     * back to the first choice rather than throwing.
     */
    record DynamicEnumField(
        String key,
        String label,
        Supplier<String> defaultValueSupplier,
        Supplier<List<String>> choicesSupplier
    ) implements ConfigField {

        /** Resolve to a concrete {@link EnumField} for {@link ConfigDialog}'s
         *  render. Both suppliers fire here. */
        public EnumField materialize() {
            List<String> choices = choicesSupplier.get();
            if (choices == null || choices.isEmpty()) {
                // Render-time fallback so the dialog stays openable
                // even before any sources are registered.
                choices = List.of("(none)");
            }
            String def = defaultValueSupplier.get();
            if (def == null || !choices.contains(def)) def = choices.get(0);
            return new EnumField(key, label, def, choices);
        }
    }

    /** Insertion-ordered map of defaults from a schema. */
    static Map<String, Object> defaultsOf(List<ConfigField> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ConfigField f : schema) {
            out.put(f.key(), switch (f) {
                case IntField i        -> i.defaultValue();
                case DoubleField d     -> d.defaultValue();
                case BoolField b       -> b.defaultValue();
                case EnumField e       -> e.defaultValue();
                case DynamicEnumField d -> d.materialize().defaultValue();
                case IntArrayField a   -> a.defaultValue();
            });
        }
        return out;
    }
}
