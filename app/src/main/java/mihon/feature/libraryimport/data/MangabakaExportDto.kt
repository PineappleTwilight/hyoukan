package mihon.feature.libraryimport.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The shapes a MangaBaka library export can arrive in.
 *
 * Three generations are in the wild and all of them must parse:
 *
 * - the **library API** answers `{ "data": [ { "entry": …, "series": … } ] }`, with the series'
 *   title list as `"titles": [{ "title": …, "language": … }]`;
 * - a **flat array** of rows mixing the entry and series columns;
 * - the **file export** (`schema_version: 2`, MangaBaka's download from the site), which drops the
 *   `series` object entirely: the series id moves to `source.mangabaka`, and `titles` becomes a
 *   single object `{ "primary": …, "native": …, "romanized": … }`.
 *
 * Every field is optional so a payload that gained or lost a column still parses; the importer
 * decides which shape it is looking at.
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
    // schema_version 2 export: the ids MangaBaka knows this series by, keyed by service.
    val source: MangabakaExportSource? = null,
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
    val titles: MangabakaExportTitles? = null,
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

/**
 * The service ids MangaBaka cross-references for a series (`schema_version: 2` export only).
 *
 * [mangabaka] is the series' own id and is the one that becomes the tracking link - the v2 export
 * has no `series.id` and no `entry.series_id`.
 */
@Serializable
data class MangabakaExportSource(
    val anilist: Long? = null,
    @SerialName("anime_news_network")
    val animeNewsNetwork: Long? = null,
    val kitsu: Long? = null,
    @SerialName("manga_updates")
    val mangaUpdates: String? = null,
    val mangabaka: Long? = null,
    @SerialName("my_anime_list")
    val myAnimeList: Long? = null,
    val shikimori: Long? = null,
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
    val titles: MangabakaExportTitles? = null,
    @SerialName("total_chapters")
    val totalChapters: JsonPrimitive? = null,
)

/**
 * A series' titles, in either of the two shapes MangaBaka ships.
 *
 * The API names every title as an entry with its language and traits; the site export names the
 * three it picked. The serializer is what lets one DTO accept both, instead of a `JsonElement`
 * leaking into the importer.
 */
@Serializable(with = MangabakaExportTitlesSerializer::class)
sealed interface MangabakaExportTitles {

    /** The API form: one row per known title. */
    data class Entries(val entries: List<MangabakaExportTitle>) : MangabakaExportTitles

    /**
     * The `schema_version: 2` export form. [primary] is the title MangaBaka itself displays, so
     * it is the one a reader would recognise; [romanized] and [native] are fallbacks for a series
     * MangaBaka has no primary title for.
     */
    data class Names(
        val primary: String? = null,
        val native: String? = null,
        val romanized: String? = null,
    ) : MangabakaExportTitles
}

object MangabakaExportTitlesSerializer : KSerializer<MangabakaExportTitles> {
    private val elementSerializer = JsonElement.serializer()

    override val descriptor: SerialDescriptor = elementSerializer.descriptor

    override fun deserialize(decoder: Decoder): MangabakaExportTitles {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("MangaBaka titles must be JSON")
        return when (val element = input.decodeJsonElement()) {
            is JsonArray -> MangabakaExportTitles.Entries(
                input.json.decodeFromJsonElement<List<MangabakaExportTitle>>(element),
            )

            is JsonObject -> MangabakaExportTitles.Names(
                primary = element.stringOrNull("primary"),
                native = element.stringOrNull("native"),
                romanized = element.stringOrNull("romanized"),
            )

            else -> throw SerializationException(
                "MangaBaka titles must be an array or an object, was ${element::class.simpleName}",
            )
        }
    }

    /**
     * Exports are only ever read. Keeping the writer unimplemented is deliberate: a second
     * spelling of this shape would have to be kept in sync with MangaBaka's own, and nothing
     * here ever writes one.
     */
    override fun serialize(encoder: Encoder, value: MangabakaExportTitles) {
        throw SerializationException("MangaBaka titles are decode-only")
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

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
