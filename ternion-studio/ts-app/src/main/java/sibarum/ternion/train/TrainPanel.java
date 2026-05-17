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
import sibarum.dasum.gui.core.theme.Palette;
import sibarum.dasum.gui.core.theme.Theme;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.status.Status;
import sibarum.ternion.app.AppContext;
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

    public static Component build(AppContext ctx) {
        TrainingController controller = ctx.trainingController();

        Component controls = buildControls(ctx);
        Component params   = buildParameters(controller);
        Component status   = buildStatus(controller);
        Component chart    = LossChart.build(controller);

        return new Component.Flex(
            null, null, Em.of(0.8f), SURFACE_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.8f),
            List.of(controls, params, status, chart),
            false, 1
        );
    }

    private static Component buildControls(AppContext ctx) {
        TrainingController controller = ctx.trainingController();
        // Toggle button label adapts to state. We can't rebuild a Text's
        // content via the record (it's immutable), so we use TextStates'
        // content-override mechanism — same one the editable Text widgets
        // use. Reading via Render goes through TextStates.contentOf.
        // Build the button by hand (instead of Themed.button) so we hold
        // a reference to the label Text and can mutate its content.
        Palette palette = Theme.of(Variant.SUCCESS);
        Component.Text toggleLabel = new Component.Text(
            "Start", sibarum.dasum.gui.core.text.FontGroups.DEFAULT,
            Em.of(0.85f), palette.onBase(),
            null, null, Em.ZERO, null, false,
            false, false, false, false, 0);
        Component toggle = new Component.Flex(
            Em.of(6.5f), Em.of(2f), Em.of(0.3f), palette.base(),
            Direction.ROW, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
            List.of(toggleLabel), true, 0);
        Handlers.onClick(toggle, () -> onToggle(controller));

        Component step  = Themed.button("Step",   Em.of(5.5f), Variant.INFO,    0);
        Component stop  = Themed.button("Stop",   Em.of(5.5f), Variant.ERROR,   0);
        Component reset = Themed.button("Reset",  Em.of(5.5f), Variant.WARNING, 0);
        Handlers.onClick(step,  controller::step);
        Handlers.onClick(stop,  controller::stop);
        Handlers.onClick(reset, () -> onReset(ctx));

        // Update toggle label on state changes.
        controller.state().subscribe(s -> TextStates.setContent(toggleLabel, toggleLabelFor(s)));

        return new Component.Flex(
            null, Em.of(2.6f), Em.of(0.5f), TOOLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.6f),
            List.of(toggle, step, stop, reset),
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

    private static String toggleLabelFor(TrainingState s) {
        return switch (s) {
            case IDLE, STOPPED -> "Start";
            case RUNNING       -> "Pause";
            case PAUSED        -> "Resume";
        };
    }

    private static Component buildParameters(TrainingController controller) {
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

        // Slider drag → updates lr → mirrors back into the text input.
        // This may stomp on a half-typed value if the user is editing while
        // dragging, but the user wouldn't typically be doing both at once.
        lr.subscribe(v -> TextStates.setContent(lrEdit, formatLr(v)));

        Component applyLr = Themed.button("Apply", Em.of(4.5f), Variant.PRIMARY, 0, () -> {
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

    private static Component buildStatus(TrainingController controller) {
        Component.Text state = labelText(
            "state: " + controller.state().get(), LABEL_FG);
        Component.Text epoch = labelText(
            "epoch: " + controller.currentEpoch().get(), LABEL_FG);
        Component.Text example = labelText(
            "example: " + controller.currentExample().get(), LABEL_FG);
        Component.Text loss = labelText(
            "loss: " + formatLoss(controller.currentLoss().get()), LABEL_FG);
        Component.Text error = labelText("", ERROR_FG);

        controller.state().subscribe(s -> TextStates.setContent(state,   "state: " + s));
        controller.currentEpoch().subscribe(v -> TextStates.setContent(epoch,   "epoch: " + v));
        controller.currentExample().subscribe(v -> TextStates.setContent(example, "example: " + v));
        controller.currentLoss().subscribe(v -> TextStates.setContent(loss,    "loss: " + formatLoss(v)));
        controller.lastError().subscribe(s -> TextStates.setContent(error,
            (s == null || s.isEmpty()) ? "" : "error: " + s));

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
