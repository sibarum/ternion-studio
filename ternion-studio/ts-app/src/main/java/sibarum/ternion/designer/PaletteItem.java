package sibarum.ternion.designer;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.graph.NodeBuilder;
import sibarum.dasum.gui.core.graph.Ports;
import sibarum.dasum.gui.core.render.Color;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.substrate.TransformationNode;
import sibarum.mcc.primitive.Primitive;
import sibarum.mcc.value.ValueType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Spawnable item in the designer palette. Holds a label, a category for
 * grouping, and a {@link Supplier} that produces a fresh
 * {@link Primitive} on each call (each spawned node has its own primitive
 * instance so state — Parameter weights, Linear weights — is per-node).
 *
 * <p>{@link #spawn(String)} produces a {@link NodeSpec} ready to drop
 * onto a {@link sibarum.dasum.gui.core.component.Component.GraphSurface}.
 * Port-Component to slot-index mapping is derived from the primitive's
 * declared {@link Primitive#inputTypes()} order — NodeBuilder declares
 * input ports in add-order, so position N in the input port list lines
 * up with slot N on the resulting {@code CompGraphNode}.
 */
public final class PaletteItem {

    /** Visual / palette grouping. Cosmetic only. */
    public enum Category { INPUT, OUTPUT, ARITHMETIC, ACTIVATION, LINEAR, RESHAPE, GEOMETRY, CONVERSION, EMBEDDING }

    private final String key;
    private final String label;
    private final Category category;
    private final Color background;
    private final Supplier<Primitive> primitiveFactory;
    private final List<String> inputLabels;
    private final String outputLabel;

    public PaletteItem(String key, String label, Category category, Color background,
                       Supplier<Primitive> primitiveFactory,
                       List<String> inputLabels, String outputLabel) {
        this.key = key;
        this.label = label;
        this.category = category;
        this.background = background;
        this.primitiveFactory = primitiveFactory;
        this.inputLabels = List.copyOf(inputLabels);
        this.outputLabel = outputLabel;
    }

    public String key()          { return key; }
    public String label()        { return label; }
    public Category category()   { return category; }

    /**
     * Produce a fresh node ready for drop-in: builds the primitive, the
     * dasum node Component (with ports already declared via
     * {@link Ports#declare}), and the matching mcc
     * {@link CompGraphNode}. The caller is expected to add the visual to
     * the GraphSurface and register the {@link NodeSpec} with
     * {@link GraphSync}.
     */
    public NodeSpec spawn(String nodeId) {
        Primitive primitive = primitiveFactory.get();
        if (primitive.inputTypes().size() != inputLabels.size()) {
            throw new IllegalStateException(
                "PaletteItem '" + key + "' declares " + inputLabels.size()
                    + " input labels but primitive expects " + primitive.inputTypes().size());
        }
        NodeBuilder b = NodeBuilder.titled(label).background(background);
        for (int i = 0; i < inputLabels.size(); i++) {
            b.input(ValueTypeMapping.portTypeOf(primitive.inputTypes().get(i)), inputLabels.get(i));
        }
        if (outputLabel != null) {
            b.output(ValueTypeMapping.portTypeOf(primitive.outputType()), outputLabel);
        }
        Component visual = b.build();

        TransformationNode tNode = new TransformationNode(nodeId, primitive);
        CompGraphNode cgNode = new CompGraphNode(nodeId, tNode);

        Map<Component, PortBinding> bindings = new IdentityHashMap<>();
        List<Component> orderedInputs = new ArrayList<>(inputLabels.size());

        // Resolve port Components by the names we just declared via NodeBuilder.
        for (int i = 0; i < inputLabels.size(); i++) {
            Ports.Port p = Ports.byName(visual, inputLabels.get(i));
            if (p == null) {
                throw new IllegalStateException("Port not found post-build: " + inputLabels.get(i));
            }
            orderedInputs.add(p.component());
            bindings.put(p.component(),
                new PortBinding(cgNode, i, primitive.inputTypes().get(i)));
        }
        Component outputComp = null;
        if (outputLabel != null) {
            Ports.Port p = Ports.byName(visual, outputLabel);
            if (p == null) {
                throw new IllegalStateException("Output port not found: " + outputLabel);
            }
            outputComp = p.component();
            bindings.put(outputComp,
                new PortBinding(cgNode, PortBinding.OUTPUT_SLOT, primitive.outputType()));
        }
        return new NodeSpec(key, visual, tNode, cgNode, bindings, orderedInputs, outputComp);
    }

    /** Convenience: no input, single output. */
    public static PaletteItem source(String key, String label, Category cat, Color bg,
                                     Supplier<Primitive> primFactory, String outputLabel) {
        return new PaletteItem(key, label, cat, bg, primFactory, List.of(), outputLabel);
    }

    /** Convenience: unary op. */
    public static PaletteItem unary(String key, String label, Category cat, Color bg,
                                    Supplier<Primitive> primFactory,
                                    String inputLabel, String outputLabel) {
        return new PaletteItem(key, label, cat, bg, primFactory, List.of(inputLabel), outputLabel);
    }

    /** Convenience: binary op. */
    public static PaletteItem binary(String key, String label, Category cat, Color bg,
                                     Supplier<Primitive> primFactory,
                                     String aLabel, String bLabel, String outputLabel) {
        return new PaletteItem(key, label, cat, bg, primFactory, List.of(aLabel, bLabel), outputLabel);
    }
}
