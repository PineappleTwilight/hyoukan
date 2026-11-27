package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The strike record's transitions - the part of the WebGPU fallback that decided, twice in the
 * field, that a device should never be offered the renderer again.
 *
 * The rules under test: a count belongs to the build that recorded it, strikes are consecutive, and
 * the count is capped. See `WebGpuHealthPolicy` for why each one exists.
 */
@Execution(ExecutionMode.CONCURRENT)
class WebGpuHealthPolicyTest {

    private val build = "1.25.3-12000"
    private val otherBuild = "1.25.3-11981"

    private fun record(strikes: Int, build: String? = this.build) =
        WebGpuHealthPolicy.Record(strikes = strikes, build = build)

    // --- the threshold ------------------------------------------------------------------------

    @Test
    fun `a fresh install has no strikes`() {
        WebGpuHealthPolicy.strikesForBuild(WebGpuHealthPolicy.EMPTY, build) shouldBe 0
        WebGpuHealthPolicy.isDisabled(WebGpuHealthPolicy.EMPTY, build).shouldBeFalse()
    }

    @Test
    fun `one strike does not disable the renderer`() {
        val once = WebGpuHealthPolicy.afterStrike(WebGpuHealthPolicy.EMPTY, build)
        WebGpuHealthPolicy.strikesForBuild(once, build) shouldBe 1
        WebGpuHealthPolicy.isDisabled(once, build).shouldBeFalse()
    }

    @Test
    fun `two strikes disable the renderer`() {
        val twice = WebGpuHealthPolicy.afterStrike(
            WebGpuHealthPolicy.afterStrike(WebGpuHealthPolicy.EMPTY, build),
            build,
        )
        WebGpuHealthPolicy.strikesForBuild(twice, build) shouldBe WebGpuHealthPolicy.DISABLE_STRIKES
        WebGpuHealthPolicy.isDisabled(twice, build).shouldBeTrue()
    }

    @Test
    fun `the count is capped at the disable threshold`() {
        // Past the cap the counter says nothing new, and an uncapped count could overflow on a
        // device that keeps failing.
        var record = WebGpuHealthPolicy.EMPTY
        repeat(50) { record = WebGpuHealthPolicy.afterStrike(record, build) }
        WebGpuHealthPolicy.strikesForBuild(record, build) shouldBe WebGpuHealthPolicy.DISABLE_STRIKES
        record.strikes shouldBe WebGpuHealthPolicy.DISABLE_STRIKES
    }

    // --- the build scope ----------------------------------------------------------------------

    @Test
    fun `a count recorded by another build is not this build's`() {
        val foreign = record(strikes = 2, build = otherBuild)
        WebGpuHealthPolicy.shouldDiscard(foreign, build).shouldBeTrue()
        WebGpuHealthPolicy.strikesForBuild(foreign, build) shouldBe 0
        WebGpuHealthPolicy.isDisabled(foreign, build).shouldBeFalse()
    }

    @Test
    fun `a strike after a foreign record starts at one for this build`() {
        // The foreign count of 2 must not be added to: the previous build's verdict does not
        // describe this one, and +1 would disable the renderer before it was ever tried.
        val next = WebGpuHealthPolicy.afterStrike(record(strikes = 2, build = otherBuild), build)
        next.strikes shouldBe 1
        next.build shouldBe build
        WebGpuHealthPolicy.isDisabled(next, build).shouldBeFalse()
    }

    @Test
    fun `an untagged count is discarded - it belongs to a build that predates the tag`() {
        // Every count that existed before the tag is unattributable, and those are exactly the
        // devices a previous build's latch left stuck on the legacy reader. Discarding them costs
        // a genuinely broken device one failed session; honouring them costs every stuck device
        // the renderer, which is the bug this rule exists to fix.
        val untagged = record(strikes = 2, build = null)
        WebGpuHealthPolicy.shouldDiscard(untagged, build).shouldBeTrue()
        WebGpuHealthPolicy.strikesForBuild(untagged, build) shouldBe 0
        WebGpuHealthPolicy.isDisabled(untagged, build).shouldBeFalse()
    }

    @Test
    fun `an empty record is not worth erasing`() {
        WebGpuHealthPolicy.shouldDiscard(WebGpuHealthPolicy.EMPTY, build).shouldBeFalse()
    }

    @Test
    fun `the same build is never discarded`() {
        WebGpuHealthPolicy.shouldDiscard(record(strikes = 1), build).shouldBeFalse()
        WebGpuHealthPolicy.strikesForBuild(record(strikes = 1), build) shouldBe 1
    }

    @Test
    fun `an unclean-session marker only counts for the build that wrote it`() {
        // The marker survives a process crash, so it is the only verdict a SIGILL-struck session
        // can leave behind. It must obey the same build scope as the strike count.
        WebGpuHealthPolicy.uncleanSessionCounts(build, build).shouldBeTrue()
        WebGpuHealthPolicy.uncleanSessionCounts(otherBuild, build).shouldBeFalse()
        WebGpuHealthPolicy.uncleanSessionCounts(null, build).shouldBeFalse()
    }

    @Test
    fun `a record from a newer build is discarded too`() {
        // Downgrades happen (a bad beta rolled back locally); the comparison is equality, not
        // version ordering, so either direction gets a fresh try.
        WebGpuHealthPolicy.shouldDiscard(record(strikes = 2, build = "1.25.4-13001"), build)
            .shouldBeTrue()
    }

    // --- consecutive, not cumulative ----------------------------------------------------------

    @Test
    fun `a clean session clears the count`() {
        val cleared = WebGpuHealthPolicy.afterHealthySession(record(strikes = 1))
        WebGpuHealthPolicy.strikesForBuild(cleared, build) shouldBe 0
        cleared.build shouldBe null
    }

    @Test
    fun `a clean session clears the disable as well`() {
        val cleared = WebGpuHealthPolicy.afterHealthySession(record(strikes = 2))
        WebGpuHealthPolicy.isDisabled(cleared, build).shouldBeFalse()
    }

    @Test
    fun `a clean session on an empty record does not allocate a new one`() {
        // The common case: a healthy session should cost a read, not a preference write.
        val empty = WebGpuHealthPolicy.EMPTY
        (WebGpuHealthPolicy.afterHealthySession(empty) === empty).shouldBeTrue()
    }

    @Test
    fun `a clean session after foreign strikes returns the empty record`() {
        val cleared = WebGpuHealthPolicy.afterHealthySession(record(strikes = 2, build = otherBuild))
        cleared shouldBe WebGpuHealthPolicy.EMPTY
    }

    @Test
    fun `a reset returns the empty record`() {
        WebGpuHealthPolicy.afterReset() shouldBe WebGpuHealthPolicy.EMPTY
    }

    // --- the contract the provider and the viewer share ----------------------------------------

    @Test
    fun `the disable threshold is the one the provider reads`() {
        // WebGpuHealth's constants are aliases of these; a change here must move both.
        WebGpuHealth.DISABLE_STRIKES shouldBe WebGpuHealthPolicy.DISABLE_STRIKES
        WebGpuHealth.SESSION_LOSS_STRIKE_THRESHOLD shouldBe
            WebGpuHealthPolicy.SESSION_LOSS_STRIKE_THRESHOLD
    }

    @Test
    fun `a negative stored count reads as zero`() {
        // Defensive: a corrupted preference file must not produce a negative "healthy" reading.
        WebGpuHealthPolicy.strikesForBuild(record(strikes = -3), build) shouldBe 0
    }
}
