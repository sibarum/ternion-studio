package sibarum.ternion.data.source;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.render.Color;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.StringValue;
import sibarum.mcc.value.Value;
import sibarum.mcc.value.ValueType;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.designer.DataSourceBoundNodes;
import sibarum.ternion.designer.DatasetColumnPrimitive;
import sibarum.ternion.designer.GraphSync;
import sibarum.ternion.designer.LookupColumnPrimitive;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Phase 2 contract: a {@link DataSource} declares per-column types,
 * and source-reading primitives (DatasetColumn, LookupColumn) read
 * that declared type when the user picks AUTO instead of forcing an
 * explicit choice in the primitive's config.
 */
final class TypedColumnsTest {

    @BeforeEach
    void resetSidecar() {
        DataSourceBoundNodes.clearForTesting();
    }

    @Test
    void bundledWordEmbed_declaresEmbeddingAsMatrix() {
        // The bundled embedding table registers its 'embedding'
        // column as MATRIX; default for everything else is STRING.
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new sibarum.ternion.train.TrainingController(ctx));

        DataSource embed = ctx.dataSources().byId("word_embed_k16");
        assertNotNull(embed, "word_embed_k16 is registered at AppContext startup");
        assertEquals(ValueType.STRING, embed.columnType("surface"),
            "surface is STRING");
        assertEquals(ValueType.MATRIX, embed.columnType("embedding"),
            "embedding is MATRIX (declared at registration)");
    }

    @Test
    void datasetColumn_autoOutputTypeReadsColumnType() {
        // outputType=null (palette AUTO) → DatasetColumn picks up the
        // source's declared columnType. Embedding cell parses as
        // MATRIX without the user re-declaring it.
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new sibarum.ternion.train.TrainingController(ctx));

        DatasetColumnPrimitive col = new DatasetColumnPrimitive(
            "word_embed_k16", "embedding", /*outputType*/ null);
        assertEquals(ValueType.MATRIX, col.outputType(),
            "AUTO resolves embedding to MATRIX");

        Value out = col.apply(List.of());
        MatrixValue mv = assertInstanceOf(MatrixValue.class, out);
        assertEquals(16, mv.data().length, "16-dim embedding parses cleanly");
    }

    @Test
    void datasetColumn_autoOutputType_stringColumnReturnsString() {
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new sibarum.ternion.train.TrainingController(ctx));

        // pos_train.token is STRING (default).
        DatasetColumnPrimitive col = new DatasetColumnPrimitive(
            "pos_train", "token", /*outputType*/ null);
        assertEquals(ValueType.STRING, col.outputType(),
            "AUTO falls back to STRING default for pos_train.token");

        Value out = col.apply(List.of());
        assertInstanceOf(StringValue.class, out);
    }

    @Test
    void lookupColumn_autoOutputTypeReadsValueColumnType() {
        Component.GraphSurface surface = newSurface();
        GraphSync sync = newSync(surface);
        AppContext ctx = new AppContext(surface, sync);
        ctx.attachTrainingController(new sibarum.ternion.train.TrainingController(ctx));

        LookupColumnPrimitive lookup = new LookupColumnPrimitive(
            "word_embed_k16", "surface", "embedding", /*outputType*/ null);
        assertEquals(ValueType.MATRIX, lookup.outputType(),
            "AUTO resolves to MATRIX via value column's declared type");

        // Known key from the bundled table.
        Value out = lookup.apply(List.of(new StringValue("the")));
        MatrixValue mv = assertInstanceOf(MatrixValue.class, out);
        assertEquals(16, mv.data().length, "16-dim embedding parses cleanly");
    }

    @Test
    void editableDataSource_columnTypeRoundTrips() {
        EditableDataSource src = new EditableDataSource(
            "typed_fixture", "Typed", List.of("name", "vec"));
        assertEquals(ValueType.STRING, src.columnType("name"));
        assertEquals(ValueType.STRING, src.columnType("vec"),
            "new columns default to STRING");

        src.setColumnType("vec", ValueType.MATRIX);
        assertEquals(ValueType.MATRIX, src.columnType("vec"),
            "setColumnType updates the declared type");

        // Add a new column — defaults to STRING regardless of the existing types.
        src.addColumn("score");
        assertEquals(ValueType.STRING, src.columnType("score"));

        // Remove the typed column — types stay in sync.
        src.removeColumn("vec");
        assertEquals(ValueType.STRING, src.columnType("name"));
        assertEquals(ValueType.STRING, src.columnType("score"));
    }

    // ---------- helpers ----------

    private static Component.GraphSurface newSurface() {
        return new Component.GraphSurface(
            null, null, new Color(0f, 0f, 0f, 0f), List.of(), true, 0);
    }

    private static GraphSync newSync(Component.GraphSurface s) {
        GraphSync sync = new GraphSync(s);
        sync.install();
        return sync;
    }
}
