package mihon.feature.libraryimport.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mihon.feature.libraryimport.model.ImportMigrateFlag
import tachiyomi.domain.importer.model.ImportEntry
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Asks which parts of an import entry should come along when it is added to the library.
 *
 * Deliberately small next to the migration dialog: a shell has no chapters of its own to merge, so
 * the only real choices are the read progress the export recorded, the tracking row it already
 * has, and the note.
 */
@Composable
fun MigrateImportEntryDialog(
    entry: ImportEntry,
    targetSourceName: String,
    applicableFlags: List<ImportMigrateFlag>,
    trackerName: String?,
    isMigrating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<ImportMigrateFlag>) -> Unit,
) {
    var selected by remember(entry.id, targetSourceName) {
        mutableStateOf(applicableFlags.toSet())
    }

    AlertDialog(
        onDismissRequest = { if (!isMigrating) onDismiss() },
        title = { Text(stringResource(KMR.strings.library_import_action)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Text(
                    text = stringResource(KMR.strings.library_import_dialog_text, entry.title, targetSourceName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(visible = isMigrating) {
                    Text(
                        text = stringResource(MR.strings.loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                applicableFlags.forEach { flag ->
                    LabeledCheckbox(
                        modifier = Modifier.fillMaxWidth(),
                        label = when (flag) {
                            ImportMigrateFlag.CHAPTER -> stringResource(
                                KMR.strings.library_import_flag_chapters,
                                entry.progressChapter.formattedProgress(),
                            )
                            ImportMigrateFlag.TRACK -> stringResource(
                                KMR.strings.library_import_flag_track,
                                trackerName.orEmpty(),
                            )
                            ImportMigrateFlag.NOTES -> stringResource(KMR.strings.library_import_flag_notes)
                        },
                        checked = flag in selected,
                        onCheckedChange = {
                            selected = if (flag in selected) selected - flag else selected + flag
                        },
                        enabled = !isMigrating,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isMigrating,
                onClick = { onConfirm(selected) },
            ) {
                Text(stringResource(KMR.strings.library_import_action))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isMigrating,
                onClick = onDismiss,
            ) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private fun Double.formattedProgress(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()
