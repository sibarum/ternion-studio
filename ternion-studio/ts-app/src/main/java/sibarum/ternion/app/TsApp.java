package sibarum.ternion.app;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.command.CommandRegistry;
import sibarum.dasum.gui.core.command.EverythingMenu;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.graph.ConnectionSelection;
import sibarum.dasum.gui.core.input.ConnectionDragController;
import sibarum.dasum.gui.core.input.ConnectionSelectionController;
import sibarum.dasum.gui.core.input.ContextMenuController;
import sibarum.dasum.gui.core.input.CursorManager;
import sibarum.dasum.gui.core.input.FocusState;
import sibarum.dasum.gui.core.input.GraphSurfaceController;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.HoverState;
import sibarum.dasum.gui.core.input.InputState;
import sibarum.dasum.gui.core.input.ScrollStates;
import sibarum.dasum.gui.core.input.ScrollbarController;
import sibarum.dasum.gui.core.input.SliderController;
import sibarum.dasum.gui.core.input.TabsController;
import sibarum.dasum.gui.core.input.TextInputController;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.layout.HitTest;
import sibarum.dasum.gui.core.layout.LatestLayout;
import sibarum.dasum.gui.core.layout.Layout;
import sibarum.dasum.gui.core.layout.LayoutResult;
import sibarum.dasum.gui.core.layout.PixelRect;
import sibarum.dasum.gui.core.layout.Render;
import sibarum.dasum.gui.core.overlay.OverlayStack;
import sibarum.dasum.gui.core.render.Batcher;
import sibarum.dasum.gui.core.render.Projection;
import sibarum.dasum.gui.core.render.RenderStats;
import sibarum.dasum.gui.core.render.Texture;
import sibarum.dasum.gui.core.status.Status;
import sibarum.dasum.gui.core.text.AtlasData;
import sibarum.dasum.gui.core.text.FontGroup;
import sibarum.dasum.gui.core.text.FontGroups;
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.dasum.gui.natives.glfw.Glfw;
import sibarum.dasum.gui.natives.glfw.GlfwCallbacks;
import sibarum.ternion.designer.FrozenBadge;
import sibarum.ternion.designer.NodePreviewRefresher;
import sibarum.ternion.train.LossChart;

import java.util.IdentityHashMap;
import java.util.Map;

import static sibarum.dasum.gui.natives.gl.Gl.GL_COLOR_BUFFER_BIT;

public final class TsApp {

    private static final float WHEEL_PIXELS_PER_STEP = 40f;

    /** Press target for click dispatch; cleared on release. */
    private static Component pressTarget = null;

