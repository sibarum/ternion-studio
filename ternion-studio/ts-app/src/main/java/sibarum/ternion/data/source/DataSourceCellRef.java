package sibarum.ternion.data.source;

/**
 * Stable reference to a single column of a single {@link DataSource}.
 * Used by graph primitives ({@code DatasetColumn}, {@code LossOutput})
 * to address their cell location in a uniform, source-agnostic way —
 * no matter whether the underlying source is bundled, editable, or
 * imported. Resolution happens at training time via the project's
 * {@link DataSourceRegistry}.
 *
 * <p>Equality on (sourceId, columnName) — two refs to the same cell
 * are equal regardless of who constructed them.
 */
public record DataSourceCellRef(String sourceId, String columnName) {

    public DataSourceCellRef {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must be non-blank");
        }
        if (columnName == null || columnName.isBlank()) {
            throw new IllegalArgumentException("columnName must be non-blank");
        }
    }

    /** Canonical {@code "sourceId:columnName"} encoding for config dropdowns. */
    public String encoded() {
        return sourceId + ":" + columnName;
    }

    /** Inverse of {@link #encoded()}. Throws on missing colon. */
    public static DataSourceCellRef decode(String encoded) {
        if (encoded == null) throw new IllegalArgumentException("encoded == null");
        int colon = encoded.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(
                "Expected 'sourceId:columnName'; got: " + encoded);
        }
        return new DataSourceCellRef(
            encoded.substring(0, colon),
            encoded.substring(colon + 1));
    }
}
