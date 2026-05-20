package sibarum.ternion.data.source;

import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.ternion.data.curated.CuratedDataset;
import sibarum.ternion.data.curated.CuratedDatasets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-project registry of {@link DataSource}s — the project-level
 * "database" the user sees in the Data tab and that graph primitives
 * resolve through.
 *
 * <p>Ordering is insertion-ordered (LinkedHashMap) — bundled sources
 * are typically seeded first via {@link #seedBundled}, then editable
 * + imported entries are added as the user creates them or as
 * {@code ProjectIo} restores them on load.
 *
 * <p>{@link #structureVersion} bumps on add/remove (not on
 * mutations within an existing source — those are signalled by each
 * source's own {@link DataSource#structureVersion}). Use this Property
 * to drive UI re-renders of the source picker.
 *
 * <h2>Reference checking</h2>
 * {@link #remove} refuses while {@link #referencingNodeIds} returns
 * non-empty — protects the graph from dangling
 * {@code DataSourceCellRef}s. The check is supplied by a graph
 * adapter callback set via {@link #setReferenceFinder}; the registry
 * itself stays free of designer dependencies.
 */
public final class DataSourceRegistry {

    /**
     * Process-global "current" registry. ternion-studio runs one
     * {@link sibarum.ternion.app.AppContext} per JVM, so a static
     * accessor lets graph primitives resolve source ids at
     * construction time without taking an explicit registry
     * parameter. Set by {@code AppContext}'s constructor; tests that
     * spin up their own AppContext also see it overwritten there.
     *
     * <p>Mirrors the static accessor pattern of
     * {@link sibarum.ternion.data.curated.CuratedDatasets}.
     */
    private static volatile DataSourceRegistry CURRENT;

    /** The active per-project registry, or {@code null} before AppContext is built. */
    public static DataSourceRegistry current() { return CURRENT; }

    /** Install (or replace) the active registry. {@code AppContext} calls this from its constructor. */
    public static void setCurrent(DataSourceRegistry reg) { CURRENT = reg; }

    private final Map<String, DataSource> sources = new LinkedHashMap<>();
    private final Property<Integer> structureVersion = new Property<>(0);
    private java.util.function.Function<String, Set<String>> referenceFinder = id -> Set.of();

    public Property<Integer> structureVersion() { return structureVersion; }

    /** All registered sources in insertion order. Defensive copy. */
    public List<DataSource> all() {
        return new ArrayList<>(sources.values());
    }

    /** Lookup by id; {@code null} if not registered. */
    public DataSource byId(String id) {
        return id == null ? null : sources.get(id);
    }

    /** Whether a source with this id is registered. */
    public boolean contains(String id) {
        return id != null && sources.containsKey(id);
    }

    /** Add a source. Throws on duplicate id. */
    public void add(DataSource source) {
        if (source == null) throw new IllegalArgumentException("source");
        if (sources.containsKey(source.id())) {
            throw new IllegalStateException(
                "DataSource id collision: '" + source.id() + "' is already registered");
        }
        sources.put(source.id(), source);
        bump();
    }

    /** Convenience: create and register a fresh editable source. */
    public EditableDataSource newEditable(String id, String displayLabel, List<String> columns) {
        EditableDataSource src = new EditableDataSource(id, displayLabel, columns);
        add(src);
        return src;
    }

    /** Remove by id. Refuses (throws) if any graph node references it. */
    public void remove(String id) {
        if (id == null || !sources.containsKey(id)) return;
        Set<String> refs = referencingNodeIds(id);
        if (!refs.isEmpty()) {
            throw new IllegalStateException(
                "Cannot remove source '" + id + "' — referenced by graph node(s): " + refs);
        }
        sources.remove(id);
        bump();
    }

    /**
     * Force-remove without the reference check. Reserved for project
     * load/teardown where the graph isn't yet wired up. Normal user
     * actions should go through {@link #remove}.
     */
    public void removeForced(String id) {
        if (id == null) return;
        if (sources.remove(id) != null) bump();
    }

    /** Seed wrappers for every bundled curated dataset. Idempotent. */
    public void seedBundled() {
        for (CuratedDataset ds : CuratedDatasets.all()) {
            if (!sources.containsKey(ds.id())) {
                sources.put(ds.id(), new BundledDataSource(ds));
            }
        }
        bump();
    }

    /**
     * Install the graph-side callback that returns the ids of graph
     * nodes currently referencing a given source. {@link #remove}
     * consults this to refuse removal while references exist.
     * <p>
     * Set once at app startup (after the graph + sidecars are ready);
     * the default of "no references" is safe but lets remove always
     * succeed.
     */
    public void setReferenceFinder(java.util.function.Function<String, Set<String>> finder) {
        this.referenceFinder = finder == null ? (id -> Set.of()) : finder;
    }

    /** Graph node ids that currently reference the source with this id. */
    public Set<String> referencingNodeIds(String sourceId) {
        Set<String> refs = referenceFinder.apply(sourceId);
        return refs == null ? Set.of() : Collections.unmodifiableSet(refs);
    }

    private void bump() {
        structureVersion.set(structureVersion.get() + 1);
        Invalidator.invalidate();
    }
}
