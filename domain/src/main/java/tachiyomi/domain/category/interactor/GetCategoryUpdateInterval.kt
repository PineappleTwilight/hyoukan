package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * How long an entry should wait before being updated again, given the categories it sits in.
 *
 * Returns null when no category the entry belongs to carries an interval of its own, which is the
 * normal case: the global interval already governs when the job runs at all, so applying one here
 * as well would change how every existing entry is scheduled. A per-category interval may only ever
 * narrow that, never loosen it.
 *
 * A subcategory with no interval of its own inherits its parent's, and a category that does carry
 * one is never overridden by an ancestor: the more specific answer wins. When several bound
 * categories apply, the shortest interval wins. A user who asked for one category to update daily
 * should keep getting daily updates for an entry that is also filed under a monthly one; taking the
 * longest would let the monthly category silently override the daily one.
 */
@Inject
class GetCategoryUpdateInterval(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun intervalDaysFor(categoryIds: Set<Long>): Int? {
        if (categoryIds.isEmpty()) return null
        val categories = categoryRepository.getAll()
        val intervals = categories
            .mapNotNull { category -> category.updateInterval?.let { category.id to it } }
            .toMap()
        val parents = categories
            .filter { it.parentId != 0L }
            .associate { it.id to it.parentId }
        return intervalDaysFor(intervals, parents, categoryIds)
    }

    companion object {

        /**
         * Pure overload for callers that already hold every category, which is what the update job
         * does - querying per entry would be one database round trip per manga in the library.
         *
         * [parentByCategoryId] maps a subcategory to its parent; categories without an entry are
         * roots. Inheritance is resolved here rather than in the job so the schedule stays one
         * lookup per entry.
         */
        fun intervalDaysFor(
            intervalsByCategoryId: Map<Long, Int>,
            parentByCategoryId: Map<Long, Long>,
            categoryIds: Set<Long>,
        ): Int? {
            if (categoryIds.isEmpty()) return null
            return categoryIds.mapNotNull { categoryId ->
                resolve(categoryId, intervalsByCategoryId, parentByCategoryId)
            }.minOrNull()
        }

        /** The nearest interval at or above [categoryId], or null when the chain has none. */
        private fun resolve(
            categoryId: Long,
            intervalsByCategoryId: Map<Long, Int>,
            parentByCategoryId: Map<Long, Long>,
        ): Int? {
            var current = categoryId
            // A category graph is a tree by construction, but a corrupted or hand-edited row can
            // point in a circle. Bounded by the number of categories so it can never spin.
            var hops = 0
            val limit = intervalsByCategoryId.size + parentByCategoryId.size + 1
            while (hops++ <= limit) {
                intervalsByCategoryId[current]?.let { return it }
                current = parentByCategoryId[current] ?: return null
                if (current == 0L) return null
            }
            return null
        }

        /** Kept for callers that only have a flat interval map (no subcategory relationship). */
        fun intervalDaysFor(intervalsByCategoryId: Map<Long, Int>, categoryIds: Set<Long>): Int? =
            intervalDaysFor(intervalsByCategoryId, emptyMap(), categoryIds)
    }
}
