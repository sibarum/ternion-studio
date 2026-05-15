package sibarum.ternion.app;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;

import sibarum.ternion.data.CorpusModel;
import sibarum.ternion.data.DataPanel;
import sibarum.ternion.designer.DesignerPanel;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.train.TrainPanel;
import sibarum.ternion.train.TrainingController;

import java.util.List;

public final class MainShell {

    static final Color FRAME_BG    = new Color(0.05f, 0.06f, 0.09f, 1f);
    static final Color TOOLBAR_BG  = new Color(0.13f, 0.14f, 0.18f, 1f);
    static final Color LABEL_FG    = new Color(1f, 1f, 1f, 0.95f);
    private static final Color DESIGNER_BG = new Color(0.05f, 0.07f, 0.10f, 1f);

    public static Component build() {
        // Build the shared AppContext once — Designer/Data/Train all see
        // the same GraphSync + CorpusModel state.
        Component.GraphSurface designerSurface = new Component.GraphSurface(
            null, null, DESIGNER_BG, List.of(), true, 0);
        GraphSync graphSync = new GraphSync(designerSurface);
        graphSync.install();
        CorpusModel corpus = new CorpusModel();
        AppContext ctx = new AppContext(designerSurface, graphSync, corpus);
        ctx.attachTrainingController(new TrainingController(ctx));

        return new Component.Flex(
            null, null, Em.of(0.5f), FRAME_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.5f),
            List.of(buildToolbar(), buildTabs(ctx)),
            false, 0
        );
    }

    private static Component buildToolbar() {
        Component title = new Component.Text("Ternion Studio", Em.of(1.0f), LABEL_FG);

        Component newBtn  = Themed.button("New",     Em.of(5f), Variant.DEFAULT, 0);
        Component openBtn = Themed.button("Open",    Em.of(5f), Variant.DEFAULT, 0);
        Component saveBtn = Themed.button("Save",    Em.of(5f), Variant.PRIMARY, 0);

        Handlers.onClick(newBtn,  () -> System.out.println("[stub] New project"));
        Handlers.onClick(openBtn, () -> System.out.println("[stub] Open project"));
        Handlers.onClick(saveBtn, () -> System.out.println("[stub] Save project"));

        Component spacer = new Component.Flex(
            null, Em.of(2f), Em.ZERO, new Color(0f, 0f, 0f, 0f),
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.ZERO,
            List.of(), false, 1
        );

        return new Component.Flex(
            null, Em.of(3f), Em.of(0.5f), TOOLBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.75f),
            List.of(title, spacer, newBtn, openBtn, saveBtn),
            false, 0
        );
    }

    private static Component buildTabs(AppContext ctx) {
        Property<Integer> activeTab = new Property<>(0);
        return Themed.tabs(
            null, null,
            Em.of(2.6f), Em.of(1.0f), Em.of(0.5f),
            Em.of(1.1f),
            List.of(
                new Component.Tabs.TabPanel("Designer", DesignerPanel.build(ctx)),
                new Component.Tabs.TabPanel("Data",     DataPanel.build(ctx)),
                new Component.Tabs.TabPanel("Train",    TrainPanel.build(ctx))
            ),
            activeTab,
            Variant.PRIMARY
        ).withFlexGrow(1);
    }

    private MainShell() {}
}
