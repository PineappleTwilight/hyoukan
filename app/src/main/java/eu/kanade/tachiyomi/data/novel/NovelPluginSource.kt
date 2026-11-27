package eu.kanade.tachiyomi.data.novel

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextSource

/**
 * A browseable source backed by one user-installed JavaScript plugin (#104).
 *
 * ### What it looks like to the rest of the app
 *
 * An ordinary [CatalogueSource] plus [TextSource], so browse, search, library updates, tracking,
 * downloads and the novel reader all work without knowing a plugin exists. The only difference is
 * where the data comes from: every method is one sandboxed call into [NovelPluginRuntime].
 *
 * The plugin's answer shapes are documented in [NovelPlugin]; the mapping lives in
 * [NovelPluginCodec] and is deliberately forgiving, because a third-party script that omits an
 * optional field should still browse rather than disappear.
 */
class NovelPluginSource(
    private val plugin: NovelPlugin,
    private val runtime: NovelPluginRuntime,
) : CatalogueSource,
    TextSource {

    override val id: Long = plugin.sourceId
    override val name: String = plugin.name
    override val lang: String = plugin.lang
    override val supportsLatest: Boolean = true

    override fun getFilterList(): FilterList = FilterList()

    override suspend fun getPopularManga(page: Int): MangasPage =
        page(page = page, method = "popular", args = listOf(page.toString()))

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        page(page = page, method = "latest", args = listOf(page.toString()))

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        page(page = page, method = "search", args = listOf(query, page.toString()))

    private suspend fun page(page: Int, method: String, args: List<String>): MangasPage {
        val json = runtime.invoke(plugin, method, args)
        val mangas = NovelPluginCodec.mangas(json)
        // A plugin that returns fewer entries than a full page is the only end-of-list signal the
        // API has; there is no page-count metadata to trust.
        return MangasPage(mangas, hasNextPage = mangas.isNotEmpty())
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = if (fetchDetails) {
            val detailJson = runtime.invoke(plugin, "manga", listOf(manga.url))
            NovelPluginCodec.mangas("[${detailJson.orEmpty()}]").firstOrNull() ?: manga
        } else {
            manga
        }
        val list = if (fetchChapters) {
            NovelPluginCodec.chapters(runtime.invoke(plugin, "chapters", listOf(manga.url)))
        } else {
            chapters
        }
        return SMangaUpdate(details, list)
    }

    override suspend fun getTextPageList(chapter: SChapter): List<TextPage> =
        NovelPluginCodec.pages(
            runtime.invoke(plugin, "text", listOf(chapter.url)),
            chapter.url,
        )

    override suspend fun getPageList(chapter: SChapter): List<Page> =
        throw UnsupportedOperationException("Novel plugin chapters are text, not images")
}
