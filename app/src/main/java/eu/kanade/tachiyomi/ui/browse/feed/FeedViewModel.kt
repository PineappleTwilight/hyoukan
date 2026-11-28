@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.ui.browse.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.FeedItemUI
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.concurrency.AppDispatchers
import mihon.core.viewmodel.StateViewModel
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.interactor.CountFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.DeleteFeedSavedSearchById
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchBySourceId
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.interactor.InsertFeedSavedSearch
import tachiyomi.domain.source.interactor.ReorderFeed
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.service.SourceManager
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import tachiyomi.domain.manga.model.Manga as DomainManga

/**
 * Presenter of [feedTab]
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
open class FeedViewModel(
    val sourceManager: SourceManager,
    val sourcePreferences: SourcePreferences,
    private val getManga: GetManga,
    private val networkToLocalManga: NetworkToLocalManga,
    getFeedSavedSearchGlobal: GetFeedSavedSearchGlobal,
    private val getSavedSearchGlobalFeed: GetSavedSearchGlobalFeed,
    private val countFeedSavedSearchGlobal: CountFeedSavedSearchGlobal,
    private val getSavedSearchBySourceId: GetSavedSearchBySourceId,
    private val insertFeedSavedSearch: InsertFeedSavedSearch,
    private val deleteFeedSavedSearchById: DeleteFeedSavedSearchById,
    // KMK -->
    private val reorderFeed: ReorderFeed,
    // KMK <--
    private val appDispatchers: AppDispatchers,
    // KMK --> seeds for the local suggestion sections (#107)
    private val getFavorites: tachiyomi.domain.manga.interactor.GetFavorites,
    private val getHistory: tachiyomi.domain.history.interactor.GetHistory,
    // KMK <--
) : StateViewModel<FeedScreenState>(FeedScreenState()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    private val coroutineDispatcher = appDispatchers.backgroundOps
    var pushed: Boolean = false

    init {
        // KMK -->
        mutableState.update { it.copy(enhancementsEnabled = sourcePreferences.feedEnhancements().get()) }
        // KMK <--
        getFeedSavedSearchGlobal.subscribe()
            .distinctUntilChanged()
            .onEach {
                sourceManager.isInitialized.first { it }
                val items = getSourcesToGetFeed(it).map { (feed, savedSearch) ->
                    createCatalogueSearchItem(
                        feed = feed,
                        savedSearch = savedSearch,
                        source = sourceManager.get(feed.source),
                        results = null,
                    )
                }
                mutableState.update { state ->
                    state.copy(
                        // KMK -->
                        items = items.toImmutableList(),
                        categories = currentCategories(items).toImmutableList(),
                        // KMK <--
                    )
                }
                getFeed(items)
            }
            .catch { _events.send(Event.FailedFetchingSources) }
            .launchIn(viewModelScope)
    }

    fun init() {
        pushed = false
        // KMK -->
        // Refetch without clearing results so stale rows stay visible while loading.
        val currentItems = state.value.items ?: return
        getFeed(currentItems)
        // KMK <--
    }

    fun openAddDialog() {
        viewModelScope.launchIO {
            if (hasTooManyFeeds()) {
                _events.send(Event.TooManyFeeds)
                return@launchIO
            }
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.AddFeed(getEnabledSources()),
                )
            }
        }
    }

    fun openAddSearchDialog(source: Source) {
        viewModelScope.launchIO {
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.AddFeedSearch(
                        source,
                        (
                            // KMK -->
                            // (if (source.supportsLatest) persistentListOf(null) else persistentListOf()) +
                            persistentListOf(null) +
                                // KMK <-->
                                getSourceSavedSearches(source.id)
                            ).toImmutableList(),
                    ),
                )
            }
        }
    }

    fun openDeleteDialog(feed: FeedSavedSearch) {
        viewModelScope.launchIO {
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.DeleteFeed(feed),
                )
            }
        }
    }

    // KMK -->
    fun openActionsDialog(
        feed: FeedItemUI,
    ) {
        viewModelScope.launchIO {
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.FeedActions(
                        feedItem = feed,
                    ),
                )
            }
        }
    }
    // KMK <--

    private suspend fun hasTooManyFeeds(): Boolean {
        return countFeedSavedSearchGlobal.await() > MaxFeedItems
    }

    private fun getEnabledSources(): ImmutableList<Source> {
        val languages = sourcePreferences.enabledLanguages().get()
        val pinnedSources = sourcePreferences.pinnedSources().get()
        val disabledSources = sourcePreferences.disabledSources().get()
            .mapNotNull { it.toLongOrNull() }

        val list = sourceManager.getVisibleSources()
            .filter { it.lang in languages }
            .filterNot { it.id in disabledSources }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { "(${it.lang}) ${it.name}" })

        return list.sortedBy { it.id.toString() !in pinnedSources }.toImmutableList()
    }

    private suspend fun getSourceSavedSearches(sourceId: Long): ImmutableList<SavedSearch> {
        return getSavedSearchBySourceId.await(sourceId).toImmutableList()
    }

    fun createFeed(source: Source, savedSearch: SavedSearch?) {
        viewModelScope.launchNonCancellable {
            insertFeedSavedSearch.await(
                FeedSavedSearch(
                    id = -1,
                    source = source.id,
                    savedSearch = savedSearch?.id,
                    global = true,
                    feedOrder = 0,
                ),
            )
            runCatching {
                globalAppGraph.achievementManager.tryUnlockDirect("feed_user")
                globalAppGraph.rotatingAchievementPool.markProgress("rotating_daily_extra_3")
            }
        }
    }

    fun deleteFeed(feed: FeedSavedSearch) {
        viewModelScope.launchNonCancellable {
            deleteFeedSavedSearchById.await(feed.id)
        }
    }

    // KMK -->
    fun changeOrder(feed: FeedSavedSearch, newIndex: Int) {
        viewModelScope.launch {
            reorderFeed.changeOrder(feed, newIndex)
        }
    }
    // KMK <--

    private suspend fun getSourcesToGetFeed(feedSavedSearch: List<FeedSavedSearch>): List<Pair<FeedSavedSearch, SavedSearch?>> {
        val savedSearches = getSavedSearchGlobalFeed.await()
            .associateBy { it.id }
        return feedSavedSearch
            .mapNotNull { feed ->
                val savedId = feed.savedSearch
                if (savedId != null && savedId !in savedSearches) {
                    logcat(LogPriority.WARN) {
                        "Feed: dropping feed ${feed.id} referencing missing savedSearch $savedId"
                    }
                    return@mapNotNull null
                }
                feed to savedSearches[savedId]
            }
    }

    /**
     * Creates a catalogue search item
     */
    private fun createCatalogueSearchItem(
        feed: FeedSavedSearch,
        savedSearch: SavedSearch?,
        source: Source?,
        results: List<DomainManga>?,
    ): FeedItemUI {
        return FeedItemUI(
            feed,
            savedSearch,
            source,
            savedSearch?.name ?: (source?.name ?: feed.source.toString()),
            if (savedSearch != null) {
                source?.name ?: feed.source.toString()
            } else {
                LocaleHelper.getLocalizedDisplayName(source?.lang)
            },
            results,
            // KMK -->
            category = sourcePreferences.feedCategory(feed.id).get().takeIf { it.isNotBlank() },
            // KMK <--
        )
    }

    // KMK -->
    private val hideInLibraryFeedItems = sourcePreferences.hideInLibraryFeedItems()
    // KMK <--

    /**
     * Initiates get manga per feed.
     */
    private fun getFeed(feedSavedSearch: List<FeedItemUI>) {
        viewModelScope.launch {
            feedSavedSearch.map { itemUI ->
                async {
                    updateFeedItem(fetchFeedItem(itemUI))
                }
            }.awaitAll()
            // KMK -->
            loadEnhancementContext()
            recomputeEnhancementLists()
            // KMK <--
        }
    }

    // KMK -->
    fun retryFeed(item: FeedItemUI) {
        viewModelScope.launchIO {
            if (state.value.items?.none { it.feed.id == item.feed.id } != false) return@launchIO
            updateFeedItem(item.copy(results = null, failed = false))
            updateFeedItem(fetchFeedItem(item))
            recomputeEnhancementLists()
        }
    }

    fun refreshFeed(feed: FeedSavedSearch) {
        state.value.items
            ?.firstOrNull { it.feed.id == feed.id }
            ?.let { retryFeed(it) }
    }

    /** Retries every row currently showing a failure, from the summary card. */
    fun retryAllFailed() {
        state.value.items.orEmpty()
            .filter { it.failed }
            .forEach { retryFeed(it) }
    }

    fun setFilters(filters: FeedEnhancements.State) {
        mutableState.update { it.copy(filters = filters) }
    }

    fun setFeedCategory(feed: FeedSavedSearch, category: String) {
        val trimmed = category.trim()
        sourcePreferences.feedCategory(feed.id).set(trimmed)
        val items = state.value.items.orEmpty().map {
            if (it.feed.id == feed.id) {
                it.copy(category = trimmed.takeIf { value -> value.isNotBlank() })
            } else {
                it
            }
        }
        mutableState.update {
            it.copy(
                items = items.toImmutableList(),
                categories = currentCategories(items).toImmutableList(),
                dialog = null,
            )
        }
    }

    fun openCategoryDialog(feedItem: FeedItemUI) {
        mutableState.update {
            it.copy(
                dialog = Dialog.SetFeedCategory(
                    feedItem = feedItem,
                    current = sourcePreferences.feedCategory(feedItem.feed.id).get(),
                ),
            )
        }
    }

    private var librarySeed: List<DomainManga> = emptyList()
    private var historyTitles: List<String> = emptyList()
    private var readIds: Set<Long> = emptySet()

    private fun currentCategories(items: List<FeedItemUI>): List<String> =
        items.mapNotNull { item ->
            sourcePreferences.feedCategory(item.feed.id).get().takeIf { it.isNotBlank() }
        }
            .distinct()
            .sortedBy { it.lowercase() }

    /**
     * Reads the library and history once per refresh. The suggestion sections are local: they
     * score the page that was already fetched, so this snapshot is the only database work the
     * feature adds.
     */
    private suspend fun loadEnhancementContext() {
        if (!state.value.enhancementsEnabled) return
        librarySeed = runCatching { getFavorites.await() }.getOrDefault(emptyList())
        val history = runCatching {
            getHistory.subscribe("", null, null, null).first()
        }.getOrDefault(emptyList())
        readIds = history.map { it.mangaId }.toSet()
        historyTitles = history.map { it.title }.distinct()
    }

    private fun recomputeEnhancementLists() {
        if (!state.value.enhancementsEnabled) return
        val candidates = FeedEnhancements.dedupe(
            state.value.items.orEmpty().flatMap { it.results.orEmpty() },
        )
        val genres = FeedEnhancements.genresOf(candidates).toImmutableList()
        val suggestions = FeedEnhancements.Suggestions.build(candidates, librarySeed, historyTitles)
            .toImmutableList()
        mutableState.update { it.copy(genres = genres, suggestions = suggestions, readIds = readIds) }
    }
    // KMK <--

    private suspend fun fetchFeedItem(itemUI: FeedItemUI): FeedItemUI {
        val page = try {
            if (itemUI.source != null) {
                withContext(coroutineDispatcher) {
                    if (itemUI.savedSearch == null) {
                        // KMK -->
                        if (itemUI.source.supportsLatest) {
                            // KMK <--
                            itemUI.source.getLatestUpdates(1)
                            // KMK -->
                        } else {
                            itemUI.source.getPopularManga(1)
                        }
                        // KMK <--
                    } else {
                        itemUI.source.getSearchManga(
                            1,
                            itemUI.savedSearch.query?.sanitize().orEmpty(),
                            getFilterList(itemUI.savedSearch, itemUI.source),
                        )
                    }
                }.mangas
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            // KMK -->
            // Keep stale results on failure so the row doesn't blank out.
            return itemUI.copy(failed = true)
            // KMK <--
        }

        val result = withIOContext {
            itemUI.copy(
                results = page
                    .mapNotNull { itemUI.source?.let { source -> it.toDomainManga(source.id) } }
                    .distinctBy { it.url }
                    .let { networkToLocalManga(it) }
                    // KMK -->
                    .filter { !hideInLibraryFeedItems.get() || !it.favorite }
                    .let { if (sourcePreferences.feedEnhancements().get()) FeedEnhancements.dedupe(it) else it },
                failed = false,
                // KMK <--
            )
        }

        return result
    }

    // KMK -->
    private fun updateFeedItem(result: FeedItemUI) {
        mutableState.update { state ->
            state.copy(
                items = state.items?.map { if (it.feed.id == result.feed.id) result else it }
                    ?.toImmutableList(),
            )
        }
    }
    // KMK <--

    private val filterSerializer = FilterSerializer()

    private fun getFilterList(savedSearch: SavedSearch, source: Source): FilterList {
        val filters = savedSearch.filtersJson ?: return FilterList()
        return runCatching {
            val originalFilters = source.getFilterList()
            filterSerializer.deserialize(
                filters = originalFilters,
                json = Json.decodeFromString(filters),
            )
            originalFilters
        }.getOrElse { FilterList() }
    }

    @Composable
    fun getManga(initialManga: DomainManga): State<DomainManga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .collectLatest { manga ->
                    if (manga == null) return@collectLatest
                    value = manga
                }
        }
    }

    // KMK -->
    fun showDialog(dialog: Dialog) {
        if (!state.value.isLoading) {
            mutableState.update {
                it.copy(dialog = dialog)
            }
        }
    }
    // KMK <--

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed class Dialog {
        data class AddFeed(val options: ImmutableList<Source>) : Dialog()
        data class AddFeedSearch(val source: Source, val options: ImmutableList<SavedSearch?>) : Dialog()
        data class DeleteFeed(val feed: FeedSavedSearch) : Dialog()

        // KMK -->
        data class FeedActions(
            val feedItem: FeedItemUI,
        ) : Dialog()

        data class SetFeedCategory(
            val feedItem: FeedItemUI,
            val current: String,
        ) : Dialog()
        // KMK <--
    }

    sealed class Event {
        data object FailedFetchingSources : Event()
        data object TooManyFeeds : Event()
    }
}

data class FeedScreenState(
    val dialog: FeedViewModel.Dialog? = null,
    val items: ImmutableList<FeedItemUI>? = null,
    // KMK -->
    val enhancementsEnabled: Boolean = false,
    val filters: FeedEnhancements.State = FeedEnhancements.State(),
    val categories: ImmutableList<String> = persistentListOf(),
    val genres: ImmutableList<String> = persistentListOf(),
    val suggestions: ImmutableList<FeedEnhancements.Suggestions.Section> = persistentListOf(),
    /** Library entries that appear in the reading history, for the read/unread filter. */
    val readIds: Set<Long> = emptySet(),
    // KMK <--
) {
    val isLoading
        get() = items == null

    val isEmpty
        get() = items.isNullOrEmpty()

    val isLoadingItems
        get() = items?.fastAny { it.results == null } != false
}

const val MaxFeedItems = 20
