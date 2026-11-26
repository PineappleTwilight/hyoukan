package mihon.feature.libraryimport.data

import android.content.Context
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.importer.model.ImportEntry
import tachiyomi.domain.importer.model.ImportSession
import java.io.File

/**
 * Persistence of import sessions.
 *
 * The properties that matter for resumability: a session written by one screen is readable by the
 * next app start, a mark applied to one entry does not drop the others, and a file the app cannot
 * read leaves the import UI working rather than crashing it.
 */
@Execution(ExecutionMode.CONCURRENT)
class ImportSessionStoreTest {

    @TempDir
    lateinit var tempDir: File

    private val json = Json { ignoreUnknownKeys = true }

    private fun store(): ImportSessionStore {
        val context = mockk<Context>()
        every { context.filesDir } returns tempDir
        return ImportSessionStore(context, json)
    }

    private fun session(
        id: String = "session-1",
        entries: List<ImportEntry> = listOf(
            ImportEntry(id = "1", title = "One"),
            ImportEntry(id = "2", title = "Two"),
            ImportEntry(id = "3", title = "Three"),
        ),
        updatedAt: Long = 1L,
    ) = ImportSession(
        id = id,
        name = "export.json",
        sourceId = "mangabaka",
        createdAt = 1L,
        updatedAt = updatedAt,
        entries = entries,
    )

    @Test
    fun `a saved session is readable back`() = runTest {
        val store = store()
        store.save(session())

        val loaded = store.get("session-1").shouldNotBeNull()

        loaded.name shouldBe "export.json"
        loaded.sourceId shouldBe "mangabaka"
        loaded.entries.map { it.title } shouldBe listOf("One", "Two", "Three")
    }

    @Test
    fun `sessions are listed newest first`() = runTest {
        val store = store()
        store.save(session(id = "old", updatedAt = 10L))
        store.save(session(id = "new", updatedAt = 20L))

        store.getAll().map { it.id } shouldBe listOf("new", "old")
        store.sessions.first().map { it.id } shouldBe listOf("new", "old")
    }

    @Test
    fun `marking an entry imported leaves the rest pending`() = runTest {
        val store = store()
        store.save(session())

        store.markImported("session-1", "2")

        val loaded = store.get("session-1").shouldNotBeNull()
        loaded.importedEntryIds shouldBe setOf("2")
        loaded.pendingEntries.map { it.id } shouldBe listOf("1", "3")
    }

    @Test
    fun `an entry can be skipped and restored`() = runTest {
        val store = store()
        store.save(session())

        store.markSkipped("session-1", "1")
        store.get("session-1").shouldNotBeNull().skippedEntryIds shouldBe setOf("1")

        store.markPending("session-1", "1")
        val restored = store.get("session-1").shouldNotBeNull()
        restored.skippedEntryIds shouldBe emptySet()
        restored.importedEntryIds shouldBe emptySet()
    }

    @Test
    fun `marking an unknown session or entry is a no-op`() = runTest {
        val store = store()
        store.save(session())

        store.markImported("missing", "1")
        store.markImported("session-1", "missing")

        val loaded = store.get("session-1").shouldNotBeNull()
        loaded.importedEntryIds shouldBe emptySet()
    }

    @Test
    fun `saving again replaces the session instead of duplicating it`() = runTest {
        val store = store()
        store.save(session())
        store.save(session().copy(name = "renamed"))

        store.getAll().size shouldBe 1
        store.get("session-1").shouldNotBeNull().name shouldBe "renamed"
    }

    @Test
    fun `a session survives a fresh store`() = runTest {
        store().save(session())

        // A second instance is what the next app start looks like: nothing in memory, same file.
        val reopened = store()
        reopened.get("session-1").shouldNotBeNull().entries.size shouldBe 3
    }

    @Test
    fun `the sessions flow reads the file on first collection`() = runTest {
        store().save(session())

        // The sessions screen only collects the flow; nothing else has touched the new store.
        val emitted = store().sessions.first()

        emitted.map { it.id } shouldBe listOf("session-1")
    }

    @Test
    fun `the sessions flow follows later writes`() = runTest {
        val store = store()
        store.save(session(id = "one", updatedAt = 1L))

        val emitted = store.sessions.first()

        emitted.size shouldBe 1
        store.save(session(id = "two", updatedAt = 2L))
        store.sessions.first().map { it.id } shouldBe listOf("two", "one")
    }

    @Test
    fun `deleting a session removes it`() = runTest {
        val store = store()
        store.save(session())
        store.delete("session-1")

        store.get("session-1").shouldBeNull()
        store.getAll().shouldBeEmpty()
    }

    @Test
    fun `an unreadable file starts from an empty list`() = runTest {
        val directory = File(tempDir, "library_imports")
        directory.mkdirs()
        File(directory, "sessions.json").writeText("{ not json")

        store().getAll().shouldBeEmpty()
    }

    @Test
    fun `an empty store has no sessions`() = runTest {
        store().getAll().shouldBeEmpty()
    }
}
