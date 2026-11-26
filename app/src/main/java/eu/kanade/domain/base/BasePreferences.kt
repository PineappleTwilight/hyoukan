package eu.kanade.domain.base

import android.content.Context
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.system.GLUtil
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR

@SingleIn(AppScope::class)
@Inject
class BasePreferences(
    val context: Context,
    private val preferenceStore: PreferenceStore,
) {

    fun downloadedOnly() = preferenceStore.getBoolean(
        Preference.appStateKey("pref_downloaded_only"),
        false,
    )

    fun incognitoMode() = preferenceStore.getBoolean(Preference.appStateKey("incognito_mode"), false)

    fun extensionInstaller() = ExtensionInstallerPreference(context, preferenceStore)

    fun shownOnboardingFlow() = preferenceStore.getBoolean(Preference.appStateKey("onboarding_complete"), false)

    enum class ExtensionInstaller(val titleRes: StringResource, val requiresSystemPermission: Boolean) {
        LEGACY(MR.strings.ext_installer_legacy, true),
        PACKAGEINSTALLER(MR.strings.ext_installer_packageinstaller, true),
        SHIZUKU(MR.strings.ext_installer_shizuku, false),
        PRIVATE(MR.strings.ext_installer_private, false),
    }

    fun displayProfile() = preferenceStore.getString("pref_display_profile_key", "")

    fun hardwareBitmapThreshold() = preferenceStore.getInt("pref_hardware_bitmap_threshold", GLUtil.SAFE_TEXTURE_LIMIT)

    fun alwaysDecodeLongStripWithSSIV() = preferenceStore.getBoolean("pref_always_decode_long_strip_with_ssiv", false)

    // Mihon -->
    // KMK --> On by default. The reader is offered the renderer wherever WebGPU is available, and
    // a device whose GPU device keeps failing falls back through WebGpuHealth - so the switch
    // states a preference, and the health latch is the device's own verdict on it. A default of
    // off meant the renderer was invisible to everyone who never found this row, which is not a
    // choice a "high quality" build should be making for them.
    fun highQualityRenderer() = preferenceStore.getBoolean("pref_high_quality_renderer_key", true)

    /**
     * The WebGPU reader's screen-space tile cache.
     *
     * On by default: it is the sharpening path every reader gets. It is an advanced switch because
     * a GPU that cannot afford the atlas shows it as memory pressure or a lost device, not as a
     * quality regression - and PowerVR is pinned to the cache-free path in the library regardless
     * of this value. Texture tiling itself is not affected; pages are always uploaded as tiles.
     */
    fun webGpuTileCache() = preferenceStore.getBoolean("pref_webgpu_tile_cache_key", true)
    // KMK <--
    // Mihon <--
}
