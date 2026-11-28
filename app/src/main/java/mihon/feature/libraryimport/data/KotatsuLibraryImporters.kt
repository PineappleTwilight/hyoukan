package mihon.feature.libraryimport.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.export.LibraryInteropFormat
import eu.kanade.tachiyomi.data.track.core.TrackerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.importer.model.ImportEntry
import tachiyomi.domain.importer.model.ImportTrackingLink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Reads Kototoro and Usagi backups (#106).
 *
 * Both apps descend from Kotatsu and share its ZIP-of-JSON archive; the two adapters differ only
 * in the file names they claim and the label shown in the import list. The archive itself is read
 * by [LibraryInteropFormat].
 */
@SingleIn(AppScope::class)
@Inject
class KototoroLibraryImporter : LibraryImportAdapter {
    override val id: String = "kototoro"
    override val displayName: String = "Kototoro"
    override val extensions: List<String> = listOf("zip", "bk")

    override fun parse(content: ByteArray): List<ImportEntry> {
        val library = LibraryInteropFormat.decode(content)
        return library.toImportEntries()
    }

    override fun matchesFileName(fileName: String?): Boolean {
        val name = fileName?.lowercase() ?: return false
        val archive = name.endsWith(".zip") || name.endsWith(".bk") || name.endsWith(".bk.zip")
        return archive && (name.contains("kototoro") || name.contains("kotatsu"))
    }
}

@SingleIn(AppScope::class)
@Inject
class UsagiLibraryImporter : LibraryImportAdapter {
    override val id: String = "usagi"
    override val displayName: String = "Usagi"
    override val extensions: List<String> = listOf("zip", "bk")

    override fun parse(content: ByteArray): List<ImportEntry> {
        val library = LibraryInteropFormat.decode(content)
        return library.toImportEntries()
    }

    override fun matchesFileName(fileName: String?): Boolean {
        val name = fileName?.lowercase() ?: return false
        val archive = name.endsWith(".zip") || name.endsWith(".bk") || name.endsWith(".bk.zip")
        return archive && name.contains("usagi")
    }
}

/**
 * Reads Aniyomi's protobuf backup (#106).
 *
 * Aniyomi writes the Mihon protobuf shape (gzip-compressed) and adds an anime list at field 501.
 * Both lists are imported: an anime entry is still a series with a title, source, url and cover,
 * which is exactly what an import entry is. Only the fields the migration flow consumes are
 * declared here; everything else in the message is skipped by the protobuf decoder.
 */
@SingleIn(AppScope::class)
@Inject
class AniyomiLibraryImporter : LibraryImportAdapter {
    override val id: String = "aniyomi"
    override val displayName: String = "Aniyomi"
    override val extensions: List<String> = listOf("tachibk", "gz")

    override fun parse(content: ByteArray): List<ImportEntry> {
        if (content.size > MAX_ARCHIVE_BYTES) {
            throw IllegalArgumentException("Aniyomi backup is larger than $MAX_ARCHIVE_BYTES bytes")
        }
        val raw = if (content.isGzip()) gunzip(content) else content
        val backup = runCatching { ProtoBuf.decodeFromByteArray<AniyomiBackup>(raw) }
            .getOrElse { throw IllegalArgumentException("Not an Aniyomi backup", it) }
        val entries = (
            backup.manga.map { it.toImportEntry(type = "manga") } +
                backup.anime.map { it.toImportEntry(type = "anime") }
            )
            .filter { it.title.isNotBlank() && it.extra["url"].orEmpty().isNotBlank() }
        if (entries.isEmpty()) {
            throw IllegalArgumentException("Not an Aniyomi backup: no entries found")
        }
        return entries.distinctBy { it.id }
    }

    override fun matchesFileName(fileName: String?): Boolean {
        val name = fileName?.lowercase() ?: return false
        return (name.endsWith(".tachibk") || name.endsWith(".bak") || name.endsWith(".gz")) &&
            (name.contains("aniyomi") || name.contains("anime"))
    }

    private fun ByteArray.isGzip(): Boolean =
        size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()

    private fun gunzip(bytes: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = gzip.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_DECODED_BYTES) {
                    throw IllegalArgumentException("Aniyomi backup expands past $MAX_DECODED_BYTES bytes")
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun AniyomiEntry.toImportEntry(type: String): ImportEntry {
        val lastReadAt = history.maxOfOrNull { it.lastRead } ?: 0L
        return ImportEntry(
            id = "$type:$source:$url",
            title = title,
            author = author,
            artist = artist,
            description = description,
            coverUrl = thumbnailUrl?.takeIf { it.isNotBlank() },
            tags = genre,
            status = statusName(status),
            type = type,
            rating = null,
            seriesUrl = url,
            trackingLinks = tracking.mapNotNull { it.toImportTrackingLink() },
            extra = buildMap {
                put("url", url)
                put("source", source.toString())
                if (lastReadAt > 0) put("lastReadAt", lastReadAt.toString())
            },
        )
    }

    private fun AniyomiTracking.toImportTrackingLink(): ImportTrackingLink? {
        // Aniyomi's sync ids follow the Mihon order; this app's own TrackerId values differ for
        // the trackers after Kitsu, so the mapping has to be explicit rather than identity.
        val trackerId = when (syncId) {
            1 -> TrackerId.MYANIMELIST
            2 -> TrackerId.ANILIST
            3 -> TrackerId.KITSU
            4 -> TrackerId.MANGA_UPDATES
            5 -> TrackerId.SHIKIMORI
            6 -> TrackerId.BANGUMI
            else -> return null
        }
        val remote = mediaId.takeIf { it != 0L } ?: mediaIdInt.toLong()
        if (remote == 0L) return null
        return ImportTrackingLink(
            trackerId = trackerId,
            remoteId = remote,
            remoteUrl = trackingUrl.takeIf { it.isNotBlank() },
            status = null,
            score = score.toDouble().takeIf { it > 0.0 },
            progressChapter = lastEpisodeSeen.toDouble(),
            startDate = startedWatchingDate.takeIf { it > 0 }?.toString(),
            finishDate = finishedWatchingDate.takeIf { it > 0 }?.toString(),
            isPrivate = private,
        )
    }

    private fun statusName(status: Int): String = when (status) {
        1 -> "ongoing"
        2 -> "completed"
        3 -> "licensed"
        4 -> "published"
        5 -> "cancelled"
        6 -> "on_hiatus"
        else -> "unknown"
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
        const val MAX_DECODED_BYTES = 512L * 1024 * 1024
    }
}

@Serializable
private data class AniyomiBackup(
    @ProtoNumber(1) val manga: List<AniyomiEntry> = emptyList(),
    @ProtoNumber(501) val anime: List<AniyomiEntry> = emptyList(),
)

@Serializable
private data class AniyomiEntry(
    @ProtoNumber(1) val source: Long = 0,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val title: String = "",
    @ProtoNumber(4) val artist: String? = null,
    @ProtoNumber(5) val author: String? = null,
    @ProtoNumber(6) val description: String? = null,
    @ProtoNumber(7) val genre: List<String> = emptyList(),
    @ProtoNumber(8) val status: Int = 0,
    @ProtoNumber(9) val thumbnailUrl: String? = null,
    @ProtoNumber(13) val dateAdded: Long = 0,
    @ProtoNumber(18) val tracking: List<AniyomiTracking> = emptyList(),
    @ProtoNumber(100) val favorite: Boolean = true,
    @ProtoNumber(104) val history: List<AniyomiHistory> = emptyList(),
)

@Serializable
private data class AniyomiHistory(
    @ProtoNumber(1) val url: String = "",
    @ProtoNumber(2) val lastRead: Long = 0,
    @ProtoNumber(3) val readDuration: Long = 0,
)

@Serializable
private data class AniyomiTracking(
    @ProtoNumber(1) val syncId: Int = 0,
    @ProtoNumber(2) val libraryId: Long = 0,
    @ProtoNumber(3) val mediaIdInt: Int = 0,
    @ProtoNumber(4) val trackingUrl: String = "",
    @ProtoNumber(5) val title: String = "",
    @ProtoNumber(6) val lastEpisodeSeen: Float = 0f,
    @ProtoNumber(7) val totalEpisodes: Int = 0,
    @ProtoNumber(8) val score: Float = 0f,
    @ProtoNumber(9) val status: Int = 0,
    @ProtoNumber(10) val startedWatchingDate: Long = 0,
    @ProtoNumber(11) val finishedWatchingDate: Long = 0,
    @ProtoNumber(12) val private: Boolean = false,
    @ProtoNumber(100) val mediaId: Long = 0,
)
