package mihon.feature.libraryimport.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.MigrateSearchScreen
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.concurrency.AppDispatchers
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.util.LocalBackStack
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.feature.libraryimport.domain.MigrateImportEntryUseCase
import mihon.feature.libraryimport.model.ImportMigrateFlag
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.importer.interactor.GetImportSessions
import tachiyomi.domain.importer.interactor.MarkImportEntryStatus
import tachiyomi.domain.importer.model.ImportEntry
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The source search an import entry is matched through.
 *
 * It is the same multi-source search the migration feature uses, with the shell's title as the
 * query. Picking a result asks what to carry over and then imports it; the entry list behind this
 * screen refreshes itself from the session store, so the reader lands back on the next entry
 * without having navigated anywhere.
 */
@Composable
fun ImportEntrySearchScreen(
    sessionId: String,
    entryId: String,
) {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val model = assistedMetroViewModel<ImportEntrySearchViewModel, ImportEntrySearchViewModel.Factory> {
        create(sessionId, entryId)
    }
    val state by model.state.collectAsState()
    val importState by model.importState.collectAsState()
    val bulkFavoriteScreenModel = metroViewModel<BulkFavoriteViewModel>()

    LaunchedEffect(importState.result) {
        val result = importState.result ?: return@LaunchedEffect
        model.clearResult()
        if (result) {
            context.toast(KMR.strings.library_import_status_imported)
            backStack.removeLastOrNull()
        } else {
            context.toast(KMR.strings.library_import_failed)
        }
    }

    MigrateSearchScreen(
        state = state,
        fromSourceId = null,
        navigateUp = { backStack.removeLastOrNull() },
        onChangeSearchQuery = model::updateSearchQuery,
        onSearch = { model.search() },
        getManga = { model.getManga(it) },
        onChangeSearchFilter = model::setSourceFilter,
        onToggleResults = model::toggleFilterResults,
        // The results for every source are already inline; there is no per-source screen to
        // drill into here.
        onClickSource = {},
        onClickItem = { model.openTarget(it) },
        onLongClickItem = { backStack.add(MangaRoute(it.id, true)) },
        bulkFavoriteScreenModel = bulkFavoriteScreenModel,
        hasPinnedSources = model.hasPinnedSources(),
    )

    val entry = importState.entry
    val target = importState.target
    if (entry != null && target != null) {
        MigrateImportEntryDialog(
            entry = entry,
            targetSourceName = importState.targetSourceName.orEmpty(),
            applicableFlags = importState.applicableFlags,
            trackerName = importState.trackerName,
            isMigrating = importState.isMigrating,
            onDismiss = { model.dismissTarget() },
            onConfirm = { flags -> model.migrate(flags) },
        )
    }
}

@AssistedInject
class ImportEntrySearchViewModel(
    @Assisted private val sessionId: String,
    @Assisted private val entryId: String,
    getManga: GetManga,
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    extensionManager: ExtensionManager,
    networkToLocalManga: NetworkToLocalManga,
    preferences: SourcePreferences,
    appDispatchers: AppDispatchers,
    updateMangaFromRemote: UpdateMangaFromRemote,
    getChaptersByMangaId: GetChaptersByMangaId,
    private val getImportSessions: GetImportSessions,
    private val markImportEntryStatus: MarkImportEntryStatus,
    private val migrateImportEntry: MigrateImportEntryUseCase,
    private val trackerManager: TrackerManager,
) : SearchViewModel(
    initialState = State(),
    sourcePreferences = sourcePreferences,
    sourceManager = sourceManager,
    extensionManager = extensionManager,
    networkToLocalManga = networkToLocalManga,
    getManga = getManga,
    preferences = preferences,
    appDispatchers = appDispatchers,
    updateMangaFromRemote = updateMangaFromRemote,
    getChaptersByMangaId = getChaptersByMangaId,
) {
    // KMK --> Import entries are matched the same way a migration search is, so they get the
    // latest chapter number on each result too.
    override val fetchChaptersForResults: Boolean
        get() = sourcePreferences.migrationFetchChaptersForResults().get()
    // KMK <--

    private val _importState = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = getImportSessions.await(sessionId) ?: return@launch
            val entry = session.entries.firstOrNull { it.id == entryId } ?: return@launch
            _importState.update { it.copy(entry = entry) }
            mutableState.update { it.copy(searchQuery = entry.title) }
            search()
        }
    }

    fun openTarget(manga: Manga) {
        val entry = _importState.value.entry ?: return
        val applicable = buildList {
            if (entry.progressChapter > 0.0) add(ImportMigrateFlag.CHAPTER)
            if (entry.note?.isNotBlank() == true) add(ImportMigrateFlag.NOTES)
        }.toMutableList()
        val loggedInLink = entry.trackingLinks.firstOrNull { link ->
            trackerManager.trackers.any { it.id == link.trackerId && it.isLoggedIn }
        }
        val tracker = loggedInLink?.let { link -> trackerManager.trackers.firstOrNull { it.id == link.trackerId } }
        if (tracker != null) {
            applicable += ImportMigrateFlag.TRACK
        }

        _importState.update {
            it.copy(
                target = manga,
                targetSourceName = sourceManager.get(manga.source)?.name,
                applicableFlags = applicable,
                trackerName = tracker?.name,
            )
        }
    }

    fun dismissTarget() {
        _importState.update { it.copy(target = null) }
    }

    fun migrate(flags: Set<ImportMigrateFlag>) {
        val state = _importState.value
        val entry = state.entry ?: return
        val target = state.target ?: return
        if (state.isMigrating) return

        _importState.update { it.copy(isMigrating = true) }

        viewModelScope.launchIO {
            val success = migrateImportEntry(entry, target, flags)
            if (success) {
                markImportEntryStatus.imported(sessionId, entry.id)
            }
            withUIContext {
                _importState.update { it.copy(isMigrating = false, target = null, result = success) }
            }
        }
    }

    fun clearResult() {
        _importState.update { it.copy(result = null) }
    }

    data class ImportState(
        val entry: ImportEntry? = null,
        val target: Manga? = null,
        val targetSourceName: String? = null,
        val applicableFlags: List<ImportMigrateFlag> = emptyList(),
        val trackerName: String? = null,
        val isMigrating: Boolean = false,
        /** `null` until an import has run; true when it landed in the library. */
        val result: Boolean? = null,
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sessionId: String, entryId: String): ImportEntrySearchViewModel
    }
}
