package eu.kanade.presentation.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.FeedListShimmer
import eu.kanade.presentation.browse.components.GlobalSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.SourcesSearchBox
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.browse.feed.FeedEnhancements
import eu.kanade.tachiyomi.ui.browse.feed.FeedScreenState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.plus
import kotlin.time.Duration.Companion.seconds
import tachiyomi.domain.source.model.Source as DomainSource

data class FeedItemUI(
    val feed: FeedSavedSearch,
    val savedSearch: SavedSearch?,
    val source: Source?,
    val title: String,
    val subtitle: String,
    val results: List<Manga>?,
    // KMK -->
    val failed: Boolean = false,
    /** Reader-assigned category for the Feed category chips (#107), null when uncategorised. */
    val category: String? = null,
    // KMK <--
)

@Composable
fun FeedScreen(
    state: FeedScreenState,
    contentPadding: PaddingValues,
    onClickSavedSearch: (SavedSearch, Source) -> Unit,
    onClickSource: (Source) -> Unit,
    // KMK -->
    onLongClickFeed: (FeedItemUI) -> Unit,
    onRetryFeed: (FeedItemUI) -> Unit,
    // KMK <--
    onClickManga: (Manga) -> Unit,
    // KMK -->
    onLongClickManga: (Manga) -> Unit,
    selection: List<Manga>,
    // KMK <--
    onRefresh: () -> Unit,
    getMangaState: @Composable (Manga) -> State<Manga>,
    // KMK --> Feed enhancements (#107); every parameter defaults to the pre-enhancement view.
    enhancementsEnabled: Boolean = false,
    filters: FeedEnhancements.State = FeedEnhancements.State(),
    categories: ImmutableList<String> = persistentListOf(),
    genres: ImmutableList<String> = persistentListOf(),
    suggestions: ImmutableList<FeedEnhancements.Suggestions.Section> = persistentListOf(),
    readIds: Set<Long> = emptySet(),
    onFiltersChange: (FeedEnhancements.State) -> Unit = {},
    onRetryAll: () -> Unit = {},
    // KMK <--
) {
    when {
        // KMK --> Layout-shaped placeholder instead of a centred spinner: a feed's first load is
        // a network round-trip per source, and the spinner left the screen empty for all of it. The
        // strip is built at the same fixed card widths the real rows use, so it does not re-flow
        // horizontally when the results land.
        // KMK <--
        state.isLoading -> FeedListShimmer(
            contentPadding = contentPadding,
            panorama = globalAppGraph.uiPreferences.usePanoramaCoverFlow().collectAsState().value,
        )
        state.isEmpty -> EmptyScreen(
            SYMR.strings.feed_tab_empty,
            modifier = Modifier.padding(contentPadding),
        )
        else -> {
            var refreshing by remember { mutableStateOf(false) }
            LaunchedEffect(refreshing) {
                if (refreshing) {
                    delay(1.seconds)
                    refreshing = false
                }
            }
            PullRefresh(
                refreshing = refreshing && state.isLoadingItems,
                onRefresh = {
                    refreshing = true
                    onRefresh()
                },
                enabled = !state.isLoadingItems,
            ) {
                ScrollbarLazyColumn(
                    contentPadding = contentPadding + topSmallPaddingValues,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // KMK -->
                    val feeds = state.items.orEmpty()
                    val visibleFeeds = if (enhancementsEnabled) {
                        feeds.filter { item ->
                            val categoryOk = filters.category == null || item.category == filters.category
                            val results = item.results
                            categoryOk && (
                                results == null || item.failed ||
                                    FeedEnhancements.applyFilters(results, filters, readIds).isNotEmpty()
                                )
                        }
                    } else {
                        feeds
                    }
                    if (enhancementsEnabled) {
                        item(key = "feed_enhancements") {
                            FeedEnhancementHeader(
                                filters = filters,
                                categories = categories,
                                genres = genres,
                                failedCount = feeds.count { it.failed },
                                suggestions = suggestions,
                                onFiltersChange = onFiltersChange,
                                onRetryAll = onRetryAll,
                                getMangaState = getMangaState,
                                onClickManga = onClickManga,
                                onLongClickManga = onLongClickManga,
                                selection = selection,
                            )
                        }
                    }
                    if (enhancementsEnabled && visibleFeeds.isEmpty() && filters.isActive) {
                        item(key = "feed_filters_empty") {
                            Text(
                                text = stringResource(KMR.strings.feed_filters_empty),
                                modifier = Modifier.padding(
                                    horizontal = MaterialTheme.padding.medium,
                                    vertical = MaterialTheme.padding.small,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(
                        items = visibleFeeds,
                        key = { it.feed.key },
                    ) { item ->
                        // KMK <--
                        GlobalSearchResultItem(
                            title = item.title,
                            subtitle = item.subtitle,
                            onLongClick = {
                                // KMK -->
                                onLongClickFeed(item)
                                // KMK <--
                            },
                            onClick = {
                                if (item.savedSearch != null && item.source != null) {
                                    onClickSavedSearch(item.savedSearch, item.source)
                                } else if (item.source != null) {
                                    onClickSource(item.source)
                                }
                            },
                            modifier = Modifier.animateItem(),
                        ) {
                            // KMK --> The filters only hide cards; the fetched page itself is
                            // never rewritten, so switching a chip back shows the row again
                            // without another network round-trip.
                            val displayedResults = item.results?.takeIf { enhancementsEnabled }
                                ?.let { FeedEnhancements.applyFilters(it, filters, readIds) }
                                ?: item.results
                            FeedItem(
                                item = if (displayedResults === item.results) {
                                    item
                                } else {
                                    item.copy(results = displayedResults)
                                },
                                getMangaState = { getMangaState(it) },
                                onClickManga = onClickManga,
                                // KMK -->
                                onLongClickManga = onLongClickManga,
                                selection = selection,
                                onRetryFeed = onRetryFeed,
                                // KMK <--
                            )
                            // KMK <--
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FeedItem(
    item: FeedItemUI,
    getMangaState: @Composable ((Manga) -> State<Manga>),
    onClickManga: (Manga) -> Unit,
    // KMK -->
    onLongClickManga: (Manga) -> Unit,
    selection: List<Manga>,
    onRetryFeed: (FeedItemUI) -> Unit,
    // KMK <--
) {
    // KMK --> crossfade loading/empty/content instead of a hard swap
    Crossfade(targetState = item.failed to item.results, label = "feedItem") { (failed, results) ->
        when {
            // KMK -->
            failed && results.isNullOrEmpty() -> FeedErrorResultItem(
                onRetry = { onRetryFeed(item) },
            )
            // KMK <--
            results == null -> {
                GlobalSearchLoadingResultItem()
            }
            // KMK -->
            results.isEmpty() && !failed -> {
                GlobalSearchErrorResultItem(message = stringResource(MR.strings.no_results_found))
            }
            // KMK <--
            else -> {
                GlobalSearchCardRow(
                    titles = results.orEmpty(),
                    getManga = getMangaState,
                    onClick = onClickManga,
                    // KMK -->
                    onLongClick = onLongClickManga,
                    selection = selection,
                    // KMK <--
                )
            }
        }
    }
    // KMK <--
}

// KMK -->
@Composable
private fun FeedEnhancementHeader(
    filters: FeedEnhancements.State,
    categories: ImmutableList<String>,
    genres: ImmutableList<String>,
    failedCount: Int,
    suggestions: ImmutableList<FeedEnhancements.Suggestions.Section>,
    onFiltersChange: (FeedEnhancements.State) -> Unit,
    onRetryAll: () -> Unit,
    getMangaState: @Composable (Manga) -> State<Manga>,
    onClickManga: (Manga) -> Unit,
    onLongClickManga: (Manga) -> Unit,
    selection: List<Manga>,
) {
    Column(Modifier.fillMaxWidth()) {
        if (failedCount > 0) {
            FeedFailureSummary(failedCount = failedCount, onRetryAll = onRetryAll)
        }
        FeedFilterChips(
            filters = filters,
            categories = categories,
            genres = genres,
            onFiltersChange = onFiltersChange,
        )
        if (suggestions.isNotEmpty()) {
            FeedSuggestionSections(
                suggestions = suggestions,
                getMangaState = getMangaState,
                onClickManga = onClickManga,
                onLongClickManga = onLongClickManga,
                selection = selection,
            )
        }
    }
}

@Composable
private fun FeedFilterChips(
    filters: FeedEnhancements.State,
    categories: ImmutableList<String>,
    genres: ImmutableList<String>,
    onFiltersChange: (FeedEnhancements.State) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.small),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (categories.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = MaterialTheme.padding.medium),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filters.category == null,
                    onClick = { onFiltersChange(filters.copy(category = null)) },
                    label = { Text(stringResource(KMR.strings.feed_category_all)) },
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = filters.category == category,
                        onClick = {
                            onFiltersChange(
                                filters.copy(category = if (filters.category == category) null else category),
                            )
                        },
                        label = { Text(category) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filters.libraryFilter == FeedEnhancements.LibraryFilter.NOT_IN_LIBRARY,
                onClick = {
                    val next = if (filters.libraryFilter == FeedEnhancements.LibraryFilter.NOT_IN_LIBRARY) {
                        FeedEnhancements.LibraryFilter.ALL
                    } else {
                        FeedEnhancements.LibraryFilter.NOT_IN_LIBRARY
                    }
                    onFiltersChange(filters.copy(libraryFilter = next))
                },
                label = { Text(stringResource(KMR.strings.feed_filter_not_in_library)) },
            )
            FilterChip(
                selected = filters.readFilter == FeedEnhancements.ReadFilter.UNREAD,
                onClick = {
                    val next = if (filters.readFilter == FeedEnhancements.ReadFilter.UNREAD) {
                        FeedEnhancements.ReadFilter.ALL
                    } else {
                        FeedEnhancements.ReadFilter.UNREAD
                    }
                    onFiltersChange(filters.copy(readFilter = next))
                },
                label = { Text(stringResource(KMR.strings.feed_filter_unread)) },
            )
            genres.forEach { genre ->
                FilterChip(
                    selected = filters.genre == genre,
                    onClick = {
                        onFiltersChange(filters.copy(genre = if (filters.genre == genre) null else genre))
                    },
                    label = { Text(genre) },
                )
            }
        }
    }
}

@Composable
private fun FeedFailureSummary(failedCount: Int, onRetryAll: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Outlined.Error, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(KMR.strings.feed_failed_summary, failedCount),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MaterialTheme.padding.small),
            )
            TextButton(onClick = onRetryAll) {
                Text(text = stringResource(KMR.strings.feed_retry_all))
            }
        }
    }
}

@Composable
private fun FeedSuggestionSections(
    suggestions: ImmutableList<FeedEnhancements.Suggestions.Section>,
    getMangaState: @Composable (Manga) -> State<Manga>,
    onClickManga: (Manga) -> Unit,
    onLongClickManga: (Manga) -> Unit,
    selection: List<Manga>,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = MaterialTheme.padding.small)) {
        Text(
            text = stringResource(KMR.strings.feed_suggestions),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        )
        suggestions.forEach { section ->
            Text(
                text = stringResource(sectionTitle(section.reason)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(
                    start = MaterialTheme.padding.medium,
                    top = MaterialTheme.padding.small,
                ),
            )
            GlobalSearchCardRow(
                titles = section.suggestions.map { it.manga },
                getManga = getMangaState,
                onClick = onClickManga,
                onLongClick = onLongClickManga,
                selection = selection,
            )
        }
    }
}

private fun sectionTitle(reason: FeedEnhancements.Suggestions.Reason) = when (reason) {
    FeedEnhancements.Suggestions.Reason.FRANCHISE -> KMR.strings.feed_suggestion_franchise
    FeedEnhancements.Suggestions.Reason.SIMILARITY -> KMR.strings.feed_suggestion_similarity
    FeedEnhancements.Suggestions.Reason.TAG -> KMR.strings.feed_suggestion_tag
    FeedEnhancements.Suggestions.Reason.SOURCE -> KMR.strings.feed_suggestion_source
}

@Composable
private fun FeedErrorResultItem(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            )
            .fillMaxWidth()
            .clickable(onClick = onRetry),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = Icons.Outlined.Error, contentDescription = null)
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(MR.strings.internal_error),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(MR.strings.action_retry))
        }
    }
}
// KMK <--

