package tachiyomi.source.local

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.EntryType
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextSearchHit
import eu.kanade.tachiyomi.source.text.TocEntry
import logcat.LogPriority
import mihon.core.archive.PlainBookConverter
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves `.fb2` and `.txt` chapters as books (#104).
 *
 * ### Why not fold this into the EPUB adapter
 *
 * EPUB is a container the archive engine owns: leases, spine order, publisher stylesheets. FB2 and
 * TXT are single files with no container at all, so the only shared part is the output shape -
 * `TextPage` with a complete HTML document. That conversion lives in
 * [mihon.core.archive.PlainBookConverter]; this adapter is the file plumbing around it.
 *
 * ### Caching
 *
 * Parsing a whole novel on every page turn would be unacceptable, so the parsed sections are kept
 * per chapter URL and invalidated by the file's size and mtime. Two parses of the same unchanged
 * file share one list; replacing the file on disk is picked up without a refresh.
 */
internal class LocalPlainBookAdapter(
    private val fileSystem: LocalSourceFileSystem,
) {

    private data class Parsed(val stamp: Long, val sections: List<PlainBookConverter.Section>)

    private val cache = ConcurrentHashMap<String, Parsed>()

    /** True when [chapter] is one of the plain formats this adapter owns. */
    fun handles(chapter: SChapter): Boolean {
        val file = locate(chapter) ?: return false
        return file.isPlainBook()
    }

    fun textPages(chapter: SChapter): List<TextPage> = pages(parse(chapter), chapter)

    fun textPageIndex(chapter: SChapter): List<TextPage> =
        pages(parse(chapter), chapter).map { it.copy(text = "") }

    fun textPageText(chapter: SChapter, page: TextPage): String? =
        parse(chapter).getOrNull(page.index)?.html

    fun tableOfContents(chapter: SChapter): List<TocEntry> =
        parse(chapter).mapIndexedNotNull { index, section ->
            val title = section.title ?: return@mapIndexedNotNull null
            TocEntry(
                label = title,
                href = "${chapter.url}#$index",
                pageIndex = index,
            )
        }

    fun searchInChapter(chapter: SChapter, query: String): List<TextSearchHit> {
        if (query.isBlank()) return emptyList()
        val regex = runCatching { Regex(Regex.escape(query), RegexOption.IGNORE_CASE) }.getOrNull()
            ?: return emptyList()
        return buildList {
            parse(chapter).forEachIndexed { index, section ->
                regex.findAll(section.plainText).forEach { match ->
                    val start = (match.range.first - SNIPPET_CONTEXT).coerceAtLeast(0)
                    val end = (match.range.last + SNIPPET_CONTEXT).coerceAtMost(section.plainText.length)
                    add(
                        TextSearchHit(
                            pageIndex = index,
                            snippet = section.plainText.substring(start, end),
                            matchOffset = match.range.first,
                            documentOffset = start,
                            documentLength = section.plainText.length,
                        ),
                    )
                }
            }
        }
    }

    /** Whether a series folder consists entirely of prose books this adapter can read. */
    fun entryTypeOf(seriesUrl: String): EntryType? {
        val entry = fileSystem.getBaseDirectory()?.findFile(seriesUrl) ?: return null
        val files = if (entry.isDirectory) {
            entry.listFiles().orEmpty()
                .filter { !it.isDirectory && !it.name.orEmpty().startsWith('.') }
        } else {
            listOf(entry)
        }
        if (files.isEmpty()) return null
        return if (files.all { it.isPlainBook() }) EntryType.BOOK else null
    }

    fun detectBookEntry(chapter: SChapter): Boolean = locate(chapter)?.isPlainBook() == true

    fun release(chapterUrl: String) {
        cache.remove(chapterUrl)
    }

    fun releaseAll() {
        cache.clear()
    }

    // ---------------------------------------------------------------- files

    private fun pages(
        sections: List<PlainBookConverter.Section>,
        chapter: SChapter,
    ): List<TextPage> = sections.mapIndexed { index, section ->
        TextPage(
            index = index,
            url = "${chapter.url}#$index",
            text = section.html,
            // The documents carry no relative resources, so there is no base to resolve against.
            baseHref = null,
        )
    }

    private fun parse(chapter: SChapter): List<PlainBookConverter.Section> {
        val file = locate(chapter) ?: return emptyList()
        val stamp = file.lastModified() * 31L + file.length()
        cache[chapter.url]?.takeIf { it.stamp == stamp }?.let { return it.sections }

        val bytes = try {
            file.openInputStream()?.use { it.readUpTo(MAX_BOOK_BYTES) }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "LocalPlainBook: could not read ${chapter.url}" }
            null
        } ?: return emptyList()

        val text = PlainBookConverter.decode(bytes)
        val sections = if (file.extension.equals("fb2", ignoreCase = true)) {
            PlainBookConverter.fb2Sections(text)
        } else {
            PlainBookConverter.plainTextSections(text)
        }
        cache[chapter.url] = Parsed(stamp, sections)
        return sections
    }

    private fun locate(chapter: SChapter): UniFile? {
        val parts = chapter.url.split('/', limit = 2)
        if (parts.size != 2) return null
        return fileSystem.findChapterFile(parts[0], parts[1])
    }

    private fun UniFile.isPlainBook(): Boolean {
        val ext = extension ?: return false
        return ext.equals("fb2", ignoreCase = true) || ext.equals("txt", ignoreCase = true)
    }

    private fun InputStream.readUpTo(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > max) throw IOException("Book exceeds the $max byte limit")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_BOOK_BYTES = 64 * 1024 * 1024
        const val SNIPPET_CONTEXT = 60
    }
}
