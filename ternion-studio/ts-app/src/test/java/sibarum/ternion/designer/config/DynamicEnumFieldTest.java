package sibarum.ternion.designer.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.render.Color;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.source.EditableDataSource;
import sibarum.ternion.designer.DataSourceBoundNodes;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.designer.Palette;
import sibarum.ternion.designer.PaletteItem;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 contract: the source picker in DatasetColumn / LookupColumn
 * / LossOutput palette entries re-queries the live
 * {@link sibarum.ternion.data.source.DataSourceRegistry} each time a
 * config dialog opens. Editable sources registered after JVM startup
 * appear immediately — no JVM restart needed.
 */
final class DynamicEnumFieldTest {

    @BeforeEach
    void resetSidecar() {
        DataSourceBoundNodes.clearForTesting();
    }

    @Test
    void datasetColumnSourcePicker_includesRuntimeAddedSources() {
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new sibarum.ternion.train.TrainingController(ctx));

        // Initial materialize: bundled sources only.
        PaletteItem datasetCol = Palette.byKey("input.dataset.column");
        assertNotNull(datasetCol);
        ConfigField.DynamicEnumField sourceField =
            (ConfigField.DynamicEnumField) datasetCol.configSchema().get(0);
        List<String> before = sourceField.materialize().choices();
        assertTrue(before.stream().anyMatch(s -> s.startsWith("pos_train:")),
            "bundled pos_train present initially");
        assertTrue(before.stream().noneMatch(s -> s.startsWith("runtime_added:")),
            "runtime source NOT yet present: " + before);

        // Register a new editable source.
        EditableDataSource fresh = new EditableDataSource(
            "runtime_added", "Runtime Added", List.of("alpha", "beta"));
        ctx.dataSources().add(fresh);

        // Re-materialize: choices include the freshly-added source.
        List<String> after = sourceField.materialize().choices();
        assertTrue(after.contains("runtime_added:alpha"),
            "runtime_added:alpha present after registration; got: " + after);
        assertTrue(after.contains("runtime_added:beta"),
            "runtime_added:beta present after registration; got: " + after);
        assertTrue(after.stream().anyMatch(s -> s.startsWith("pos_train:")),
            "bundled pos_train still present after re-materialize");
    }

    @Test
    void lookupColumnSourcePicker_includesRuntimeAddedSources() {
        Component.GraphSurface surface = new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
        GraphSync sync = new GraphSync(surface);
        sync.install();
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new sibarum.ternion.train.TrainingController(ctx));

        PaletteItem lookupCol = Palette.byKey("input.lookup.column");
        assertNotNull(lookupCol);
        ConfigField.DynamicEnumField sourceField =
            (ConfigField.DynamicEnumField) lookupCol.configSchema().get(0);

        // Add a runtime source with at least 2 columns so we get
        // (key, value) pairs.
        EditableDataSource fresh = new EditableDataSource(
            "embed_runtime", "Runtime Embed", List.of("key", "vec"));
        ctx.dataSources().add(fresh);

        List<String> choices = sourceField.materialize().choices();
        assertTrue(choices.contains("embed_runtime:key:vec"),
            "lookup choices include runtime source's (key, value) pair; got: " + choices);
    }

    @Test
    void defaultValueFallback_whenSupplierReturnsMissingDefault() {
        // If the live default supplier returns a value that's no
        // longer in the choices (registry drift between resolves),
        // materialize falls back to the first choice rather than
        // throwing an EnumField default-mismatch.
        ConfigField.DynamicEnumField field = new ConfigField.DynamicEnumField(
            "test", "Test",
            () -> "no_longer_present",
            () -> List.of("a", "b", "c"));

        ConfigField.EnumField materialized = field.materialize();
        assertEquals("a", materialized.defaultValue(),
            "missing default falls back to first choice");
        assertInstanceOf(ConfigField.EnumField.class, materialized);
    }

    @Test
    void emptyChoicesFallback_keepsDialogOpenable() {
        // Even with no choices at all, materialize produces a
        // non-empty EnumField — the dialog should render without
        // exploding.
        ConfigField.DynamicEnumField field = new ConfigField.DynamicEnumField(
            "test", "Test",
            () -> null,
            () -> List.of());

        ConfigField.EnumField materialized = field.materialize();
        assertEquals(List.of("(none)"), materialized.choices());
        assertEquals("(none)", materialized.defaultValue());
    }
}
