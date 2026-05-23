package sibarum.ternion.data.curated;

import sibarum.mcc.value.ValueType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of curated datasets bundled with the executable. Each entry
 * is a small, deterministic subset produced offline by
 * {@code sibarum.ternion.tools.CurateTrainData} and committed under
 * {@code src/main/resources/datasets/*.csv}.
 *
 * <p>Loaded once at class init: the static initializer parses every
 * registered CSV from the classpath into a {@link CuratedDataset}.
 * For now the parse happens at JVM startup (negligible cost — datasets
 * are a few hundred rows each); a future
 * {@code META-INF/native-image/native-image.properties} entry can mark
 * this class {@code --initialize-at-build-time} so GraalVM bakes the
 * parsed structure straight into the image at native-image build time.
 *
 * <p>The CSV reader is intentionally minimal — splits on commas, drops
 * malformed rows (field count {@code != columns.size()}). Matches the
 * curation tool's permissiveness so the same source-file artefacts
 * (unquoted commas in tokens) are handled uniformly.
 */
public final class CuratedDatasets {

    /** All registered datasets in deterministic (registration-order) iteration. */
    private static final Map<String, CuratedDataset> ALL = new LinkedHashMap<>();

    static {
        // One entry per bundled dataset. Adding a dataset = add a CSV
        // under /datasets/ + one line here.
        register(loadCsv(
            "pos_train",
            "POS Tagging · curated train (100/class)",
            "/datasets/pos_train.csv",
            /*targetColumn=*/ "upos",
            Map.of()));   // all columns default to STRING
        register(loadCsv(
            "word_embed_k16",
            "Pretrained word embeddings · K=16",
            "/datasets/word_embed_k16.csv",
            /*targetColumn=*/ null,
            Map.of("embedding", ValueType.MATRIX)));
    }

    private CuratedDatasets() {}

    /** Every registered dataset, registration-order. */
    public static List<CuratedDataset> all() {
        return new ArrayList<>(ALL.values());
    }

    /** Lookup by id; {@code null} if unknown. */
    public static CuratedDataset byId(String id) {
        return ALL.get(id);
    }

    private static void register(CuratedDataset ds) {
        ALL.put(ds.id(), ds);
    }

    /**
     * Parse a CSV resource into a {@link CuratedDataset}. Throws
     * {@link IllegalStateException} if the resource is missing or
     * unparseable — datasets are bundled artefacts; missing one means
     * the build is broken, and a noisy failure beats a silent empty
     * dataset.
     */
    private static CuratedDataset loadCsv(String id, String displayLabel,
                                          String resourcePath, String targetColumn,
                                          Map<String, ValueType> columnTypeOverrides) {
        try (InputStream in = CuratedDatasets.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                    "Curated dataset resource missing: " + resourcePath
                    + " — check that " + resourcePath + " is on the classpath.");
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String headerLine = r.readLine();
                if (headerLine == null) {
                    throw new IllegalStateException(
                        "Curated dataset is empty: " + resourcePath);
                }
                List<String> columns = List.of(headerLine.split(",", -1));
                int targetIdx = -1;
                if (targetColumn != null) {
                    targetIdx = columns.indexOf(targetColumn);
                    if (targetIdx < 0) {
                        throw new IllegalStateException(
                            "Target column '" + targetColumn + "' not found in "
                            + resourcePath + "; have " + columns);
                    }
                }
                List<String[]> rowList = new ArrayList<>();
                String line;
                int width = columns.size();
                while ((line = r.readLine()) != null) {
                    String[] cells = line.split(",", -1);
                    if (cells.length != width) continue;  // skip malformed
                    rowList.add(cells);
                }
                String[][] rows = rowList.toArray(new String[0][]);
                // Resolve per-column types: overrides win, then default
                // to STRING. Unknown override names are an error — they
                // signal a registration bug (typo, stale registration).
                for (String overrideName : columnTypeOverrides.keySet()) {
                    if (!columns.contains(overrideName)) {
                        throw new IllegalStateException(
                            "columnType override for unknown column '" + overrideName
                            + "' in " + resourcePath + "; have " + columns);
                    }
                }
                List<ValueType> types = new ArrayList<>(columns.size());
                for (String col : columns) {
                    types.add(columnTypeOverrides.getOrDefault(col, ValueType.STRING));
                }
                return new CuratedDataset(id, displayLabel,
                    Collections.unmodifiableList(columns),
                    Collections.unmodifiableList(types),
                    targetIdx, rows);
            }
        } catch (IOException ioe) {
            throw new IllegalStateException(
                "Failed reading curated dataset " + resourcePath, ioe);
        }
    }
}
