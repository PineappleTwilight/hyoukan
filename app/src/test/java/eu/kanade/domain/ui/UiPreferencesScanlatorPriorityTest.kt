package eu.kanade.domain.ui

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Where the scanlator-priority entry lives on the details page.
 *
 * The row has always been a full-width entry under the description; the switch moves it into the
 * toolbar's overflow menu, the way the Suggestions entry can already move. Three things matter and
 * nothing else: the default must keep today's layout, an explicit choice must survive, and the key
 * must stay the string a settings backup already carries.
 */
@Execution(ExecutionMode.CONCURRENT)
class UiPreferencesScanlatorPriorityTest {

    private fun preferences(store: PreferenceStore) = UiPreferences(store)

    @Test
    fun `the details page keeps the row when nothing was chosen`() {
        preferences(BooleanPreferenceStore()).scanlatorPriorityInOverflow().get().shouldBeFalse()
    }

    @Test
    fun `an explicit move into the overflow is honoured`() {
        val store = BooleanPreferenceStore()
        preferences(store).scanlatorPriorityInOverflow().set(true)
        preferences(store).scanlatorPriorityInOverflow().get().shouldBeTrue()
    }

    @Test
    fun `moving it back to the page is honoured`() {
        val store = BooleanPreferenceStore()
        preferences(store).scanlatorPriorityInOverflow().set(true)
        preferences(store).scanlatorPriorityInOverflow().set(false)
        preferences(store).scanlatorPriorityInOverflow().get().shouldBeFalse()
    }

    @Test
    fun `the key is stable so a settings backup still applies`() {
        preferences(BooleanPreferenceStore()).scanlatorPriorityInOverflow().key() shouldBe
            "scanlator_priority_in_overflow"
    }

    @Test
    fun `the entry is shown when nothing was chosen`() {
        preferences(BooleanPreferenceStore()).showScanlatorPriority().get().shouldBeTrue()
    }

    @Test
    fun `an explicit hide is honoured`() {
        val store = BooleanPreferenceStore()
        preferences(store).showScanlatorPriority().set(false)
        preferences(store).showScanlatorPriority().get().shouldBeFalse()
    }

    @Test
    fun `the visibility key is stable so a settings backup still applies`() {
        preferences(BooleanPreferenceStore()).showScanlatorPriority().key() shouldBe
            "show_scanlator_priority"
    }
}

/**
 * A store that remembers what it was handed.
 *
 * `core:common`'s [tachiyomi.core.common.preference.InMemoryPreferenceStore] hands out a fresh
 * preference per call without caching it, so a write through one handle is invisible to the next
 * read — enough for a preview, useless for asserting that a setting sticks.
 */
private class BooleanPreferenceStore : PreferenceStore {
    private val booleans = mutableMapOf<String, Boolean>()
    private val flows = mutableMapOf<String, MutableStateFlow<Boolean>>()

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> {
        val flow = flows.getOrPut(key) { MutableStateFlow(booleans[key] ?: defaultValue) }
        return object : Preference<Boolean> {
            override fun key() = key
            override fun get() = flow.value
            override fun set(value: Boolean) {
                booleans[key] = value
                flow.value = value
            }

            override fun isSet() = booleans.containsKey(key)
            override fun delete() {
                booleans.remove(key)
                flow.value = defaultValue
            }

            override fun defaultValue() = defaultValue
            override fun changes() = flow
            override fun stateIn(scope: CoroutineScope) = flow
        }
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
