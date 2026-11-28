// KMK -->
package eu.kanade.tachiyomi.source.online.all.komga

/**
 * The chapter-title format ported from the Keiyoushi Komga extension.
 *
 * The extension renders it with Apache Commons Text's `StringSubstitutor`; the app does not ship
 * that library, so this is the same substitution in a few dozen lines, with the same semantics the
 * extension documents:
 *
 *  - `{name}` is replaced by the value for `name`;
 *  - `${name}` is an escaped brace and renders literally as `{name}`;
 *  - a name the template does not know makes the format *invalid* (the settings dialog refuses it)
 *    rather than being rendered as `{tile}` on every chapter for ever;
 *  - everything else, including a stray `{` with no closing brace, is left as written.
 *
 * Pure: no Android import, so the rules are asserted by a plain unit test instead of on a device.
 * What the values mean is [Komga.chapterName]'s business — this object only substitutes.
 */
internal object KomgaChapterNameTemplate {

    /**
     * The shape this source has always produced.
     *
     * Deliberately **not** the extension's default (`{number} - {title} ({size})`): the size
     * suffix would appear on every chapter of every existing library after an update, for a user
     * who never touched the setting. A reader who wants it can type it; a reader coming from the
     * extension already has it stored under the same key.
     */
    const val DEFAULT = "{number} - {title}"

    /** Placeholder names the template understands, in the order the settings dialog lists them. */
    val PLACEHOLDERS: List<String> = listOf(
        "title",
        "seriesTitle",
        "number",
        "createdDate",
        "releaseDate",
        "size",
        "sizeBytes",
    )

    /**
     * Substitutes [values] into [template].
     *
     * A placeholder with no value in [values] is left exactly as written: the dialog validates
     * before storing, so this only happens for a hand-edited preference, and leaving the text
     * visible is more honest than rendering an empty string.
     */
    fun render(template: String, values: Map<String, String>): String {
        if (template.isEmpty()) return ""
        val out = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '$' && i + 1 < template.length && template[i + 1] == '{') {
                // Escaped brace: the extension's `${series}` spelling for a literal `{series}`.
                out.append('{')
                i += 2
                continue
            }
            if (c == '{') {
                val end = template.indexOf('}', startIndex = i + 1)
                if (end > i) {
                    val name = template.substring(i + 1, end)
                    val value = values[name]
                    if (value != null) {
                        out.append(value)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /**
     * Whether the settings dialog may store [template].
     *
     * Every `{…}` that is not escaped must name a placeholder above. A blank format is invalid:
     * it would render every chapter as the empty string, and the app trims a blank chapter name
     * away entirely.
     */
    fun isRenderable(template: String): Boolean {
        if (template.isBlank()) return false
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '$' && i + 1 < template.length && template[i + 1] == '{') {
                i += 2
                continue
            }
            if (c == '{') {
                val end = template.indexOf('}', startIndex = i + 1)
                if (end > i) {
                    val name = template.substring(i + 1, end)
                    if (name !in PLACEHOLDERS) return false
                    i = end + 1
                    continue
                }
            }
            i++
        }
        return true
    }
}
// KMK <--
