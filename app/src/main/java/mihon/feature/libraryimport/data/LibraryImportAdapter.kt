package mihon.feature.libraryimport.data

import tachiyomi.domain.importer.model.ImportEntry

/**
 * One supported kind of external library export.
 *
 * Everything an export needs to become [ImportEntry]s lives behind this interface, so adding
 * another service is one more implementation plus a line in [LibraryImportAdapters] - the import
 * screens, the storage and the migration flow are all written against the parsed entries only.
 */
interface LibraryImportAdapter {
    /** Stable id stored on the session, e.g. "mangabaka". */
    val id: String

    /** Human-readable name of the export this adapter reads. */
    val displayName: String

    /** Extensions offered in the file picker. */
    val extensions: List<String>

    /**
     * Parses the raw contents of an export file.
     *
     * Bytes rather than text: the Kotatsu-family archives are ZIP files and Aniyomi's backup is
     * gzip-compressed protobuf, so a text reader would not even be able to hand those adapters
     * their input. Text exports decode UTF-8 themselves.
     *
     * Implementations must be tolerant of the shapes their service has shipped over time, and
     * must throw only when the content is not that service's export at all - an empty but valid
     * export is a session with no entries, not an error.
     */
    fun parse(content: ByteArray): List<ImportEntry>

    /**
     * Whether a file with this name plausibly belongs to this adapter, used to pick a default
     * when several adapters could read the same file.
     */
    fun matchesFileName(fileName: String?): Boolean
}
