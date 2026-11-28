package mihon.feature.libraryimport.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.track.core.TrackerId
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBakaApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import tachiyomi.domain.importer.model.ImportEntry
import tachiyomi.domain.importer.model.ImportTrackingLink

/**
 * Reads MangaBaka library exports.
 *
 * The export is the reader's own library as MangaBaka has it: each row carries the series (title,
 * cover, authors, tags, publishing status, chapter total) and the reader's own entry (state,
 * progress, rating, note, dates). All of it is mapped onto [ImportEntry]; MangaBaka's own series id
 * becomes the tracking link, which is what lets a migrated entry come back tracked without the
 * reader binding it by hand.
 */
@Inject
@SingleIn(AppScope::class)
class MangabakaLibraryImporter : LibraryImportAdapter {

    override val id: String = ID
    override val displayName: String = "MangaBaka"
    override val extensions: List<String> = listOf("json")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    override fun parse(content: ByteArray): List<ImportEntry> {
        val text = content.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val root = json.parseToJsonElement(text)
        val items = when (root) {
            is JsonArray -> json.decodeFromJsonElement<List<MangabakaExportItem>>(root)
            is JsonObject -> {
                val envelope = json.decodeFromJsonElement<MangabakaExportEnvelope>(root)
                envelope.items()
                    ?: throw IllegalArgumentException("Not a MangaBaka library export: no entry list found")
            }
            else -> throw IllegalArgumentException("Not a MangaBaka library export")
        }

        return items
            .mapNotNull { it.toImportEntry() }
            .distinctBy { it.id }
    }

    override fun matchesFileName(fileName: String?): Boolean {
        val name = fileName?.lowercase() ?: return false
        return name.endsWith(".json") && (name.contains("mangabaka") || name.contains("manga_baka"))
    }

    private fun MangabakaExportItem.toImportEntry(): ImportEntry? {
        val series = this.series
        val entry = this.entry
        // schema_version 2 moves the series id from `series.id`/`entry.series_id` to
        // `source.mangabaka`; both forms end up as the tracking link.
        val resolvedSeriesId = series?.id ?: entry?.seriesId ?: seriesId ?: source?.mangabaka ?: id

        val title = (series?.titles ?: titles).chooseBestTitle() ?: title?.takeIf { it.isNotBlank() }
        if (title.isNullOrBlank()) return null

        val resolvedState = entry?.state ?: state
        val progress = entry?.progressChapter.doubleOrNull() ?: progressChapter.doubleOrNull() ?: 0.0
        val progressVolume = entry?.progressVolume.doubleOrNull() ?: progressVolume.doubleOrNull() ?: 0.0
        val rating = entry?.rating.doubleOrNull() ?: rating.doubleOrNull()
        val note = entry?.note?.takeIf { it.isNotBlank() } ?: note?.takeIf { it.isNotBlank() }
        val startDate = entry?.startDate ?: startDate
        val finishDate = entry?.finishDate ?: finishDate
        val isPrivate = entry?.isPrivate ?: isPrivate ?: false

        val coverUrl = (series?.cover ?: cover)?.bestUrl()?.takeIf { it.isNotBlank() }
        val authors = (series?.authors ?: authors)?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
        val artists = (series?.artists ?: artists)?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
        val tags = (series?.tags ?: tags).orEmpty().filter { it.isNotBlank() }
        val description = (series?.description ?: description)?.trim()?.takeIf { it.isNotBlank() }
        val publishingStatus = series?.status ?: status
        val type = series?.type ?: type
        val totalChapters = (series?.totalChapters ?: totalChapters).doubleOrNull()?.toLong() ?: 0L
        val canonicalUrl = (series?.canonicalUrl ?: canonicalUrl)?.takeIf { it.isNotBlank() }
            ?: resolvedSeriesId?.let { "${MangaBakaApi.BASE_URL}/$it" }

        val entryId = resolvedSeriesId?.toString()
            ?: "title:${title.lowercase()}"

        val trackingLink = resolvedSeriesId?.let { seriesIdValue ->
            ImportTrackingLink(
                trackerId = TrackerId.MANGABAKA,
                remoteId = seriesIdValue,
                remoteUrl = canonicalUrl,
                status = resolvedState,
                score = rating,
                progressChapter = progress,
                startDate = startDate,
                finishDate = finishDate,
                isPrivate = isPrivate,
            )
        }

        return ImportEntry(
            id = entryId,
            title = title,
            author = authors?.joinToString(", "),
            artist = artists?.joinToString(", "),
            description = description,
            coverUrl = coverUrl,
            tags = tags,
            status = publishingStatus,
            type = type,
            totalChapters = totalChapters,
            progressChapter = progress,
            progressVolume = progressVolume,
            rating = rating,
            note = note,
            startDate = startDate,
            finishDate = finishDate,
            seriesUrl = canonicalUrl,
            trackingLinks = listOfNotNull(trackingLink),
        )
    }

    private fun MangabakaExportTitles?.chooseBestTitle(): String? = when (this) {
        null -> null
        is MangabakaExportTitles.Entries -> entries.chooseBestTitle()
        // The site export already names the title it shows first; romanized/native are only
        // fallbacks for a series with no primary title.
        is MangabakaExportTitles.Names -> primary?.takeIf { it.isNotBlank() }
            ?: romanized?.takeIf { it.isNotBlank() }
            ?: native?.takeIf { it.isNotBlank() }
    }

    private fun List<MangabakaExportTitle>.chooseBestTitle(): String? {
        if (isEmpty()) return null
        val bestPerLanguage = TITLE_PRIORITIES.associateWith { language ->
            this.filter { it.language == language }
                .minByOrNull {
                    when {
                        it.isPrimary -> 0
                        "official" in it.traits -> 1
                        "native" in it.traits -> 2
                        else -> 3
                    }
                }
        }
        return TITLE_PRIORITIES.firstNotNullOfOrNull { bestPerLanguage[it]?.title }
            ?: this.first().title
    }

    private fun JsonPrimitive?.doubleOrNull(): Double? = this?.content?.toDoubleOrNull()

    companion object {
        const val ID = "mangabaka"

        private val TITLE_PRIORITIES = listOf("en", "ja-Latn", "ja", "ko-Latn", "ko", "zh-Latn", "zh")
    }
}
