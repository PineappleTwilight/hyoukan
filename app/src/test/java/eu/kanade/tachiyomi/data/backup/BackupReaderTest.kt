package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.IOException

/**
 * The reader treats every length in the file as untrusted input (#99).
 *
 * Both the per-entry length and the metadata fields are varints copied straight out of the backup,
 * so a corrupted or hand-made file could ask for a multi-gigabyte allocation before any parsing
 * began. These pin the rejection points, and that an ordinary backup still reads.
 */
@Execution(ExecutionMode.CONCURRENT)
class BackupReaderTest {

    private val parser = ProtoBuf

    private fun Buffer.writeVarint(value: Long) {
        var remaining = value
        while ((remaining and 0x7FL.inv()) != 0L) {
            writeByte((remaining and 0x7F).toInt() or 0x80)
            remaining = remaining ushr 7
        }
        writeByte(remaining.toInt())
    }

    private fun Buffer.entryTag() = writeVarint(1L shl 3 or 2L)

    private fun Buffer.lengthDelimitedTag(field: Int) = writeVarint(field.toLong() shl 3 or 2L)

    @Test
    fun `an entry longer than the ceiling is rejected before reading it`() {
        val buffer = Buffer().apply {
            entryTag()
            writeVarint(MAX_ENTRY_BYTES + 1)
        }

        val error = runCatching { BackupReader(buffer, parser).nextEntry() }.exceptionOrNull()

        error.shouldBeInstanceOf<IOException>()
        error.message.orEmpty().contains("length") shouldBe true
    }

    @Test
    fun `a metadata field longer than the ceiling is rejected`() {
        val buffer = Buffer().apply {
            lengthDelimitedTag(field = 2)
            writeVarint(MAX_METADATA_BYTES + 1)
        }

        val error = runCatching { BackupReader(buffer, parser).nextEntry() }.exceptionOrNull()

        error.shouldBeInstanceOf<IOException>()
    }

    @Test
    fun `metadata accumulated past the ceiling is rejected even when each field fits`() {
        // One tiny field, then one field whose declared length alone would carry the buffer over
        // the limit. The check is on the running total, so the second one must be refused before
        // its bytes are read.
        val buffer = Buffer().apply {
            lengthDelimitedTag(field = 3)
            writeVarint(1)
            writeByte(0)
            lengthDelimitedTag(field = 4)
            writeVarint(MAX_METADATA_BYTES)
        }

        val error = runCatching { BackupReader(buffer, parser).nextEntry() }.exceptionOrNull()

        error.shouldBeInstanceOf<IOException>()
        error.message.orEmpty().contains("metadata") shouldBe true
    }

    @Test
    fun `an entry inside the ceiling still decodes`() {
        val manga = BackupManga(source = 1L, url = "/manga/1", title = "Moriarty")
        val bytes = parser.encodeToByteArray(BackupManga.serializer(), manga)
        val buffer = Buffer().apply {
            entryTag()
            writeVarint(bytes.size.toLong())
            write(bytes)
        }
        val reader = BackupReader(buffer, parser)

        reader.nextEntry() shouldBe true
        reader.readEntry().title shouldBe "Moriarty"
    }

    @Test
    fun `a negative-looking varint is rejected rather than wrapped`() {
        // Ten bytes of continuation bits is the classic way to smuggle a negative long into a
        // length. Either the varint decoder or the ceiling must stop it; neither may produce a
        // negative readByteArray argument.
        val buffer = Buffer().apply {
            entryTag()
            repeat(10) { writeByte(0xFF) }
            writeByte(0x01)
        }

        runCatching { BackupReader(buffer, parser).nextEntry() }
            .exceptionOrNull()
            .shouldBeInstanceOf<IOException>()
    }
}
