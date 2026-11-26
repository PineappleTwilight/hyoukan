package mihon.feature.libraryimport.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The set of export adapters this build understands.
 *
 * A plain list rather than a DI multibinding: the app has no set contributions today, and an
 * explicit list is where a reader looks to see what can be imported.
 */
@Inject
@SingleIn(AppScope::class)
class LibraryImportAdapters(
    mangabaka: MangabakaLibraryImporter,
) {
    val all: List<LibraryImportAdapter> = listOf(mangabaka)

    fun byId(id: String): LibraryImportAdapter? = all.firstOrNull { it.id == id }

    /** Picks the adapter that recognises [fileName], falling back to the first that parses. */
    fun byFileName(fileName: String?): LibraryImportAdapter = all.firstOrNull { it.matchesFileName(fileName) } ?: all.first()
}
