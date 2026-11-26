package mihon.feature.libraryimport.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shapes a MangaBaka library export can arrive in.
 *
 * MangaBaka's API answers its library endpoint with `{ "data": [ { "entry": …, "series": … } ] }`;
 * the file export from the site is the same list, and has also been shipped as a bare array. Both
 * are accepted, and the per-item forms below keep every field optional so a payload that gained or
 * lost a column still parses.
 */
@Serializable
data class MangabakaExportEnvelope(
    val data: List<MangabakaExportItem>? = null,
    val entries: List<MangabakaExportItem>? = null,
    val library: List<MangabakaExportItem>? = null,
    val items: List<MangabakaExportItem>? = null,
) {
    fun items(): List<MangabakaExportItem>? = data ?: entries ?: library ?: items
}

@Serializable
data class MangabakaExportItem(
    val entry: MangabakaExportEntry? = null,
    val series: MangabakaExportSeries? = null,
    // Flat form: the library row's own fields and the series' fields side by side.
    val state: String? = null,
    @SerialName("progress_chapter")
    val progressChapter: JsonPrimitive? = null,
    @SerialName("progress_volume")
    val progressVolume: JsonPrimitive? = null,
    val rating: JsonPrimitive? = null,
    val note: String? = null,
    @SerialName("is_private")
    val isPrivate: Boolean? = null,
    @SerialName("start_date")
    val startDate: String? = null,
    @SerialName("finish_date")
    val finishDate: String? = null,
    @SerialName("series_id")
    val seriesId: Long? = null,
    val id: Long? = null,
    val title: String? = null,
    @SerialName("canonical_url")
    val canonicalUrl: String? = null,
    val cover: MangabakaExportCover? = null,
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val tags: List<String>? = null,
    val description: String? = null,
    val status: String? = null,
    val type: String? = null,
    val titles: List<MangabakaExportTitle>? = null,
    @SerialName("total_chapters")
    val totalChapters: JsonPrimitive? = null,
)

@Serializable
data class MangabakaExportEntry(
    val state: String? = null,
    @SerialName("progress_chapter")
    val progressChapter: JsonPrimitive? = null,
    @SerialName("progress_volume")
    val progressVolume: JsonPrimitive? = null,
    val rating: JsonPrimitive? = null,
    val note: String? = null,
    @SerialName("is_private")
    val isPrivate: Boolean? = null,
    @SerialName("start_date")
    val startDate: String? = null,
    @SerialName("finish_date")
    val finishDate: String? = null,
    @SerialName("series_id")
    val seriesId: Long? = null,
)

@Serializable
data class MangabakaExportSeries(
    val id: Long? = null,
    @SerialName("canonical_url")
    val canonicalUrl: String? = null,
    val cover: MangabakaExportCover? = null,
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val tags: List<String>? = null,
    val description: String? = null,
    val status: String? = null,
    val type: String? = null,
    val titles: List<MangabakaExportTitle>? = null,
    @SerialName("total_chapters")
    val totalChapters: JsonPrimitive? = null,
)

@Serializable
data class MangabakaExportCover(
    val raw: MangabakaExportRawCover? = null,
    val x150: MangabakaExportScaledCover? = null,
    val x250: MangabakaExportScaledCover? = null,
    val x350: MangabakaExportScaledCover? = null,
) {
    fun bestUrl(): String? = raw?.url ?: x350?.x1 ?: x250?.x1 ?: x150?.x1
}

@Serializable
data class MangabakaExportRawCover(
    val url: String? = null,
)

@Serializable
data class MangabakaExportScaledCover(
    val x1: String? = null,
    val x2: String? = null,
    val x3: String? = null,
)

@Serializable
data class MangabakaExportTitle(
    val title: String,
    val language: String? = null,
    val traits: List<String> = emptyList(),
    @SerialName("is_primary")
    val isPrimary: Boolean = false,
)