    public static void main(String[] args) {
        try (GlfwContext glfwCtx = GlfwContext.init();
             Window window = Window.create(1280, 800, "Ternion Studio");
             Batcher batcher = new Batcher();
             CursorManager cursors = new CursorManager(window.handle().address())) {

            Gl.load();
            batcher.init();
            cursors.init();
            EmContext.setDpiScale(window.contentScaleX());

            try (Texture primaryTexture = Texture.fromPngResource("/dasum/atlas/primary.png")) {
                AtlasData primaryAtlas = AtlasData.loadFromResource("/dasum/atlas/primary.json");
                FontGroups.register(FontGroup.of(FontGroups.DEFAULT, primaryAtlas, primaryTexture));

                MainShell.Built built = MainShell.build();
                Component root = Status.wrap(built.root());
                AppContext ctx = built.ctx();
                ctx.attachWindow(window);
                Status.setDefaultMessage(
                    "Ctrl+S: save · Ctrl+O: open · Ctrl+N: new · Space: pause/resume training · Ctrl+Space: commands");
                wireInput(window, cursors, ctx);
                registerCommands(window, ctx);

                RenderStats stats = new RenderStats();
                System.out.println("Ternion Studio — Designer / Data / Train. Ctrl+Space for commands; Ctrl+=/- zoom.");

                FreezeDetector.start();
                EventLoop loop = new EventLoop(window, () -> {
                    FreezeDetector.beat();
                    // Main-thread sidecar refresh — DynamicChildren mutations
                    // must not race the training worker's mutations to the
                    // same IdentityHashMap.
                    FrozenBadge.refresh(ctx.graphSync());
                    LossChart.refreshAll();
                    NodePreviewRefresher.refresh();

                    int fbW = window.framebufferWidth();
                    int fbH = window.framebufferHeight();
                    float[] projection = Projection.orthoTopLeft(fbW, fbH);

                    Gl.glViewport(0, 0, fbW, fbH);
                    Gl.glClearColor(0.03f, 0.03f, 0.05f, 1f);
                    Gl.glClear(GL_COLOR_BUFFER_BIT);

                    PixelRect viewport = new PixelRect(0f, 0f, fbW, fbH);
                    LayoutResult mainLayout = Layout.compute(root, viewport);
                    Map<Component, PixelRect> mergedRects = new IdentityHashMap<>(mainLayout.rects());
                    OverlayStack.layoutInto(mergedRects, viewport);
                    LayoutResult layout = new LayoutResult(mergedRects);
                    LatestLayout.store(root, layout);

                    batcher.beginFrame(fbH);
                    Render.render(root, layout, batcher, projection);
                    if (OverlayStack.isActive()) {
                        batcher.flush(projection);
                        if (OverlayStack.anyModal()) {
                            batcher.submit(new sibarum.dasum.gui.core.render.DrawCommand.ColoredQuad(
                                viewport.x(), viewport.y(), viewport.width(), viewport.height(),
                                sibarum.dasum.gui.core.theme.Theme.overlayBackdrop()
                            ));
                            batcher.flush(projection);
                        }
                        for (OverlayStack.Overlay o : OverlayStack.active()) {
                            Render.render(o.component(), layout, batcher, projection);
                            batcher.flush(projection);
                        }
                    }
                    batcher.endFrame(projection);

                    stats.recordFrame(batcher.drawCallsThisFrame(), batcher.verticesThisFrame());
                });
                loop.run();

                System.out.println("Exited cleanly; frames=" + loop.renderedFrameCount()
                    + " idle=" + loop.idleFrameCount());
            }
        }
    }

