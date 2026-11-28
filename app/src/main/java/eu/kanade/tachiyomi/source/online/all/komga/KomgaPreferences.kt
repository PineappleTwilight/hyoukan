package eu.kanade.tachiyomi.source.online.all.komga

import android.content.SharedPreferences

/**
 * Per-source preferences for the baked-in Komga source, stored on the standard
 * `source_$id` SharedPreferences file (extension ABI). Keys mirror the official
 * Keiyoushi extension's so the two can share muscle memory, but the class layout
 * is our own and none of it crosses the extension boundary.
 *
 * Mirroring the keys is load-bearing for the **delegated** source (issue #69): when
 * the Keiyoushi extension is installed and delegation is on, the app substitutes
 * `Komga` under the extension's own source id, so the same `source_<id>` file is
 * read. A value the reader set in the extension — a chapter-title format, a
 * scanlator separator, the default libraries — therefore applies to the delegated
 * source as it stands, with no migration.
 */

object KomgaSettingKeys {
    const val ADDRESS = "Address"
    const val USERNAME = "Username"
    const val PASSWORD = "Password"
    const val API_KEY = "API key"
    const val DISPLAY_NAME = "Source display name"

    // KMK --> Ported from the Keiyoushi extension (same spellings, including its
    // "seperator" typo: the key is what an existing install already stored).
    const val CHAPTER_NAME_TEMPLATE = "Chapter name template"
    const val SCANLATOR_SEPARATOR = "Scanlator seperator"
    const val DEFAULT_LIBRARIES = "Default libraries"

    /**
     * The libraries the last successful login saw, serialised as a [LibraryDto] JSON array.
     *
     * A `MultiSelectListPreference` has to know its entries the moment the settings screen
     * opens, and that screen cannot await a network call — so the list is cached here and the
     * dialog is only offered once the cache exists.
     */
    const val LIBRARY_LIST = "library_list"
    // KMK <--
}

/** Configured server address with a single trailing slash removed, or blank when unset. */
fun SharedPreferences.serverBase(): String =
    getString(KomgaSettingKeys.ADDRESS, "")!!.trim().trimEnd('/')

/** Display name for the source list (e.g. "Komga (home)"), or blank. */
fun SharedPreferences.displayName(): String =
    getString(KomgaSettingKeys.DISPLAY_NAME, "")!!.trim()

// KMK -->
/**
 * The chapter-title format, or [KomgaChapterNameTemplate.DEFAULT] when unset.
 *
 * The default is the shape this source has always produced (`{number} - {title}`) rather than
 * the Keiyoushi extension's (`{number} - {title} ({size})`): a user who never opens the setting
 * must not find "(123.4 MB)" appended to every chapter name after an update.
 */
fun SharedPreferences.chapterNameTemplate(): String =
    getString(KomgaSettingKeys.CHAPTER_NAME_TEMPLATE, "")!!
        .takeIf { it.isNotBlank() }
        ?: KomgaChapterNameTemplate.DEFAULT

/**
 * The character used between multiple translator names in the chapter's scanlator field.
 *
 * One character, default `,` — the extension's default, which is also the `", "` join this
 * source used before the setting existed.
 */
fun SharedPreferences.scanlatorSeparator(): String =
    getString(KomgaSettingKeys.SCANLATOR_SEPARATOR, "")!!
        .takeIf { it.length == 1 }
        ?: ","

/** The library ids the reader limited browsing to; empty means every library. */
fun SharedPreferences.defaultLibraries(): Set<String> =
    getStringSet(KomgaSettingKeys.DEFAULT_LIBRARIES, emptySet())!!.toSet()
// KMK <--
