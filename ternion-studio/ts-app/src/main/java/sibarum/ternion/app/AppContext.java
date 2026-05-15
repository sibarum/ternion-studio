package sibarum.ternion.app;

import sibarum.dasum.gui.core.component.Component;
import sibarum.ternion.data.CorpusModel;
import sibarum.ternion.designer.GraphSync;

/**
 * Cross-panel application state. Built once in {@link MainShell}, then
 * passed into each tab's builder so they share the same {@link GraphSync}
 * and {@link CorpusModel}.
 */
public final class AppContext {

    private final Component.GraphSurface designerSurface;
    private final GraphSync graphSync;
    private final CorpusModel corpus;

    public AppContext(Component.GraphSurface designerSurface, GraphSync graphSync, CorpusModel corpus) {
        this.designerSurface = designerSurface;
        this.graphSync = graphSync;
        this.corpus = corpus;
    }

    public Component.GraphSurface designerSurface() { return designerSurface; }
    public GraphSync graphSync() { return graphSync; }
    public CorpusModel corpus() { return corpus; }
}
