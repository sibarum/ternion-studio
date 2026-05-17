package sibarum.ternion.designer.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Insertion-ordered map of defaults from a schema. */
    static Map<String, Object> defaultsOf(List<ConfigField> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ConfigField f : schema) {
            out.put(f.key(), switch (f) {
                case IntField i      -> i.defaultValue();
                case DoubleField d   -> d.defaultValue();
                case BoolField b     -> b.defaultValue();
                case EnumField e     -> e.defaultValue();
                case IntArrayField a -> a.defaultValue();
            });
        }
        return out;
    }
}
