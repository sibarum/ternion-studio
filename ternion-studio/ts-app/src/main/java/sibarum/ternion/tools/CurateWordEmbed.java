package sibarum.ternion.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline curation tool — strips the 22-column
 * {@code vocab_codes_k16_multiprior_lower.csv} produced by mcc-elden-ring
 * down to two columns the runtime cares about:
 *
 * <pre>
 *   surface,embedding
 *   the,-1.152845 0.275607 -0.391811 ...
 * </pre>
 *
 * <p>The 16-dim {@code z0..z15} cells get joined into a single
 * space-separated {@code embedding} string so the loader's naïve
 * comma-split parser sees one cell (the comma-everywhere convention
 * doesn't survive an embedded comma; space-separated is the same
 * format {@code Linear} / {@code Parameter} cells already use).
 *
 * <p>Source-side CSV quoting is handled inline ({@link #parseCsvLine})
 * so rows like {@code "200,000",...} round-trip correctly. Surfaces
 * that themselves contain a comma are skipped on output — they'd
 * collide with the downstream parser's comma split. The 4 or 5
 * affected rows are numbers / punctuation; the loss is acceptable for
 * this curation.
 *
 * <p>Run via:
 * <pre>
 *   mvn -pl ts-app compile exec:java \
 *       -Dexec.mainClass=sibarum.ternion.tools.CurateWordEmbed \
 *       -Dexec.args="
 *         ../mandate-carve-compose/mcc-elden-ring/out/upos_tokenizer_multiprior_lower/vocab_codes_k16_multiprior_lower.csv
 *         src/main/resources/datasets/word_embed_k16.csv"
 * </pre>
 */
public final class CurateWordEmbed {

    private static final int DIM = 16;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println(
                "Usage: CurateWordEmbed <input.csv> <output.csv>");
            System.exit(2);
        }
        Path input  = Path.of(args[0]);
        Path output = Path.of(args[1]);

        int written = 0;
        int skippedFieldCount = 0;
        int skippedCommaSurface = 0;
        try (BufferedReader r = Files.newBufferedReader(input);
             BufferedWriter w = Files.newBufferedWriter(output)) {
            String header = r.readLine();
            if (header == null) throw new IOException("input is empty");
            List<String> headerFields = parseCsvLine(header);
            int surfaceIdx = headerFields.indexOf("surface");
            int firstZIdx  = headerFields.indexOf("z0");
            if (surfaceIdx < 0 || firstZIdx < 0) {
                throw new IOException(
                    "expected 'surface' and 'z0..z15' columns; got " + headerFields);
            }
            // Sanity-check that z0..z15 are contiguous.
            for (int i = 0; i < DIM; i++) {
                int idx = headerFields.indexOf("z" + i);
                if (idx != firstZIdx + i) {
                    throw new IOException(
                        "z" + i + " expected at index " + (firstZIdx + i)
                        + " but found at " + idx);
                }
            }

            w.write("surface,embedding");
            w.newLine();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                List<String> fields = parseCsvLine(line);
                if (fields.size() != headerFields.size()) {
                    skippedFieldCount++;
                    continue;
                }
                String surface = fields.get(surfaceIdx);
                if (surface.indexOf(',') >= 0) {
                    skippedCommaSurface++;
                    continue;
                }
                StringBuilder emb = new StringBuilder(16 * 12);
                for (int i = 0; i < DIM; i++) {
                    if (i > 0) emb.append(' ');
                    emb.append(fields.get(firstZIdx + i));
                }
                w.write(surface);
                w.write(',');
                w.write(emb.toString());
                w.newLine();
                written++;
            }
        }
        System.out.println("Wrote " + written + " rows to " + output);
        if (skippedFieldCount > 0) {
            System.out.println("  skipped " + skippedFieldCount + " row(s) with wrong field count");
        }
        if (skippedCommaSurface > 0) {
            System.out.println("  skipped " + skippedCommaSurface + " row(s) whose surface contained a comma");
        }
    }

    /**
     * Minimal RFC-4180-ish CSV splitter: respects double-quoted fields
     * (an embedded {@code ""} is a literal quote). Doesn't support
     * embedded newlines (none in the source file). Good enough for
     * mcc-elden-ring's output shape.
     */
    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"' && cur.length() == 0) {
                    inQuotes = true;
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    private CurateWordEmbed() {}
}
