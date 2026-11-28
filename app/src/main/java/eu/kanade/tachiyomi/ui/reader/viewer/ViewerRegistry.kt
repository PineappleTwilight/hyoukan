package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.annotation.ColorInt
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.TextPageLoader
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuHealth
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuOffer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewerContinuous
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.webGpuOfferFor
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import exh.log.xLogE
import logcat.LogPriority
import logcat.LogPriority.DEBUG
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.logcat

/**
 * Pluggable factory for [Viewer] implementations.
 *
 * A provider returns a [Viewer] for the reading modes it handles, or null to
 * let the next provider try. Providers are consulted in [order]; the built-in
 * [WebGpuProvider] and [LegacyProvider] mirror the historical
 * `ReadingMode.toViewer` mapping. Custom readers (e.g. a novel viewer) register
 * via [ViewerRegistry.register] with a lower [order] to take precedence.
 *
 * ### A null return is a claim, not a shrug
 *
 * "I do not handle this" has to mean *that kind of chapter*, not *this instance of it*. A provider
 * that declines a chapter another provider is the only renderer for does not fall back to a wrong
 * viewer - it hands the reader to an implementation that cannot display the content, which shows as
 * an empty page with no error anywhere. That is worse than failing loudly.
 *
 * So the image providers refuse text chapters outright ([declinesTextChapters]), and if nothing
 * handles what is left, [create] throws rather than silently rendering nothing.
 */
interface ViewerProvider {
    val order: Int
    fun create(mode: ReadingMode, activity: ReaderActivity, @ColorInt seedColor: Int?): Viewer?

    /**
     * True for viewers that cannot render a text chapter and must not be handed one.
     *
     * Checked before [create] so the refusal is explicit and uniform, rather than each provider
     * re-deriving "is this text?" from whatever state happens to be loaded.
     */
    val declinesTextChapters: Boolean get() = false
}

/**
 * True when the chapter on screen is served as text rather than images.
 *
 * Read from the loader, which is the authoritative signal: [TextPageLoader] is what
 * [PageLoaderRegistry] selects for a `TextSource` and for nothing else. Asking the page list instead
 * needs the pages to exist first and reports "not text" while they do not - indistinguishable from a
 * genuinely non-text chapter, which is the exact ambiguity that let an EPUB reach the pager.
 */
internal fun ReaderActivity.isTextChapter(): Boolean {
    val chapter = viewModel.state.value.currentChapter
    // KMK --> The loader's identity is the claim, so the answer is logged with everything needed to
    // see *why* it came out the way it did: which chapter, which loader, and whether pages exist yet.
    // A null loader is not an error - it is the normal state before a chapter is loaded - but it is
    // also the state in which a text chapter would be handed to an image viewer, so it is worth a line.
    val loader = chapter?.pageLoader
    val isText = loader is TextPageLoader
    logcat {
        "isTextChapter: chapter=${chapter?.chapter?.url ?: "null"} " +
            "loader=${loader?.let { it::class.simpleName } ?: "null"} " +
            "isText=$isText pages=${chapter?.pages?.size ?: "not loaded"}"
    }
    return isText
}

object ViewerRegistry {
    private val providers = mutableListOf<ViewerProvider>(WebGpuProvider, LegacyProvider)

    /**
     * The simple name of [value]'s class, or `"?"` when there is none to report.
     *
     * Both spellings of this in Kotlin have an edge: `KClass.simpleName` is nullable, which will not
     * drop straight into `sorted()`, and `Any.javaClass` is deprecated on a `KClass` receiver. Every
     * decision line below needs the name of a *loader*, a *viewer* or a *provider* — three different
     * static types, so there is no single receiver form that covers them. Routing through one helper is
     * what keeps the log statements readable and keeps the compiler out of the argument.
     */
    private fun nameOf(value: Any): String = value::class.simpleName ?: "?"

    fun register(provider: ViewerProvider) {
        providers.removeAll { it::class == provider::class }
        providers.add(provider)
        providers.sortBy { it.order }
    }

