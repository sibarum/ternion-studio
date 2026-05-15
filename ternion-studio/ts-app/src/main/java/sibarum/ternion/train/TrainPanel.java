package sibarum.ternion.train;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.render.Color;

import java.util.List;

public final class TrainPanel {

    private static final Color SURFACE_BG = new Color(0.07f, 0.09f, 0.13f, 1f);
    private static final Color LABEL_FG   = new Color(0.70f, 0.75f, 0.82f, 0.85f);

    public static Component build() {
        Component placeholder = new Component.Text(
            "Train  (Phase 6: training controller + loss chart coming)",
            Em.of(1.0f), LABEL_FG);
        return new Component.Flex(
            null, null, Em.of(1f), SURFACE_BG,
            Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
            List.of(placeholder), false, 1
        );
    }

    private TrainPanel() {}
}
