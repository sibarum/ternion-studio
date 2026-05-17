package sibarum.ternion.project;

import sibarum.dasum.gui.core.reactive.Property;

import java.nio.file.Path;

/**
 * In-memory metadata about the current project. Persisted to
 * {@code project.json} on save; reset on New.
 */
public final class Project {

    public static final int SCHEMA_VERSION = 1;

    private final Property<String> name = new Property<>("Untitled");
    private final Property<Path>   path = new Property<>(null);

    public Property<String> name() { return name; }
    public Property<Path>   path() { return path; }

    /** Reset to a fresh, unsaved state. */
    public void reset() {
        name.set("Untitled");
        path.set(null);
    }
}