    private static void wireInput(Window window, CursorManager cursors, AppContext ctx) {
        GlfwCallbacks.setKeyListener((win, key, scancode, action, mods) -> {
            if (action != Glfw.GLFW_PRESS && action != Glfw.GLFW_REPEAT) return;
            boolean ctrl  = (mods & Glfw.GLFW_MOD_CONTROL) != 0;
            boolean shift = (mods & Glfw.GLFW_MOD_SHIFT)   != 0;

            if (ctrl && key == Glfw.GLFW_KEY_SPACE && !EverythingMenu.isOpen()) {
                EverythingMenu.open();
                return;
            }
            if (ContextMenuController.handleKey(key)) return;
            if (EverythingMenu.handleKey(key)) return;

            if (ctrl && key == 'C') { if (TextInputController.onCopy(window.handle())) return; }
            if (ctrl && key == 'X') { if (TextInputController.onCut(window.handle())) return; }
            if (ctrl && key == 'V') { if (TextInputController.onPaste(window.handle())) return; }
            if (ctrl && key == 'A') { if (TextInputController.onSelectAll()) return; }
            if (ctrl && key == 'Z') {
                if (shift) { if (TextInputController.onRedo()) return; }
                else       { if (TextInputController.onUndo()) return; }
            }
            if (ctrl && key == 'Y') { if (TextInputController.onRedo()) return; }

            // Project shortcuts. After the clipboard/undo group so editable
            // text keeps its standard editing keys.
            if (ctrl && key == 'S' && !shift) { MainShell.triggerSave(ctx); return; }
            if (ctrl && key == 'O' && !shift) { MainShell.triggerOpen(ctx); return; }
            if (ctrl && key == 'N' && !shift) { MainShell.triggerNew(ctx);  return; }

            // Space toggles training pause/resume — only when focus isn't on
            // an editable text (so corpus cells can type spaces normally).
            if (key == Glfw.GLFW_KEY_SPACE && !ctrl) {
                Component focused = FocusState.focused();
                boolean focusedIsEditableText =
                    (focused instanceof Component.Text t) && t.editable();
                if (!focusedIsEditableText && toggleTrainingPause(ctx)) return;
            }

            if (key == Glfw.GLFW_KEY_BACKSPACE && TextInputController.onBackspace(ctrl)) return;
            if (key == Glfw.GLFW_KEY_DELETE    && TextInputController.onDelete(ctrl))    return;
            if ((key == Glfw.GLFW_KEY_DELETE || key == Glfw.GLFW_KEY_BACKSPACE)
                && ConnectionSelectionController.onDelete()) return;
            if (key == Glfw.GLFW_KEY_ENTER && TextInputController.onEnter()) return;

            if (key == Glfw.GLFW_KEY_SPACE || key == Glfw.GLFW_KEY_ENTER) {
                Component focused = FocusState.focused();
                if (focused instanceof Component.Checkbox || focused instanceof Component.Radio<?>) {
                    Component layoutRoot = LatestLayout.root();
                    Handlers.activate(focused, layoutRoot);
                    return;
                }
            }

            if (key == Glfw.GLFW_KEY_TAB && TextInputController.onTab()) return;

            if (TextInputController.onKey(key, shift, ctrl)) return;
            if (SliderController.onKey(key)) return;
            if (TabsController.onKey(key))   return;

            if (key == Glfw.GLFW_KEY_ESCAPE && action == Glfw.GLFW_PRESS) {
                if (ConnectionDragController.isDragging()) {
                    ConnectionDragController.cancelDrag();
                    return;
                }
                if (OverlayStack.isActive()) { OverlayStack.pop(); return; }
                if (ConnectionSelection.has()) { ConnectionSelection.clear(); return; }
                Component focused = FocusState.focused();
                if (focused instanceof Component.Text t && t.selectable() && TextStates.of(focused).hasSelection()) {
                    TextStates.of(focused).collapseToCaret();
                    Invalidator.invalidate();
                } else if (focused != null) {
                    FocusState.clear();
                } else {
                    Glfw.glfwSetWindowShouldClose(window.handle(), true);
                    Invalidator.invalidate();
                }
            } else if (key == Glfw.GLFW_KEY_TAB) {
                Component layoutRoot = OverlayStack.activeInputRoot(LatestLayout.root());
                if (layoutRoot != null) FocusState.cycle(layoutRoot, shift);
            } else if (ctrl && key == Glfw.GLFW_KEY_EQUAL) {
                EmContext.multiplyZoom(1.1f);
            } else if (ctrl && key == Glfw.GLFW_KEY_MINUS) {
                EmContext.multiplyZoom(1f / 1.1f);
            } else if (ctrl && key == Glfw.GLFW_KEY_0) {
                EmContext.setZoom(1f);
            }
        });

        GlfwCallbacks.setCursorPosListener((win, x, y) -> {
            InputState.updateMousePos(x, y);
            ScrollbarController.onCursorMove(x, y);
            if (ScrollbarController.isDragging()) return;
            ConnectionDragController.onCursorMove(x, y);
            if (ConnectionDragController.isDragging()) return;
            GraphSurfaceController.onCursorMove(x, y);
            if (GraphSurfaceController.isDragging()) return;
            ContextMenuController.onCursorMove(x, y);

            LayoutResult lr = LatestLayout.result();
            Component layoutRoot = LatestLayout.root();
            if (lr == null || layoutRoot == null) return;

            Component hitRoot = OverlayStack.activeInputRoot(layoutRoot);
            Component hit = HitTest.test(hitRoot, lr, (float) x, (float) y);
            HoverState.update(hit);
            cursors.setShape(cursorShapeFor(hit));

            TextInputController.onCursorMove(hit, x, y);
            SliderController.onCursorMove(x, y);
            TabsController.onCursorMove(x, y);
        });

        GlfwCallbacks.setMouseButtonListener((win, button, action, mods) -> {
            if (button == Glfw.GLFW_MOUSE_BUTTON_RIGHT) {
                if (action == Glfw.GLFW_PRESS) {
                    ContextMenuController.onMouseDown(
                        InputState.mouseX(), InputState.mouseY(), mods, window.handle());
                }
                return;
            }
            if (button != Glfw.GLFW_MOUSE_BUTTON_LEFT) return;
            boolean pressed = (action == Glfw.GLFW_PRESS);
            InputState.setLeftButtonHeld(pressed);
            boolean shift = (mods & Glfw.GLFW_MOD_SHIFT) != 0;

            if (pressed) {
                if (ScrollbarController.onMouseDown(InputState.mouseX(), InputState.mouseY())) {
                    pressTarget = null; return;
                }
                if (TabsController.onMouseDown(InputState.mouseX(), InputState.mouseY())) {
                    pressTarget = null; return;
                }
                if (ConnectionDragController.onMouseDown(InputState.mouseX(), InputState.mouseY())) {
                    pressTarget = null; return;
                }
                if (GraphSurfaceController.onMouseDown(InputState.mouseX(), InputState.mouseY())) {
                    pressTarget = null; return;
                }
                if (ConnectionSelectionController.onMouseDown(InputState.mouseX(), InputState.mouseY())) {
                    pressTarget = null; return;
                }
                if (OverlayStack.isActive()) {
                    LayoutResult lr = LatestLayout.result();
                    if (OverlayStack.isOutsideTopmost(lr, (float) InputState.mouseX(), (float) InputState.mouseY())) {
                        if (OverlayStack.anyModal()) OverlayStack.pop();
                        pressTarget = null;
                        return;
                    }
                    Component overlayRoot = OverlayStack.activeInputRoot(null);
                    Component hit = (lr != null && overlayRoot != null)
                        ? HitTest.test(overlayRoot, lr, (float) InputState.mouseX(), (float) InputState.mouseY())
                        : null;
                    pressTarget = hit;
                    if (hit != null) FocusState.set(hit);
                    TextInputController.onMouseDown(hit, InputState.mouseX(), InputState.mouseY(), shift);
                    SliderController.onMouseDown(hit, InputState.mouseX(), InputState.mouseY());
                    return;
                }
                // Fresh hit-test on press — HoverState is only refreshed on
                // cursor-move events, so it's stale when the UI shifts under a
                // stationary cursor (e.g. a row deletes and another row slides
                // into the cursor's position).
                LayoutResult lrPress = LatestLayout.result();
                Component pressRoot = OverlayStack.activeInputRoot(LatestLayout.root());
                Component hovered = (lrPress != null && pressRoot != null)
                    ? HitTest.test(pressRoot, lrPress, (float) InputState.mouseX(), (float) InputState.mouseY())
                    : null;
                HoverState.update(hovered);
                pressTarget = hovered;
                if (hovered != null) FocusState.set(hovered);
                else                 FocusState.clear();
                TextInputController.onMouseDown(hovered, InputState.mouseX(), InputState.mouseY(), shift);
                SliderController.onMouseDown(hovered, InputState.mouseX(), InputState.mouseY());
            } else {
                boolean scrollDrag = ScrollbarController.isDragging();
                boolean sliderDrag = SliderController.isDragging();
                boolean canvasDrag = GraphSurfaceController.isDragging();
                boolean connectionDrag = ConnectionDragController.isDragging();
                ScrollbarController.onMouseUp();
                SliderController.onMouseUp();
                GraphSurfaceController.onMouseUp();
                ConnectionDragController.onMouseUp();

                LayoutResult lr2 = LatestLayout.result();
                Component dispatchRoot = OverlayStack.activeInputRoot(LatestLayout.root());
                Component released = (lr2 != null && dispatchRoot != null)
                    ? HitTest.test(dispatchRoot, lr2, (float) InputState.mouseX(), (float) InputState.mouseY())
                    : null;
                if (!scrollDrag && !sliderDrag && !canvasDrag && !connectionDrag && pressTarget != null && released == pressTarget) {
                    Handlers.activate(pressTarget, dispatchRoot);
                }
                pressTarget = null;
            }
        });

        GlfwCallbacks.setCharListener((win, codepoint) -> TextInputController.onCharInput(codepoint));

        GlfwCallbacks.setCursorEnterListener((win, entered) -> {
            if (!entered) {
                HoverState.clear();
                TextStates.clearAllHoverCarets();
                ScrollbarController.clearHover();
                TabsController.clearHover();
                cursors.setShape(CursorManager.CursorShape.ARROW);
                Invalidator.invalidate();
            }
        });

        GlfwCallbacks.setWindowFocusListener((win, focused) -> {
            if (!focused) {
                SliderController.cancelDrag();
                ScrollbarController.cancelDrag();
                GraphSurfaceController.cancelDrag();
                ConnectionDragController.cancelDrag();
                HoverState.clear();
                TextStates.clearAllHoverCarets();
                InputState.setLeftButtonHeld(false);
            }
            Invalidator.invalidate();
        });

        GlfwCallbacks.setScrollListener((win, xOff, yOff) -> {
            LayoutResult lr = LatestLayout.result();
            Component layoutRoot = OverlayStack.activeInputRoot(LatestLayout.root());
            if (lr == null || layoutRoot == null) return;
            Component.Scroll target = HitTest.findScroll(layoutRoot, lr, (float) InputState.mouseX(), (float) InputState.mouseY());
            if (target == null) return;

            boolean shift = Glfw.glfwGetKey(window.handle(), Glfw.GLFW_KEY_LEFT_SHIFT) == Glfw.GLFW_PRESS
                        || Glfw.glfwGetKey(window.handle(), Glfw.GLFW_KEY_RIGHT_SHIFT) == Glfw.GLFW_PRESS;
            double dx, dy;
            if (shift) { dx = -yOff * WHEEL_PIXELS_PER_STEP; dy = 0; }
            else        { dx = -xOff * WHEEL_PIXELS_PER_STEP; dy = -yOff * WHEEL_PIXELS_PER_STEP; }
            ScrollStates.of(target).scrollByPx((float) dx, (float) dy);
        });
    }

