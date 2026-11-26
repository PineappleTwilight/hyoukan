package tachiyomi.domain.importer.model

import kotlinx.serialization.Serializable

/**
 * A resumable import of an external library export.
 *
 * The session owns the parsed [entries] and records which of them have been migrated into the
 * library or deliberately skipped, which is what makes an import survivable across app restarts:
 * re-opening the session continues exactly where the reader stopped.
 *
 * Sessions are generic. [sourceId] names the adapter that produced the entries, so another
 * service's export can be imported through the same screens and storage.
 */
@Serializable
data class ImportSession(
    val id: String,
    /** Shown in the session list; normally the export's file name or service name. */
    val name: String,
    /** Id of the importer adapter that parsed this export ("mangabaka"). */
    val sourceId: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val entries: List<ImportEntry> = emptyList(),
    val importedEntryIds: Set<String> = emptySet(),
    val skippedEntryIds: Set<String> = emptySet(),
) {
    val total: Int
        get() = entries.size

    val importedCount: Int
        get() = entries.count { it.id in importedEntryIds }

    val skippedCount: Int
        get() = entries.count { it.id in skippedEntryIds }

    /** Entries that still need a decision, in export order. */
    val pendingEntries: List<ImportEntry>
        get() = entries.filterNot { it.id in importedEntryIds || it.id in skippedEntryIds }

    val pendingCount: Int
        get() = entries.size - importedEntryIds.size - skippedEntryIds.size

    /** 0f..1f, finished entries over all entries. */
    val progress: Float
        get() = if (entries.isEmpty()) 0f else (importedEntryIds.size + skippedEntryIds.size).toFloat() / entries.size

    val isComplete: Boolean
        get() = entries.isNotEmpty() && pendingCount == 0

    fun isImported(entryId: String): Boolean = entryId in importedEntryIds

    fun isSkipped(entryId: String): Boolean = entryId in skippedEntryIds

    /** Marks [entryId] migrated into the library, dropping it from the skipped set if it was there. */
    fun withImported(entryId: String): ImportSession = copy(
        importedEntryIds = importedEntryIds + entryId,
        skippedEntryIds = skippedEntryIds - entryId,
        updatedAt = System.currentTimeMillis(),
    )

    /** Marks [entryId] skipped, dropping it from the imported set if it was there. */
    fun withSkipped(entryId: String): ImportSession = copy(
        skippedEntryIds = skippedEntryIds + entryId,
        importedEntryIds = importedEntryIds - entryId,
        updatedAt = System.currentTimeMillis(),
    )

    /** Returns [entryId] to the pending list, dropping it from both done sets. */
    fun withPending(entryId: String): ImportSession = copy(
        importedEntryIds = importedEntryIds - entryId,
        skippedEntryIds = skippedEntryIds - entryId,
        updatedAt = System.currentTimeMillis(),
    )
}
