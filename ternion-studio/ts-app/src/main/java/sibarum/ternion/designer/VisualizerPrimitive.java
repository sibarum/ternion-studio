package sibarum.ternion.designer;

import sibarum.mcc.primitive.Differentiable;
import sibarum.mcc.primitive.Inversion;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;

import java.util.List;

/**
 * Identity pass-through primitive for the Visualizer node — sits inline
 * on a wire, forwards its input verbatim, and lets the gradient flow
 * unchanged on the way back. Adds no computational change to the model.
 *
 * <p>All visualization happens outside the primitive: {@link
 * sibarum.ternion.train.TrainingController} reads the post-forward
 * {@link sibarum.mcc.graph.CompGraphNode#producedValue} and the
 * per-node gradient already captured during its backward walk, then
 * publishes a {@link sibarum.dasum.gui.vis.pointcloud.PointCloudSnapshot}
 * via {@link VisualizerNodes}. Keeping the primitive dumb means it
 * costs nothing extra on the math side, and the visual layer can be
 * iterated without recompiling mcc.
 */
public final class VisualizerPrimitive implements Differentiable {

    private final ValueType type;

    public VisualizerPrimitive(ValueType type) {
        if (type == null) throw new IllegalArgumentException("type");
        this.type = type;
    }

    public ValueType valueType() { return type; }

    @Override
    public String name() {
        return "visualizer-" + type.name().toLowerCase();
    }

    @Override
    public List<ValueType> inputTypes() {
        return List.of(type);
    }

    @Override
    public ValueType outputType() {
        return type;
    }

    @Override
    public Value apply(List<Value> inputs) {
        return inputs.getFirst();
    }

    @Override
    public List<Value> backward(Value gradOutput) {
        return List.of(gradOutput);
    }

    @Override
    public Inversion inversion() {
        return (target, ctx) -> List.of(target);
    }
}
