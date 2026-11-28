package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The provider's gate order - the single decision behind which viewer a reader gets.
 *
 * There are exactly two gates now, and their order matters: the user's switch outranks the
 * renderer probe, because probing initialises the GPU device. **There is deliberately no health
 * gate**: the WebGPU renderer is the reader's standard viewer, and a device's past sessions can
 * never demote it to the legacy pager. That is asserted in `WebGpuFallbackScenarioTest`.
 */
// The probe flag is instance state shared by the helper below, so methods of this class run on one
// thread; the policy itself is pure and needs no isolation from other classes.
@Execution(ExecutionMode.SAME_THREAD)
class WebGpuOfferTest {

    private var probed = false

    private fun offer(
        wantsHighQuality: Boolean = true,
        available: Boolean = true,
    ) = webGpuOfferFor(wantsHighQuality) {
        probed = true
        available
    }

    @Test
    fun `a wanted, available renderer is offered`() {
        probed = false
        offer() shouldBe WebGpuOffer.OFFERED
        probed.shouldBeTrue()
    }

    @Test
    fun `the switch being off declines without probing the renderer`() {
        probed = false
        offer(wantsHighQuality = false) shouldBe WebGpuOffer.SWITCH_OFF
        // The probe initialises the device; a reader that will not use it must not create one.
        probed.shouldBeFalse()
    }

    @Test
    fun `an unavailable renderer is the last gate`() {
        probed = false
        offer(available = false) shouldBe WebGpuOffer.RENDERER_UNAVAILABLE
        probed.shouldBeTrue()
    }

    @Test
    fun `the switch outranks an unavailable renderer`() {
        offer(wantsHighQuality = false, available = false) shouldBe WebGpuOffer.SWITCH_OFF
    }

    @Test
    fun `the offer has no health state to consult`() {
        // The signature is the assertion: `webGpuOfferFor` takes the switch and the probe only.
        // A re-added strike count would have to change this file, `WebGpuOfferHarness` and the
        // provider, which is the point of pinning it.
        offer() shouldBe WebGpuOffer.OFFERED
    }
}
