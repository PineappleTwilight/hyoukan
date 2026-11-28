// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import exh.log.xLogE

/**
 * Cross-session record of WebGPU sessions whose device died without coming back.
 *
 * **Diagnostics only.** This record exists so a session that went wrong leaves a trace in the
 * in-app log - the one channel a phone-only user can send. It never decides which viewer a reader
 * gets: the WebGPU renderer is the reader's standard viewer, and a lost GPU device is answered by
 * rebuilding it (`WebGpuRenderer.recoverFromDeviceLoss`, the app's recovery chain and
 * `noteObservedDeviceLoss`), not by demoting the user to the legacy pager on later launches.
 *
 * This object is the Android half - storage, the app's build name, the in-app log. The decision
 * itself is [WebGpuHealthPolicy] (the strike record's transitions) and [sessionVerdict] (whether a
 * closed session counts at all); both are Android-free so `WebGpuHealthHarness` and
 * `SessionHealthHarness` assert them offline.
 *
 * ## What counts as a strike
 *
 * One strike per reader session that closes with the renderer still dead (gated on
 * `activity.isFinishing`, because a phone drops the GPU device on backgrounding as a matter of
 * course and an OS background-kill is not a session the user gave up on), that parked its
 * swapchain, that lost the device [SESSION_LOSS_STRIKE_THRESHOLD] times, or that decoded pages
 * without ever drawing any. A *clean* session clears the count - "consecutive" is the rule.
 *
 * The count is scoped to the build that recorded it (see [currentBuild]), so a fix is never
 * reported as an older build's problem. Every strike and every discarded count is written to the
 * **in-app log** with the session's diagnostics block: the strike says *that* a session went
 * wrong, the block says what it did.
 */
object WebGpuHealth {
    private const val PREFS = "webgpu_health"
    private const val KEY_STRIKES = "dead_device_strikes"
    private const val KEY_BUILD = "dead_device_strikes_build"

    /**
     * A session whose device is dead and whose recovery ladder was exhausted, written while that
     * session is still open.
     *
     * The close-time verdict cannot count a session that never reaches `destroy()`. A native
     * SIGILL (the PowerVR readback crash) kills the process before any Kotlin close path runs, so
     * the device that caused the black screen recorded no strike and every launch opened the same
     * broken renderer again. This marker is the crash-surviving half: it is written when recovery
     * gives up, cleared when a recovery succeeds or the session closes normally, and consumed as
     * one strike on the next reader open when it is still there - i.e. exactly when the process
     * (or the user) ended a session that never recovered.
     */
    private const val KEY_DEGRADED_IN_FLIGHT = "degraded_session_build"

    /** See [WebGpuHealthPolicy.SESSION_LOSS_STRIKE_THRESHOLD]; kept here for call sites. */
    const val SESSION_LOSS_STRIKE_THRESHOLD: Int = WebGpuHealthPolicy.SESSION_LOSS_STRIKE_THRESHOLD

    /**
     * See [WebGpuHealthPolicy.DISABLE_STRIKES]. **Diagnostic only**: nothing gates the renderer on
     * this count any more. It stays because the strike record and its harness still describe the
     * threshold a degraded session is counted against.
     */
    const val DISABLE_STRIKES: Int = WebGpuHealthPolicy.DISABLE_STRIKES

    /**
     * The build a strike count belongs to.
     *
     * `VERSION_NAME` is what a user and a report name, and it carries the CI build number for
     * preview builds. `BUILD_TIME` rides along because it makes **every install** its own scope:
     * a local rebuild of the same commit (same version name) is a new binary, and a fix that only
     * changed uncommitted sources must not inherit the previous binary's latch. Within one
     * installed APK both are constants, so the latch still works across app restarts.
     */
    private fun currentBuild(): String = "${BuildConfig.VERSION_NAME}/${BuildConfig.BUILD_TIME}"

    private fun read(context: Context): WebGpuHealthPolicy.Record {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WebGpuHealthPolicy.Record(
            strikes = prefs.getInt(KEY_STRIKES, 0),
            build = prefs.getString(KEY_BUILD, null),
        )
    }

