package sibarum.ternion.app;

import sibarum.dasum.gui.core.component.Component;
import sibarum.ternion.data.CorpusModel;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.train.TrainingController;

/**
 * Cross-panel application state. Built once in {@link MainShell}, then
 * passed into each tab's builder so they share the same
 * {@link GraphSync}, {@link CorpusModel}, and {@link TrainingController}.
 *
 * <p>The {@code TrainingController} is created after the AppContext
 * because it captures the context itself; it's installed via
 * {@link #attachTrainingController}.
 */
public final class AppContext {

    private final Component.GraphSurface designerSurface;
    private final GraphSync graphSync;
    private final CorpusModel corpus;
    private TrainingController trainingController;

    public AppContext(Component.GraphSurface designerSurface, GraphSync graphSync, CorpusModel corpus) {
        this.designerSurface = designerSurface;
        this.graphSync = graphSync;
        this.corpus = corpus;
    }

    public Component.GraphSurface designerSurface() { return designerSurface; }
    public GraphSync graphSync() { return graphSync; }
    public CorpusModel corpus() { return corpus; }
    public TrainingController trainingController() {
        if (trainingController == null) {
            throw new IllegalStateException("trainingController not attached");
        }
        return trainingController;
    }

    public void attachTrainingController(TrainingController controller) {
        this.trainingController = controller;
    }
}
