package mihon.feature.libraryimport.model

/**
 * What a migrated import entry carries over from the export.
 *
 * Deliberately a small set: an import entry is a shell, so there is no chapter list, category or
 * custom cover to copy. What it does have is how far the reader got, their note, and the fact that
 * the series is already in their tracking list.
 */
enum class ImportMigrateFlag {
    /** Mark the target's chapters read up to the export's progress. */
    CHAPTER,

    /** Attach the tracking link the export already had, when that tracker is logged in. */
    TRACK,

    /** Carry the export's note over to the new library entry. */
    NOTES,
}
