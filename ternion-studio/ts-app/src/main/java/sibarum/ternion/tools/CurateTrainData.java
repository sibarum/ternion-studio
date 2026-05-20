package sibarum.ternion.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Offline curation tool — produces a balanced, frequency-prioritized
 * subsample of a CSV-style POS dataset (or any dataset with the shape
 * {@code sample_idx,token,gold_coarse,gold_name,upos}).
 *
 * <p>Selection algorithm:
 * <ol>
 *   <li>Read every well-formed row from the input CSV (rows with a field
 *       count {@code !=} 5 are skipped — guards against the unquoted-
 *       comma artifacts in the source).</li>
 *   <li>Compute the global frequency of every {@code token} across the
 *       whole input.</li>
 *   <li>For each distinct {@code gold_name} class, collect the unique
 *       tokens that ever appeared with that class. Sort them by global
 *       frequency descending. Keep the top {@code perClass} (or all of
 *       them if the class has fewer unique tokens than that).</li>
 *   <li>Emit one canonical row per selected {@code (token, class)}
 *       pair — the first row in source order — preserving the original
 *       column shape so downstream importers don't need to know
 *       anything about the curation step.</li>
 * </ol>
 *
 * <p>Run via {@code java}'s single-source-file mode or
 * {@code mvn -pl ts-app exec:java -Dexec.mainClass=...}. Not part of
 * the runtime data path — intentionally placed in {@code tools/} so
 * it's obvious this isn't called from app code.
 */
public final class CurateTrainData {

    private record Row(String sampleIdx, String token, String goldCoarse,
                       String goldName, String upos) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println(
                "Usage: CurateTrainData <input.csv> <output.csv> <perClass>");
            System.exit(2);
        }
        Path input    = Path.of(args[0]);
        Path output   = Path.of(args[1]);
        int  perClass = Integer.parseInt(args[2]);

        List<Row> rows = readCsv(input);
        System.out.println("Read " + rows.size() + " well-formed rows from " + input);

        // 1. Per-class token frequency — how often each token appears
        //    *with this class label*. This is the signal that picks the
        //    canonical tokens FOR each class (e.g. "Bush" for PROPN, "I"
        //    for PRON), as opposed to ranking by global frequency which
        //    surfaces noise (a common token that mis-labels once into a
        //    rare class outranks the class's actual canonical tokens).
        Map<String, Map<String, Integer>> classTokenFreq = new HashMap<>();
        // 2. Per-class: token → canonical first-occurrence row. LinkedHashMap
        //    so within-class ties break by source order rather than
        //    HashMap's iteration randomness.
        Map<String, Map<String, Row>> classToTokenRow = new LinkedHashMap<>();
        for (Row r : rows) {
            classTokenFreq
                .computeIfAbsent(r.upos, k -> new HashMap<>())
                .merge(r.token, 1, Integer::sum);
            classToTokenRow
                .computeIfAbsent(r.upos, k -> new LinkedHashMap<>())
                .putIfAbsent(r.token, r);
        }

        // 3. For each class, top-perClass tokens by *within-class* frequency.
        TreeMap<String, List<Row>> selected = new TreeMap<>();
        for (var entry : classToTokenRow.entrySet()) {
            Map<String, Integer> freqInClass = classTokenFreq.get(entry.getKey());
            List<Row> classRows = new ArrayList<>(entry.getValue().values());
            classRows.sort(Comparator
                .<Row>comparingInt(r -> freqInClass.getOrDefault(r.token, 0))
                .reversed());
            int n = Math.min(perClass, classRows.size());
            selected.put(entry.getKey(), new ArrayList<>(classRows.subList(0, n)));
        }

        // 4. Write the output CSV. Header matches the input so the file
        //    drops into any importer the source CSV would.
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(output)) {
            w.write("sample_idx,token,gold_coarse,gold_name,upos\n");
            for (List<Row> classRows : selected.values()) {
                for (Row r : classRows) {
                    w.write(r.sampleIdx + "," + r.token + "," + r.goldCoarse
                        + "," + r.upos + "," + r.upos + "\n");
                }
            }
        }

        int total = selected.values().stream().mapToInt(List::size).sum();
        System.out.println("Wrote " + total + " rows to " + output);
        System.out.println("Per-class counts (target perClass=" + perClass + "):");
        for (var e : selected.entrySet()) {
            int got = e.getValue().size();
            int distinctInSource = classToTokenRow.get(e.getKey()).size();
            String note = (got < perClass)
                ? "  (only " + distinctInSource + " distinct tokens in source)"
                : "";
            System.out.printf("  %-8s  %4d%s%n", e.getKey(), got, note);
        }
    }

    /**
     * Permissive CSV reader — skips rows whose field count doesn't match
     * the header. Handles the unquoted-comma artifacts in the source
     * without bringing in a CSV-parsing dependency.
     */
    private static List<Row> readCsv(Path p) throws IOException {
        List<Row> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(p)) {
            String header = r.readLine();
            if (header == null) throw new IOException("Empty CSV: " + p);
            int dropped = 0;
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(",", -1);
                if (parts.length != 5) { dropped++; continue; }
                out.add(new Row(parts[0], parts[1], parts[2], parts[3], parts[4]));
            }
            if (dropped > 0) {
                System.err.println("Note: skipped " + dropped
                    + " malformed row(s) (field count != 5).");
            }
        }
        return out;
    }

    private CurateTrainData() {}
}
