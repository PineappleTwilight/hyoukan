package mihon.feature.libraryimport.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.importer.model.ImportEntry

/**
 * The set of export adapters this build understands.
 *
 * A plain list rather than a DI multibinding: the app has no set contributions today, and an
 * explicit list is where a reader looks to see what can be imported.
 *
 * The Kototoro, Usagi and Aniyomi adapters are opt-in (#106) and appear only while
 * [BackupPreferences.libraryInterop] is on, so the default import path accepts exactly what it
 * always did.
 */
@Inject
@SingleIn(AppScope::class)
class LibraryImportAdapters(
    mangabaka: MangabakaLibraryImporter,
    kototoro: KototoroLibraryImporter,
    usagi: UsagiLibraryImporter,
    aniyomi: AniyomiLibraryImporter,
    private val backupPreferences: BackupPreferences,
) {
    val all: List<LibraryImportAdapter> = buildList {
        add(mangabaka)
        if (backupPreferences.libraryInterop().get()) {
            add(kototoro)
            add(usagi)
            add(aniyomi)
        }
    }

    fun byId(id: String): LibraryImportAdapter? = all.firstOrNull { it.id == id }

    /** Picks the adapter that recognises [fileName], falling back to the first that parses. */
    fun byFileName(fileName: String?): LibraryImportAdapter = all.firstOrNull { it.matchesFileName(fileName) } ?: all.first()

    /**
     * Parses [content] with the adapter whose file name matches first, then every other adapter.
     *
     * The fallback matters for archives with opaque names (`backup_2026.zip`): the picker offers
     * no format choice, so the file has to identify itself through its own content. Only when
     * every adapter refuses the bytes is the first failure raised, which keeps the import screen's
     * "not a readable export" message truthful.
     */
    fun parse(fileName: String?, content: ByteArray): Pair<LibraryImportAdapter, List<ImportEntry>> {
        val preferred = byFileName(fileName)
        val ordered = listOf(preferred) + all.filterNot { it === preferred }
        var firstFailure: Throwable? = null
        for (adapter in ordered) {
            runCatching { adapter.parse(content) }
                .onSuccess { return adapter to it }
                .onFailure { if (firstFailure == null) firstFailure = it }
        }
        throw firstFailure ?: IllegalArgumentException("No import adapter accepted the file")
    }
}
