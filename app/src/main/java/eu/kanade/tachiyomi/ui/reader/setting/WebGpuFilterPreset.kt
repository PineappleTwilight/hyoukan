package eu.kanade.tachiyomi.ui.reader.setting

import kotlinx.serialization.Serializable

/**
 * A named snapshot of the WebGPU reader's colour and sharpening settings (#103).
 *
 * Only the knobs in the image filter system are captured. Transition, zoom and layout are not
 * "looks" in the sense a reader saves one for, and including them would make applying a preset
 * also move the page.
 */
@Serializable
data class WebGpuFilterPreset(
    val name: String,
    val brightness: Int = 0,
    val contrast: Int = 100,
    val sharpen: Int = 0,
    val lutPreset: String = "none",
    val lutIntensity: Int = 100,
    val eink: Boolean = false,
)
