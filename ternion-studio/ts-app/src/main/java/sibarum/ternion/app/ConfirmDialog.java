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
 * Modal yes/no confirmation overlay. Used for destructive actions
 * (New project, Reset Training, Delete Frozen Node) where an accidental
 * click could lose work.
 */
public final class ConfirmDialog {

    private static final Color DIALOG_BG     = new Color(0.10f, 0.12f, 0.16f, 1f);
    private static final Color DIALOG_BORDER = new Color(0.85f, 0.55f, 0.25f, 0.9f);
    private static final Color LABEL_FG      = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color BODY_FG       = new Color(0.78f, 0.84f, 0.92f, 0.95f);
    private static final Color TRANSPARENT   = new Color(0f, 0f, 0f, 0f);

    private ConfirmDialog() {}

    /**
     * @param title         dialog header
     * @param message       body text (single line; wrapped at ~24em)
     * @param confirmLabel  label on the destructive button (e.g. "Discard")
     * @param confirmVariant variant for the destructive button (typically ERROR or WARNING)
     * @param onConfirm     fires when the user clicks the destructive button
     */
    public static void show(String title, String message,
                            String confirmLabel, Variant confirmVariant,
                            Runnable onConfirm) {
        Component titleText = new Component.Text(title, Em.of(1.15f), LABEL_FG);
        Component bodyText  = new Component.Text(message, Em.of(0.95f), BODY_FG)
            .withWrapWidth(Em.of(24f))
            .withClip(true);

        Component cancel  = Themed.button("Cancel", Em.of(6f), Variant.DEFAULT, 0, OverlayStack::pop);
        Component confirm = Themed.button(confirmLabel, Em.of(8f), confirmVariant, 0, () -> {
            OverlayStack.pop();
            onConfirm.run();
        });

        Component buttonRow = new Component.Flex(
            null, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.END, AlignItems.CENTER, Em.of(0.5f),
            List.of(cancel, confirm), false, 0);

        Component dialog = new Component.Flex(
            Em.of(28f), Em.AUTO, Em.of(1.0f), DIALOG_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.7f),
            List.of(titleText, bodyText, buttonRow), false, 0);

        Component framed = new Component.Flex(
            Em.AUTO, Em.AUTO, Em.of(0.125f), DIALOG_BORDER,
            Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
            List.of(dialog), false, 0);

        OverlayStack.push(new OverlayStack.Overlay(framed, Anchor.CENTER, true, () -> {}));
    }
}
