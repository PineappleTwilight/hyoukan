// Runs the REAL provider gate (compiled straight from
// app/.../viewer/webgpu/WebGpuOffer.kt).
//
// This is the decision behind "the WebGPU reader keeps falling back": which gate stopped the
// renderer, and in what order. There are two gates: the user's switch, then the renderer probe.
// **There is no health gate** - the record in WebGpuHealth is diagnostic only, so no history of
// degraded sessions can demote the renderer to the legacy viewer. The probe must not run when the
// switch has already declined, because reading `WebGpuRenderer.isAvailable` initialises the GPU
// device.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuOffer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.webGpuOfferFor

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

private var probed = false

private fun offer(
    wantsHighQuality: Boolean = true,
    available: Boolean = true,
): WebGpuOffer = webGpuOfferFor(wantsHighQuality) {
    probed = true
    available
}

fun main() {
    // --- the happy path ----------------------------------------------------------------------
    probed = false
    check("a wanted, available renderer is offered", offer() == WebGpuOffer.OFFERED)
    check("the offered path probes the renderer", probed)

    // --- the switch ------------------------------------------------------------------------
    probed = false
    check("the switch being off declines", offer(wantsHighQuality = false) == WebGpuOffer.SWITCH_OFF)
    check("the switch-off path does not probe the renderer", !probed)
    check(
        "the switch outranks an unavailable renderer",
        offer(wantsHighQuality = false, available = false) == WebGpuOffer.SWITCH_OFF,
    )

    // --- the renderer probe ----------------------------------------------------------------
    probed = false
    check("an unavailable renderer declines", offer(available = false) == WebGpuOffer.RENDERER_UNAVAILABLE)
    check("the unavailable path probes the renderer", probed)

    // --- no health gate --------------------------------------------------------------------
    // The signature carries the assertion: `webGpuOfferFor` takes the switch and the probe only.
    // A re-added strike count has to change this line, the provider and the JUnit test.
    probed = false
    check("a healthy device offers the renderer", offer() == WebGpuOffer.OFFERED)
    check("every offer decision probes the renderer once", probed)

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}
