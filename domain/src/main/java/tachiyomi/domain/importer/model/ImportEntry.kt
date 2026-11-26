package tachiyomi.domain.importer.model

import kotlinx.serialization.Serializable

/**
 * One entry of a library export, before it exists as a manga in this app.
 *
 * An import entry is a *shell*: it carries the metadata the export knows about, but it is not a
 * [tachiyomi.domain.manga.model.Manga] and owns no chapters, so it cannot be opened or read. It
 * becomes a real library entry only when the reader picks a source for it through the import
 * screen, which runs the same "migrate to a source" flow the migration feature uses.
 *
 * The model is deliberately importer-agnostic. [ImportSession.sourceId] names the adapter that
 * produced it (today only the MangaBaka export), and everything the adapter knows that has no
 * generic home rides along in [extra].
 */
@Serializable
data class ImportEntry(
    /**
     * Stable identity inside the export. Must not change between re-reads of the same file, since
     * the session tracks progress by id.
     */
    val id: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val tags: List<String> = emptyList(),
    /** Publication status as the export names it ("completed", "releasing", …). */
    val status: String? = null,
    /** Content type as the export names it ("manga", "novel", …). */
    val type: String? = null,
    val totalChapters: Long = 0L,
    /** How far the reader got, as the export recorded it. */
    val progressChapter: Double = 0.0,
    val progressVolume: Double = 0.0,
    /** The reader's own score, on the exporting service's own scale. */
    val rating: Double? = null,
    val note: String? = null,
    val startDate: String? = null,
    val finishDate: String? = null,
    /** A page for this series on the exporting service, if it has one. */
    val seriesUrl: String? = null,
    /**
     * Tracker entries this export already has, so a migrated manga can be tracked without the
     * reader binding it by hand. Every adapter is expected to fill this in for its own service.
     */
    val trackingLinks: List<ImportTrackingLink> = emptyList(),
    /** Adapter-specific fields that have no generic representation. */
    val extra: Map<String, String> = emptyMap(),
) {
    val hasProgress: Boolean
        get() = progressChapter > 0.0
}

/**
 * A tracking row the export knows about, keyed by the tracker it belongs to.
 *
 * [trackerId] is the app's own tracker id (`TrackerId.MANGABAKA`, …), not the exporting service's
 * name for it, so the migration flow can look the tracker up directly.
 */
@Serializable
data class ImportTrackingLink(
    val trackerId: Long,
    val remoteId: Long,
    val remoteUrl: String? = null,
    /** The state as the export spells it; resolved against the tracker's own status list. */
    val status: String? = null,
    val score: Double? = null,
    val progressChapter: Double = 0.0,
    val startDate: String? = null,
    val finishDate: String? = null,
    /** Whether the reader kept this row private on the exporting service. */
    val isPrivate: Boolean = false,
)
