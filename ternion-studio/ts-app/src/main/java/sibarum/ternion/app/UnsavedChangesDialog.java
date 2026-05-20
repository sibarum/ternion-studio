package sibarum.ternion.app;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.overlay.Anchor;
import sibarum.dasum.gui.core.overlay.OverlayStack;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;

import java.util.List;

/**
 * Three-button modal asked when the user tries to close the window
 * with unsaved changes. Mirrors the visual shell of
 * {@link ConfirmDialog} but adds a primary {@code Save} button so the
 * reflexive {@code Cmd+W} doesn't force a Save-Then-Close-Manually
 * detour.
 *
 * <p>Button semantics:
 * <ul>
 *   <li><b>Cancel</b> — dismiss the dialog; window stays open.</li>
 *   <li><b>Discard</b> — fire {@code onDiscard}; caller closes the
 *       window without saving.</li>
 *   <li><b>Save</b> (primary) — fire {@code onSave}; caller is
 *       expected to save then close. If save fails the caller should
 *       leave the window open and surface the error.</li>
 * </ul>
 */
public final class UnsavedChangesDialog {

    private static final Color DIALOG_BG     = new Color(0.10f, 0.12f, 0.16f, 1f);
    private static final Color DIALOG_BORDER = new Color(0.85f, 0.55f, 0.25f, 0.9f);
    private static final Color LABEL_FG      = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color BODY_FG       = new Color(0.78f, 0.84f, 0.92f, 0.95f);
    private static final Color TRANSPARENT   = new Color(0f, 0f, 0f, 0f);

    private UnsavedChangesDialog() {}

    public static void show(Runnable onSave, Runnable onDiscard) {
        Component titleText = new Component.Text(
            "Unsaved Changes", Em.of(1.15f), LABEL_FG);
        Component bodyText  = new Component.Text(
            "You have unsaved changes. Save your work before closing?",
            Em.of(0.95f), BODY_FG)
            .withWrapWidth(Em.of(24f))
            .withClip(true);

        Component cancel  = Themed.button("Cancel",  Em.of(6f), Variant.DEFAULT, 0,
            OverlayStack::pop);
        Component discard = Themed.button("Discard", Em.of(7f), Variant.ERROR, 0, () -> {
            OverlayStack.pop();
            onDiscard.run();
        });
        Component save    = Themed.button("Save",    Em.of(7f), Variant.PRIMARY, 0, () -> {
            OverlayStack.pop();
            onSave.run();
        });

        Component buttonRow = new Component.Flex(
            null, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.END, AlignItems.CENTER, Em.of(0.5f),
            List.of(cancel, discard, save), false, 0);

        Component dialog = new Component.Flex(
            Em.of(30f), Em.AUTO, Em.of(1.0f), DIALOG_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.7f),
            List.of(titleText, bodyText, buttonRow), false, 0);

        Component framed = new Component.Flex(
            Em.AUTO, Em.AUTO, Em.of(0.125f), DIALOG_BORDER,
            Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
            List.of(dialog), false, 0);

        OverlayStack.push(new OverlayStack.Overlay(framed, Anchor.CENTER, true, () -> {}));
    }
}
