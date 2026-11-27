package eu.kanade.tachiyomi.extension.health

import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.source.model.SourceFailureKind
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@Execution(ExecutionMode.CONCURRENT)
class SourceFailureClassifierTest {

    @Test
    fun `timeouts and dns failures are recognised through the cause chain`() {
        SourceFailureClassifier.classify(SocketTimeoutException("timeout")) shouldBe SourceFailureKind.TIMEOUT
        SourceFailureClassifier.classify(UnknownHostException("example.com")) shouldBe SourceFailureKind.DNS

        val wrapped = IOException("request failed", UnknownHostException("example.com"))
        SourceFailureClassifier.classify(wrapped) shouldBe SourceFailureKind.DNS
    }

    @Test
    fun `http statuses map to the buckets the screen explains`() {
        SourceFailureClassifier.classify(HttpException(401)) shouldBe SourceFailureKind.AUTH
        SourceFailureClassifier.classify(HttpException(403)) shouldBe SourceFailureKind.CLOUDFLARE
        SourceFailureClassifier.classify(HttpException(404)) shouldBe SourceFailureKind.NOT_FOUND
        SourceFailureClassifier.classify(HttpException(410)) shouldBe SourceFailureKind.NOT_FOUND
        SourceFailureClassifier.classify(HttpException(500)) shouldBe SourceFailureKind.HTTP
        SourceFailureClassifier.classify(HttpException(429)) shouldBe SourceFailureKind.HTTP
    }

    @Test
    fun `a cloudflare message wins over the status code`() {
        // A challenge usually arrives as a 403/503; the message is the only thing that tells the
        // reader to open the site in a WebView instead of simply retrying.
        val challenge = IOException("HTTP 503: Just a moment... cf-challenge-platform")
        SourceFailureClassifier.classify(challenge) shouldBe SourceFailureKind.CLOUDFLARE
    }

    @Test
    fun `plain io failures are network, unknown throwables are unknown`() {
        SourceFailureClassifier.classify(IOException("connection reset")) shouldBe SourceFailureKind.NETWORK
        SourceFailureClassifier.classify(IllegalStateException("parser broke")) shouldBe SourceFailureKind.UNKNOWN
    }
}
