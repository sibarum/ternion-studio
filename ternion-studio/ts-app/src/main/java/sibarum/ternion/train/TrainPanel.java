package sibarum.ternion.train;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.status.Status;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.app.Toolbars;
import sibarum.ternion.app.generated.Icons;
import sibarum.ternion.designer.GraphSync;

import java.util.List;

/**
 * Train tab. Hosts the run/pause/step/stop controls, LR slider, and
 * live status readout backed by {@link TrainingController}'s
 * {@code Property} fields. Loss chart + per-node feedback render here
 * too in Phases 7–8; this MVP version shows numeric status only.
 */
public final class TrainPanel {

    private static final Color SURFACE_BG = new Color(0.07f, 0.09f, 0.13f, 1f);
    private static final Color TOOLBAR_BG = new Color(0.10f, 0.12f, 0.15f, 1f);
    private static final Color LABEL_FG   = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color HINT_FG    = new Color(0.70f, 0.75f, 0.82f, 0.85f);
    private static final Color ERROR_FG   = new Color(0.90f, 0.45f, 0.45f, 1f);

    private static final Color SL_TRACK = new Color(0.18f, 0.20f, 0.25f, 1f);
    private static final Color SL_FILL  = new Color(0.30f, 0.55f, 0.85f, 1f);
    private static final Color SL_THUMB = new Color(0.90f, 0.92f, 0.97f, 1f);
    private static final Color CB_BOX   = new Color(0.18f, 0.20f, 0.25f, 1f);
    private static final Color CB_CHECK = new Color(0.30f, 0.80f, 0.50f, 1f);
    private static final Color INPUT_BG = new Color(0.13f, 0.16f, 0.21f, 1f);

    /** Mutable bag the per-section builders fill in so {@link #build}
     *  can wire {@link TrainStatusRefresher} with every Text ref at once. */
    private static final class Refs {
        Component.Text toggleLabel;
        Component.Text lrEdit;
        Component.Text state, epoch, example, loss, error;
    }

