package eu.kanade.tachiyomi.data.novel

import java.io.File

/**
 * One user-installed novel plugin script (#104).
 *
 * The identity comes from a header comment rather than from executing the file: a plugin is
 * untrusted code, and the sources list must be buildable at startup without running any of it.
 * Execution happens only when the reader actually browses or opens a chapter.
 *
 * ### File shape
 *
 * ```
 * // @id example-novels
 * // @name Example Novels
 * // @lang en
 * // @version 1
 * // @site https://example.com
 * module.exports = {
 *   popular: function (page) { ... },
 *   search: function (query, page) { ... },
 *   manga: function (url) { ... },
 *   chapters: function (url) { ... },
 *   text: function (url) { ... },
 * }
 * ```
 */
data class NovelPlugin(
    val id: String,
    val name: String,
    val lang: String,
    val version: String,
    val site: String?,
    val file: File,
) {
    /**
     * Stable source id, in a range no extension or built-in source uses.
     *
     * Derived from [id] so a reinstalled plugin keeps the library rows it already owns. The range
     * leaves 100k slots, which is far more plugins than anyone installs and still leaves room
     * above the built-in/extension ids.
     */
    val sourceId: Long get() = NOVEL_PLUGIN_SOURCE_ID_BASE + (stableHash(id) % NOVEL_PLUGIN_SOURCE_ID_SLOTS)

    companion object {
        const val NOVEL_PLUGIN_SOURCE_ID_BASE = 7_000_000_000L
        const val NOVEL_PLUGIN_SOURCE_ID_SLOTS = 100_000L

        /** 64-bit FNV-1a, positive and deterministic across processes. */
        internal fun stableHash(value: String): Long {
            var hash = -0x340d631b7bdddcdbL
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xFF)
                hash *= 0x100000001b3L
            }
            return hash and Long.MAX_VALUE
        }
    }
}

/** Parsed header of a plugin file. Pure so the import screen can reject a file without running it. */
object NovelPluginHeader {

    data class Header(
        val id: String,
        val name: String,
        val lang: String,
        val version: String,
        val site: String?,
    )

    private val directive = Regex("""^\s*//\s*@([A-Za-z]+)\s+(.+?)\s*$""")

    fun parse(script: String): Header? {
        val values = mutableMapOf<String, String>()
        script.lineSequence().take(MAX_HEADER_LINES).forEach { line ->
            val match = directive.find(line) ?: return@forEach
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
            if (key in ALLOWED_KEYS) values[key] = value
        }
        val id = values["id"]?.takeIf { it.matches(IDENTIFIER) } ?: return null
        val name = values["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lang = values["lang"]?.trim()?.takeIf { it.length in 2..8 } ?: "en"
        return Header(
            id = id,
            name = name,
            lang = lang.lowercase(),
            version = values["version"]?.trim() ?: "1",
            site = values["site"]?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        )
    }

    private val IDENTIFIER = Regex("[A-Za-z0-9._-]{1,64}")
    private const val MAX_HEADER_LINES = 40
    private val ALLOWED_KEYS = setOf("id", "name", "lang", "version", "site")
}
