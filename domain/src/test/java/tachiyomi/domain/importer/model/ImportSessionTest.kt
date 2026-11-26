package tachiyomi.domain.importer.model

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class ImportSessionTest {

    private fun entry(id: String, title: String = "Title $id") = ImportEntry(id = id, title = title)

    private fun session(
        entries: List<ImportEntry> = listOf(entry("1"), entry("2"), entry("3")),
        imported: Set<String> = emptySet(),
        skipped: Set<String> = emptySet(),
    ) = ImportSession(
        id = "session",
        name = "export.json",
        sourceId = "mangabaka",
        createdAt = 1L,
        entries = entries,
        importedEntryIds = imported,
        skippedEntryIds = skipped,
    )

    @Test
    fun `an untouched session has every entry pending`() {
        val session = session()

        session.pendingCount shouldBe 3
        session.pendingEntries.map { it.id } shouldBe listOf("1", "2", "3")
        session.progress shouldBe 0f
        session.isComplete shouldBe false
    }

    @Test
    fun `progress counts imported and skipped entries`() {
        val session = session(imported = setOf("1"), skipped = setOf("2"))

        session.pendingCount shouldBe 1
        session.pendingEntries.map { it.id } shouldBe listOf("3")
        session.progress shouldBe (2f / 3f plusOrMinus 0.0001f)
        session.importedCount shouldBe 1
        session.skippedCount shouldBe 1
        session.isComplete shouldBe false
    }

    @Test
    fun `a session is complete when nothing is left to decide`() {
        val session = session(imported = setOf("1", "3"), skipped = setOf("2"))

        session.pendingCount shouldBe 0
        session.isComplete shouldBe true
        session.progress shouldBe 1f
    }

    @Test
    fun `an empty session is not complete`() {
        val session = session(entries = emptyList())

        session.progress shouldBe 0f
        session.isComplete shouldBe false
    }

    @Test
    fun `importing an entry moves it out of the skipped set`() {
        val session = session(skipped = setOf("1"))

        val updated = session.withImported("1")

        updated.isImported("1") shouldBe true
        updated.isSkipped("1") shouldBe false
        updated.importedEntryIds shouldBe setOf("1")
        updated.skippedEntryIds shouldBe emptySet()
    }

    @Test
    fun `skipping an entry moves it out of the imported set`() {
        val session = session(imported = setOf("2"))

        val updated = session.withSkipped("2")

        updated.isSkipped("2") shouldBe true
        updated.isImported("2") shouldBe false
    }

    @Test
    fun `restoring an entry clears both done sets`() {
        val session = session(imported = setOf("1"), skipped = setOf("2"))

        val restored = session.withPending("1").withPending("2")

        restored.pendingCount shouldBe 3
        restored.isImported("1") shouldBe false
        restored.isSkipped("2") shouldBe false
    }

    @Test
    fun `marking stamps updatedAt`() {
        val session = session()

        val updated = session.withImported("1")

        (updated.updatedAt >= session.updatedAt) shouldBe true
    }

    @Test
    fun `an entry without progress does not claim any`() {
        ImportEntry(id = "1", title = "T").hasProgress shouldBe false
        ImportEntry(id = "2", title = "T", progressChapter = 12.0).hasProgress shouldBe true
    }
}
