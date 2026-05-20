package sibarum.ternion.app;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.overlay.Tooltips;
import sibarum.dasum.gui.core.text.Icon;
import sibarum.dasum.gui.core.theme.Palette;
import sibarum.dasum.gui.core.theme.Theme;
import sibarum.dasum.gui.core.theme.Variant;

import java.util.List;

/**
 * Shared icon-only-button factories used across the Designer / Data /
 * Train toolbars. Each button is a Flex containing a single Lucide
 * glyph, sized 2.4em × 2.0em — compact enough that several fit on a
 * narrow toolbar, large enough that the glyph reads clearly.
 *
 * <p>Tooltips are mandatory on the {@link #iconButton} entry point —
 * an icon without context is a guessing game, so the constructor
 * forces the caller to supply hover text. Use {@link Tooltips#set} on
 * the returned component directly if you need to swap text later
 * (e.g. a toggle button whose action changes by state).
 */
public final class Toolbars {

    /** Default glyph size inside an {@link #iconButton}. */
    public static final Em ICON_GLYPH_SIZE = Em.of(1.3f);
    /** Default button outer size — square-ish, fits comfortably on a
     *  2.5em toolbar with 0.5em padding above + below. */
    public static final Em BUTTON_WIDTH  = Em.of(2.4f);
    public static final Em BUTTON_HEIGHT = Em.of(2.0f);

    private Toolbars() {}

    /**
     * Icon-only button styled by the given {@link Variant}. Click invokes
     * {@code onClick}; {@code tooltip} appears on dwell.
     *
     * <p>Returns the wrapper {@link Component.Flex} — the caller can keep
     * a reference if it needs to mutate state later (e.g. update the
     * tooltip text via {@link Tooltips#set} on a toggle button).
     */
    public static Component.Flex iconButton(int codepoint, Variant variant,
                                            String tooltip, Runnable onClick) {
        Palette palette = Theme.of(variant);
        Component glyph = Icon.of(codepoint, ICON_GLYPH_SIZE, palette.onBase());
        Component.Flex btn = new Component.Flex(
            BUTTON_WIDTH, BUTTON_HEIGHT, Em.ZERO, palette.base(),
            Direction.ROW, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
            List.of(glyph), true, 0);
        Handlers.onClick(btn, onClick);
        if (tooltip != null && !tooltip.isEmpty()) {
            Tooltips.set(btn, tooltip);
        }
        return btn;
    }

    /**
     * Like {@link #iconButton} but exposes the inner glyph Text so the
     * caller can swap its content (and therefore the rendered icon) via
     * {@link sibarum.dasum.gui.core.input.TextStates#setContent}. Use for
     * state-driven toggles like Start ↔ Pause ↔ Resume.
     */
    public static Toggle toggleIconButton(int initialCodepoint, Variant variant,
                                          String tooltip, Runnable onClick) {
        Palette palette = Theme.of(variant);
        Component.Text glyph = Icon.of(initialCodepoint, ICON_GLYPH_SIZE, palette.onBase());
        Component.Flex btn = new Component.Flex(
            BUTTON_WIDTH, BUTTON_HEIGHT, Em.ZERO, palette.base(),
            Direction.ROW, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
            List.of(glyph), true, 0);
        Handlers.onClick(btn, onClick);
        if (tooltip != null && !tooltip.isEmpty()) {
            Tooltips.set(btn, tooltip);
        }
        return new Toggle(btn, glyph);
    }

    /** Pair returned by {@link #toggleIconButton}. */
    public record Toggle(Component.Flex button, Component.Text glyph) {}
}