    private fun write(context: Context, record: WebGpuHealthPolicy.Record) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_STRIKES, record.strikes)
        if (record.build == null) {
            editor.remove(KEY_BUILD)
        } else {
            editor.putString(KEY_BUILD, record.build)
        }
        editor.apply()
    }

    /**
     * Strikes recorded **by this build**, or 0 when the stored count belongs to another one.
     *
     * Discarding the old count is a write, not just a read: leaving it in place would let a
     * reinstall of the older build pick its own latch back up.
     */
    fun strikeCount(context: Context): Int = runCatching {
        val build = currentBuild()
        val record = read(context)
        if (WebGpuHealthPolicy.shouldDiscard(record, build)) {
            write(context, WebGpuHealthPolicy.EMPTY)
            if (record.strikes > 0) {
                // The in-app log is the only channel a phone-only user has, and "why did the
                // reader stop opening on WebGPU" must have an answer there.
                xLogE(
                    "WebGPU health: discarding ${record.strikes} strike(s) recorded by build " +
                        "${record.build} - this build ($build) gets a fresh try",
                )
            }
        }
        WebGpuHealthPolicy.strikesForBuild(record, build)
    }.getOrDefault(0)

    fun isDisabled(context: Context): Boolean = strikeCount(context) >= DISABLE_STRIKES

    /**
     * Marks the running session as having given up on its GPU device. See
     * [KEY_DEGRADED_IN_FLIGHT]; the marker is only meaningful for [currentBuild], so a fix
     * installed as a new build never inherits the previous one's crash record.
     */
    fun markDegradedSessionInFlight(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DEGRADED_IN_FLIGHT, currentBuild())
                .apply()
        }
    }

    /** Clears the marker: the session recovered, or it is closing through the normal verdict. */
    fun clearDegradedSessionInFlight(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_DEGRADED_IN_FLIGHT)
                .apply()
        }
    }

    /**
     * Consumes a marker left by a session that never closed cleanly and records one diagnostic
     * strike (with the session block) in the in-app log.
     *
     * Called when a reader is about to be created; a marker from another build is discarded
     * without counting. **Nothing gates on the result** - see the object's header.
     */
    fun commitUncleanSession(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val markerBuild = prefs.getString(KEY_DEGRADED_IN_FLIGHT, null) ?: return@runCatching
            prefs.edit().remove(KEY_DEGRADED_IN_FLIGHT).apply()
            if (!WebGpuHealthPolicy.uncleanSessionCounts(markerBuild, currentBuild())) return@runCatching
            xLogE(
                "WebGPU health: previous session never closed after giving up on its GPU device " +
                    "($markerBuild) - recording one strike",
            )
            recordStrike(context)
        }
    }

    fun recordStrike(context: Context) {
        runCatching {
            val build = currentBuild()
            val next = WebGpuHealthPolicy.afterStrike(read(context), build)
            write(context, next)
            // KMK --> The app's logcat lines never reach a user who only has the phone; the
            // in-app log file is what a field report actually arrives with. Say what the
            // strike means for their next launches, and call out the disable explicitly -
            // "the reader stopped opening on WebGPU" with no record of why reads as a
            // mystery regression. xLogE is an Any-extension; the receiver names the tag.
            // The session aggregate rides along: the strike says *that* a session went wrong, this
            // says what it did. Neither half is much use to a field report without the other.
            val diagnostics = runCatching { WebGpuDiagnostics.render() }.getOrNull()
            xLogE(
                "WebGPU health: degraded session recorded (strike ${next.strikes}, build $build)" +
                    " - diagnostics only; the WebGPU viewer stays in use" +
                    (diagnostics?.let { "\n$it" } ?: ""),
            )
            // KMK <--
        }
    }

    /**
     * A session that ended without a degraded shape, so the "consecutive" part of the rule is real.
     *
     * The count used to only ever climb: a single unlucky session stayed on the books forever, and
     * a second strike months later disabled the renderer for a device that had read fine every time
     * in between. The documented promise is *consecutive* dead sessions, so a clean close clears
     * the dial. It cannot launder a genuinely broken device either - that device has no clean
     * closes to clear it with.
     */
    fun recordHealthySession(context: Context) {
        runCatching {
            val record = read(context)
            val next = WebGpuHealthPolicy.afterHealthySession(record)
            if (next !== record) write(context, next)
        }
    }

    /** Clears every strike. True when the count actually moved, so the reset UI can say so. */
    fun resetStrikes(context: Context): Boolean = runCatching {
        val record = read(context)
        write(context, WebGpuHealthPolicy.afterReset())
        record.strikes > 0
    }.getOrDefault(false)
}
// KMK <--
