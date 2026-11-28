package eu.kanade.tachiyomi.ui.browse.feed

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.manga.model.Manga

@Execution(ExecutionMode.CONCURRENT)
class FeedEnhancementsTest {

    private fun manga(
        id: Long,
        title: String,
        author: String? = null,
        genre: List<String> = emptyList(),
        source: Long = 1,
        favorite: Boolean = false,
        lastUpdate: Long = 0,
        dateAdded: Long = 0,
    ): Manga = Manga.create().copy(
        id = id,
        ogTitle = title,
        ogAuthor = author,
        ogGenre = genre,
        source = source,
        favorite = favorite,
        lastUpdate = lastUpdate,
        dateAdded = dateAdded,
    )

    @Test
    fun `dedupe keeps the newest copy of the same series`() {
        val older = manga(id = 1, title = "One Piece", author = "Oda", lastUpdate = 10)
        val newer = manga(id = 2, title = "one piece!", author = "Oda", lastUpdate = 20)
        val other = manga(id = 3, title = "Naruto", author = "Kishimoto")

        FeedEnhancements.dedupe(listOf(older, newer, other)) shouldContainExactly listOf(newer, other)
    }

    @Test
    fun `dedupe compares dateAdded when timestamps tie`() {
        val older = manga(id = 1, title = "Same", lastUpdate = 5, dateAdded = 1)
        val newer = manga(id = 2, title = "Same", lastUpdate = 5, dateAdded = 2)

        FeedEnhancements.dedupe(listOf(older, newer)).single().id shouldBe 2L
    }

    @Test
    fun `a library copy wins a duplicate even when an older feed copy is seen first`() {
        val feedCopy = manga(id = 1, title = "Same", lastUpdate = 50)
        val libraryCopy = manga(id = 2, title = "Same", favorite = true, lastUpdate = 10)

        FeedEnhancements.dedupe(listOf(feedCopy, libraryCopy)).single().id shouldBe 2L
    }

    @Test
    fun `filters match genre, library and read state`() {
        val action = manga(id = 1, title = "A", genre = listOf("Action"), favorite = true)
        val drama = manga(id = 2, title = "B", genre = listOf("Drama"))

        val byGenre = FeedEnhancements.applyFilters(
            listOf(action, drama),
            FeedEnhancements.State(genre = "action"),
            emptySet(),
        )
        byGenre.map { it.id } shouldBe listOf(1L)

        val notInLibrary = FeedEnhancements.applyFilters(
            listOf(action, drama),
            FeedEnhancements.State(libraryFilter = FeedEnhancements.LibraryFilter.NOT_IN_LIBRARY),
            emptySet(),
        )
        notInLibrary.map { it.id } shouldBe listOf(2L)

        val unread = FeedEnhancements.applyFilters(
            listOf(action, drama),
            FeedEnhancements.State(readFilter = FeedEnhancements.ReadFilter.UNREAD),
            setOf(2L),
        )
        unread.map { it.id } shouldBe listOf(1L)
    }

    @Test
    fun `genres are deduplicated case-insensitively and sorted`() {
        val entries = listOf(
            manga(id = 1, title = "A", genre = listOf("Action", "Drama")),
            manga(id = 2, title = "B", genre = listOf("action", "Comedy")),
        )

        FeedEnhancements.genresOf(entries) shouldBe listOf("Action", "Comedy", "Drama")
    }

    @Test
    fun `suggestions put a franchise continuation in its own section`() {
        val library = listOf(manga(id = 10, title = "One Piece", favorite = true))
        val candidates = listOf(
            manga(id = 11, title = "One Piece Part 2", source = 9),
            manga(id = 12, title = "Unrelated Story", source = 9),
        )

        val sections = FeedEnhancements.Suggestions.build(candidates, library, historyTitles = emptyList())
        val franchise = sections.single { it.reason == FeedEnhancements.Suggestions.Reason.FRANCHISE }

        franchise.suggestions.single().manga.id shouldBe 11L
        franchise.suggestions.single().basis shouldBe "One Piece"
    }

    @Test
    fun `a title the reader already read is not suggested back to them`() {
        val library = listOf(manga(id = 10, title = "Blue Lock", favorite = true))
        val candidates = listOf(manga(id = 11, title = "Clone Wars", source = 3))

        val sections = FeedEnhancements.Suggestions.build(
            candidates = candidates,
            library = library,
            historyTitles = listOf("Clone Wars"),
        )
        // Only the source affinity is left, never a "similar to itself" row.
        sections.none { it.reason == FeedEnhancements.Suggestions.Reason.SIMILARITY } shouldBe true
    }

    @Test
    fun `tag affinity and source affinity produce sections`() {
        val library = listOf(
            manga(id = 10, title = "Grimdark Saga", genre = listOf("Dark Fantasy", "Action"), favorite = true),
        )
        val tagMatch = manga(id = 11, title = "Nightfall", genre = listOf("dark fantasy"), source = 5)
        val sourceOnly = manga(id = 12, title = "Quiet Days", source = 1, favorite = false)
        val libraryEntry = manga(id = 13, title = "Already Saved", genre = listOf("Action"), source = 1, favorite = true)
        val librarySource = library.first().source

        val sections = FeedEnhancements.Suggestions.build(
            candidates = listOf(tagMatch, sourceOnly, libraryEntry),
            library = library + manga(id = 14, title = "Home", source = librarySource),
            historyTitles = emptyList(),
        )

        val tag = sections.single { it.reason == FeedEnhancements.Suggestions.Reason.TAG }
        tag.suggestions.single().manga.id shouldBe 11L
        tag.suggestions.single().basis shouldBe "dark fantasy"
        // The already-favourited candidate is excluded from every section.
        sections.flatMap { it.suggestions }.none { it.manga.id == 13L } shouldBe true
    }

    @Test
    fun `inactive state is reported for the chips`() {
        FeedEnhancements.State().isActive shouldBe false
        FeedEnhancements.State(genre = "Action").isActive shouldBe true
    }
}
