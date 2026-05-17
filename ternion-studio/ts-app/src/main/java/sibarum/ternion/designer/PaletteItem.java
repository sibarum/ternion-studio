package sibarum.ternion.designer;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.DynamicChildren;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.graph.NodeBuilder;
import sibarum.dasum.gui.core.graph.Ports;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.text.FontGroups;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.substrate.TransformationNode;
import sibarum.mcc.primitive.Primitive;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.designer.config.ConfigField;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Spawnable item in the designer palette. Holds a label, a category for
 * grouping, an optional {@link ConfigField} schema for structural
 * parameters, and a factory that produces a fresh {@link Primitive}
 * from the user-supplied config map (or defaults).
 *
 * <p>For primitives whose port shape depends on config (e.g. Linear's
 * {@code inDim}/{@code outDim}, HarmonicLift's {@code K}/{@code inputDim}),
 * the schema is the single source of truth — palette doesn't bake shape
 * variants as separate entries. For stateless primitives (Relu, Add) the
 * schema is empty and the factory ignores its argument.
 */
public final class PaletteItem {

    /** Visual / palette grouping. Cosmetic only. */
    public enum Category { INPUT, OUTPUT, ARITHMETIC, ACTIVATION, LINEAR, RESHAPE, GEOMETRY, CONVERSION, EMBEDDING }

    private final String key;
    private final String label;
    private final Category category;
    private final Color background;
    private final Function<Map<String, Object>, Primitive> primitiveFactory;
    private final List<ConfigField> configSchema;
    private final List<String> inputLabels;
    private final String outputLabel;

    public PaletteItem(String key, String label, Category category, Color background,
                       List<ConfigField> configSchema,
                       Function<Map<String, Object>, Primitive> primitiveFactory,
                       List<String> inputLabels, String outputLabel) {
        this.key = key;
        this.label = label;
        this.category = category;
        this.background = background;
        this.primitiveFactory = primitiveFactory;
        this.configSchema = List.copyOf(configSchema);
        this.inputLabels = List.copyOf(inputLabels);
        this.outputLabel = outputLabel;
    }

    public String key()                       { return key; }
    public String label()                     { return label; }
    public Category category()                { return category; }
    public List<ConfigField> configSchema()   { return configSchema; }

    /** Spawn with the given config map. Use {@link ConfigField#defaultsOf}
     *  for the all-defaults case. Missing schema keys are backfilled from
     *  defaults so the factory and serialization always see a complete map. */
    public NodeSpec spawn(String nodeId, Map<String, Object> config) {
        Map<String, Object> resolved = new java.util.LinkedHashMap<>(ConfigField.defaultsOf(configSchema));
        if (config != null) resolved.putAll(config);
        Primitive primitive = primitiveFactory.apply(resolved);
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

        Component.Text previewText = new Component.Text(
            "", FontGroups.DEFAULT, Em.of(0.78f), PREVIEW_FG,
            null, null, Em.of(0.1f),
            Em.of(16f),
            true,
            false, false, false, false, 0);
        Component previewRow = new Component.Flex(
            null, Em.AUTO, Em.of(0.1f), PREVIEW_BG,
            Direction.ROW, JustifyContent.START, AlignItems.START, Em.ZERO,
            List.of(previewText), false, 0);
        DynamicChildren.add(visual, previewRow);

        return new NodeSpec(key, Map.copyOf(resolved), visual, tNode, cgNode,
            bindings, orderedInputs, outputComp, previewText);
    }

    private static final Color PREVIEW_FG = new Color(0.78f, 0.84f, 0.92f, 0.9f);
    private static final Color PREVIEW_BG = new Color(0f, 0f, 0f, 0.18f);

    /** Convenience: no inputs, single output, no config. */
    public static PaletteItem source(String key, String label, Category cat, Color bg,
                                     Function<Map<String, Object>, Primitive> primFactory,
                                     String outputLabel) {
        return new PaletteItem(key, label, cat, bg, List.of(), primFactory, List.of(), outputLabel);
    }

    /** Convenience: no inputs, single output, with schema. */
    public static PaletteItem source(String key, String label, Category cat, Color bg,
                                     List<ConfigField> schema,
                                     Function<Map<String, Object>, Primitive> primFactory,
                                     String outputLabel) {
        return new PaletteItem(key, label, cat, bg, schema, primFactory, List.of(), outputLabel);
    }

    /** Convenience: unary op, no config. */
    public static PaletteItem unary(String key, String label, Category cat, Color bg,
                                    Function<Map<String, Object>, Primitive> primFactory,
                                    String inputLabel, String outputLabel) {
        return new PaletteItem(key, label, cat, bg, List.of(), primFactory, List.of(inputLabel), outputLabel);
    }

    /** Convenience: unary op, with schema. */
    public static PaletteItem unary(String key, String label, Category cat, Color bg,
                                    List<ConfigField> schema,
                                    Function<Map<String, Object>, Primitive> primFactory,
                                    String inputLabel, String outputLabel) {
        return new PaletteItem(key, label, cat, bg, schema, primFactory, List.of(inputLabel), outputLabel);
    }

    /** Convenience: binary op, no config. */
    public static PaletteItem binary(String key, String label, Category cat, Color bg,
                                     Function<Map<String, Object>, Primitive> primFactory,
                                     String aLabel, String bLabel, String outputLabel) {
        return new PaletteItem(key, label, cat, bg, List.of(), primFactory, List.of(aLabel, bLabel), outputLabel);
    }
}
