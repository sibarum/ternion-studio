package sibarum.ternion.app;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.window.Window;
import sibarum.ternion.data.source.DataSourceRegistry;
import sibarum.ternion.designer.DataSourceBoundNodes;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.project.Project;
import sibarum.ternion.train.TrainingController;

/**
 * Cross-panel application state. Built once in {@link MainShell}, then
 * passed into each tab's builder so they share the same
 * {@link GraphSync}, {@link DataSourceRegistry}, and
 * {@link TrainingController}.
 *
 * <p>The {@code TrainingController} is created after the AppContext
 * because it captures the context itself; it's installed via
 * {@link #attachTrainingController}.
 */
public final class AppContext {

    private final Component.GraphSurface designerSurface;
    private final GraphSync graphSync;
    private final DataSourceRegistry dataSources = new DataSourceRegistry();
    private final Project project = new Project();
    private TrainingController trainingController;
    private Window window;

    public AppContext(Component.GraphSurface designerSurface, GraphSync graphSync) {
        this.designerSurface = designerSurface;
        this.graphSync = graphSync;
        // Seed bundled curated datasets at startup. Editable + imported
        // sources are added later (Data tab UI; project load).
        this.dataSources.seedBundled();
        // Publish as the process-global "current" registry so graph
        // primitives (DatasetColumn, LossOutput) can resolve source ids
        // at construction time without taking an AppContext handle.
        DataSourceRegistry.setCurrent(this.dataSources);
        // Fresh session: wipe the per-graph node sidecar so leftover
        // bindings from a prior AppContext (test JVMs run multiple) can't
        // bleed into preflight or training-path selection. Production
        // runs only ever build one AppContext per JVM so this is a no-op
        // there.
        DataSourceBoundNodes.clearForTesting();
    }

    public Component.GraphSurface designerSurface() { return designerSurface; }
    public GraphSync graphSync() { return graphSync; }
    public DataSourceRegistry dataSources() { return dataSources; }
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
