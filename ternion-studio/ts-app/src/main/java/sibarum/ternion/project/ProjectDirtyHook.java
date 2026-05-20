package sibarum.ternion.project;

import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.source.DataSource;

import java.util.HashSet;
import java.util.Set;

/**
 * Wires {@link Project#dirty} to every mutation that should count as
 * an unsaved change:
 *
 * <ul>
 *   <li>Graph topology + wire edits via
 *       {@link sibarum.ternion.designer.GraphSync#mutationVersion()}.</li>
 *   <li>Source-registry add / remove via
 *       {@link sibarum.ternion.data.source.DataSourceRegistry#structureVersion()}.</li>
 *   <li>Cell / row / column edits on every registered source via
 *       {@link DataSource#structureVersion()}. Subscriptions are
 *       (re-)installed whenever the registry's own structure version
 *       bumps, so newly-added editable sources start tracking
 *       immediately.</li>
 * </ul>
 *
 * <p>{@link ProjectIo#save} / {@code load} and {@code MainShell.onNew}
 * call {@link Project#markClean} to clear the flag at the appropriate
 * boundaries.
 *
 * <p>Install once at app startup from {@code MainShell.build} —
 * subscriptions persist for the JVM's lifetime, which matches V1's
 * single-AppContext-per-process model.
 */
public final class ProjectDirtyHook {

    private ProjectDirtyHook() {}

    public static void install(AppContext ctx) {
        Project project = ctx.project();

        // Graph mutations (spawn / remove / clearAll / replaceConfig /
        // connection ADDED / REMOVED) all flow through this single
        // counter.
        ctx.graphSync().mutationVersion().subscribe(v -> project.markDirty());

        // Source-registry add / remove (also fires on seedBundled at
        // construction, but markDirty is harmless before the first
        // markClean — the close prompt is gated on dirty being set
        // AFTER the project is in its loaded/new state).
        ctx.dataSources().structureVersion().subscribe(v -> {
            project.markDirty();
            ensureSourceSubscriptions(ctx, project);
        });
        ensureSourceSubscriptions(ctx, project);
    }

    /** Idempotent: subscribes any not-yet-subscribed source's
     *  {@code structureVersion} so cell + row + column edits feed
     *  into {@code project.markDirty()}. */
    private static void ensureSourceSubscriptions(AppContext ctx, Project project) {
        Set<String> subscribed = subscriptionState(ctx);
        for (DataSource ds : ctx.dataSources().all()) {
            if (subscribed.add(ds.id())) {
                ds.structureVersion().subscribe(v -> project.markDirty());
            }
        }
    }

    // Per-AppContext set of source ids we've already wired. The hook
    // is installed once per AppContext, but {@link
    // #ensureSourceSubscriptions} runs every time the registry's
    // version bumps; without dedup, every new source addition would
    // double-subscribe every existing source.
    private static Set<String> subscriptionState(AppContext ctx) {
        Set<String> s = STATES.get(ctx);
        if (s == null) {
            s = new HashSet<>();
            STATES.put(ctx, s);
        }
        return s;
    }

    private static final java.util.IdentityHashMap<AppContext, Set<String>> STATES
        = new java.util.IdentityHashMap<>();
}
