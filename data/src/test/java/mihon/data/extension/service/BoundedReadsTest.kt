package mihon.data.extension.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.IOException

/**
 * Remote extension indexes are bounded before they reach the decoder (#100).
 */
@Execution(ExecutionMode.CONCURRENT)
class BoundedReadsTest {

    @Test
    fun `a body inside the limit is returned whole`() {
        Buffer().writeUtf8("0123456789").readBoundedByteArray(maxBytes = 10).size shouldBe 10
    }

    @Test
    fun `a body exactly at the limit is allowed`() {
        val source = Buffer().write(ByteArray(8) { it.toByte() })

        source.readBoundedByteArray(maxBytes = 8).size shouldBe 8
    }

    @Test
    fun `a body one byte over the limit is refused`() {
        val source = Buffer().write(ByteArray(9))

        val error = runCatching { source.readBoundedByteArray(maxBytes = 8) }.exceptionOrNull()

        error.shouldBeInstanceOf<IOException>()
        error.message.orEmpty().contains("limit") shouldBe true
    }

    @Test
    fun `an empty body reads as empty rather than failing`() {
        Buffer().readBoundedByteArray(maxBytes = 8).size shouldBe 0
    }

    @Test
    fun `a non-positive limit is a programming error`() {
        runCatching { Buffer().readBoundedByteArray(maxBytes = 0) }
            .exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()
    }
}
