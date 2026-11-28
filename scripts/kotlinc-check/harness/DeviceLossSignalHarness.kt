// Runs the REAL DeviceLossSignal (compiled straight from the library's DeviceLossSignal.kt).
//
// This is the discriminator that decides whether a failed native call is treated as a dead GPU
// device - which rebuilds the device and resets every decoded page - or as an ordinary failure
// that should not. Both directions are costly when wrong:
//
//   - a real loss that is not recognised is the 2026-11-27 PowerVR session: the device was dead,
//     no callback arrived, and the reader presented 350 black frames into the same device;
//   - a plain failure that IS treated as a loss throws away a healthy device for nothing and, on
//     a low-memory phone, buys the reader a visible re-decode of the page being read.
//
// The messages below are Dawn's own strings (verified against the bundled library: it contains
// "%s is lost." and "Buffer was destroyed before mapping was resolved.").

import ca.mpreg.webgpuviewer.renderer.DeviceLossSignal

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

private fun lost(message: String?) = DeviceLossSignal.isLostDeviceMessage(message)

fun main() {
    // --- the exact field message, status prefix included ------------------------------------
    // The 2026-11-27 M8_4G readback threw `4: [Device] is lost.`; 4 is a status code the app's
    // Status object does not name, and the text after it is the proof.
    check("the 2026-11-27 readback message is a loss", lost("4: [Device] is lost."))
    check("Dawn's bare device message is a loss", lost("[Device] is lost."))
    check("a message without the bracket is a loss", lost("Device is lost."))
    check("a destroyed device is a loss", lost("Device was destroyed."))
    check("matching ignores case", lost("[DEVICE] IS LOST."))
    check("surrounding text does not hide the answer", lost("map failed 4: [Device] is lost."))

    // --- what must not be mistaken for a device loss -----------------------------------------
    // A buffer can be destroyed while its map is pending; that is a lifetime bug in one buffer,
    // not a dead device. The readback in WebGpuRenderer is exactly where both can occur.
    check(
        "a destroyed buffer is not a device loss",
        !lost("Buffer was destroyed before mapping was resolved."),
    )
    check(
        "an unmapped buffer is not a device loss",
        !lost("Buffer was unmapped before mapping was resolved."),
    )
    check(
        "a mapping-range rejection is not a device loss",
        !lost("Mapping range (offset:0, size: 4) doesn't fit in the size (256) of buffer [page-readback]."),
    )
    check(
        "a usage validation error is not a device loss",
        !lost("The usage includes CopySrc; the texture was created without it."),
    )
    // A lost *surface* is recovered by rebuilding the swapchain, not by resetting every page.
    check("a lost surface is not a lost device", !lost("Surface is lost."))
    check("a timeout is not a device loss", !lost("4: Timeout"))

    // --- degenerate input ---------------------------------------------------------------------
    check("null is not a loss", !lost(null))
    check("blank is not a loss", !lost(""))
    check("whitespace is not a loss", !lost("   "))

    // --- the observed reason sentinel ------------------------------------------------------------------
    // Hosts tell an observed loss from a driver-reported one by comparing the reason. Dawn only
    // reports the enum values 0 and 1, so the sentinel must sit outside them.
    check("the observed reason is negative", DeviceLossSignal.REASON_OBSERVED < 0)
    check("the observed reason is not a driver reason", DeviceLossSignal.REASON_OBSERVED != 0 &&
        DeviceLossSignal.REASON_OBSERVED != 1)

    // --- which backend a recovery asks for -----------------------------------------------------------
    // PowerVR's failures are Vulkan-shaped, and the swapchain ladder has already proven Dawn's GLES
    // backend on these devices. Rebuilding the same Vulkan path is what turns one fault into a
    // session-long loop; moving to GLES is the way out.
    check(
        "an observed PowerVR fault moves the recovery to GLES",
        DeviceLossSignal.shouldSwitchToGles(
            powerVr = true,
            alreadyOnGles = false,
            reason = DeviceLossSignal.REASON_OBSERVED,
            message = "4: [Device] is lost.",
        ),
    )
    check(
        "a PowerVR device-memory fault moves the recovery to GLES",
        DeviceLossSignal.shouldSwitchToGles(
            powerVr = true,
            alreadyOnGles = false,
            reason = 0,
            message = "vkQueueSubmit failed with VK_ERROR_OUT_OF_DEVICE_MEMORY",
        ),
    )
    check(
        "a PowerVR device-lost callback moves the recovery to GLES",
        DeviceLossSignal.shouldSwitchToGles(
            powerVr = true,
            alreadyOnGles = false,
            reason = 0,
            message = "VK_ERROR_DEVICE_LOST",
        ),
    )
    check(
        "a backgrounding loss keeps the current backend",
        !DeviceLossSignal.shouldSwitchToGles(
            powerVr = true,
            alreadyOnGles = false,
            reason = 0,
            message = "Device lost",
        ),
    )
    check(
        "a non-PowerVR device keeps its backend",
        !DeviceLossSignal.shouldSwitchToGles(
            powerVr = false,
            alreadyOnGles = false,
            reason = DeviceLossSignal.REASON_OBSERVED,
            message = "4: [Device] is lost.",
        ),
    )
    check(
        "a session already on GLES is not switched again",
        !DeviceLossSignal.shouldSwitchToGles(
            powerVr = true,
            alreadyOnGles = true,
            reason = DeviceLossSignal.REASON_OBSERVED,
            message = "4: [Device] is lost.",
        ),
    )

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}
