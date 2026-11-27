package eu.kanade.presentation.more.settings.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.novel.NovelPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Install and remove JavaScript novel plugins (#104).
 *
 * A plugin is a single `.js` file with a header comment and a `module.exports` object. The screen
 * never previews what a plugin will do - it cannot, without running untrusted code - so it shows
 * the header's identity and the sandbox rules in the same place.
 */
@Serializable
data object SettingsNovelPluginsRoute : NavKey, SearchableRoute {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_novel_plugins_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val repository = remember { globalAppGraph.novelPluginRepository }
        val plugins by repository.plugins.collectAsState()
        val scope = rememberCoroutineScope()
        var pendingDelete by remember { mutableStateOf<NovelPlugin?>(null) }

        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val failure = withContext(Dispatchers.IO) {
                    try {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "plugin.js"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("The picked file could not be opened")
                        if (bytes.size > MAX_PLUGIN_BYTES) error("The plugin is larger than 2 MiB")
                        repository.import(name, String(bytes, Charsets.UTF_8)).exceptionOrNull()
                    } catch (e: Exception) {
                        e
                    }
                }
                failure?.let {
                    Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
            }
        }

        val items = mutableListOf<Preference>()
        items += Preference.PreferenceItem.InfoPreference(
            title = stringResource(KMR.strings.pref_novel_plugins_summary),
        )
        items += Preference.PreferenceItem.TextPreference(
            title = stringResource(KMR.strings.novel_plugins_import),
            subtitle = stringResource(KMR.strings.novel_plugins_import_summary),
            onClick = { importLauncher.launch("*/*") },
        )
        if (plugins.isEmpty()) {
            items += Preference.PreferenceItem.InfoPreference(
                title = stringResource(KMR.strings.novel_plugins_empty),
            )
        } else {
            plugins.forEach { plugin ->
                items += Preference.PreferenceItem.TextPreference(
                    title = plugin.name,
                    subtitle = "${plugin.lang} · ${plugin.id} · v${plugin.version}",
                    onClick = { pendingDelete = plugin },
                )
            }
        }

        pendingDelete?.let { plugin ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(KMR.strings.novel_plugins_delete)) },
                text = { Text(stringResource(KMR.strings.novel_plugins_delete_body, plugin.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            repository.delete(plugin)
                            pendingDelete = null
                        },
                    ) {
                        Text(stringResource(KMR.strings.novel_plugins_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        return items
    }

    private const val MAX_PLUGIN_BYTES = 2 * 1024 * 1024
}
