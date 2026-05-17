package sibarum.ternion.designer;

import sibarum.dasum.gui.core.component.Component;
import sibarum.mcc.primitive.Configurable;

import java.util.Map;
import java.util.OptionalInt;

/**
 * Resolves the concrete output dimension of a port, when the owning
 * primitive's {@link Configurable#config} makes it knowable. Returns
 * {@link OptionalInt#empty} when the primitive's port shape can't be
 * read from config alone — those ports get the conservative "allow the
 * wire, let training surface the error" treatment in
 * {@link ConnectionRules}.
 *
 * <p>This is the small table mentioned in the design — it lives here in
 * ternion rather than as a {@code Primitive}-level interface in mcc-core
 * so that mcc stays focused on math and serialization, and the editor
 * owns its own shape rules.
 */
public final class PortDimResolver {

    private PortDimResolver() {}

    /**
     * @param spec node whose port we want to size
     * @param port the port component (must belong to {@code spec})
     * @return its dim if knowable from the primitive's {@code config()},
     *         empty otherwise
     */
    public static OptionalInt dimOf(NodeSpec spec, Component port) {
        if (spec == null || port == null) return OptionalInt.empty();
        if (!(spec.tNode().primitive() instanceof Configurable c)) return OptionalInt.empty();
        Map<String, Object> cfg = c.config();
        boolean isInput = spec.orderedInputPorts().contains(port);
        boolean isOutput = port == spec.outputPort();
        if (!isInput && !isOutput) return OptionalInt.empty();

        return switch (spec.paletteKey()) {
            case "linear.linear" -> isInput
                ? intFrom(cfg, "inDim")
                : intFrom(cfg, "outDim");
            case "linear.harmonic_lift" -> {
                OptionalInt in = intFrom(cfg, "inputDim");
                if (isInput) yield in;
                OptionalInt k = intFrom(cfg, "K");
                yield (in.isPresent() && k.isPresent())
                    ? OptionalInt.of(2 * k.getAsInt() * in.getAsInt())
                    : OptionalInt.empty();
            }
            default -> OptionalInt.empty();
        };
    }

    private static OptionalInt intFrom(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        return v instanceof Number n ? OptionalInt.of(n.intValue()) : OptionalInt.empty();
    }
}
