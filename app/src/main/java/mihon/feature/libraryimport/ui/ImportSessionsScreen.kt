package mihon.feature.libraryimport.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.navigation.ImportSessionRoute
import mihon.core.navigation.util.LocalBackButtonVisibility
import mihon.core.navigation.util.LocalBackStack
import mihon.core.viewmodel.StateViewModel
import mihon.feature.libraryimport.data.LibraryImportAdapters
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.importer.interactor.DeleteImportSession
import tachiyomi.domain.importer.interactor.GetImportSessions
import tachiyomi.domain.importer.interactor.SaveImportSession
import tachiyomi.domain.importer.model.ImportEntry
import tachiyomi.domain.importer.model.ImportSession
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.UUID

/**
 * The settings area for every resumable library import: one card per session, with how far it
 * got and a way back into it.
 */
@Composable
fun ImportSessionsScreen() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val backVisibility = LocalBackButtonVisibility.current
    val model = metroViewModel<ImportSessionsViewModel>()
    val state by model.state.collectAsState()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            model.importFile(uri)
        }
    }

    LaunchedEffect(state.failure) {
        val failure = state.failure ?: return@LaunchedEffect
        context.toast(
            when (failure) {
                ImportFailure.Unreadable -> KMR.strings.library_import_parse_failed
                ImportFailure.Empty -> KMR.strings.library_import_empty_export
            },
        )
        model.clearFailure()
    }

    LaunchedEffect(state.createdSessionId) {
        val created = state.createdSessionId ?: return@LaunchedEffect
        model.clearCreatedSession()
        backStack.add(ImportSessionRoute(created))
    }

    var deleting by remember { mutableStateOf<ImportSession?>(null) }
    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(KMR.strings.library_import_delete_session)) },
            text = { Text(stringResource(KMR.strings.library_import_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.delete(session.id)
                        deleting = null
                    },
                ) {
                    Text(stringResource(KMR.strings.library_import_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(KMR.strings.library_imports),
                navigateUp = {
                    backStack.removeLastOrNull()
                    Unit
                }.takeIf { backVisibility },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { filePicker.launch("*/*") }) {
                if (state.isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.FileDownload,
                        contentDescription = stringResource(KMR.strings.library_import_file_picker),
                    )
                }
            }
        },
    ) { contentPadding ->
        if (state.sessions.isEmpty()) {
            EmptyImports(contentPadding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                item {
                    Text(
                        text = stringResource(KMR.strings.library_imports_summary),
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.sessions, key = { it.id }) { session ->
                    ImportSessionCard(
                        session = session,
                        onClick = { backStack.add(ImportSessionRoute(session.id)) },
                        onDelete = { deleting = session },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyImports(contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Text(
            text = stringResource(KMR.strings.library_import_empty),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(KMR.strings.library_import_empty_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportSessionCard(
    session: ImportSession,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        KMR.strings.library_import_session_progress,
                        session.importedCount,
                        session.total,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { session.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(KMR.strings.library_import_delete_session),
                )
            }
        }
    }
}

/** Why a picked file did not become a session. */
internal enum class ImportFailure { Unreadable, Empty }

private class EmptyExportException : Exception()

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
internal class ImportSessionsViewModel(
    private val context: Context,
    private val getImportSessions: GetImportSessions,
    private val saveImportSession: SaveImportSession,
    private val deleteImportSession: DeleteImportSession,
    private val adapters: LibraryImportAdapters,
) : StateViewModel<ImportSessionsViewModel.State>(State()) {

    init {
        viewModelScope.launch {
            getImportSessions.subscribe().collect { sessions ->
                mutableState.update { it.copy(sessions = sessions) }
            }
        }
    }

    fun importFile(uri: Uri) {
        if (state.value.isImporting) return
        mutableState.update { it.copy(isImporting = true, failure = null) }

        viewModelScope.launchIO {
            val result = runCatching {
                val fileName = displayName(uri) ?: uri.lastPathSegment
                val content = withIOContext {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: error("Could not open the picked file")

                val adapter = adapters.byFileName(fileName)
                val entries = adapter.parse(content)
                if (entries.isEmpty()) throw EmptyExportException()

                createOrReuseSession(adapter.id, fileName, entries)
            }

            withUIContext {
                result.fold(
                    onSuccess = { session -> mutableState.update { it.copy(isImporting = false, createdSessionId = session.id) } },
                    onFailure = { error ->
                        val failure = if (error is EmptyExportException) ImportFailure.Empty else ImportFailure.Unreadable
                        if (failure == ImportFailure.Unreadable) {
                            logcat(LogPriority.WARN, error) { "Could not import a library export" }
                        }
                        mutableState.update { it.copy(isImporting = false, failure = failure) }
                    },
                )
            }
        }
    }

    /**
     * The document's own name rather than the opaque last path segment of a SAF uri, so a session
     * is named after the file the reader picked.
     */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /**
     * Re-importing the same export continues the existing session instead of starting a second
     * copy of it: sessions are identified by their adapter and the exact set of entry ids, which
     * is stable across re-reads of the same file.
     */
    private suspend fun createOrReuseSession(
        sourceId: String,
        fileName: String?,
        entries: List<ImportEntry>,
    ): ImportSession {
        val existing = getImportSessions.await().firstOrNull { session ->
            session.sourceId == sourceId && session.entries.map { it.id } == entries.map { it.id }
        }
        val now = System.currentTimeMillis()
        val session = existing?.copy(
            name = fileName?.takeIf { it.isNotBlank() } ?: existing.name,
            entries = entries,
            updatedAt = now,
        ) ?: ImportSession(
            id = UUID.randomUUID().toString(),
            name = fileName?.takeIf { it.isNotBlank() } ?: sourceId,
            sourceId = sourceId,
            createdAt = now,
            entries = entries,
        )
        saveImportSession.await(session)
        return session
    }

    fun delete(sessionId: String) {
        viewModelScope.launchIO { deleteImportSession.await(sessionId) }
    }

    fun clearCreatedSession() {
        mutableState.update { it.copy(createdSessionId = null) }
    }

    fun clearFailure() {
        mutableState.update { it.copy(failure = null) }
    }

    data class State(
        val sessions: List<ImportSession> = emptyList(),
        val isImporting: Boolean = false,
        val failure: ImportFailure? = null,
        /** Set once a file has been read; the screen navigates to it and clears this. */
        val createdSessionId: String? = null,
    )
}
