package sibarum.ternion.app;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.dialog.FileDialog;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.status.Status;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;

import sibarum.ternion.data.DataPanel;
import sibarum.ternion.designer.DesignerPanel;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.project.ProjectIo;
import sibarum.ternion.train.TrainPanel;
import sibarum.ternion.train.TrainingController;

import java.io.IOException;
import java.nio.file.Path;

import java.util.List;

public final class MainShell {

    static final Color FRAME_BG    = new Color(0.05f, 0.06f, 0.09f, 1f);
    static final Color TOOLBAR_BG  = new Color(0.13f, 0.14f, 0.18f, 1f);
    static final Color LABEL_FG    = new Color(1f, 1f, 1f, 0.95f);
    private static final Color DESIGNER_BG = new Color(0.05f, 0.07f, 0.10f, 1f);

    public record Built(Component root, AppContext ctx) {}

    public static Built build() {
        // Build the shared AppContext once — Designer/Data/Train all see
        // the same GraphSync + CorpusModel state.
        // flexGrow=1 baked in at construction. NEVER call .withFlexGrow on
        // this — the Flex/GraphSurface "with*" methods return a new record
        // instance, which would orphan every sidecar entry (Handlers,
        // GraphSurfaceChildren, ContextMenuStates) registered on the
        // original. This silently broke the Designer's right-click + spawn
        // pipeline before the fix.
        // Earlier we declared a "Right-click to add a node…" Text as a
        // surface child to act as a gentle backdrop. Dasum's batcher does
        // a two-pass flush per frame (all solid fills, then all MSDF text)
        // which means surface-child glyphs paint over later-drawn node
        // backgrounds — the hint visibly clipped through spawned nodes.
        // The same guidance lives in the Designer panel's toolbar above
        // the surface, so the in-surface hint is dropped.
        Component.GraphSurface designerSurface = new Component.GraphSurface(
            null, null, DESIGNER_BG, List.of(), true, 1);
        GraphSync graphSync = new GraphSync(designerSurface);
        graphSync.install();
        AppContext ctx = new AppContext(designerSurface, graphSync);
        ctx.attachTrainingController(new TrainingController(ctx));
        // Wire dirty-tracking listeners AFTER the registry has been
        // seeded (AppContext constructor does seedBundled) and AFTER
        // any other startup mutations, so the freshly-built project
        // starts clean.
        sibarum.ternion.project.ProjectDirtyHook.install(ctx);
        ctx.project().markClean();

        Component root = new Component.Flex(
            null, null, Em.of(0.5f), FRAME_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.of(0.5f),
            List.of(buildToolbar(ctx), buildTabs(ctx)),
            false, 0
        );
        return new Built(root, ctx);
    }

    /** Save/Open/New triggers reusable from app-level key handlers. */
    public static void triggerNew(AppContext ctx)  { confirmNew(ctx); }
    public static void triggerOpen(AppContext ctx) { onOpen(ctx); }
    public static void triggerSave(AppContext ctx) { onSave(ctx); }

    /**
     * Try to close the window. If the project is clean, closes
     * immediately. If dirty, opens the Unsaved-Changes dialog and
     * defers the decision to the user:
     * <ul>
     *   <li>Save → run {@link #onSave} then close (the close fires
     *       only if save resolves to a real path; a cancelled
     *       save-dialog leaves the window open and the prompt
     *       dismissed).</li>
     *   <li>Discard → close immediately, dropping unsaved edits.</li>
     *   <li>Cancel → just dismiss the dialog.</li>
     * </ul>
     */
    public static void requestClose(AppContext ctx) {
        if (!ctx.project().dirty().get()) {
            forceClose(ctx);
            return;
        }
        UnsavedChangesDialog.show(
            () -> {
                onSave(ctx);
                // onSave is fire-and-forget on the GLFW thread; the
                // file dialog blocks until completion, after which
                // markClean has either fired (success) or not
                // (cancel / error). Honor the clean flag.
                if (!ctx.project().dirty().get()) forceClose(ctx);
            },
            () -> forceClose(ctx));
    }

    /** Unconditional close — no dirty check. Used after the user
     *  confirms via the Unsaved-Changes dialog or chose Discard. */
    public static void forceClose(AppContext ctx) {
        sibarum.dasum.gui.natives.glfw.Glfw.glfwSetWindowShouldClose(
            ctx.window().handle(), true);
        sibarum.dasum.gui.core.event.Invalidator.invalidate();
    }

    private static void confirmNew(AppContext ctx) {
        ConfirmDialog.show(
            "New Project?",
            "Discards the current graph and corpus. Save first if you want to keep your work.",
            "Discard & Start New", Variant.ERROR,
            () -> onNew(ctx));
    }

    private static Component buildToolbar(AppContext ctx) {
        Component title = new Component.Text("Ternion Studio", Em.of(1.0f), LABEL_FG);

        Component newBtn  = Themed.button("New",  Em.of(5f), Variant.DEFAULT, 0,
            () -> confirmNew(ctx));
        Component openBtn = Themed.button("Open", Em.of(5f), Variant.DEFAULT, 0,
            () -> onOpen(ctx));
        Component saveBtn = Themed.button("Save", Em.of(5f), Variant.PRIMARY, 0,
            () -> onSave(ctx));

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

    private static void onNew(AppContext ctx) {
        ctx.trainingController().stop();
        ctx.graphSync().clearAll();
        // Drop every non-bundled source so a fresh project doesn't
        // inherit the previous session's editable / imported tables.
        for (sibarum.ternion.data.source.DataSource ds :
                new java.util.ArrayList<>(ctx.dataSources().all())) {
            if (ds.origin() != sibarum.ternion.data.source.Origin.BUNDLED) {
                ctx.dataSources().removeForced(ds.id());
            }
        }
        ctx.project().reset();
        Status.info("New project — ready");
    }

    private static final List<FileDialog.Filter> TSP_FILTERS =
        List.of(FileDialog.Filter.of("Ternion Project", "tsp"));

    private static void onOpen(AppContext ctx) {
        FileDialog.pickFolder(ctx.window(), defaultDir(ctx)).ifPresent(p -> {
            try {
                ProjectIo.load(p, ctx);
                Status.success("Opened " + p.getFileName());
            } catch (IOException | RuntimeException e) {
                Status.error("Open failed: " + e.getMessage(), e.toString());
            }
        });
    }

    private static void onSave(AppContext ctx) {
        FileDialog.save(ctx.window(), TSP_FILTERS, defaultDir(ctx), defaultName(ctx)).ifPresent(p -> {
            Path target = p.getFileName().toString().endsWith(".tsp")
                ? p
                : Path.of(p.toString() + ".tsp");
            try {
                ProjectIo.save(target, ctx);
                Status.success("Saved " + target.getFileName());
            } catch (IOException | RuntimeException e) {
                Status.error("Save failed: " + e.getMessage(), e.toString());
            }
        });
    }

    private static Path defaultDir(AppContext ctx) {
        Path current = ctx.project().path().get();
        if (current != null) {
            Path parent = current.getParent();
            if (parent != null) return parent;
        }
        return Path.of(System.getProperty("user.home", "."));
    }

    private static String defaultName(AppContext ctx) {
        String name = ctx.project().name().get();
        if (name == null || name.isBlank()) name = "untitled";
        return name.endsWith(".tsp") ? name : name + ".tsp";
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
