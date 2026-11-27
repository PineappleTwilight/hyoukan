package tachiyomi.domain.source.model

import kotlinx.serialization.Serializable

/**
 * What the last call to a source actually failed with.
 *
 * Deliberately coarse: the point of the health screen is to tell a reader whether to retry, wait,
 * open the site in a WebView, or report the extension - not to reproduce the stack trace, which
 * the extension watchdog already keeps separately.
 */
@Serializable
enum class SourceFailureKind {
    NONE,
    TIMEOUT,
    DNS,
    NOT_FOUND,
    CLOUDFLARE,
    AUTH,
    HTTP,
    NETWORK,
    UNKNOWN,
}

/**
 * The health buckets a source can be in.
 *
 * [UNKNOWN] is not "healthy": it means the app has no evidence either way - no check has run, or
 * the device is offline. It must never be presented as a failure, which is exactly why the
 * classification takes connectivity as an input instead of inferring it from an exception.
 */
enum class SourceHealthStatus {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    BLOCKED,
    SUSPECTED_DEAD,
}

/**
 * Everything the health screen knows about one source.
 *
 * Contains no title, URL or reading data by construction: an export of this record can be shared
 * in a bug report without exposing the library. [sourceName] is the extension's own name, not the
 * name of anything the reader is reading.
 */
@Serializable
data class SourceHealthRecord(
    val sourceId: Long,
    val sourceName: String,
    val consecutiveFailures: Int = 0,
    val totalFailures: Int = 0,
    val lastFailureKind: SourceFailureKind = SourceFailureKind.NONE,
    val lastFailureAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
)

/**
 * Pure health policy: recording outcomes, classifying status, and building the redacted export.
 *
 * The whole feature is local and offline - nothing here sends anything anywhere. Keeping it free
 * of Android and network types is what lets the thresholds be pinned by tests, which matters more
 * than usual because a wrong "dead" verdict tells a reader to abandon a working source.
 */
object SourceHealthPolicy {

    /**
     * How many consecutive DNS/404 failures turn "degraded" into "suspected dead".
     *
     * One failure is never enough: a DNS blip during a reconnect and a single 404 while a site
     * re-publishes its catalogue are both ordinary. Three in a row is the issue's stated bar.
     */
    const val SUSPECTED_DEAD_THRESHOLD = 3

    private val DEAD_KINDS = setOf(SourceFailureKind.DNS, SourceFailureKind.NOT_FOUND)
    private val BLOCKED_KINDS = setOf(SourceFailureKind.CLOUDFLARE, SourceFailureKind.AUTH)

    /**
     * The status to show for [record] right now.
     *
     * Offline wins over everything: a stored "dead" verdict from yesterday must not be shown as a
     * fresh verdict while the phone has no network, and a failure that happened offline was never
     * recorded in the first place (see [onFailure]).
     */
    fun statusOf(record: SourceHealthRecord?, online: Boolean): SourceHealthStatus {
        if (!online) return SourceHealthStatus.UNKNOWN
        if (record == null) return SourceHealthStatus.UNKNOWN
        if (record.consecutiveFailures == 0) {
            return if (record.lastSuccessAt > 0L) SourceHealthStatus.HEALTHY else SourceHealthStatus.UNKNOWN
        }
        if (record.lastFailureKind in BLOCKED_KINDS) return SourceHealthStatus.BLOCKED
        if (record.consecutiveFailures >= SUSPECTED_DEAD_THRESHOLD &&
            record.lastFailureKind in DEAD_KINDS
        ) {
            return SourceHealthStatus.SUSPECTED_DEAD
        }
        return SourceHealthStatus.DEGRADED
    }

    /**
     * A call succeeded.
     *
     * Clears the failure streak outright: the source answered, which is stronger evidence than any
     * number of earlier failures. The lifetime counter is kept so the export still shows a source
     * that has been flaky over time.
     */
    fun onSuccess(
        record: SourceHealthRecord?,
        sourceId: Long,
        sourceName: String,
        now: Long,
    ): SourceHealthRecord {
        val base = record ?: SourceHealthRecord(sourceId = sourceId, sourceName = sourceName)
        return base.copy(
            sourceName = sourceName,
            consecutiveFailures = 0,
            lastFailureKind = SourceFailureKind.NONE,
            lastSuccessAt = now,
        )
    }

    /**
     * A call failed with [kind].
     *
     * Offline failures return the record untouched: no counter, no timestamp, no last-failure kind.
     * The issue's first acceptance criterion is that an offline run leaves counts unchanged, and
     * the only place that can be guaranteed is here, before anything is stored.
     */
    fun onFailure(
        record: SourceHealthRecord?,
        sourceId: Long,
        sourceName: String,
        kind: SourceFailureKind,
        online: Boolean,
        now: Long,
    ): SourceHealthRecord {
        val base = record ?: SourceHealthRecord(sourceId = sourceId, sourceName = sourceName)
        if (!online) return base.copy(sourceName = sourceName)
        return base.copy(
            sourceName = sourceName,
            consecutiveFailures = base.consecutiveFailures + 1,
            totalFailures = base.totalFailures + 1,
            lastFailureKind = kind,
            lastFailureAt = now,
        )
    }

    /**
     * A plain-text summary safe to paste into a bug report or a repository issue.
     *
     * The only per-source identity is the source's own name, which is public information about an
     * extension. No manga title, chapter name, URL, account or history is reachable from
     * [SourceHealthRecord], so the export cannot leak one even by accident.
     */
    fun redactedReport(
        records: Collection<SourceHealthRecord>,
        online: Boolean,
        generatedAt: Long,
    ): String {
        val header = buildString {
            appendLine("Source health diagnostics")
            appendLine("Generated: $generatedAt")
            appendLine("Network: ${if (online) "online" else "offline"}")
            appendLine("No library titles, URLs, accounts or reading history are included.")
        }
        val rows = records
            .sortedWith(compareBy({ statusRank(statusOf(it, online)) }, { it.sourceName.lowercase() }))
            .joinToString("\n") { record ->
                buildString {
                    append("- ${record.sourceName}")
                    append(" [${statusOf(record, online)}]")
                    append(" failures=${record.consecutiveFailures}/${record.totalFailures}")
                    if (record.lastFailureKind != SourceFailureKind.NONE) {
                        append(" last=${record.lastFailureKind}")
                    }
                    if (record.lastSuccessAt > 0L) {
                        append(" lastSuccess=${record.lastSuccessAt}")
                    }
                }
            }
        return if (rows.isEmpty()) "$header\nNo source checks recorded yet." else "$header\n$rows"
    }

    /** Worst first, so a shared report leads with what the reader came for. */
    private fun statusRank(status: SourceHealthStatus): Int = when (status) {
        SourceHealthStatus.SUSPECTED_DEAD -> 0
        SourceHealthStatus.BLOCKED -> 1
        SourceHealthStatus.DEGRADED -> 2
        SourceHealthStatus.UNKNOWN -> 3
        SourceHealthStatus.HEALTHY -> 4
    }
}
