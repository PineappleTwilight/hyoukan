package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.more.settings.Preference
import kotlinx.serialization.Serializable
import mihon.app.di.globalAppGraph
import tachiyomi.domain.source.model.SourceFailureKind
import tachiyomi.domain.source.model.SourceHealthPolicy
import tachiyomi.domain.source.model.SourceHealthRecord
import tachiyomi.domain.source.model.SourceHealthStatus
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Local-only source health (#98): one row per source the app has actually contacted, its status
 * bucket, and a redacted export.
 *
 * The screen deliberately reads its snapshot once per [refresh] tick instead of observing a flow.
 * Health changes on the I/O thread behind a preference; a saved-state snapshot with an explicit
 * Refresh button is honest about when the numbers were taken, and avoids a settings screen that
 * recomposes while a library update is running.
 */
@Serializable
data object SettingsSourceHealthRoute : NavKey, SearchableRoute {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_source_health_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val store = remember { globalAppGraph.sourceHealthStore }
        var refresh by remember { mutableIntStateOf(0) }

        val records = remember(refresh) { store.records() }
        val online = remember(refresh) { store.isOnline() }
        val report = remember(refresh) { store.redactedReport() }
        val shareTitle = stringResource(KMR.strings.source_health_share_title)
        // Every known source, plus any source with a record, so a source that has never failed
        // still appears as Unknown instead of vanishing.
        val knownSources = remember(refresh, records) {
            buildMap {
                runCatching { globalAppGraph.sourceManager.getAll() }.getOrDefault(emptyList())
                    .forEach { put(it.id, it.name) }
                records.forEach { put(it.sourceId, it.sourceName) }
            }
        }
        val rows = remember(knownSources, records, online) {
            knownSources.entries
                .map { (sourceId, name) ->
                    val record = records.firstOrNull { it.sourceId == sourceId }
                    Triple(name, record, SourceHealthPolicy.statusOf(record, online))
                }
                .sortedWith(compareBy({ statusRank(it.third) }, { it.first.lowercase() }))
        }

        val items = mutableListOf<Preference>()
        items += Preference.PreferenceItem.InfoPreference(
            title = stringResource(KMR.strings.pref_source_health_summary),
        )
        if (!online) {
            items += Preference.PreferenceItem.InfoPreference(
                title = stringResource(KMR.strings.source_health_offline),
            )
        }
        if (rows.isEmpty()) {
            items += Preference.PreferenceItem.InfoPreference(
                title = stringResource(KMR.strings.source_health_no_records),
            )
        } else {
            rows.forEach { (name, record, status) ->
                items += Preference.PreferenceItem.TextPreference(
                    title = "$name · ${statusLabel(status)}",
                    subtitle = recordSubtitle(record, status),
                    enabled = false,
                )
            }
        }
        items += Preference.PreferenceItem.TextPreference(
            title = stringResource(KMR.strings.source_health_refresh),
            onClick = { refresh++ },
        )
        items += Preference.PreferenceItem.TextPreference(
            title = stringResource(KMR.strings.source_health_export),
            subtitle = stringResource(KMR.strings.source_health_export_summary),
            onClick = {
                runCatching {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    context.startActivity(Intent.createChooser(intent, shareTitle))
                }
            },
        )
        items += Preference.PreferenceItem.TextPreference(
            title = stringResource(KMR.strings.source_health_clear),
            subtitle = stringResource(KMR.strings.source_health_clear_summary),
            onClick = {
                store.clear()
                refresh++
            },
        )
        return items
    }

    @Composable
    private fun statusLabel(status: SourceHealthStatus): String = stringResource(
        when (status) {
            SourceHealthStatus.UNKNOWN -> KMR.strings.source_health_status_unknown
            SourceHealthStatus.HEALTHY -> KMR.strings.source_health_status_healthy
            SourceHealthStatus.DEGRADED -> KMR.strings.source_health_status_degraded
            SourceHealthStatus.BLOCKED -> KMR.strings.source_health_status_blocked
            SourceHealthStatus.SUSPECTED_DEAD -> KMR.strings.source_health_status_suspected_dead
        },
    )

    @Composable
    private fun recordSubtitle(record: SourceHealthRecord?, status: SourceHealthStatus): String {
        if (record == null) {
            return stringResource(KMR.strings.source_health_no_record_hint)
        }
        val parts = mutableListOf<String>()
        if (record.consecutiveFailures > 0) {
            parts += stringResource(
                KMR.strings.source_health_record_summary,
                record.consecutiveFailures,
                record.totalFailures,
            )
        }
        if (record.lastFailureKind != SourceFailureKind.NONE) {
            parts += stringResource(KMR.strings.source_health_last_failure, record.lastFailureKind.name)
        }
        parts += when (status) {
            SourceHealthStatus.BLOCKED -> stringResource(KMR.strings.source_health_blocked_hint)
            SourceHealthStatus.SUSPECTED_DEAD -> stringResource(KMR.strings.source_health_dead_hint)
            SourceHealthStatus.DEGRADED -> stringResource(KMR.strings.source_health_degraded_hint)
            else -> ""
        }.takeIf { it.isNotEmpty() }.orEmpty()
        return parts.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun statusRank(status: SourceHealthStatus): Int = when (status) {
        SourceHealthStatus.SUSPECTED_DEAD -> 0
        SourceHealthStatus.BLOCKED -> 1
        SourceHealthStatus.DEGRADED -> 2
        SourceHealthStatus.UNKNOWN -> 3
        SourceHealthStatus.HEALTHY -> 4
    }
}