@Composable
fun FeedAddDialog(
    sources: ImmutableList<Source>,
    onDismiss: () -> Unit,
    onClickAdd: (Source?) -> Unit,
) {
    // KMK -->
    var query by remember { mutableStateOf("") }
    val sourceList = sources
        .filter { source ->
            if (query.isBlank()) return@filter true
            query.split(",").any {
                val input = it.trim()
                if (input.isEmpty()) return@any false
                source.name.contains(input, ignoreCase = true) ||
                    source.id == input.toLongOrNull()
            }
        }
    val composeOptions: List<@Composable () -> Unit> = sourceList
        .map {
            {
                val source = DomainSource(
                    id = it.id,
                    lang = it.lang,
                    name = it.name,
                    supportsLatest = it.supportsLatest,
                    isStub = false,
                )
                SourceIcon(source = source)
                Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
                Text(text = it.getNameForMangaInfo())
            }
        }
    // KMK <--
    var selected by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        title = {
            Text(text = stringResource(SYMR.strings.feed))
        },
        text = {
            // KMK -->
            RadioSelectorSearchable(
                options = composeOptions,
                queryString = query,
                onChangeSearchQuery = {
                    query = it ?: ""
                },
                // KMK <--
                selected = selected,
            ) {
                selected = it
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onClickAdd(selected?.let { sourceList[it] }) }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

// KMK --> Feed category editor (#107): the category is a local label, not a library category,
// so it is edited in place instead of opening a screen of its own.
@Composable
fun FeedCategoryDialog(
    initialValue: String,
    onDismissRequest: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(KMR.strings.feed_category_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(KMR.strings.feed_category_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(text = stringResource(KMR.strings.feed_category_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.small),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { onSave("") }) {
                Text(text = stringResource(KMR.strings.feed_category_clear))
            }
        },
    )
}
// KMK <--

@Composable
fun FeedAddSearchDialog(
    source: Source,
    savedSearches: ImmutableList<SavedSearch?>,
    onDismiss: () -> Unit,
    onClickAdd: (Source, SavedSearch?) -> Unit,
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        title = {
            Text(text = source.name)
        },
        text = {
            val context = LocalContext.current
            val savedSearchStrings = remember {
                savedSearches.map {
                    it?.name
                        // KMK -->
                        ?: if (source.supportsLatest) {
                            // KMK <--
                            context.stringResource(MR.strings.latest)
                            // KMK -->
                        } else {
                            context.stringResource(MR.strings.popular)
                        }
                    // KMK <--
                }.toImmutableList()
            }
            RadioSelectorSearchable(
                options = savedSearches,
                optionStrings = savedSearchStrings,
                selected = selected,
            ) {
                selected = it
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onClickAdd(source, selected?.let { savedSearches[it] }) },
                // KMK -->
                enabled = selected != null,
                // KMK <--
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
fun <T> RadioSelectorSearchable(
    options: ImmutableList<T>,
    optionStrings: ImmutableList<String> = remember { options.map { it.toString() }.toImmutableList() },
    selected: Int?,
    onSelectOption: (Int) -> Unit = {},
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        optionStrings.forEachIndexed { index, option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onSelectOption(index) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected == index, onClick = null)
                Spacer(Modifier.width(MaterialTheme.padding.extraSmall))
                Text(option, maxLines = 1)
            }
        }
    }
}

// KMK -->
@Composable
fun RadioSelectorSearchable(
    options: List<@Composable () -> Unit>,
    queryString: String? = null,
    onChangeSearchQuery: ((String?) -> Unit)? = null,
    selected: Int?,
    onSelectOption: (Int) -> Unit = {},
) {
    BackHandler(enabled = !queryString.isNullOrBlank()) {
        onChangeSearchQuery?.invoke("")
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        SourcesSearchBox(
            searchQuery = queryString,
            onChangeSearchQuery = onChangeSearchQuery ?: {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MaterialTheme.padding.small),
        ).takeIf { onChangeSearchQuery != null }
        options.forEachIndexed { index, option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onSelectOption(index) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected == index, onClick = null)
                Spacer(Modifier.width(MaterialTheme.padding.extraSmall))
                option()
            }
        }
    }
}
// KMK <--