    fun create(mode: ReadingMode, activity: ReaderActivity, @ColorInt seedColor: Int?): Viewer {
        val isText = activity.isTextChapter()
        // KMK --> Logged with the state the decision was actually made from. `isText` is the whole
        // fork, and its input - the open chapter's loader - is null early and set later, so a report
        // of "my book opens as pictures" is unanswerable without seeing which of those it was.
        // Every log line here names a class through [nameOf], because both spellings have a problem of
        // their own: `KClass.simpleName` is nullable (so it will not drop straight into `sorted()`),
        // and `Any.javaClass` is deprecated on a `KClass` receiver. The one total form is a helper.
        val registered = providers.map { nameOf(it) }.sorted()
        logcat {
            "ViewerRegistry: mode=$mode isTextChapter=$isText " +
                "loader=${activity.viewModel.state.value.currentChapter?.pageLoader?.let { nameOf(it) } ?: "null"} " +
                "registered=$registered"
        }
        for (provider in providers.sortedBy { it.order }) {
            if (isText && provider.declinesTextChapters) {
                // Logged, not merely skipped in silence: this is the path that produced a blank
                // reader twice, and one line here is what makes the next report diagnosable.
                logcat(DEBUG) {
                    "ViewerRegistry: ${provider::class.simpleName} cannot render text, declining chapter"
                }
                continue
            }
            val viewer = runCatching { provider.create(mode, activity, seedColor) }.getOrNull()
            logcat {
                "ViewerRegistry:   ${nameOf(provider)}(order=${provider.order}) -> ${viewer?.let { nameOf(it) } ?: "declined"}"
            }
            if (viewer != null) {
                logcat { "ViewerRegistry: viewer chosen = ${nameOf(viewer)} for mode=$mode (isText=$isText)" }
                return viewer
            }
        }
        error(
            if (isText) {
                "No ViewerProvider handled reading mode $mode for a text chapter. The novel viewer " +
                    "must always claim one: refusing to fall back to an image viewer is deliberate."
            } else {
                "No ViewerProvider handled reading mode: $mode"
            },
        )
    }
}

/**
 * Offers the WebGPU viewer. There are exactly two gates:
 *
 * 1. the user's high-quality switch is off;
 * 2. WebGPU cannot run on this device at all - it failed to initialise, or a lost device could
 *    not be rebuilt (the reason `WebGpuRenderer.unavailableReason` names).
 *
 * **There is no health gate and no per-device fallback.** The WebGPU renderer is the reader's
 * standard viewer; a session that loses its GPU device is recovered, not remembered against the
 * device. A previous session's dead device is rebuilt right here before the decision, because this
 * is the one place that can do it before a viewer exists.
 *
 * Reason 2 is logged on the `ViewerRegistry` line **and**, for a user with only a phone, to the
 * in-app log - a reader that cannot open the renderer is indistinguishable from a bug, so the
 * sentence says why. Reason 1 is silent on purpose: it is the switch doing its job.
 */
