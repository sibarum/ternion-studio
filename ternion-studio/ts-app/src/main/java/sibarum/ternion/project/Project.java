package sibarum.ternion.project;

import sibarum.dasum.gui.core.reactive.Property;

import java.nio.file.Path;

/**
 * In-memory metadata about the current project. Persisted to
 * {@code project.json} on save; reset on New.
 *
 * <p>{@link #dirty} tracks whether there are unsaved mutations since
 * the last save / load / new. Graph + source mutation hooks call
 * {@link #markDirty}; {@link ProjectIo#save}/{@code load} and
 * {@code MainShell.onNew} call {@link #markClean}. The close-guard
 * reads it to decide whether to prompt before quitting.
 */
public final class Project {

    public static final int SCHEMA_VERSION = 1;

    private final Property<String>  name  = new Property<>("Untitled");
    private final Property<Path>    path  = new Property<>(null);
    private final Property<Boolean> dirty = new Property<>(false);

    public Property<String>  name()  { return name; }
    public Property<Path>    path()  { return path; }
    public Property<Boolean> dirty() { return dirty; }

    public void markDirty() {
        if (!dirty.get()) dirty.set(true);
    }

    public void markClean() {
        if (dirty.get()) dirty.set(false);
    }

    /** Reset to a fresh, unsaved-but-clean state. */
    public void reset() {
        name.set("Untitled");
        path.set(null);
        markClean();
    }
}
