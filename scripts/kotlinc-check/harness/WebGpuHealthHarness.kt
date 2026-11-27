// Runs the REAL strike-record state machine (compiled straight from
// app/.../viewer/webgpu/WebGpuHealthPolicy.kt).
//
// `WebGpuHealth` (the Android half) owns SharedPreferences and the app build name, so it cannot be
// linked here; its decisions are all delegates to this file, which is pure. The rules pinned here
// are the two that produced field reports:
//
//   - a count belongs to the build that recorded it (an older build's verdict must not outlive the
//     fix), and
//   - strikes are *consecutive* - a clean close clears the dial, so one bad session months ago
//     cannot count toward disabling a device that has read fine ever since.

import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.SessionFacts
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.SessionVerdict
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuHealthPolicy
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuHealthPolicy.Record
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.sessionVerdict

private var failures = 0

private fun check(label: String, cond: Boolean) {
    if (!cond) {
        failures++
        println("FAIL: $label")
    } else {
        println("ok: $label")
    }
}

private const val BUILD = "1.25.3-12000"
private const val OTHER_BUILD = "1.25.3-11981"

private fun record(strikes: Int, build: String? = BUILD) = Record(strikes = strikes, build = build)

/** One closed reader session, applied exactly as `WebGpuViewer.destroy` does. */
private fun apply(record: Record, facts: SessionFacts): Record =
    if (sessionVerdict(facts) == SessionVerdict.HEALTHY) {
        WebGpuHealthPolicy.afterHealthySession(record)
    } else {
        WebGpuHealthPolicy.afterStrike(record, BUILD)
    }

private fun chapterToTheEnd() = SessionFacts(
    deadAtExit = false,
    surfaceParks = 0,
    decodedPages = 40,
    durationMs = 600_000L,
    anyPageDrawn = true,
    presentedFrames = 12_000,
    deviceLosses = 0,
)

private fun blackScreen() = SessionFacts(
    deadAtExit = false,
    surfaceParks = 0,
    decodedPages = 6,
    durationMs = 9_000L,
    anyPageDrawn = false,
    presentedFrames = 0,
    deviceLosses = 0,
)

private fun offered(record: Record, at: String = BUILD): Boolean =
    WebGpuHealthPolicy.isDisabled(record, at) == false