private object WebGpuProvider : ViewerProvider {
    override val order: Int = 0
    override val declinesTextChapters: Boolean get() = true

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        // KMK --> The renderer is offered wherever WebGPU itself is available; a device is no
        // longer refused for its RAM, and it is no longer refused for a bad history. The user's
        // high-quality toggle is the only preference gate.
        // KMK <--
        val basePreferences = globalAppGraph.basePreferences
        val wantsHighQuality = basePreferences.highQualityRenderer().get()
        // KMK --> Diagnostic only: a session that gave up on its GPU device (or died natively
        // before it could close) leaves a marker; consuming it here writes the previous session's
        // unclean end to the in-app log. It deliberately does not change the decision below.
        WebGpuHealth.commitUncleanSession(activity)
        // KMK <--
        // KMK --> The probe is a lambda because it initialises the GPU device, and it also rebuilds
        // a dead device: without this, a device that was lost while the previous reader was closing
        // would decline this reader for the rest of the process instead of starting fresh.
        val offer = webGpuOfferFor(wantsHighQuality) {
            if (runCatching { ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.isAvailable }.getOrDefault(false)) {
                true
            } else {
                // A *latched init failure* is a device that cannot run WebGPU at all; retrying it
                // on every reader open would trade one fast decline for an adapter request each
                // time. Only a renderer that initialised and then lost its device is rebuilt.
                val canRebuild = runCatching {
                    ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.initError == null
                }.getOrDefault(false)
                canRebuild &&
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            // The device is process-wide — the companion owns the instance, the
                            // adapter and the device — but `reinit` is an instance method, so a
                            // throwaway renderer is the handle this pre-viewer probe uses.
                            // `recreateDeviceLocked` touches only that shared state and the
                            // process-wide pipeline caches, never the handle's own surface.
                            ca.mpreg.webgpuviewer.renderer.WebGpuRenderer().reinit()
                        }
                    }.getOrDefault(false) &&
                    runCatching {
                        ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.isAvailable
                    }.getOrDefault(false)
            }
        }
        when (offer) {
            WebGpuOffer.SWITCH_OFF -> return null

            WebGpuOffer.RENDERER_UNAVAILABLE -> {
                // `isAvailable` is false for a latched init failure or a device that could not be
                // rebuilt; `unavailableReason` says which, and it is the only place that sentence
                // exists. This is the only decline: there is no other renderer for the reader.
                val reason = "WebGPU viewer declined: renderer unavailable - " +
                    runCatching { ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.unavailableReason }
                        .getOrNull().orEmpty().ifEmpty { "unknown reason" }
                logcat(LogPriority.INFO) { reason }
                xLogE(reason)
                return null
            }

            WebGpuOffer.OFFERED -> Unit
        }
        // KMK <--
        // KMK --> The advanced switch is read after the renderer probe, because the probe is
        // what initialises the adapter the library's PowerVR override keys on. PowerVR stays off
        // even when this preference is on; the effective value is logged with the session.
        val wantsTileCache = basePreferences.webGpuTileCache().get()
        ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.requestTileCache(wantsTileCache)
        logcat {
            "WebGpuProvider: tileCacheRequested=$wantsTileCache " +
                "effective=${ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.tileCacheUsable} " +
                "reason=${ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.tileCacheOffReason.ifEmpty { "on" }}"
        }
        // KMK <--
        return try {
            when (mode) {
                ReadingMode.LEFT_TO_RIGHT -> WebGpuViewer(activity, isReversed = false, isVertical = false)
                ReadingMode.RIGHT_TO_LEFT -> WebGpuViewer(activity, isReversed = true, isVertical = false)
                ReadingMode.VERTICAL -> WebGpuViewer(activity, isReversed = false, isVertical = true)
                ReadingMode.WEBTOON -> WebGpuViewerContinuous(activity, useGap = false)
                ReadingMode.CONTINUOUS_VERTICAL -> WebGpuViewerContinuous(activity, useGap = true)
                ReadingMode.DEFAULT -> null
            }
        } catch (e: Throwable) {
            android.util.Log.w("ReadingMode", "WebGPU viewer failed, falling back to pager", e)
            xLogE("WebGPU viewer failed to build, falling back to the legacy pager", e)
            null
        }
    }
}

private object LegacyProvider : ViewerProvider {
    override val order: Int = 100
    override val declinesTextChapters: Boolean get() = true

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        return when (mode) {
            ReadingMode.LEFT_TO_RIGHT -> L2RPagerViewer(activity, seedColor = seedColor)
            ReadingMode.RIGHT_TO_LEFT -> R2LPagerViewer(activity, seedColor = seedColor)
            ReadingMode.VERTICAL -> VerticalPagerViewer(activity, seedColor = seedColor)
            ReadingMode.WEBTOON -> WebtoonViewer(activity, seedColor = seedColor)
            ReadingMode.CONTINUOUS_VERTICAL -> WebtoonViewer(activity, isContinuous = false, seedColor = seedColor)
            ReadingMode.DEFAULT -> null
        }
    }
}
