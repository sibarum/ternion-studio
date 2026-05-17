package sibarum.ternion.data;

import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;

import java.util.ArrayList;
import java.util.List;

/**
 * Component factory for one row in the data table. Each row has a
 * label cell, one cell per declared input, a target cell, and a delete
 * button. Cells are editable {@link sibarum.dasum.gui.core.component.Component.Text}
 * components; content-change listeners pipe edits back to
 * {@link CorpusModel}.
 */
public final class ExampleRow {

    public static final float ROW_HEIGHT_EM = 2.4f;

    private static final Color CELL_BG    = new Color(0.10f, 0.12f, 0.16f, 1f);
    private static final Color CELL_FG    = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color LABEL_FG   = new Color(0.70f, 0.75f, 0.82f, 0.85f);
    private static final Color TRANSPARENT = new Color(0f, 0f, 0f, 0f);

    private static final Em LABEL_W  = Em.of(6f);
    private static final Em CELL_W   = Em.of(8f);
    private static final Em TARGET_W = Em.of(8f);
    private static final Em DELETE_W = Em.of(4.5f);

    public static Component buildHeader(CorpusModel corpus) {
        List<Component> cells = new ArrayList<>();
        cells.add(headerCell("Label", LABEL_W));
        for (InputSchema in : corpus.inputSchema()) {
            cells.add(headerCell(in.displayLabel(), CELL_W));
        }
        cells.add(headerCell("Target (" + corpus.targetSchema().type() + ")", TARGET_W));
        cells.add(headerCell("", DELETE_W));
        return new Component.Flex(
            null, Em.of(ROW_HEIGHT_EM), Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.4f),
            cells, false, 0
        );
    }

    public static Component build(CorpusModel corpus, int rowIndex) {
        CorpusModel.Row row = corpus.rows().get(rowIndex);

        List<Component> cells = new ArrayList<>();
        cells.add(editableCell(LABEL_W, row.label,
            v -> corpus.setLabel(rowIndex, v)));
        for (InputSchema in : corpus.inputSchema()) {
            String initial = row.inputStrings.getOrDefault(in.key(), "");
            cells.add(editableCell(CELL_W, initial,
                v -> corpus.setInputCell(rowIndex, in.key(), v)));
        }
        cells.add(editableCell(TARGET_W,
            row.targetString == null ? "" : row.targetString,
            v -> corpus.setTargetCell(rowIndex, v)));

        Component delete = Themed.button("Delete", DELETE_W, Variant.ERROR, 0);
        Handlers.onClick(delete, () -> corpus.removeRow(rowIndex));
        cells.add(delete);

        return new Component.Flex(
            null, Em.of(ROW_HEIGHT_EM), Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.of(0.4f),
            cells, false, 0
        );
    }

    private static Component headerCell(String text, Em width) {
        Component label = new Component.Text(text, Em.of(0.85f), LABEL_FG);
        return new Component.Flex(
            width, Em.of(ROW_HEIGHT_EM - 0.2f), Em.of(0.4f), TRANSPARENT,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.ZERO,
            List.of(label), false, 0
        );
    }

    private static Component editableCell(Em cellWidth, String initial,
                                          java.util.function.Consumer<String> onChange) {
        // Give the Text an explicit width (cell interior) so its hit-rect
        // covers the entire cell horizontally even when empty. Pair with
        // AlignItems.STRETCH on the parent so the Text rect also fills the
        // cell vertically — otherwise the rect collapses to the glyph's
        // intrinsic height and the user has to click on a thin strip mid-
        // cell to focus. Trim the Text's own padding to 0.1em so the
        // clip rect doesn't eat below-baseline descenders ('g', 'y',
        // 'p') at a cell height that needs to stay compact.
        float pad = 0.3f;  // cell-side padding in em (matches CELL pad below)
        Em textWidth = Em.of(Math.max(0.5f, cellWidth.value() - 2f * pad));
        Component.Text text = new Component.Text(
            initial,
            sibarum.dasum.gui.core.text.FontGroups.DEFAULT,
            Em.of(0.95f), CELL_FG,
            textWidth, null, Em.of(0.1f),
            null, true,
            true, true, true, false, 0
        );
        // Subscribe to edits — pipe back to model. Avoids triggering when
        // initial content is set programmatically (TextStates.setContent
        // fires the same listener; we don't call setContent in this path).
        TextStates.onContentChange(text, onChange);
        return new Component.Flex(
            cellWidth, Em.of(ROW_HEIGHT_EM - 0.2f), Em.of(pad), CELL_BG,
            Direction.ROW, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(text), false, 0
        );
    }

    private ExampleRow() {}
}
