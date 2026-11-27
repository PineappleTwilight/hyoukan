package eu.kanade.tachiyomi.extension.health

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.util.system.isOnline
import tachiyomi.domain.source.model.SourceFailureKind
import tachiyomi.domain.source.model.SourceHealthPolicy
import tachiyomi.domain.source.model.SourceHealthRecord
import tachiyomi.domain.source.model.SourceHealthStatus

/**
 * Local persistence for [SourceHealthRecord]s.
 *
 * Backed by one preference string rather than a table: the record set is one row per source the
 * app has actually contacted, which is tens of rows at most, and a health badge is not worth a
 * schema migration. Writes are serialised because failures arrive from reader, download and
 * update threads at once, and a read-modify-write on the preference would otherwise drop one of
 * two simultaneous updates.
 *
 * Nothing here leaves the device.
 */
@SingleIn(AppScope::class)
@Inject
class SourceHealthStore(
    private val context: Context,
    private val sourcePreferences: SourcePreferences,
) {

    private val lock = Any()

    /**
     * Source ids with a recorded failure, kept in memory so the hot path does not decode the
     * preference JSON for every success. `recordSuccess` is called once per manga per library
     * update; without this a 500-entry library would parse 500 times to discover nothing changed.
     */
    private val failingSourceIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>().apply {
        addAll(records().filter { it.consecutiveFailures > 0 }.map { it.sourceId })
    }

    fun records(): List<SourceHealthRecord> = sourcePreferences.sourceHealthRecords().get()

    fun recordSuccess(sourceId: Long, sourceName: String, now: Long = System.currentTimeMillis()) {
        if (!failingSourceIds.contains(sourceId)) return
        update { records ->
            val existing = records.firstOrNull { it.sourceId == sourceId }
            // Nothing recorded for this source, or it is already healthy: every successful
            // browse page would otherwise rewrite the preference for no visible change.
            if (existing == null || existing.consecutiveFailures == 0) {
                return@update records
            }
            records.replaced(
                sourceId,
                SourceHealthPolicy.onSuccess(
                    record = existing,
                    sourceId = sourceId,
                    sourceName = sourceName,
                    now = now,
                ),
            )
        }
        failingSourceIds.remove(sourceId)
    }

    fun recordFailure(
        sourceId: Long,
        sourceName: String,
        kind: SourceFailureKind,
        now: Long = System.currentTimeMillis(),
    ) {
        val online = isOnline()
        update { records ->
            records.replaced(
                sourceId,
                SourceHealthPolicy.onFailure(
                    record = records.firstOrNull { it.sourceId == sourceId },
                    sourceId = sourceId,
                    sourceName = sourceName,
                    kind = kind,
                    online = online,
                    now = now,
                ),
            )
        }
        if (online) failingSourceIds.add(sourceId)
    }

    fun isOnline(): Boolean = context.isOnline()

    fun statusOf(record: SourceHealthRecord): SourceHealthStatus =
        SourceHealthPolicy.statusOf(record, isOnline())

    fun redactedReport(now: Long = System.currentTimeMillis()): String =
        SourceHealthPolicy.redactedReport(records(), isOnline(), now)

    fun clear() {
        synchronized(lock) {
            sourcePreferences.sourceHealthRecords().set(emptyList())
            failingSourceIds.clear()
        }
    }

    private fun update(block: (List<SourceHealthRecord>) -> List<SourceHealthRecord>) {
        synchronized(lock) {
            val current = records()
            val next = block(current)
            if (next !== current) {
                sourcePreferences.sourceHealthRecords().set(next)
            }
        }
    }

    private fun List<SourceHealthRecord>.replaced(
        sourceId: Long,
        record: SourceHealthRecord,
    ): List<SourceHealthRecord> = filterNot { it.sourceId == sourceId } + record
}
