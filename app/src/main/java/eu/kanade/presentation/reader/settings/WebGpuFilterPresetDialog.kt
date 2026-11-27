package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.WebGpuFilterPreset
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** How many looks a reader can keep, so the settings screen cannot grow without bound. */
const val WEBGPU_FILTER_PRESET_LIMIT = 20

/**
 * Save, apply and delete named WebGPU filter looks (#103).
 *
 * The dialog is intentionally about the *current* values as well: a preset is only useful if
 * saving one is one tap from the sliders that produced it, so the current snapshot is passed in
 * rather than read from preferences here.
 */
@Composable
fun WebGpuFilterPresetDialog(
    presets: List<WebGpuFilterPreset>,
    current: WebGpuFilterPreset,
    onApply: (WebGpuFilterPreset) -> Unit,
    onSave: (WebGpuFilterPreset) -> Unit,
    onDelete: (WebGpuFilterPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.pref_webgpu_filter_presets)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (presets.isEmpty()) {
                    Text(stringResource(KMR.strings.webgpu_preset_empty))
                } else {
                    presets.forEach { preset ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = preset.name,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                            )
                            TextButton(onClick = { onApply(preset) }) {
                                Text(stringResource(KMR.strings.webgpu_preset_apply))
                            }
                            TextButton(onClick = { onDelete(preset) }) {
                                Text(stringResource(KMR.strings.webgpu_preset_delete))
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    singleLine = true,
                    label = { Text(stringResource(KMR.strings.webgpu_preset_name)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                TextButton(
                    // Saving over an existing name replaces it, which is how a reader iterates on
                    // a look without accumulating "mine (2)" entries.
                    enabled = trimmed.isNotEmpty() &&
                        (presets.size < WEBGPU_FILTER_PRESET_LIMIT || presets.any { it.name == trimmed }),
                    onClick = {
                        onSave(current.copy(name = trimmed))
                        name = ""
                    },
                ) {
                    Text(stringResource(KMR.strings.webgpu_preset_save))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
    )
}
