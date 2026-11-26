package mihon.feature.libraryimport.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.navigation.ImportEntrySearchRoute
import mihon.core.navigation.util.LocalBackButtonVisibility
import mihon.core.navigation.util.LocalBackStack
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.importer.interactor.GetImportSessions
import tachiyomi.domain.importer.interactor.MarkImportEntryStatus
import tachiyomi.domain.importer.model.ImportEntry
import tachiyomi.domain.importer.model.ImportSession
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * One import session's entries.
 *
 * These rows are the "fake entries" of an import: they look like library entries - cover, title,
 * progress - but they are shells. There is nothing to read behind them and no chapter list to
 * show, because the entry only becomes a manga once it is matched to a source. Tapping one opens
 * the source search; importing there adds the real entry to the library and drops the reader back
 * here, on the next entry.
 */
@Composable
fun ImportSessionScreen(sessionId: String) {
    val backStack = LocalBackStack.current
    val backVisibility = LocalBackButtonVisibility.current
    val uriHandler = LocalUriHandler.current
    val model = assistedMetroViewModel<ImportSessionViewModel, ImportSessionViewModel.Factory> {
        create(sessionId)
    }
    val state by model.state.collectAsState()
    val session = state.session

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = session?.name ?: stringResource(KMR.strings.library_imports),
                navigateUp = {
                    backStack.removeLastOrNull()
                    Unit
                }.takeIf { backVisibility },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        when {
            session == null -> Unit
            session.entries.isEmpty() -> EmptySession(contentPadding)
            else -> SessionContent(
                session = session,
                contentPadding = contentPadding,
                onOpenEntry = { entry -> backStack.add(ImportEntrySearchRoute(sessionId, entry.id)) },
                onOpenSeries = { entry -> entry.seriesUrl?.let(uriHandler::openUri) },
                onSkipEntry = { entry -> model.skip(entry.id) },
                onRestoreEntry = { entry -> model.restore(entry.id) },
            )
        }
    }
}

@Composable
private fun EmptySession(contentPadding: PaddingValues) {
    Text(
        text = stringResource(KMR.strings.library_import_empty_export),
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(MaterialTheme.padding.medium),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SessionContent(
    session: ImportSession,
    contentPadding: PaddingValues,
    onOpenEntry: (ImportEntry) -> Unit,
    onOpenSeries: (ImportEntry) -> Unit,
    onSkipEntry: (ImportEntry) -> Unit,
    onRestoreEntry: (ImportEntry) -> Unit,
) {
    val pending = session.pendingEntries
    val done = session.entries.filterNot { it.id in pending.mapTo(HashSet()) { entry -> entry.id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                Text(
                    text = stringResource(
                        KMR.strings.library_import_session_progress,
                        session.importedCount,
                        session.total,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { session.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (pending.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(KMR.strings.library_import_pending),
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(pending, key = { "pending-${it.id}" }) { entry ->
                ImportEntryRow(
                    entry = entry,
                    status = EntryStatus.Pending,
                    onClick = { onOpenEntry(entry) },
                    onLongClick = { onOpenSeries(entry) },
                    onSkip = { onSkipEntry(entry) },
                    onRestore = { onRestoreEntry(entry) },
                )
            }
        }

        if (done.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(KMR.strings.library_import_done),
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(done, key = { "done-${it.id}" }) { entry ->
                ImportEntryRow(
                    entry = entry,
                    status = if (session.isImported(entry.id)) EntryStatus.Imported else EntryStatus.Skipped,
                    onClick = { onOpenSeries(entry) },
                    onLongClick = { onOpenSeries(entry) },
                    onSkip = {},
                    onRestore = { onRestoreEntry(entry) },
                )
            }
        }
    }
}

private enum class EntryStatus { Pending, Imported, Skipped }

@Composable
private fun ImportEntryRow(
    entry: ImportEntry,
    status: EntryStatus,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSkip: () -> Unit,
    onRestore: () -> Unit,
) {
    val dimmed = status != EntryStatus.Pending
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = entry.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 44.dp, height = 62.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .alpha(if (dimmed) 0.5f else 1f),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (dimmed) 0.6f else 1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val progressLine = buildList {
                    if (entry.progressChapter > 0.0) {
                        add(
                            buildString {
                                append(entry.progressChapter.formatted())
                                if (entry.totalChapters > 0) append(" / ${entry.totalChapters}")
                            },
                        )
                    } else if (entry.totalChapters > 0) {
                        add(entry.totalChapters.toString())
                    }
                    entry.status?.takeIf { it.isNotBlank() }?.let { add(it.replaceFirstChar { c -> c.titlecase() }) }
                }
                    .joinToString(" · ")
                    .ifEmpty { stringResource(KMR.strings.library_import_tap_to_match) }
                Text(
                    text = progressLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (status) {
                EntryStatus.Pending -> IconButton(onClick = onSkip) {
                    Icon(
                        imageVector = Icons.Outlined.SkipNext,
                        contentDescription = stringResource(KMR.strings.library_import_skip_entry),
                    )
                }
                EntryStatus.Imported -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(KMR.strings.library_import_status_imported),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = onRestore) {
                        Icon(
                            imageVector = Icons.Outlined.Undo,
                            contentDescription = stringResource(KMR.strings.library_import_restore_entry),
                        )
                    }
                }
                EntryStatus.Skipped -> IconButton(onClick = onRestore) {
                    Icon(
                        imageVector = Icons.Outlined.Undo,
                        contentDescription = stringResource(KMR.strings.library_import_restore_entry),
                    )
                }
            }
        }
    }
}

private fun Double.formatted(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

@AssistedInject
class ImportSessionViewModel(
    @Assisted private val sessionId: String,
    getImportSessions: GetImportSessions,
    private val markImportEntryStatus: MarkImportEntryStatus,
) : StateViewModel<ImportSessionViewModel.State>(State()) {

    init {
        viewModelScope.launch {
            getImportSessions.subscribe().collect { sessions ->
                mutableState.update { it.copy(session = sessions.firstOrNull { session -> session.id == sessionId }) }
            }
        }
    }

    fun skip(entryId: String) {
        viewModelScope.launchIO { markImportEntryStatus.skipped(sessionId, entryId) }
    }

    fun restore(entryId: String) {
        viewModelScope.launchIO { markImportEntryStatus.pending(sessionId, entryId) }
    }

    data class State(
        val session: ImportSession? = null,
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sessionId: String): ImportSessionViewModel
    }
}
