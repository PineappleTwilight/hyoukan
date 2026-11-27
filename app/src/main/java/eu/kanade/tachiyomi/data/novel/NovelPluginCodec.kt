package eu.kanade.tachiyomi.data.novel

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.text.TextPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a plugin's JSON answer into the app's models (#104).
 *
 * Kept separate from the QuickJS runtime so the mapping - the part with the edge cases - can be
 * tested on the JVM. A plugin is free to omit every optional field: a catalogue entry needs a
 * title and a url, a chapter needs a name and a url, and a book needs at least one document.
 */
object NovelPluginCodec {

    fun mangas(json: String?): List<SManga> {
        val array = jsonArray(json) ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = item.string("url") ?: return@mapNotNull null
            val title = item.string("title") ?: return@mapNotNull null
            SManga.create().apply {
                this.url = url
                this.title = title
                thumbnail_url = item.string("cover") ?: item.string("thumbnail")
                author = item.string("author")
                artist = item.string("artist")
                description = item.string("description")
                genre = item.string("genre")
                initialized = true
            }
        }
    }

    fun chapters(json: String?): List<SChapter> {
        val array = jsonArray(json) ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = item.string("url") ?: return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            SChapter.create().apply {
                this.url = url
                this.name = name
                chapter_number = item.element("chapterNumber")?.jsonPrimitive?.floatOrNull ?: -1f
                date_upload = item.element("dateUpload")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            }
        }
    }

    /**
     * A chapter's readable documents.
     *
     * Accepts a single HTML string or an array of them: a plugin that has the whole chapter in one
     * page should not have to wrap it in an array, and one that splits by scene returns the array
     * directly.
     */
    fun pages(json: String?, chapterUrl: String): List<TextPage> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        val documents = when (root) {
            is JsonPrimitive -> if (root.isString) listOfNotNull(root.contentOrNull) else emptyList()
            is JsonArray -> root.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            }
            else -> emptyList()
        }
        return documents.mapIndexed { index, html ->
            TextPage(
                index = index,
                // Stable within the chapter, like an EPUB spine path: the offset survives edits
                // to earlier documents only by index, which is the best identity text can offer.
                url = "$chapterUrl#$index",
                text = html,
            )
        }
    }

    private fun jsonArray(json: String?): JsonArray? {
        if (json.isNullOrBlank()) return null
        return runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull()
    }

    private fun JsonObject.element(name: String): JsonElement? = this[name]

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
