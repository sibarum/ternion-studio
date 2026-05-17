package sibarum.ternion.designer;

import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.DynamicChildren;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.render.Color;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Visual badge attached as a dynamic child of a node's visual whenever
 * the node is frozen. Maintained idempotently — calling
 * {@link #refresh} repeatedly is safe and only mutates the visual when
 * the frozen state actually changed.
 *
 * <p>The atlas is ASCII-only, so we use a text-only "[ LOCKED ]" badge
 * in amber rather than a lock glyph.
 */
public final class FrozenBadge {

    private static final Color LOCK_FG = new Color(0.95f, 0.75f, 0.35f, 1f);

    private static final Map<NodeSpec, Component> ATTACHED = new IdentityHashMap<>();

    private FrozenBadge() {}

    /** Add or remove the badge so every node's badge state matches
     *  {@link FrozenNodes#isFrozen}. Safe to call from any thread that
     *  also owns dasum mutation (the GLFW main thread). */
    public static void refresh(GraphSync sync) {
        boolean anyChange = false;
        for (NodeSpec spec : sync.liveNodes()) {
            boolean frozen = FrozenNodes.isFrozen(spec);
            Component existing = ATTACHED.get(spec);
            if (frozen && existing == null) {
                Component badge = new Component.Text("[ LOCKED ]", Em.of(0.78f), LOCK_FG);
                DynamicChildren.add(spec.visual(), badge);
                ATTACHED.put(spec, badge);
                anyChange = true;
            } else if (!frozen && existing != null) {
                DynamicChildren.remove(spec.visual(), existing);
                ATTACHED.remove(spec);
                anyChange = true;
            }
        }
        // Garbage-collect entries for specs that were removed.
        ATTACHED.keySet().removeIf(s -> sync.specOf(s.visual()) == null);
        if (anyChange) Invalidator.invalidate();
    }

    public static void clearAll() {
        ATTACHED.clear();
    }
}
