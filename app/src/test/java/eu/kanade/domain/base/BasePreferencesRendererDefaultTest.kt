package eu.kanade.domain.base

import android.content.Context
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * The high-quality renderer's default.
 *
 * It used to default off, which made the WebGPU reader invisible to every user who never found the
 * switch under Settings → Advanced. It is on by default now: a device that cannot run the renderer
 * declines through `WebGpuProvider` / `WebGpuHealth`, so the preference states a choice rather than
 * hiding one. An explicit "off" must still be honoured - that is the whole point of a switch.
 */
@Execution(ExecutionMode.CONCURRENT)
class BasePreferencesRendererDefaultTest {

    private fun preferences(store: PreferenceStore) = BasePreferences(mockk<Context>(), store)

    @Test
    fun `the high-quality renderer is on when nothing was chosen`() {
        preferences(InMemoryPreferenceStore()).highQualityRenderer().get().shouldBeTrue()
    }

    @Test
    fun `an explicit off is still honoured`() {
        val store = InMemoryPreferenceStore()
        preferences(store).highQualityRenderer().set(false)
        preferences(store).highQualityRenderer().get().shouldBeFalse()
    }

    @Test
    fun `an explicit on survives a new instance`() {
        val store = InMemoryPreferenceStore()
        preferences(store).highQualityRenderer().set(true)
        preferences(store).highQualityRenderer().get().shouldBeTrue()
    }

    @Test
    fun `the key is unchanged so existing settings and backups still apply`() {
        // Backup files match preferences on the raw key string; renaming it would silently reset
        // every user who had turned the renderer off.
        preferences(InMemoryPreferenceStore()).highQualityRenderer().key() shouldBe
            "pref_high_quality_renderer_key"
    }

    // KMK --> The tile cache is a separate advanced switch, and it has its own default: on, so
    // nobody loses the sharpening path without asking. A user who turns it off must keep that.
    @Test
    fun `the WebGPU tile cache is on when nothing was chosen`() {
        preferences(InMemoryPreferenceStore()).webGpuTileCache().get().shouldBeTrue()
    }

    @Test
    fun `an explicit tile-cache off is honoured`() {
        val store = InMemoryPreferenceStore()
        preferences(store).webGpuTileCache().set(false)
        preferences(store).webGpuTileCache().get().shouldBeFalse()
    }

    @Test
    fun `the tile-cache key is stable for settings backups`() {
        preferences(InMemoryPreferenceStore()).webGpuTileCache().key() shouldBe
            "pref_webgpu_tile_cache_key"
    }
    // KMK <--
}

private class InMemoryPreferenceStore : PreferenceStore {
    private val booleans = mutableMapOf<String, Boolean>()

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        object : Preference<Boolean> {
            override fun key() = key
            override fun get() = booleans[key] ?: defaultValue
            override fun set(value: Boolean) {
                booleans[key] = value
            }
            override fun isSet() = booleans.containsKey(key)
            override fun delete() {
                booleans.remove(key)
            }
            override fun defaultValue() = defaultValue
            override fun changes() = MutableStateFlow(get())
            override fun stateIn(scope: CoroutineScope) = MutableStateFlow(get())
        }

    override fun getString(key: String, defaultValue: String): Preference<String> = TODO()
    override fun getLong(key: String, defaultValue: Long): Preference<Long> = TODO()
    override fun getInt(key: String, defaultValue: Int): Preference<Int> = TODO()
    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = TODO()
    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = TODO()
    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = TODO()

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = TODO()

    override fun getAll(): Map<String, *> = booleans
}
