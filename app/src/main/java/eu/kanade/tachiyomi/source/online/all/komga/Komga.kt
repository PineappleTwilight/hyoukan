package eu.kanade.tachiyomi.source.online.all.komga

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.download.DownloadableTextSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.EntryType
import eu.kanade.tachiyomi.source.model.EntryTypeSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MixedContentSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.source.text.TextBaseUrlSource
import eu.kanade.tachiyomi.source.text.TextCapabilities
import eu.kanade.tachiyomi.source.text.TextIncrementalSource
import eu.kanade.tachiyomi.source.text.TextPage
import eu.kanade.tachiyomi.source.text.TextResourceSource
import eu.kanade.tachiyomi.source.text.TextSearchHit
import eu.kanade.tachiyomi.source.text.TextSearchSource
import eu.kanade.tachiyomi.source.text.TextTocSource
import eu.kanade.tachiyomi.source.text.TocEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mihon.app.di.globalAppGraph
import okhttp3.Headers
import rx.Observable
import tachiyomi.core.common.util.lang.runAsObservable
import tachiyomi.core.common.util.system.logcat

/**
 * Baked-in Komga source (Komga server, https://komga.org). One server per source instance;
 * additional servers can run the Keiyoushi Komga extension (the enhanced tracker accepts both).
 *
 * What it serves:
 * - the server's **series**, every one of them either comic- or book-shaped depending on its
 *   books; browsing shows them all, EPUBs included — the extension hides the non-Divina ones
 * - chapters are the series' books; a book whose media profile is `EPUB` (and not marked
 *   Divina-compatible) opens in the built-in epub reader, anything else as image pages
 * - read progress is synced by the app's Komga enhanced tracker (series-level, same API the
 *   extension uses); a book counted as read here is counted as read there
 *
 * Implementation notes:
 * - browsing is paged through the REST API directly; thumbnails and pages stream through the
 *   shared OkHttp client (API key as a per-request header / Basic challenge answer)
 * - the epub half downloads the book once to the app cache (`komga/<id>-<hash>.epub`) and then
 *   hands it to the in-house EpubTextSource, exactly like the local manga source's epub half
 */
