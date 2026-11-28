// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/**
 * Why the WebGPU viewer was offered, or which gate stopped it first.
 *
 * This is the entire "the reader keeps falling back" decision, extracted from `WebGpuProvider` so
 * it can be asserted offline (`WebGpuOfferHarness`, `WebGpuOfferTest`).
 *
 * **There is deliberately no health gate.** The WebGPU renderer is the reader's standard viewer,
 * not an optional upgrade: a session that lost its GPU device is answered by recovering the
 * device (see `WebGpuRenderer.noteObservedDeviceLoss` and the app's recovery chain), never by
 * silently demoting the user to the legacy pager on later launches. The old latch made "the
 * renderer just stops working" a permanent, unexplainable state after two bad sessions; it is
 * gone. Degraded sessions are still written to the in-app log for diagnosis, but they cannot
 * decide what viewer a reader gets.
 *
 * The order is load-bearing: the switch is the user's choice and outranks everything, and the
 * renderer probe is last because **running it initialises the GPU device** - asking "is WebGPU
 * available?" on a reader that was never going to use it is exactly the kind of hidden cost this
 * ordering avoids.
 */
internal enum class WebGpuOffer {
    /** The renderer can be used; `WebGpuProvider` builds the viewer. */
    OFFERED,

    /** The user's high-quality switch is off. Not a failure - the switch doing its job. */
    SWITCH_OFF,

    /**
     * WebGPU cannot run on this device at all (`unavailableReason` says which): the runtime
     * failed to initialise, or the device could not be rebuilt. The only case where another
     * viewer is used, because there is nothing to recover.
     */
    RENDERER_UNAVAILABLE,
}

/**
 * The provider's gate, in order.
 *
 * [rendererAvailable] is a lambda rather than a value so it is only evaluated when the first gate
 * lets the decision reach it: probing calls `WebGpuRenderer.isAvailable`, whose first read
 * initialises the whole GPU device.
 */
internal inline fun webGpuOfferFor(
    wantsHighQuality: Boolean,
    rendererAvailable: () -> Boolean,
): WebGpuOffer = when {
    !wantsHighQuality -> WebGpuOffer.SWITCH_OFF
    !rendererAvailable() -> WebGpuOffer.RENDERER_UNAVAILABLE
    else -> WebGpuOffer.OFFERED
}
// KMK <--
