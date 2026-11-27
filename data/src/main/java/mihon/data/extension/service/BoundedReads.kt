package mihon.data.extension.service

import okio.BufferedSource
import java.io.IOException

/**
 * Largest extension-store payload the app will buffer.
 *
 * Both the store index and its per-store extension list are remote documents a repository
 * controls, and the protobuf branches used to pass `source.readByteArray()` straight to the
 * decoder: one oversized or hostile file could ask for the whole body in memory before a single
 * field was parsed. Real indexes are a few hundred kilobytes; 16 MiB leaves ample room and keeps
 * the failure before the allocation.
 */
internal const val MAX_EXTENSION_INDEX_BYTES = 16L * 1024 * 1024

/**
 * Reads [this] source to its end, refusing anything larger than [maxBytes].
 *
 * [BufferedSource.request] stops as soon as `maxBytes + 1` are buffered, so an oversized body is
 * never read further than the cap. A body that ends earlier is ordinary EOF and is returned in
 * full.
 */
internal fun BufferedSource.readBoundedByteArray(maxBytes: Long = MAX_EXTENSION_INDEX_BYTES): ByteArray {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    request(maxBytes + 1)
    if (buffer.size > maxBytes) {
        throw IOException("Extension index exceeds the $maxBytes byte limit")
    }
    return readByteArray()
}
