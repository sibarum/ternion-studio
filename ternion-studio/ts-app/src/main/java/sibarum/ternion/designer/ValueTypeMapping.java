package sibarum.ternion.designer;

import sibarum.dasum.gui.core.graph.PortType;
import sibarum.dasum.gui.core.render.Color;
import sibarum.mcc.value.ValueType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Bidirectional mapping between mcc {@link ValueType} and dasum
 * {@link PortType}. One canonical {@code PortType} instance per
 * {@code ValueType}, lazily registered with its display color.
 */
public final class ValueTypeMapping {

    private static final Map<ValueType, PortType> BY_VALUE = new EnumMap<>(ValueType.class);
    private static final Map<PortType, ValueType> BY_PORT  = new java.util.IdentityHashMap<>();

    static {
        register(ValueType.STRING,     "string",     new Color(0.30f, 0.55f, 0.85f, 1f));
        register(ValueType.NUMBER,     "number",     new Color(0.85f, 0.55f, 0.25f, 1f));
        register(ValueType.MATRIX,     "matrix",     new Color(0.35f, 0.80f, 0.55f, 1f));
        register(ValueType.TERNION,    "ternion",    new Color(0.75f, 0.45f, 0.85f, 1f));
        register(ValueType.QUATERNION, "quaternion", new Color(0.85f, 0.35f, 0.45f, 1f));
        register(ValueType.TENSOR,     "tensor",     new Color(0.65f, 0.65f, 0.30f, 1f));
    }

    private static void register(ValueType vt, String id, Color color) {
        PortType pt = PortType.of(id, vt.name(), color);
        BY_VALUE.put(vt, pt);
        BY_PORT.put(pt, vt);
    }

    public static PortType portTypeOf(ValueType vt) {
        return BY_VALUE.get(vt);
    }

    public static ValueType valueTypeOf(PortType pt) {
        return BY_PORT.get(pt);
    }

    public static Color colorOf(ValueType vt) {
        return portTypeOf(vt).color();
    }

    private ValueTypeMapping() {}
}
