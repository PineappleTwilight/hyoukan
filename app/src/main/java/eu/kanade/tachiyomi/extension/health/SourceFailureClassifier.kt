package eu.kanade.tachiyomi.extension.health

import eu.kanade.tachiyomi.network.HttpException
import tachiyomi.domain.source.model.SourceFailureKind
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps a source failure onto the coarse kind the health screen shows.
 *
 * The order matters: Cloudflare usually arrives as a 403 or 503, so the message check has to run
 * before the status-code branch or every challenge would be filed as a plain HTTP error and the
 * reader would be told to retry instead of opening the site in a WebView.
 */
object SourceFailureClassifier {

    fun classify(throwable: Throwable): SourceFailureKind {
        val chain = generateSequence(throwable) { it.cause }.toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()

        if ("cloudflare" in text || "just a moment" in text || "challenge-platform" in text) {
            return SourceFailureKind.CLOUDFLARE
        }

        for (cause in chain) {
            when (cause) {
                is UnknownHostException -> return SourceFailureKind.DNS
                is SocketTimeoutException -> return SourceFailureKind.TIMEOUT
                is HttpException -> return when (cause.code) {
                    401 -> SourceFailureKind.AUTH
                    403 -> SourceFailureKind.CLOUDFLARE
                    404, 410 -> SourceFailureKind.NOT_FOUND
                    else -> SourceFailureKind.HTTP
                }
                is SSLException -> return SourceFailureKind.NETWORK
            }
        }

        return if (chain.any { it is IOException }) SourceFailureKind.NETWORK else SourceFailureKind.UNKNOWN
    }
}
