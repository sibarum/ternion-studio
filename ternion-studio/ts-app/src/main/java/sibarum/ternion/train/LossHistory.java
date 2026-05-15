package sibarum.ternion.train;

import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.reactive.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Ring-bounded append-only list of (stepIndex, loss) samples used by
 * the loss-chart Component in Phase 7. Worker thread appends from
 * {@link TrainingController#runOneExample}; the GLFW thread reads on
 * render. Synchronized internally; {@link #version} bumps on each
 * append so subscribers can re-layout.
 *
 * <p>Capped at {@link #MAX_SAMPLES} to keep layout cost bounded even
 * during long runs; older samples drop off the front of the buffer.
 */
public final class LossHistory {

    public static final int MAX_SAMPLES = 512;

    public record Sample(long step, double loss) {}

    private final ArrayList<Sample> samples = new ArrayList<>();
    private long stepCounter = 0L;
    private final Property<Integer> version = new Property<>(0);

    /** Append one sample; thread-safe. */
    public synchronized Sample append(double loss) {
        Sample s = new Sample(stepCounter++, loss);
        samples.add(s);
        if (samples.size() > MAX_SAMPLES) {
            samples.subList(0, samples.size() - MAX_SAMPLES).clear();
        }
        bump();
        return s;
    }

    public synchronized List<Sample> snapshot() {
        return List.copyOf(samples);
    }

    public synchronized double rollingMax() {
        double m = 0.0;
        for (Sample s : samples) if (s.loss > m) m = s.loss;
        return m;
    }

    public synchronized int size() {
        return samples.size();
    }

    public synchronized long stepCount() {
        return stepCounter;
    }

    public synchronized void reset() {
        samples.clear();
        stepCounter = 0L;
        bump();
    }

    public Property<Integer> version() { return version; }

    private void bump() {
        version.set(version.get() + 1);
        Invalidator.invalidate();
    }
}