    private static void registerCommands(Window window, AppContext ctx) {
        CommandRegistry.register("zoom.in",     "Zoom In",         () -> EmContext.multiplyZoom(1.1f));
        CommandRegistry.register("zoom.out",    "Zoom Out",        () -> EmContext.multiplyZoom(1f / 1.1f));
        CommandRegistry.register("zoom.reset",  "Reset Zoom",      () -> EmContext.setZoom(1f));
        CommandRegistry.register("focus.clear", "Clear Focus",     FocusState::clear);
        CommandRegistry.register("project.new",  "Project: New",     () -> MainShell.triggerNew(ctx));
        CommandRegistry.register("project.open", "Project: Open…",   () -> MainShell.triggerOpen(ctx));
        CommandRegistry.register("project.save", "Project: Save…",   () -> MainShell.triggerSave(ctx));
        CommandRegistry.register("train.toggle", "Training: Toggle pause/resume",
            () -> toggleTrainingPause(ctx));
        CommandRegistry.register("quit",        "Quit",            () -> {
            Glfw.glfwSetWindowShouldClose(window.handle(), true);
            Invalidator.invalidate();
        });
    }

    /**
     * Space-bound training toggle. Returns true if the key was consumed
     * (some training-state transition happened or training is currently
     * relevant), false otherwise — caller falls through to other Space
     * handlers (focus activation, etc.).
     */
    private static boolean toggleTrainingPause(AppContext ctx) {
        sibarum.ternion.train.TrainingController c = ctx.trainingController();
        switch (c.state().get()) {
            case IDLE, STOPPED -> { c.start(); return true; }
            case RUNNING       -> { c.pause(); return true; }
            case PAUSED        -> { c.resume(); return true; }
        }
        return false;
    }

    private static CursorManager.CursorShape cursorShapeFor(Component hit) {
        if (hit instanceof Component.Text t && t.selectable()) return CursorManager.CursorShape.IBEAM;
        if (hit instanceof Component.GraphSurface) return CursorManager.CursorShape.ARROW;
        if (hit != null) return CursorManager.CursorShape.HAND;
        return CursorManager.CursorShape.ARROW;
    }

    private TsApp() {}
}
