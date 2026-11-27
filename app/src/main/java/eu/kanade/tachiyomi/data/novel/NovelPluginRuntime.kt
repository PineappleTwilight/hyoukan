package eu.kanade.tachiyomi.data.novel

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

/**
 * The only surface a novel plugin can reach (#104).
 *
 * QuickJS gives the script no filesystem, no class loader and no direct network access; every
 * side effect has to come through one of these methods. That is the sandbox: a plugin cannot read
 * the database, the library or a file, and cannot reach an arbitrary URL except through the same
 * HTTP client the app uses for extensions.
 */
interface NovelPluginHostApi {
    fun fetch(url: String): String?
    fun postJson(url: String, body: String): String?
    fun postForm(url: String, body: String): String?
    fun log(message: String)
    fun now(): Double
}

/**
 * Runs a plugin script in a fresh QuickJS context and returns its answer as JSON.
 *
 * ### Why a fresh context per call
 *
 * Plugin state is not trusted to be well-behaved: a global that survives between calls could make
 * one chapter's parse depend on another's, and a context that never closes accumulates native
 * memory. The cost is parsing the script each time, which is microseconds next to the network call
 * the script is about to make.
 *
 * ### What this is not
 *
 * This is not an LNReader-compatible runtime: those plugins depend on asynchronous `fetch` and a
 * `cheerio` DOM, neither of which the bundled QuickJS binding exposes. The plugin API here is
 * synchronous and returns the data a source needs; a compatibility shim for LNReader's plugin
 * format is deliberately out of scope rather than half-implemented.
 */
@SingleIn(AppScope::class)
@Inject
class NovelPluginRuntime(
    private val network: NetworkHelper,
) {

    /**
     * Calls `module.exports.[method](args...)` and returns `JSON.stringify` of the result.
     *
     * Null means the plugin is missing that method, threw, or returned nothing serialisable. The
     * caller treats all three the same way: this source had nothing to say.
     */
    suspend fun invoke(plugin: NovelPlugin, method: String, args: List<String> = emptyList()): String? =
        withContext(Dispatchers.IO) {
            if (!METHODS.contains(method)) return@withContext null
            val script = try {
                plugin.file.takeIf { it.isFile && it.length() in 1L..MAX_SCRIPT_BYTES }?.readText()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Novel plugin ${plugin.id}: could not read its script" }
                null
            } ?: return@withContext null

            try {
                app.cash.quickjs.QuickJs.create().use { js ->
                    js.set("__host", NovelPluginHostApi::class.java, Bridge(plugin.id))
                    js.evaluate(BOOTSTRAP)
                    js.evaluate(script)
                    val callArgs = args.joinToString(",") { kotlinx.serialization.json.JsonPrimitive(it).toString() }
                    val call = "JSON.stringify((module.exports && module.exports.$method)" +
                        " ? module.exports.$method($callArgs) : null)"
                    js.evaluate(call) as? String
                }
            } catch (e: Throwable) {
                // A plugin exception must not take the source down: the reader shows an empty
                // result, and the log names the plugin so the user can delete or fix it.
                logcat(LogPriority.ERROR, e) { "Novel plugin ${plugin.id}: $method failed" }
                null
            }
        }

    private inner class Bridge(private val pluginId: String) : NovelPluginHostApi {
        override fun fetch(url: String): String? = request(url, null, null)

        override fun postJson(url: String, body: String): String? =
            request(url, body, "application/json; charset=utf-8")

        override fun postForm(url: String, body: String): String? =
            request(url, body, "application/x-www-form-urlencoded; charset=utf-8")

        override fun log(message: String) {
            logcat(LogPriority.DEBUG) { "Novel plugin $pluginId: $message" }
        }

        override fun now(): Double = System.currentTimeMillis().toDouble()

        private fun request(url: String, body: String?, contentType: String?): String? {
            if (url.length > MAX_URL_LENGTH || !url.startsWith("http")) return null
            return try {
                val request = Request.Builder()
                    .url(url)
                    .apply {
                        if (body == null) {
                            get()
                        } else {
                            post(body.toRequestBody(contentType?.toMediaType()))
                        }
                    }
                    .build()
                network.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logcat(LogPriority.WARN) { "Novel plugin $pluginId: HTTP ${response.code} for $url" }
                        return null
                    }
                    val source = response.body.source()
                    if (source.request(MAX_BODY_BYTES + 1) || source.buffer.size > MAX_BODY_BYTES) {
                        throw IOException("Response exceeds the $MAX_BODY_BYTES byte limit")
                    }
                    source.readUtf8()
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Novel plugin $pluginId: request failed for $url" }
                null
            }
        }
    }

    private companion object {
        val METHODS = setOf("popular", "latest", "search", "manga", "chapters", "text")
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        const val MAX_URL_LENGTH = 8_192

        const val BOOTSTRAP = """
            var module = { exports: {} };
            var exports = module.exports;
            var host = __host;
            var console = {
                log: function (message) { __host.log(String(message)); },
                warn: function (message) { __host.log(String(message)); },
                error: function (message) { __host.log(String(message)); }
            };
        """
    }
}
