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
 * Offers the WebGPU viewer, in this order, and stops at the first reason:
 *
 * 1. the user's high-quality switch is off;
 * 2. this build has recorded [WebGpuHealth.DISABLE_STRIKES] degraded sessions on this device;
 * 3. the renderer cannot be initialised or has lost its device;
 * 4. constructing the viewer threw.
 *
 * Reasons 2-4 are logged on the `ViewerRegistry` line **and**, for a user with only a phone, to the
 * in-app log - a reader that silently opens on the legacy pager with the WebGPU settings still
 * visible is indistinguishable from a bug, which is exactly how it was reported. Each reason has
 * its own sentence so a dump names it without needing the reader's source. Reason 1 is silent on
 * purpose: it is the switch doing its job, not a failure.
 */
private object WebGpuProvider : ViewerProvider {
    override val order: Int = 0
    override val declinesTextChapters: Boolean get() = true

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        // KMK --> The renderer is offered wherever WebGPU itself is available; a device is no
        // longer refused for its RAM. A device without the headroom fails at runtime, and
        // WebGpuHealth turns repeat dead sessions into the legacy viewer instead of the same
        // black screen every launch. The user's high-quality toggle is the only gate here.
        // KMK <--
        val basePreferences = globalAppGraph.basePreferences
        val wantsHighQuality = basePreferences.highQualityRenderer().get()
        // KMK --> A session that gave up on its GPU device writes a marker that outlives the
        // process (a native SIGILL never reaches `destroy()`). Consume it before reading the
        // strike count, so a device that crashes every launch still accumulates its strikes and
        // falls back to the legacy viewer instead of opening the same black screen forever.
        WebGpuHealth.commitUncleanSession(activity)
        // KMK <--
        // KMK --> The gate itself is [webGpuOfferFor], a pure function: the order (switch, health,
        // renderer probe) is load-bearing and asserted offline. The probe is a lambda because it
        // initialises the GPU device, so it must not run for a reader that was never going to use
        // it. Sessions whose GPU device keeps dying without recovery record strikes
        // (WebGpuHealth); once they stack up, stop offering the renderer, so the reader opens
        // instead of repeating the same black screen every launch. The count is scoped to the
        // build that recorded it, so a fixed build is not held to an older one's verdict.
        val strikes = if (wantsHighQuality) WebGpuHealth.strikeCount(activity) else 0
        val offer = webGpuOfferFor(wantsHighQuality, strikes) {
            try {
                ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.isAvailable
            } catch (_: Throwable) {
                false
            }
        }
        when (offer) {
            WebGpuOffer.SWITCH_OFF -> return null
            WebGpuOffer.HEALTH_DISABLED -> {
                val reason = "WebGPU viewer declined: this build recorded $strikes/" +
                    "${WebGpuHealth.DISABLE_STRIKES} degraded sessions on this device - " +
                    "using the legacy viewer (About easter egg or toggling the renderer off/on resets it)"
                logcat(LogPriority.INFO) { reason }
                xLogE(reason)
                return null
            }

            WebGpuOffer.RENDERER_UNAVAILABLE -> {
                // `isAvailable` is false for a latched init failure or a lost device;
                // `unavailableReason` says which, and it is the only place that sentence exists.
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