fun main() {
    // --- the threshold ------------------------------------------------------------------------
    check("a fresh install is not disabled", !WebGpuHealthPolicy.isDisabled(WebGpuHealthPolicy.EMPTY, BUILD))
    check(
        "one strike does not disable",
        !WebGpuHealthPolicy.isDisabled(WebGpuHealthPolicy.afterStrike(WebGpuHealthPolicy.EMPTY, BUILD), BUILD),
    )
    val twice = WebGpuHealthPolicy.afterStrike(
        WebGpuHealthPolicy.afterStrike(WebGpuHealthPolicy.EMPTY, BUILD),
        BUILD,
    )
    check("two strikes disable", WebGpuHealthPolicy.isDisabled(twice, BUILD))
    check("two strikes equal the documented threshold", twice.strikes == WebGpuHealthPolicy.DISABLE_STRIKES)

    var capped = WebGpuHealthPolicy.EMPTY
    repeat(50) { capped = WebGpuHealthPolicy.afterStrike(capped, BUILD) }
    check("the count is capped", capped.strikes == WebGpuHealthPolicy.DISABLE_STRIKES)

    // --- the build scope ----------------------------------------------------------------------
    val foreign = record(strikes = 2, build = OTHER_BUILD)
    check("a foreign count is discarded", WebGpuHealthPolicy.shouldDiscard(foreign, BUILD))
    check("a foreign count reads as zero", WebGpuHealthPolicy.strikesForBuild(foreign, BUILD) == 0)
    check("a foreign count does not disable", !WebGpuHealthPolicy.isDisabled(foreign, BUILD))
    val nextAfterForeign = WebGpuHealthPolicy.afterStrike(foreign, BUILD)
    check("a strike after a foreign count starts at one", nextAfterForeign.strikes == 1)
    check("a strike re-tags the record", nextAfterForeign.build == BUILD)
    check("a strike after a foreign count does not disable", !WebGpuHealthPolicy.isDisabled(nextAfterForeign, BUILD))

    val untagged = record(strikes = 2, build = null)
    check("an untagged count is discarded", WebGpuHealthPolicy.shouldDiscard(untagged, BUILD))
    check("an untagged count reads as zero", WebGpuHealthPolicy.strikesForBuild(untagged, BUILD) == 0)

    check("an empty record is not worth erasing", !WebGpuHealthPolicy.shouldDiscard(WebGpuHealthPolicy.EMPTY, BUILD))
    check("this build's record is kept", !WebGpuHealthPolicy.shouldDiscard(record(strikes = 1), BUILD))
    check("a newer build's record is discarded too", WebGpuHealthPolicy.shouldDiscard(record(2, "1.25.4-13001"), BUILD))
    check("a negative count reads as zero", WebGpuHealthPolicy.strikesForBuild(record(-3), BUILD) == 0)

    // --- the crash-surviving marker ------------------------------------------------------------
    check("an unclean marker from this build counts", WebGpuHealthPolicy.uncleanSessionCounts(BUILD, BUILD))
    check(
        "an unclean marker from another build does not",
        !WebGpuHealthPolicy.uncleanSessionCounts(OTHER_BUILD, BUILD),
    )
    check("no unclean marker does not count", !WebGpuHealthPolicy.uncleanSessionCounts(null, BUILD))

    // --- consecutive, not cumulative ----------------------------------------------------------
    val cleared = WebGpuHealthPolicy.afterHealthySession(record(strikes = 1))
    check("a clean session clears one strike", WebGpuHealthPolicy.strikesForBuild(cleared, BUILD) == 0)
    check(
        "a clean session clears the disable",
        !WebGpuHealthPolicy.isDisabled(WebGpuHealthPolicy.afterHealthySession(record(strikes = 2)), BUILD),
    )
    val empty = WebGpuHealthPolicy.EMPTY
    check(
        "a clean session on an empty record does not allocate",
        WebGpuHealthPolicy.afterHealthySession(empty) === empty,
    )
    check(
        "a clean session after a foreign count returns empty",
        WebGpuHealthPolicy.afterHealthySession(foreign) == WebGpuHealthPolicy.EMPTY,
    )

    // --- reset --------------------------------------------------------------------------------
    check("a reset returns the empty record", WebGpuHealthPolicy.afterReset() == WebGpuHealthPolicy.EMPTY)

    // --- the shared vocabulary ----------------------------------------------------------------
    check("the loss threshold is three", WebGpuHealthPolicy.SESSION_LOSS_STRIKE_THRESHOLD == 3)
    check("the disable threshold is two", WebGpuHealthPolicy.DISABLE_STRIKES == 2)

    // --- the reported scenario, end to end ----------------------------------------------------
    // A chapter read to the end and closed on its transition page: every page drew, so it is a
    // healthy session however many times it happens. The old exit-page check counted each one.
    var scenario = WebGpuHealthPolicy.EMPTY
    repeat(50) { scenario = apply(scenario, chapterToTheEnd()) }
    check("fifty chapters read to the end leave the renderer offered", offered(scenario))

    scenario = apply(WebGpuHealthPolicy.EMPTY, blackScreen())
    check("one black screen still offers the renderer", offered(scenario))
    scenario = apply(scenario, blackScreen())
    check("two black screens disable it", !offered(scenario))
    check("a new build releases a disabled device", offered(scenario, at = "1.25.3-12001"))

    var forgiving = apply(WebGpuHealthPolicy.EMPTY, blackScreen())
    forgiving = apply(forgiving, chapterToTheEnd())
    forgiving = apply(forgiving, blackScreen())
    check("a healthy session between two black screens keeps the renderer", offered(forgiving))

    val untaggedHistorical = Record(strikes = 2, build = null)
    check("an untagged historical latch releases on the next read", offered(untaggedHistorical))

    if (failures == 0) {
        println("ALL PASS")
    } else {
        println("$failures FAILURES")
        kotlin.system.exitProcess(1)
    }
}
