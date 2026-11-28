package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The rule that replaced the old fallback: **a device's session history can never take the
 * WebGPU renderer away from the reader.**
 *
 * The reported bug was "it falls back to the legacy reader on all devices". The cause was a strike
 * record that, after two degraded sessions, made `WebGpuProvider` decline - and a session counted
 * as degraded for shapes that were not failures (a chapter transition at close, a backgrounded
 * device). The record is now diagnostic only: it still marks degraded sessions in the in-app log,
 * but [webGpuOfferFor] has no health input at all, so no sequence of bad sessions can open the
 * legacy pager. This test drives that sequence through both halves of the policy and asserts the
 * renderer stays offered.
 *
 * The failure is answered where it happens instead: `WebGpuRenderer.noteObservedDeviceLoss` turns
 * any native "device is lost" answer into a real loss, the app's recovery chain rebuilds the
 * device, and a PowerVR fault moves the session onto the GLES backend. See
 * `viewer/webgpu/AGENTS.md`.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuFallbackScenarioTest {

    private val build = "1.25.3-12000"

    /** One closed reader session, applied to the record exactly as `WebGpuViewer.destroy` does. */
    private fun apply(record: WebGpuHealthPolicy.Record, facts: SessionFacts): WebGpuHealthPolicy.Record =
        if (sessionVerdict(facts) == SessionVerdict.HEALTHY) {
            WebGpuHealthPolicy.afterHealthySession(record)
        } else {
            WebGpuHealthPolicy.afterStrike(record, build)
        }

    /** A genuine black screen: pages decoded, the display never presented one. */
    private fun blackScreen() = SessionFacts(
        deadAtExit = false,
        surfaceParks = 0,
        decodedPages = 6,
        durationMs = 9_000L,
        anyPageDrawn = false,
        presentedFrames = 0,
        deviceLosses = 0,
    )

    /** A chapter read from start to end, then closed on the transition page. Every page drew. */
    private fun chapterToTheEnd() = SessionFacts(
        deadAtExit = false,
        surfaceParks = 0,
        decodedPages = 40,
        durationMs = 600_000L,
        anyPageDrawn = true,
        presentedFrames = 12_000,
        deviceLosses = 0,
    )

    /** The provider's actual decision, with no health input to pass. */
    private fun offered(): Boolean =
        webGpuOfferFor(wantsHighQuality = true, rendererAvailable = { true }) == WebGpuOffer.OFFERED

    @Test
    fun `two real black screens still offer the renderer`() {
        var record = apply(WebGpuHealthPolicy.EMPTY, blackScreen())
        record = apply(record, blackScreen())
        // The old latch disabled the renderer here. WebGPU is the standard viewer now.
        record.strikes shouldBe 2
        offered().shouldBeTrue()
    }

    @Test
    fun `no number of degraded sessions disables the renderer`() {
        var record = WebGpuHealthPolicy.EMPTY
        repeat(10) {
            record = apply(record, blackScreen())
            offered().shouldBeTrue()
        }
    }

    @Test
    fun `reading many chapters to the end never affects the offer`() {
        var record = WebGpuHealthPolicy.EMPTY
        repeat(50) {
            record = apply(record, chapterToTheEnd())
            offered().shouldBeTrue()
        }
        // The consecutive rule still holds for the record itself: clean sessions clear it.
        record.strikes shouldBe 0
    }

    @Test
    fun `a healthy session between two bad ones clears the diagnostic count`() {
        var record = apply(WebGpuHealthPolicy.EMPTY, blackScreen())
        record = apply(record, chapterToTheEnd())
        record = apply(record, blackScreen())
        record.strikes shouldBe 1
        offered().shouldBeTrue()
    }
}
