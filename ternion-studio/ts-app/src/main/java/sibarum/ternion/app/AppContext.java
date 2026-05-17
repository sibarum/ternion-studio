package sibarum.ternion.app;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.window.Window;
import sibarum.ternion.data.CorpusModel;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.project.Project;
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
    private final Project project = new Project();
    private TrainingController trainingController;
    private Window window;

    public AppContext(Component.GraphSurface designerSurface, GraphSync graphSync, CorpusModel corpus) {
        this.designerSurface = designerSurface;
        this.graphSync = graphSync;
        this.corpus = corpus;
    }

    public Component.GraphSurface designerSurface() { return designerSurface; }
    public GraphSync graphSync() { return graphSync; }
    public CorpusModel corpus() { return corpus; }
    public Project project() { return project; }
    public TrainingController trainingController() {
        if (trainingController == null) {
            throw new IllegalStateException("trainingController not attached");
        }
        return trainingController;
    }

    public void attachTrainingController(TrainingController controller) {
        this.trainingController = controller;
    }

    public Window window() {
        if (window == null) {
            throw new IllegalStateException("window not attached");
        }
        return window;
    }

    public void attachWindow(Window window) {
        this.window = window;
    }
}
