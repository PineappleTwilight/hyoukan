package tachiyomi.domain.category.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class GetCategoryUpdateIntervalTest {

    private fun days(categories: Set<Long>, intervals: Map<Long, Int>) =
        GetCategoryUpdateInterval.intervalDaysFor(intervals, categories)

    @Test
    fun `no categories means nothing narrows the schedule`() {
        days(emptySet(), mapOf(1L to 7)) shouldBe null
    }

    @Test
    fun `a category without an interval leaves the schedule alone`() {
        // The entry is in category 1, which carries no interval. The global setting governs, so
        // this must report null rather than some fallback value.
        days(setOf(1L), mapOf(2L to 7)) shouldBe null
    }

    @Test
    fun `an empty interval map leaves the schedule alone`() {
        days(setOf(1L, 2L), emptyMap()) shouldBe null
    }

    @Test
    fun `the bound interval applies`() {
        days(setOf(1L), mapOf(1L to 14)) shouldBe 14
    }

    @Test
    fun `the shortest interval wins across categories`() {
        // Filed under a monthly one as well: the daily request must not be overridden.
        days(setOf(1L, 2L), mapOf(1L to 1, 2L to 30)) shouldBe 1
        days(setOf(1L, 2L), mapOf(1L to 30, 2L to 7)) shouldBe 7
    }

    @Test
    fun `unrelated categories do not contribute their interval`() {
        days(setOf(1L), mapOf(1L to 2, 9L to 30)) shouldBe 2
    }

    @Test
    fun `a category the entry is not in is ignored`() {
        days(setOf(1L), mapOf(1L to 7, 5L to 1)) shouldBe 7
    }

    // ---------- subcategory inheritance (#102) ----------

    private fun daysWithParents(
        categories: Set<Long>,
        intervals: Map<Long, Int>,
        parents: Map<Long, Long>,
    ) = GetCategoryUpdateInterval.intervalDaysFor(intervals, parents, categories)

    @Test
    fun `a subcategory without an interval inherits its parent's`() {
        daysWithParents(setOf(2L), mapOf(1L to 7), mapOf(2L to 1L)) shouldBe 7
    }

    @Test
    fun `a subcategory's own interval beats the parent's`() {
        daysWithParents(setOf(2L), mapOf(1L to 7, 2L to 1), mapOf(2L to 1L)) shouldBe 1
    }

    @Test
    fun `inheritance walks more than one level`() {
        daysWithParents(
            categories = setOf(3L),
            intervals = mapOf(1L to 14),
            parents = mapOf(2L to 1L, 3L to 2L),
        ) shouldBe 14
    }

    @Test
    fun `an unbound parent leaves the schedule alone`() {
        daysWithParents(setOf(2L), mapOf(3L to 7), mapOf(2L to 1L)) shouldBe null
    }

    @Test
    fun `the shortest interval wins across inherited ones`() {
        // One bound top-level category at monthly, one subcategory inheriting a daily parent.
        daysWithParents(
            categories = setOf(1L, 3L),
            intervals = mapOf(1L to 30, 2L to 1),
            parents = mapOf(3L to 2L),
        ) shouldBe 1
    }

    @Test
    fun `a category pointing at itself does not loop`() {
        daysWithParents(setOf(5L), mapOf(1L to 7), mapOf(5L to 5L)) shouldBe null
    }

    @Test
    fun `a parent cycle terminates with no interval`() {
        // Not a shape the UI can produce, but a restored or synced row could. The answer is the
        // same as an unbound category; hanging the update job is not.
        daysWithParents(setOf(1L), mapOf(3L to 7), mapOf(1L to 2L, 2L to 1L)) shouldBe null
    }

    @Test
    fun `the category zero sentinel is not walked as a parent`() {
        daysWithParents(setOf(2L), mapOf(0L to 1, 1L to 7), mapOf(2L to 0L)) shouldBe null
    }
}
