// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/**
 * The strike record's state machine, with no Android import.
 *
 * `WebGpuHealth` is the Android half - SharedPreferences, the app build name, the in-app log - and
 * this file is the half that decides. The split exists because the decision is what went wrong
 * twice in the field and it could not be asserted offline before: an Android-only object can only
 * be exercised on a device, and the failure mode here is a reader that silently opens on the legacy
 * pager. `WebGpuHealthHarness` drives every transition of this file on a plain JVM.
 *
 * ## The three rules
 *
 * 1. **A count belongs to the build that recorded it.** A latch that outlives the defect it was
 *    recorded for is a latch the fix cannot lift, so a record from another build - or one written
 *    before the tag existed, and therefore unattributable - reads as zero.
 * 2. **Strikes are consecutive.** A clean close clears the dial, or one unlucky session months ago
 *    counts toward disabling a device that has read fine ever since.
 * 3. **The count is capped at [DISABLE_STRIKES].** Past the cap it says nothing new, and an
 *    unbounded counter would overflow on a device that keeps failing.
 */
internal object WebGpuHealthPolicy {

    /** Strikes before the WebGPU viewer stops being offered. */
    const val DISABLE_STRIKES: Int = 2

    /**
     * Device losses inside one reader session beyond which the session itself counts as broken,
     * recovered or not: a healthy device loses the GPU at most once per session (backgrounding),
     * and a device that loses it three times is thrashing the recovery ladder - the user saw every
     * one of those as a stall.
     */
    const val SESSION_LOSS_STRIKE_THRESHOLD: Int = 3

    /** The persisted record: a count and the build that wrote it, or the untouched default. */
    data class Record(val strikes: Int, val build: String?)

    val EMPTY = Record(strikes = 0, build = null)

    /**
     * Whether the stored count cannot describe this build and should be erased on read.
     *
     * An untagged count counts as unattributable. The tag arrived with this rule, so every count
     * that existed before it is untagged - and those are exactly the latched devices the rule
     * exists to release. Erasing them costs a genuinely broken device one failed session; keeping
     * them costs every previously-struck device the renderer.
     */
    fun shouldDiscard(record: Record, build: String): Boolean =
        record.strikes != 0 && record.build != build

    /**
     * The count this build should act on: its own, never another build's.
     *
     * An untagged count reads as zero too - see [shouldDiscard] for why that is the safe answer.
     */
    fun strikesForBuild(record: Record, build: String): Int =
        if (record.build == build) record.strikes.coerceIn(0, DISABLE_STRIKES) else 0

    fun isDisabled(record: Record, build: String): Boolean =
        strikesForBuild(record, build) >= DISABLE_STRIKES

    /** One degraded session: the count moves by one, capped, and the record is re-tagged. */
    fun afterStrike(record: Record, build: String): Record =
        Record(
            strikes = (strikesForBuild(record, build) + 1).coerceAtMost(DISABLE_STRIKES),
            build = build,
        )

    /**
     * One clean session: the dial returns to zero.
     *
     * Returns the same instance when there is nothing to clear, so the caller can skip the write -
     * a healthy session is the common case and should cost a read, not a write.
     */
    fun afterHealthySession(record: Record): Record =
        if (record.strikes <= 0 && record.build == null) record else EMPTY

    /** A user- or upgrade-driven reset. */
    fun afterReset(): Record = EMPTY

    /**
     * Whether a crash-surviving "degraded session in flight" marker counts for [build].
     *
     * The marker is written while a session that gave up on its GPU device is still running, so
     * a native crash cannot lose it. It obeys the same build scope as the strike count: a build
     * that replaced the defect must not inherit the mark, or the fix is never tried.
     */
    fun uncleanSessionCounts(markerBuild: String?, build: String): Boolean =
        markerBuild != null && markerBuild == build
}
// KMK <--
