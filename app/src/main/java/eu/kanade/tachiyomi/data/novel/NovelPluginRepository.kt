package eu.kanade.tachiyomi.data.novel

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException

/**
 * Installed novel plugins (#104).
 *
 * Files live in `filesDir/novel_plugins`, one `.js` per plugin. The directory is the source of
 * truth: the list is rebuilt from it on import and delete, so a manually copied file appears
 * without any bookkeeping, and a file the user removed from disk disappears.
 */
@SingleIn(AppScope::class)
@Inject
class NovelPluginRepository(
    private val context: Context,
) {

    private val _plugins = MutableStateFlow(scan())
    val plugins: StateFlow<List<NovelPlugin>> = _plugins.asStateFlow()

    fun directory(): File = File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    fun refresh() {
        _plugins.value = scan()
    }

    /**
     * Validates a plugin file and stores it under its id.
     *
     * The script is never executed here: the header is the contract, and running arbitrary code at
     * import time is exactly what the sandbox exists to avoid.
     */
    fun import(fileName: String, script: String): Result<NovelPlugin> {
        if (script.toByteArray().size > MAX_SCRIPT_BYTES) {
            return Result.failure(IOException("Plugin exceeds the 2 MiB limit"))
        }
        val header = NovelPluginHeader.parse(script)
            ?: return Result.failure(IOException("The file has no @id/@name plugin header"))
        val target = File(directory(), "${header.id}.js")
        return try {
            target.writeText(script)
            refresh()
            val plugin = _plugins.value.firstOrNull { it.id == header.id }
                ?: return Result.failure(IOException("The plugin did not appear after import"))
            logcat(LogPriority.INFO) { "Novel plugin imported: ${header.id} from $fileName" }
            Result.success(plugin)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun delete(plugin: NovelPlugin): Boolean {
        val deleted = runCatching { plugin.file.delete() }.getOrDefault(false)
        refresh()
        return deleted
    }

    private fun scan(): List<NovelPlugin> {
        val dir = File(context.filesDir, DIRECTORY)
        if (!dir.exists()) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".js", ignoreCase = true) }
            .mapNotNull { file ->
                val header = file.takeIf { it.length() in 1L..MAX_SCRIPT_BYTES }?.let {
                    runCatching { NovelPluginHeader.parse(it.readText()) }.getOrNull()
                } ?: return@mapNotNull null
                NovelPlugin(
                    id = header.id,
                    name = header.name,
                    lang = header.lang,
                    version = header.version,
                    site = header.site,
                    file = file,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private companion object {
        const val DIRECTORY = "novel_plugins"
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
    }
}