class Komga(
    private val context: android.content.Context,
    private val sourceId: Long,
    private val sourceLang: String = "all",
) : HttpSource(),
    ConfigurableSource,
    UnmeteredSource,
    MixedContentSource,
    EntryTypeSource,
    eu.kanade.tachiyomi.source.text.TextSource,
    eu.kanade.tachiyomi.source.text.EpubArchiveSource,
    TextIncrementalSource,
    TextTocSource,
    TextSearchSource,
    TextResourceSource,
    TextBaseUrlSource,
    DownloadableTextSource {

    // ------------------------------------------------------------ basics

    override val name: String
        get() = prefs().displayName().ifBlank { "Komga" }

    override val id: Long get() = sourceId

    override val lang: String get() = sourceLang

    override val supportsLatest = true

    private fun prefs(): SharedPreferences = sourcePreferences()

    private val baseUrlValue: String
        get() = prefs().serverBase()

    override val baseUrl: String
        get() = baseUrlValue

    private val userAgent = "Houri v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"

    override val headers: Headers by lazy { KomgaClient.defaultHeaders(prefs(), userAgent) }

    override val client: okhttp3.OkHttpClient by lazy { KomgaClient.client(prefs(), globalAppGraph.networkHelper, userAgent) }

    private val json: Json get() = globalAppGraph.json

    // ------------------------------------------------------------ browse

    private fun pageUrl(path: String, params: List<Pair<String, String>>): String {
        val query = params.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
        return baseUrlValue + path + if (query.isEmpty()) "" else "?$query"
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private suspend fun getSeriesPage(page: Int, query: String, sort: String): MangasPage {
        check(!baseUrlValue.isBlank()) { "Set the server address in this source's settings" }
        // KMK --> The Keiyoushi extension's "Default libraries" setting, applied the same way it
        // applies its search conditions: repeated `library_id` parameters narrow the list, and an
        // empty selection means every library (the unset state).
        val libraries = prefs().defaultLibraries()
        // KMK <--
        val url = pageUrl(
            "/api/v1/series",
            buildList {
                add("page" to (page - 1).coerceAtLeast(0).toString())
                add("deleted" to "false")
                add("size" to "25")
                if (query.isNotBlank()) add("search" to query)
                add("sort" to sort)
                libraries.forEach { add("library_id" to it) }
            },
        )
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val wrapper = with(json) { response.parseAs<PageWrapperDto<SeriesDto>>() }
        return MangasPage(wrapper.content.map { it.toManga() }, !wrapper.last)
    }

    override suspend fun getPopularManga(page: Int): MangasPage =
        getSeriesPage(page, "", "metadata.titleSort,asc")

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getSeriesPage(page, "", "lastModifiedDate,desc")

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        getSeriesPage(page, query, "metadata.titleSort,asc")

    // The combined-suspend `getMangaUpdate` lives on CatalogueSource and drives
    // these through the Rx bridge, so both halves answer here and the bridge
    // has nothing to duplicate.
    @Deprecated("Use the suspend API instead", ReplaceWith("getMangaUpdate"))
    @Suppress("DEPRECATION")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        runAsObservable { getMangaDetails(manga) }

    suspend fun getMangaDetails(manga: SManga): SManga {
        val response = client.newCall(GET(manga.url, headers)).awaitSuccess()
        val dto = with(json) { response.parseAs<SeriesDto>() }
        return dto.toManga()
    }

    // ------------------------------------------------------------ chapters

    @Deprecated("Use the suspend API instead", ReplaceWith("getMangaUpdate"))
    @Suppress("DEPRECATION")
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        runAsObservable { getChapterList(manga) }

    suspend fun getChapterList(manga: SManga): List<SChapter> {
        val base = manga.url.trimEnd('/') + "/books"
        val response = client.newCall(
            GET("$base?unpaged=true&media_status=READY&deleted=false", headers),
        ).awaitSuccess()
        val page = with(json) { response.parseAs<PageWrapperDto<BookDto>>() }
        return page.content
            .filter { it.media.status.equals("READY", ignoreCase = true) }
            .sortedByDescending { it.metadata.numberSort }
            .mapIndexed { index, book ->
                SChapter.create().apply {
                    name = chapterName(book, manga.title)
                    chapter_number = book.metadata.numberSort.takeIf { it > 0f } ?: (index + 1).toFloat()
                    url = "$baseUrlValue/api/v1/books/${book.id}"
                    date_upload = parseDate(book.created ?: book.fileLastModified)
                    scanlator = book.metadata.authors
                        .filter { it.role == "translator" }
                        .joinToString(scanlatorJoin) { it.name }
                        .ifBlank { null }
                }
            }
    }

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    // ------------------------------------------------------------ pages (comics)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = chapter.url + "/pages"
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        val pages = with(json) { response.parseAs<List<PageDto>>() }
        return pages.sortedBy { it.number }.map { page ->
            Page(
                index = page.number,
                imageUrl = pageUrlWithConvert(chapter.url, page),
            )
        }
    }

    private fun pageUrlWithConvert(chapterUrl: String, page: PageDto): String {
        val supported = SUPPORTED_IMAGE_TYPES
        val needsConvert = page.mediaType !in supported
        return chapterUrl + "/pages/" + page.number +
            if (needsConvert) "?convert=png" else ""
    }

    override suspend fun getImageUrl(page: Page): String = page.imageUrl ?: error("No image url")

    // ------------------------------------------------------------ epub half

    // KMK -->

    // The book half is licensed by the epub reader design: EPUBs are downloaded once to the
    // app cache and read from disk, exactly like the local source's epub folder. The wifi/
    // metered rule the local source applies implicitly (user downloaded the file) is preserved
    // here by the fact each chapter fetch is one file — no silent re-download: the cache key
    // is derived from the server's fileHash, so a changed book is re-fetched, an unchanged one
    // is served from disk.
    private val epubAdapter by lazy { KomgaEpubAdapter(context, this) }

    override val textCapabilities: TextCapabilities =
        TextCapabilities.EPUB.copy(TOC = true, SEARCH = true)

    override suspend fun getTextPageList(chapter: SChapter): List<TextPage> =
        epubAdapter.textPages(chapter)

    override suspend fun getTextPageIndex(chapter: SChapter): List<TextPage> =
        epubAdapter.textPageIndex(chapter)

    override suspend fun getTextPageText(chapter: SChapter, page: TextPage): String? =
        epubAdapter.textPageText(chapter, page)

    override suspend fun getTableOfContents(chapter: SChapter): List<TocEntry> =
        epubAdapter.tableOfContents(chapter)

    override suspend fun searchInChapter(chapter: SChapter, query: String): List<TextSearchHit> =
        epubAdapter.searchInChapter(chapter, query)

    override fun openResource(absoluteUrl: String): java.io.InputStream? =
        epubAdapter.openResource(absoluteUrl)

    override fun contentTypeFor(absoluteUrl: String): String? =
        epubAdapter.contentTypeFor(absoluteUrl)

    override fun baseUrlFor(page: TextPage): String =
        epubAdapter.baseUrlFor(page)

    override fun releaseResourceReader(chapterUrl: String) =
        epubAdapter.releaseResourceReader(chapterUrl)

    override fun releaseAllResourceReaders() =
        epubAdapter.releaseAllResourceReaders()

    override fun entryTypeOf(manga: SManga): EntryType? = null

    override fun detectBookEntry(manga: SManga, chapter: SChapter): Boolean {
        // The loader provider treats false as "comic" and only asks once; a probe failure
        // therefore opens the chapter as pages, the source's historical default.
        return runBlockingFirst { isBookShaped(chapter.url) }
    }

    // ------------------------------------------------------------ offline books

    /**
     * A downloaded EPUB is the book file itself, not a page list. The probe is the same one the
     * entry router uses, so a chapter cannot download as a book and open as a comic (or the other
     * way around).
     */
    override suspend fun isTextDownload(chapter: SChapter): Boolean = isBookShaped(chapter.url)

    override suspend fun downloadTextChapter(chapter: SChapter, target: UniFile) {
        val response = client.newCall(GET("${chapter.url}/file", headers)).awaitSuccess()
        response.body.byteStream().use { input ->
            target.openOutputStream().use { output -> input.copyTo(output) }
        }
    }

    // KMK <--

    // ------------------------------------------------------------ helpers

    private suspend fun isBookShaped(bookUrl: String): Boolean {
        return try {
            val response = client.newCall(GET(bookUrl, headers)).awaitSuccess()
            val book = with(json) { response.parseAs<BookDto>() }
            book.media.mediaProfile.equals("EPUB", ignoreCase = true) && !book.media.epubDivinaCompatible
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat { "Komga: book profile probe failed for $bookUrl: ${e.message}" }
            false
        }
    }

    private fun runBlockingFirst(block: suspend () -> Boolean): Boolean =
        kotlinx.coroutines.runBlocking { block() }

    // KMK --> The Keiyoushi extension's scanlator separator: one character, `","` joined as
    // `", "` so the default reproduces the join this source used before the setting existed, and
    // any other choice joins as `" $sep "` (the extension's rule).
    private val scanlatorJoin: String
        get() = prefs().scanlatorSeparator().let { separator ->
            if (separator == ",") ", " else " $separator "
        }

    /**
     * The chapter's name, rendered from the Keiyoushi extension's chapter-title template.
     *
     * The template is what lets a reader drop the number prefix — `{title}` alone — which the
     * source used to prepend unconditionally. A template that renders to nothing falls back to
     * the book's file name, because a blank chapter name is trimmed away by the app; one that
     * renders to exactly the series title is prefixed with a zero-width space for the same
     * reason (a row whose name equals the title shows as empty). Both rules are the extension's.
     */
    private fun chapterName(book: BookDto, mangaTitle: String): String {
        val values = mapOf(
            "title" to book.metadata.title.ifBlank { book.name },
            "seriesTitle" to book.seriesTitle,
            "number" to numberOf(book),
            "createdDate" to book.created.orEmpty(),
            "releaseDate" to book.metadata.releaseDate.orEmpty(),
            "size" to book.size,
            "sizeBytes" to book.sizeBytes.toString(),
        )
        val rendered = KomgaChapterNameTemplate.render(prefs().chapterNameTemplate(), values).trim()
        return when {
            rendered.isBlank() -> book.name.ifBlank { "Book ${numberOf(book)}" }
            rendered == mangaTitle -> "\u200B$rendered"
            else -> rendered
        }
    }

    private fun numberOf(book: BookDto): String =
        book.metadata.number.ifBlank { book.metadata.numberSort.takeIf { it > 0f }?.let { it.toString().removeSuffix(".0") } ?: "?" }

    private fun parseDate(value: String?): Long {
        if (value == null) return 0L
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }
            .recoverCatching { java.time.LocalDate.parse(value.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun SeriesDto.toManga(fill: SManga? = null): SManga =
        SManga.create().apply {
            title = metadata.title.ifBlank { name }
            url = "$baseUrlValue/api/v1/series/$id"
            description = metadata.summary.ifBlank { booksMetadata?.summary.orEmpty() }
            author = booksMetadata?.authors?.filter { it.role in AUTHOR_ROLES }
                ?.joinToString(", ") { it.name }?.ifBlank { null }
            artist = author
            genre = (metadata.genres + metadata.tags).distinct().joinToString(", ")
            status = parseStatus(metadata.status)
            thumbnail_url = "$baseUrlValue/api/v1/series/$id/thumbnail"
            initialized = true
            // copy over user-edited fields (favourite, categories etc. are managed by the app)
            fill?.takeIf { it.thumbnail_url != null && it.thumbnail_url == thumbnail_url }?.let { }
        }

    private fun parseStatus(status: String): Int = when (status.uppercase()) {
        "ONGOING" -> SManga.ONGOING
        "ENDED" -> SManga.COMPLETED
        "ABANDONED", "HIATUS" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ------------------------------------------------------------ ConfigurableSource

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        fun editText(
            key: String,
            title: String,
            inputType: Int = InputType.TYPE_CLASS_TEXT,
            password: Boolean = false,
            dialogMessage: String? = null,
        ): androidx.preference.EditTextPreference =
            androidx.preference.EditTextPreference(screen.context).apply {
                this.key = key
                this.title = title
                this.dialogMessage = dialogMessage
                this.summary = "Not set"
                setDefaultValue("")
                setOnBindEditTextListener { box ->
                    box.inputType = inputType
                    if (password) box.selectAll()
                }
                // Show the stored value (or "Not set") as the row summary, like the other
                // Tachiyomi source screens.
                val pref = this
                pref.summaryProvider = androidx.preference.Preference.SummaryProvider<androidx.preference.EditTextPreference> { p ->
                    p.text?.takeIf { it.isNotBlank() }?.let { text ->
                        if (password) "*".repeat(text.length) else text
                    } ?: "Not set"
                }
                screen.addPreference(this)
            }

        editText(
            KomgaSettingKeys.ADDRESS,
            "Server address",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            dialogMessage = "Komga server address without a trailing slash, e.g. http://nas:25600",
        )
        editText(KomgaSettingKeys.USERNAME, "Username")
        editText(
            KomgaSettingKeys.PASSWORD,
            "Password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            password = true,
        )
        editText(
            KomgaSettingKeys.API_KEY,
            "API key",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            password = true,
            dialogMessage = "Optional. If set, used instead of username and password.",
        )
        editText(KomgaSettingKeys.DISPLAY_NAME, "Source display name")

        // KMK --> The Keiyoushi extension's remaining options: chapter-title format, scanlator
        // separator, and the default-library selection the extension files under the same keys.
        chapterNameTemplatePreference(screen)
        scanlatorSeparatorPreference(screen)
        defaultLibrariesPreferences(screen)
        // KMK <--
    }

    // ------------------------------------------------------------ KMK: ported options

    private val settingsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun cachedLibraries(): List<LibraryDto> = runCatching {
        val text = prefs().getString(KomgaSettingKeys.LIBRARY_LIST, "[]")!!
        globalAppGraph.json.decodeFromString(ListSerializer(LibraryDto.serializer()), text)
    }.getOrDefault(emptyList())

    /**
     * The chapter-title format, as the Keiyoushi extension offers it.
     *
     * The OK button is gated on [KomgaChapterNameTemplate.isRenderable]: a typo in a placeholder
     * would otherwise be stamped into every chapter name for ever, and the dialog is the only
     * place that can catch it. A blank value is accepted and means "the default format".
     */
    private fun chapterNameTemplatePreference(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = KomgaSettingKeys.CHAPTER_NAME_TEMPLATE
            title = "Chapter title format"
            dialogTitle = title
            setDefaultValue(KomgaChapterNameTemplate.DEFAULT)
            dialogMessage = "Placeholders: {title}, {seriesTitle}, {number}, {createdDate}, " +
                "{releaseDate}, {size}, {sizeBytes}. Drop {number} to remove the number prefix. " +
                "Type \$ before a brace to print it literally."
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                pref.text?.takeIf { it.isNotBlank() } ?: KomgaChapterNameTemplate.DEFAULT
            }
            setOnBindEditTextListener { box ->
                box.inputType = InputType.TYPE_CLASS_TEXT
                box.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(editable: Editable?) {
                            val text = editable?.toString().orEmpty()
                            val valid = text.isBlank() || KomgaChapterNameTemplate.isRenderable(text)
                            box.error = if (valid) null else "Unknown placeholder in the format"
                            box.rootView.findViewById<Button>(android.R.id.button1)?.isEnabled = valid
                        }
                    },
                )
            }
            setOnPreferenceChangeListener { _, value ->
                val text = value as? String ?: return@setOnPreferenceChangeListener false
                val valid = text.isBlank() || KomgaChapterNameTemplate.isRenderable(text)
                if (!valid) Toast.makeText(context, "Unknown placeholder in the format", Toast.LENGTH_SHORT).show()
                valid
            }
            screen.addPreference(this)
        }
    }

    /** The character between multiple translator names; one character, blank means the default. */
    private fun scanlatorSeparatorPreference(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = KomgaSettingKeys.SCANLATOR_SEPARATOR
            title = "Scanlator name separator"
            dialogTitle = title
            setDefaultValue(",")
            dialogMessage = "One character, placed between multiple translator names."
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                pref.text?.takeIf { it.isNotBlank() } ?: ","
            }
            setOnPreferenceChangeListener { _, value ->
                val text = value as? String ?: return@setOnPreferenceChangeListener false
                val valid = text.isBlank() || text.length == 1
                if (!valid) Toast.makeText(context, "Only one character", Toast.LENGTH_SHORT).show()
                valid
            }
            screen.addPreference(this)
        }
    }

    /**
     * The library selection applied when browsing, plus the row that loads the choices.
     *
     * A settings screen cannot await a network call while it builds, so the library list is a
     * cache ([KomgaSettingKeys.LIBRARY_LIST]) filled by "Reload libraries" — or once on first open
     * when the server address is already configured. The extension fetches it behind every
     * credential change; an explicit row is the same result without the screen needing to know
     * when a save finished, and without the "did it save before it fetched?" ordering trap.
     */
    private fun defaultLibrariesPreferences(screen: PreferenceScreen) {
        val libraries = cachedLibraries()
        val selection = MultiSelectListPreference(screen.context).apply {
            key = KomgaSettingKeys.DEFAULT_LIBRARIES
            title = "Default libraries"
            setDefaultValue(emptySet<String>())
            entries = libraries.map { it.name }.toTypedArray()
            entryValues = libraries.map { it.id }.toTypedArray()
            isEnabled = libraries.isNotEmpty()
            summary = librarySummary(libraries, prefs().defaultLibraries())
        }
        screen.addPreference(selection)

        val reload = Preference(screen.context).apply {
            key = RELOAD_LIBRARIES_KEY
            title = "Reload libraries"
            summary = "Fetch this server's libraries for the selection above"
        }
        screen.addPreference(reload)

        fun load() {
            if (baseUrlValue.isBlank()) {
                Toast.makeText(screen.context, "Set the server address first", Toast.LENGTH_SHORT).show()
                return
            }
            val hadCache = selection.entryValues.isNotEmpty()
            selection.isEnabled = false
            selection.summary = "Loading libraries…"
            reload.isEnabled = false
            refreshLibraries { result ->
                reload.isEnabled = true
                if (result == null) {
                    selection.isEnabled = hadCache
                    selection.summary = if (hadCache) librarySummary(libraries, prefs().defaultLibraries()) else "Could not load libraries"
                    Toast.makeText(screen.context, "Could not load libraries", Toast.LENGTH_SHORT).show()
                } else {
                    selection.entries = result.map { it.name }.toTypedArray()
                    selection.entryValues = result.map { it.id }.toTypedArray()
                    selection.isEnabled = true
                    selection.summary = librarySummary(result, prefs().defaultLibraries())
                }
            }
        }

        reload.setOnPreferenceClickListener {
            load()
            true
        }
        // First open after a fresh install: no cache, an address already set.
        if (libraries.isEmpty() && baseUrlValue.isNotBlank()) {
            load()
        }
    }

    private fun librarySummary(libraries: List<LibraryDto>, selected: Set<String>): String = when {
        libraries.isEmpty() -> "Save the server address and reload to choose libraries"
        selected.isEmpty() -> "All libraries"
        else -> libraries.filter { it.id in selected }.joinToString(", ") { it.name }
            .ifBlank { "All libraries" }
    }

    /**
     * Fetches the libraries and caches them, then hands the result to the settings screen on main.
     *
     * A failure is reported as null rather than storing an empty list: the old cache stays usable,
     * which keeps the selection dialog populated through a temporary login problem.
     */
    private fun refreshLibraries(onDone: (List<LibraryDto>?) -> Unit) {
        settingsScope.launch {
            val result = try {
                val response = client.newCall(GET("$baseUrlValue/api/v1/libraries", headers)).awaitSuccess()
                with(json) { response.parseAs<List<LibraryDto>>() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat { "Komga: could not load libraries: ${e.message}" }
                null
            }
            if (result != null) {
                prefs().edit()
                    .putString(
                        KomgaSettingKeys.LIBRARY_LIST,
                        json.encodeToString(ListSerializer(LibraryDto.serializer()), result),
                    )
                    .apply()
            }
            mainHandler.post { onDone(result) }
        }
    }

    companion object {
        private val AUTHOR_ROLES = setOf("writer", "artist", "author")
        private val SUPPORTED_IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/jxl")
        // KMK --> The reload row's key; a plain Preference, so nothing is ever stored under it.
        private const val RELOAD_LIBRARIES_KEY = "__reload_libraries"
        // KMK <--
    }
}
