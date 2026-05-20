package sibarum.ternion.designer;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Components;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.DynamicChildren;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.graph.Connection;
import sibarum.dasum.gui.core.graph.Connections;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.overlay.Anchor;
import sibarum.dasum.gui.core.overlay.OverlayStack;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.text.Icon;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.vis.math.CameraMode;
import sibarum.dasum.gui.vis.math.CameraSpec;
import sibarum.dasum.gui.vis.pointcloud.PointCloudSnapshot;
import sibarum.dasum.gui.vis.pointcloud.PointCloudStates;
import sibarum.dasum.gui.vis.pointcloud.PointHandlers;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.ternion.app.generated.Icons;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sidecar registry + UI helper for {@link VisualizerPrimitive}-wrapping
 * nodes. The mcc graph treats a visualizer as an identity pass-through;
 * all the visualization machinery lives here:
 *
 * <ul>
 *   <li>{@link #decorateIfApplicable(NodeSpec)} attaches a
 *       {@link Component.PointCloud} thumbnail and a status icon to the
 *       node body, plus a click-to-expand handler that pops the same
 *       viewport into a full-screen overlay.</li>
 *   <li>{@link #onConnectionsChanged(NodeSpec)} re-derives the
 *       {@link Mode} from the visualizer's wired ports and swaps the
 *       status icon glyph (unplug / scatter-chart) accordingly.</li>
 *   <li>{@link #of(CompGraphNode)} exposes the entry to the training
 *       worker so it can read the viewport and publish snapshots.</li>
 * </ul>
 *
 * <p>The same {@link Component.PointCloud} instance is reparented
 * between thumbnail and overlay via {@link DynamicChildren} — its
 * snapshot, camera, click handler, and GL buffer all follow the
 * component reference, so camera state survives expand / collapse for
 * free and the GL upload happens once regardless of which view is
 * active.
 */
public final class VisualizerNodes {

    /** How the visualizer interprets the wires it's connected to. */
    public enum Mode {
        /** Neither port wired — show nothing, render the "unplug" icon. */
        UNPLUGGED,
        /** Input wired only — show the forward value (no downstream gradient). */
        FORWARD,
        /** Both wired — show activation × gradient (contribution to loss). */
        CONTRIBUTION,
    }

    /** Per-visualizer state, identity-keyed by mcc node. */
    public static final class Entry {
        public final NodeSpec spec;
        public final Component.PointCloud viewport;
        public final Component thumbnailSlot;
        public final Component.Text statusIcon;
        Mode mode;

        Entry(NodeSpec spec, Component.PointCloud viewport, Component thumbnailSlot,
              Component.Text statusIcon) {
            this.spec = spec;
            this.viewport = viewport;
            this.thumbnailSlot = thumbnailSlot;
            this.statusIcon = statusIcon;
            this.mode = Mode.UNPLUGGED;
        }

        public Mode mode() { return mode; }
    }

    private static final Object LOCK = new Object();
    private static final Map<CompGraphNode, Entry> BY_NODE = new IdentityHashMap<>();
    private static final Map<Component, Entry> BY_VISUAL = new IdentityHashMap<>();

    private static final Color VIEWPORT_BG = new Color(0.04f, 0.05f, 0.08f, 1f);
    private static final Color STATUS_FG_UNPLUG  = new Color(0.85f, 0.55f, 0.25f, 0.95f);
    private static final Color STATUS_FG_LIVE    = new Color(0.55f, 0.85f, 0.65f, 0.95f);
    private static final Color OVERLAY_BG        = new Color(0.07f, 0.09f, 0.13f, 1f);
    private static final Color OVERLAY_HEADER_BG = new Color(0.13f, 0.14f, 0.18f, 1f);
    private static final Color OVERLAY_TITLE_FG  = new Color(0.92f, 0.94f, 0.97f, 0.95f);
    private static final Color PLACEHOLDER_FG    = new Color(0.65f, 0.70f, 0.85f, 0.85f);

    private static final Em THUMB_W = Em.of(12f);
    private static final Em THUMB_H = Em.of(8f);

    private VisualizerNodes() {}

    /**
     * If {@code spec} wraps a {@link VisualizerPrimitive}, attach the
     * point-cloud thumbnail + status icon + click-to-expand handler and
     * register the entry. No-op for any other node type — safe to call
     * indiscriminately from the spawn pipeline.
     */
    public static Entry decorateIfApplicable(NodeSpec spec) {
        if (!(spec.tNode().primitive() instanceof VisualizerPrimitive)) return null;

        // flexGrow=1 so the viewport stretches to fill the overlay's
        // remaining main-axis space when reparented into the expanded
        // modal. In the thumbnail slot the slot's fixed 8em main extent
        // matches the viewport's intrinsic height, so flexGrow is a no-op
        // there — same record renders thumbnail-sized in the node and
        // full-screen in the overlay.
        Component.PointCloud viewport = new Component.PointCloud(
            THUMB_W, THUMB_H, Em.ZERO, VIEWPORT_BG, true, 1);
        PointCloudStates.setCamera(viewport, CameraSpec.defaultPerspective());
        // Seed with a placeholder unit cube so the viewport advertises
        // itself before training begins. The worker's first real publish
        // (via VisualizerTap.publish) atomically replaces this snapshot
        // through PointCloudStates' AtomicReference.
        PointCloudStates.publish(viewport, placeholderCube());

        // Status icon — starts as "unplug" (no wires yet); swapped to
        // "scatter-chart" when the visualizer becomes wired enough to
        // produce data. Single Text whose content gets mutated via
        // TextStates, so the component identity is stable.
        Component.Text statusIcon = Icon.of(Icons.UNPLUG, Em.of(1.4f), STATUS_FG_UNPLUG);

        Component statusRow = new Component.Flex(
            null, Em.AUTO, Em.ZERO, new Color(0f, 0f, 0f, 0f),
            Direction.ROW, JustifyContent.CENTER, AlignItems.CENTER, Em.of(0.3f),
            List.of(statusIcon), false, 0);

        Component thumbnailSlot = new Component.Flex(
            THUMB_W, THUMB_H, Em.ZERO, new Color(0f, 0f, 0f, 0f),
            Direction.COLUMN, JustifyContent.CENTER, AlignItems.CENTER, Em.ZERO,
            List.of(), false, 0);
        DynamicChildren.add(thumbnailSlot, viewport);

        DynamicChildren.add(spec.visual(), statusRow);
        DynamicChildren.add(spec.visual(), thumbnailSlot);

        // Per-point click → log index + raw vector to status bar. The
        // raw N-dim values come from the snapshot the worker last
        // published (see VisualizerSnapshots).
        PointHandlers.onPointClick(viewport, hit -> {
            PointCloudSnapshot snap = PointCloudStates.snapshotOf(viewport);
            String detail = snap == null
                ? "(no snapshot)"
                : formatRawVector(snap, hit.pointIndex());
            sibarum.dasum.gui.core.status.Status.info(
                "Visualizer " + spec.cgNode().id() + " · point " + hit.pointIndex() + " " + detail);
        });

        Entry entry = new Entry(spec, viewport, thumbnailSlot, statusIcon);
        synchronized (LOCK) {
            BY_NODE.put(spec.cgNode(), entry);
            BY_VISUAL.put(spec.visual(), entry);
        }

        // Click-to-expand on the node body. The Handler is keyed off the
        // node visual, fires on click (release-on-same-target), and
        // reparents the viewport into the overlay tree.
        Handlers.onClick(spec.visual(), () -> openOverlay(entry));

        return entry;
    }

    /** Look up the entry for a given mcc node. Worker-thread safe. */
    public static Entry of(CompGraphNode node) {
        synchronized (LOCK) { return BY_NODE.get(node); }
    }

    /** Snapshot view of every live visualizer entry. Worker-thread safe. */
    public static Collection<Entry> all() {
        synchronized (LOCK) { return List.copyOf(BY_NODE.values()); }
    }

    /**
     * Drop the entry associated with {@code spec}. Called by
     * {@link GraphSync#removeNode} (and {@link GraphSync#clearAll})
     * after detaching the visual so the registry doesn't leak references
     * to gone-but-not-collected nodes.
     */
    public static void unregister(NodeSpec spec) {
        if (spec == null) return;
        Entry gone;
        synchronized (LOCK) {
            gone = BY_NODE.remove(spec.cgNode());
            BY_VISUAL.remove(spec.visual());
        }
        if (gone != null) sibarum.ternion.train.VisualizerTap.forget(gone);
    }

    /**
     * Re-derive the visualizer's mode from its currently wired ports and
     * update the status icon if the mode changed. Cheap; safe to call on
     * any node — no-op if {@code spec} isn't a registered visualizer.
     */
    public static void onConnectionsChanged(NodeSpec spec, GraphSync sync) {
        Entry entry;
        synchronized (LOCK) { entry = BY_NODE.get(spec.cgNode()); }
        if (entry == null) return;

        boolean inputWired  = isPortWired(sync, entry.spec.orderedInputPorts().isEmpty()
            ? null : entry.spec.orderedInputPorts().get(0));
        boolean outputWired = isPortWired(sync, entry.spec.outputPort());

        Mode newMode;
        if (!inputWired) {
            newMode = Mode.UNPLUGGED;
        } else if (outputWired) {
            newMode = Mode.CONTRIBUTION;
        } else {
            newMode = Mode.FORWARD;
        }
        if (newMode == entry.mode) return;
        entry.mode = newMode;
        applyIconForMode(entry);
    }

    private static void applyIconForMode(Entry entry) {
        int cp = (entry.mode == Mode.UNPLUGGED) ? Icons.UNPLUG : Icons.SCATTER_CHART;
        // Lucide glyphs are all in the BMP, so a single cast suffices.
        TextStates.setContent(entry.statusIcon, String.valueOf((char) cp));
    }

    private static boolean isPortWired(GraphSync sync, Component port) {
        if (port == null) return false;
        for (Connection c : Connections.on(sync.surface())) {
            if (c.from() == port || c.to() == port) return true;
        }
        return false;
    }

    /**
     * Move the viewport into a centered modal overlay with a perspective
     * / orthographic toolbar + close button. Mirrors the pattern from the
     * dasum-vis MVP demo: reparent the same Component.PointCloud
     * instance (so snapshot / camera / GL buffer follow), leave a
     * placeholder in the thumbnail slot so the node doesn't collapse.
     */
    private static void openOverlay(Entry entry) {
        Component.PointCloud viewport = entry.viewport;
        Component slot = entry.thumbnailSlot;

        Component placeholder = new Component.Box(
            THUMB_W, THUMB_H, Em.ZERO,
            new Color(0.04f, 0.05f, 0.08f, 0.6f),
            List.of(new Component.Text("In overlay", Em.of(0.85f), PLACEHOLDER_FG)));
        DynamicChildren.remove(slot, viewport);
        DynamicChildren.add(slot, placeholder);

        Component perspectiveBtn = Themed.button("Perspective",   Em.of(8f), Variant.PRIMARY, 0);
        Component orthoBtn       = Themed.button("Orthographic",  Em.of(9f), Variant.DEFAULT, 0);
        Handlers.onClick(perspectiveBtn,
            () -> PointCloudStates.setCamera(viewport,
                  PointCloudStates.cameraOf(viewport).withMode(CameraMode.PERSPECTIVE)));
        Handlers.onClick(orthoBtn,
            () -> PointCloudStates.setCamera(viewport,
                  PointCloudStates.cameraOf(viewport).withMode(CameraMode.ORTHOGRAPHIC)));

        Component title  = new Component.Text(
            "Visualizer · " + entry.spec.cgNode().id(),
            Em.of(1.1f), OVERLAY_TITLE_FG);
        Component spacer = new Component.Box(
            Em.of(1f), Em.of(0f), Em.ZERO, new Color(0f, 0f, 0f, 0f)).withFlexGrow(1);
        Component closeBtn = Themed.button("Close", Em.of(6f), Variant.PRIMARY, 0);

        Component header = new Component.Flex(
            null, Em.of(2.6f), Em.of(0.5f), OVERLAY_HEADER_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.5f),
            List.of(title, perspectiveBtn, orthoBtn, spacer, closeBtn),
            false, 0);

        // Oversize Em is clamped by OverlayStack.computeOverlayRect to the
        // viewport — gives us a full-screen modal. Swap for finite sizes
        // if you want a dialog instead.
        Component.Flex overlayRoot = new Component.Flex(
            Em.of(1000f), Em.of(1000f), Em.of(0.4f), OVERLAY_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(header), false, 0);
        DynamicChildren.add(overlayRoot, viewport);

        Runnable restore = () -> {
            DynamicChildren.remove(overlayRoot, viewport);
            DynamicChildren.remove(slot, placeholder);
            DynamicChildren.add(slot, viewport);
            Components.detach(overlayRoot);
            Components.detach(placeholder);
        };
        Handlers.onClick(closeBtn, OverlayStack::pop);
        OverlayStack.push(new OverlayStack.Overlay(overlayRoot, Anchor.CENTER, true, restore));
    }

    /** 512-point unit cube with per-axis colour bias (red~x, green~y,
     *  blue~z) — placeholder cloud rendered before any training data
     *  has arrived so the viewport is visibly alive. Mirrors the
     *  dasum-vis demo's seed cube, half the point count to keep
     *  pre-training cost negligible. */
    private static PointCloudSnapshot placeholderCube() {
        final int n = 512;
        float[] positions = new float[n * 3];
        float[] colors    = new float[n * 3];
        java.util.Random rng = new java.util.Random(0x7E5717);
        for (int i = 0; i < n; i++) {
            float x = (rng.nextFloat() - 0.5f) * 2f;
            float y = (rng.nextFloat() - 0.5f) * 2f;
            float z = (rng.nextFloat() - 0.5f) * 2f;
            positions[i*3    ] = x;
            positions[i*3 + 1] = y;
            positions[i*3 + 2] = z;
            colors[i*3    ] = 0.5f + 0.5f * x;
            colors[i*3 + 1] = 0.5f + 0.5f * y;
            colors[i*3 + 2] = 0.5f + 0.5f * z;
        }
        return new PointCloudSnapshot(3, n, positions, colors, null, null);
    }

    private static String formatRawVector(PointCloudSnapshot snap, int pointIndex) {
        int dim = snap.dimensionality();
        int base = pointIndex * dim;
        int show = Math.min(dim, 6);
        StringBuilder sb = new StringBuilder("[");
        for (int d = 0; d < show; d++) {
            if (d > 0) sb.append(", ");
            sb.append(String.format("%.3g", snap.positions()[base + d]));
        }
        if (dim > show) sb.append(", … (").append(dim).append("d)");
        sb.append("]");
        return sb.toString();
    }
}