    public static Component build(AppContext ctx) {
        TrainingController controller = ctx.trainingController();
        Refs refs = new Refs();

        Component controls = buildControls(ctx, refs);
        Component params   = buildParameters(controller, refs);
        Component status   = buildStatus(controller, refs);
        Component chart    = LossChart.build(controller);

        // Marshal every worker-fired Property update through one main-thread
        // refresh tick (called from TsApp's render closure). Avoids the
        // unsynchronized TextStates IdentityHashMap being mutated from the
        // training worker, which was causing transient renderer probe-sequence
        // failures (connectors disappearing, nodes phasing) once the
        // visualizer's per-step activity widened the race window.
        TrainStatusRefresher.install(new TrainStatusRefresher.Binding(
            controller,
            refs.state, refs.epoch, refs.example, refs.loss, refs.error,
            refs.lrEdit, refs.toggleLabel,
            TrainPanel::formatLr,
            TrainPanel::formatLoss,
            TrainPanel::toggleLabelFor,
            s -> (s == null || s.isEmpty()) ? "" : "error: " + s
        ));

        return new Component.Flex(
            null, null, Em.of(0.8f), SURFACE_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.8f),
            List.of(controls, params, status, chart),
            false, 1
        );
    }

    private static Component buildControls(AppContext ctx, Refs refs) {
        TrainingController controller = ctx.trainingController();

        // Toggle is the one button whose glyph swaps with state (Start/Resume
        // use the play icon, Pause uses pause). We keep a reference to the
        // inner Text so TrainStatusRefresher can call TextStates.setContent
        // with a fresh codepoint string on the main thread.
        Toolbars.Toggle toggle = Toolbars.toggleIconButton(
            Icons.PLAY, Variant.SUCCESS,
            "Start / Pause / Resume training (Space)",
            () -> onToggle(controller));

        Component step  = Toolbars.iconButton(
            Icons.STEP_FORWARD, Variant.INFO,
            "Step — run exactly one training example (forward + backward + step). "
            + "Bootstraps the worker paused if not already running.",
            controller::step);
        Component stop  = Toolbars.iconButton(
            Icons.SQUARE, Variant.ERROR,
            "Stop — halt training. Parameter weights remain; click Start to resume.",
            controller::stop);
        Component reset = Toolbars.iconButton(
            Icons.ROTATE_CCW, Variant.WARNING,
            "Reset — stop training, reinitialize every trainable's weights from a "
            + "fresh seed, clear the loss chart. Corpus and graph topology preserved.",
            () -> onReset(ctx));

        refs.toggleLabel = toggle.glyph();

        return new Component.Flex(
            null, Em.of(2.6f), Em.of(0.5f), TOOLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.6f),
            List.of(toggle.button(), step, stop, reset),
            false, 0
        );
    }

    /**
     * Stop training, reinitialize every Trainable's weights, unfreeze every
     * node, and clear the loss history. Corpus rows and graph topology are
     * preserved. Use when training has diverged (NaN weights) and you want
     * to start over without rebuilding the graph by hand.
     */
    private static void onReset(AppContext ctx) {
        TrainingController controller = ctx.trainingController();
        controller.stop();
        GraphSync.ResetSummary summary = ctx.graphSync().resetTraining(System.nanoTime());
        controller.lossHistory().reset();
        controller.lastError().set("");
        String msg = "Reset training: reinitialized " + summary.reinitialized() + " trainable(s)";
        if (!summary.skipped().isEmpty()) {
            msg += "; skipped (no reinit support): " + String.join(", ", summary.skipped());
        }
        Status.success(msg);
    }

    private static void onToggle(TrainingController controller) {
        TrainingState s = controller.state().get();
        switch (s) {
            case IDLE, STOPPED -> controller.start();
            case RUNNING       -> controller.pause();
            case PAUSED        -> controller.resume();
        }
    }

    /**
     * Glyph codepoint the toggle button should show in a given training
     * state, encoded as a single-char string suitable for
     * {@link sibarum.dasum.gui.core.input.TextStates#setContent}.
     * Start / Resume both use the {@code play} icon; Pause uses {@code pause}.
     */
    private static String toggleLabelFor(TrainingState s) {
        int cp = switch (s) {
            case IDLE, STOPPED -> Icons.PLAY;
            case RUNNING       -> Icons.PAUSE;
            case PAUSED        -> Icons.PLAY;
        };
        return String.valueOf((char) cp);
    }

    private static Component buildParameters(TrainingController controller, Refs refs) {
        Property<Float> lr = controller.learningRate();
        Component lrLabel = new Component.Text("Learning rate", Em.of(0.9f), LABEL_FG);
        Component lrSlider = new Component.Slider(
            Direction.ROW,
            Em.of(14f), Em.of(0.8f), Em.of(0.5f),
            SL_TRACK, SL_FILL, SL_THUMB,
            lr, 0.001f, 1.0f);

        // Editable text input mirroring the slider value. Type a precise
        // LR and press Apply (or move the slider for rough adjustment).
        Component.Text lrEdit = new Component.Text(
            formatLr(lr.get()), sibarum.dasum.gui.core.text.FontGroups.DEFAULT,
            Em.of(0.9f), LABEL_FG,
            Em.of(4.5f), null, Em.of(0.2f),
            Em.of(4.5f), true,
            true, true, true, false, 0);
        Component lrInputFrame = new Component.Flex(
            Em.of(4.5f), Em.of(1.7f), Em.ZERO, INPUT_BG,
            Direction.ROW, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(lrEdit), false, 0);

        // Slider drag → updates lr → main-thread refresher mirrors back
        // into the text input (avoids worker-thread TextStates writes when
        // an external Property.set happens from outside the UI thread).
        refs.lrEdit = lrEdit;

        Component applyLr = Toolbars.iconButton(
            Icons.CHECK, Variant.PRIMARY,
            "Apply — parse the typed value and set the learning rate. Range (0, 10].",
            () -> {
                String s = TextStates.contentOf(lrEdit).trim();
                try {
                    float v = Float.parseFloat(s);
                    if (v <= 0f || v > 10f) {
                        sibarum.dasum.gui.core.status.Status.warn(
                            "LR must be in (0, 10]; got " + s);
                        return;
                    }
                    lr.set(v);
                } catch (NumberFormatException ex) {
                    sibarum.dasum.gui.core.status.Status.warn(
                        "LR not a number: '" + s + "'");
                }
            });

        Component stepEvery = new Component.Checkbox(Em.of(1.0f),
            CB_BOX, CB_CHECK, controller.stepEveryExample());
        Component stepEveryLabel = new Component.Text(
            "Step every example", Em.of(0.9f), HINT_FG);

        return new Component.Flex(
            null, Em.of(2.2f), Em.of(0.5f), SURFACE_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.7f),
            List.of(lrLabel, lrSlider, lrInputFrame, applyLr, stepEvery, stepEveryLabel),
            false, 0
        );
    }

    private static String formatLr(float lr) {
        return String.format("%.3f", lr);
    }

    private static Component buildStatus(TrainingController controller, Refs refs) {
        Component.Text state = labelText(
            "state: " + controller.state().get(), LABEL_FG);
        Component.Text epoch = labelText(
            "epoch: " + controller.currentEpoch().get(), LABEL_FG);
        Component.Text example = labelText(
            "example: " + controller.currentExample().get(), LABEL_FG);
        Component.Text loss = labelText(
            "loss: " + formatLoss(controller.currentLoss().get()), LABEL_FG);
        Component.Text error = labelText("", ERROR_FG);

        // Property → TextStates writes happen on the render thread via
        // TrainStatusRefresher.refresh — never directly from worker-fired
        // Property.set chains (Property fires subscribers synchronously
        // on the calling thread; the worker calls maybePublishUi, which
        // would otherwise update TextStates from the wrong thread).
        refs.state   = state;
        refs.epoch   = epoch;
        refs.example = example;
        refs.loss    = loss;
        refs.error   = error;

        return new Component.Flex(
            null, Em.AUTO, Em.of(0.5f), SURFACE_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(1.2f),
            List.of(state, epoch, example, loss, error),
            false, 0
        );
    }

    private static String formatLoss(double v) {
        return String.format("%.4f", v);
    }

    private static Component.Text labelText(String s, Color fg) {
        return new Component.Text(s, sibarum.dasum.gui.core.text.FontGroups.DEFAULT,
            Em.of(0.9f), fg,
            null, null, Em.ZERO, null, false,
            false, false, false, false, 0);
    }

    private TrainPanel() {}
}
