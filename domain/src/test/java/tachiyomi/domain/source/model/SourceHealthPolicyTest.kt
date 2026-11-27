package tachiyomi.domain.source.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Source health classification (#98).
 *
 * A wrong "suspected dead" tells a reader to give up on a working source, and a wrong "healthy"
 * hides a source that has not answered in days. Both are cheap to pin here because the policy is
 * pure; the store and the screen only feed it.
 */
@Execution(ExecutionMode.CONCURRENT)
class SourceHealthPolicyTest {

    private val record = SourceHealthRecord(
        sourceId = 1L,
        sourceName = "Example",
    )

    private fun failed(
        kind: SourceFailureKind,
        times: Int,
        online: Boolean = true,
    ): SourceHealthRecord {
        var current: SourceHealthRecord? = record
        repeat(times) {
            current = SourceHealthPolicy.onFailure(current, 1L, "Example", kind, online, now = 100L + it)
        }
        return current!!
    }

    @Test
    fun `no record is unknown, not healthy`() {
        SourceHealthPolicy.statusOf(null, online = true) shouldBe SourceHealthStatus.UNKNOWN
    }

    @Test
    fun `offline is unknown no matter what was recorded`() {
        val dead = failed(SourceFailureKind.DNS, times = 5)
        SourceHealthPolicy.statusOf(dead, online = false) shouldBe SourceHealthStatus.UNKNOWN
    }

    @Test
    fun `an offline failure does not change any count or timestamp`() {
        val before = failed(SourceFailureKind.TIMEOUT, times = 1)
        val after = SourceHealthPolicy.onFailure(
            record = before,
            sourceId = 1L,
            sourceName = "Example",
            kind = SourceFailureKind.TIMEOUT,
            online = false,
            now = 999L,
        )

        after.consecutiveFailures shouldBe before.consecutiveFailures
        after.totalFailures shouldBe before.totalFailures
        after.lastFailureAt shouldBe before.lastFailureAt
        after.lastFailureKind shouldBe before.lastFailureKind
    }

    @Test
    fun `one timeout is degraded`() {
        SourceHealthPolicy.statusOf(
            failed(SourceFailureKind.TIMEOUT, times = 1),
            online = true,
        ) shouldBe SourceHealthStatus.DEGRADED
    }

    @Test
    fun `two dns failures are still degraded`() {
        SourceHealthPolicy.statusOf(
            failed(SourceFailureKind.DNS, times = 2),
            online = true,
        ) shouldBe SourceHealthStatus.DEGRADED
    }

    @Test
    fun `three dns failures are suspected dead`() {
        SourceHealthPolicy.statusOf(
            failed(SourceFailureKind.DNS, times = 3),
            online = true,
        ) shouldBe SourceHealthStatus.SUSPECTED_DEAD
    }

    @Test
    fun `three 404s are suspected dead`() {
        SourceHealthPolicy.statusOf(
            failed(SourceFailureKind.NOT_FOUND, times = 3),
            online = true,
        ) shouldBe SourceHealthStatus.SUSPECTED_DEAD
    }

    @Test
    fun `three timeouts are not dead, only degraded`() {
        // A timeout is evidence of a slow network or a struggling server, not of a gone site.
        SourceHealthPolicy.statusOf(
            failed(SourceFailureKind.TIMEOUT, times = 10),
            online = true,
        ) shouldBe SourceHealthStatus.DEGRADED
    }

    @Test
    fun `cloudflare and auth are blocked even before the dead threshold`() {
        SourceHealthPolicy.statusOf(
            failed(SourceFailureKind.CLOUDFLARE, times = 1),
            online = true,
        ) shouldBe SourceHealthStatus.BLOCKED
        SourceHealthPolicy.statusOf(
            failed(SourceFailureKind.AUTH, times = 1),
            online = true,
        ) shouldBe SourceHealthStatus.BLOCKED
    }

    @Test
    fun `blocked wins over a long dns streak`() {
        // The last failure is what the reader is told to act on; a stale DNS streak must not
        // resurface as "dead" once the site starts challenging instead.
        val record = failed(SourceFailureKind.DNS, times = 4)
        val blocked = SourceHealthPolicy.onFailure(
            record = record,
            sourceId = 1L,
            sourceName = "Example",
            kind = SourceFailureKind.CLOUDFLARE,
            online = true,
            now = 500L,
        )

        SourceHealthPolicy.statusOf(blocked, online = true) shouldBe SourceHealthStatus.BLOCKED
    }

    @Test
    fun `a success clears the streak and makes the source healthy`() {
        val dead = failed(SourceFailureKind.DNS, times = 4)
        val recovered = SourceHealthPolicy.onSuccess(dead, 1L, "Example", now = 900L)

        recovered.consecutiveFailures shouldBe 0
        recovered.lastFailureKind shouldBe SourceFailureKind.NONE
        recovered.totalFailures shouldBe dead.totalFailures
        SourceHealthPolicy.statusOf(recovered, online = true) shouldBe SourceHealthStatus.HEALTHY
    }

    @Test
    fun `a record with a success and no failures is healthy`() {
        val recovered = SourceHealthPolicy.onSuccess(null, 1L, "Example", now = 10L)
        SourceHealthPolicy.statusOf(recovered, online = true) shouldBe SourceHealthStatus.HEALTHY
    }

    @Test
    fun `the redacted report names sources but carries no library data`() {
        val records = listOf(
            failed(SourceFailureKind.DNS, times = SourceHealthPolicy.SUSPECTED_DEAD_THRESHOLD),
            SourceHealthPolicy.onFailure(null, 2L, "Another", SourceFailureKind.TIMEOUT, true, 11L),
        )

        val report = SourceHealthPolicy.redactedReport(records, online = true, generatedAt = 12L)

        report shouldContain "Example"
        report shouldContain "Another"
        report shouldContain "SUSPECTED_DEAD"
        report shouldContain "DEGRADED"
        report shouldNotContain "https://"
        report shouldNotContain "/manga/"
    }

    @Test
    fun `the redacted report leads with the worst status`() {
        val healthy = SourceHealthPolicy.onSuccess(null, 3L, "ZHealthy", now = 5L)
        val dead = failed(SourceFailureKind.DNS, times = 3)
        val records = listOf(healthy, dead)

        val report = SourceHealthPolicy.redactedReport(records, online = true, generatedAt = 1L)
        val lines = report.lines().filter { it.startsWith("- ") }

        lines.first() shouldContain "Example"
        lines.last() shouldContain "ZHealthy"
    }

    @Test
    fun `an empty report says so instead of looking broken`() {
        val report = SourceHealthPolicy.redactedReport(emptyList(), online = true, generatedAt = 1L)
        report shouldContain "No source checks recorded yet"
    }

    @Test
    fun `offline export marks statuses unknown rather than replaying old verdicts`() {
        val dead = failed(SourceFailureKind.DNS, times = 5)
        val report = SourceHealthPolicy.redactedReport(listOf(dead), online = false, generatedAt = 1L)

        report shouldContain "Network: offline"
        report shouldContain "[UNKNOWN]"
        report shouldNotContain "SUSPECTED_DEAD"
    }
}
