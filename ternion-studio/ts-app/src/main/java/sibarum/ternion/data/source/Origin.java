package sibarum.ternion.data.source;

/**
 * Where a {@link DataSource}'s rows came from. Drives the Data tab's
 * source-list icon, the project save shape (bundled = id only,
 * editable = full row dump, imported = file path + cached copy), and
 * which {@link DataSourceRegistry} mutators are accepted for the
 * source.
 */
public enum Origin {
    /** Bundled with the executable; rows are read-only and rehydrate
     *  from {@code CuratedDatasets} on load. */
    BUNDLED,
    /** Hand-edited via the Data tab; rows + schema persisted in full
     *  with the project. */
    EDITABLE,
    /** Imported from an external file (CSV / parquet / …). V1
     *  placeholder — actual import isn't wired yet. */
    IMPORTED
}
