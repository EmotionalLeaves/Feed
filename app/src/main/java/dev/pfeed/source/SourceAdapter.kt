package dev.pfeed.source

import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.model.StreamInfo

/**
 * One content source. See README.md → "Adding a SourceAdapter".
 *
 * Contract:
 *  - Throw [SourceException] for anything the user should see (bad credentials, not configured,
 *    HTTP errors). The feed shows the message and carries on with the other sources.
 *  - [fetchFeed] with `cursor == null` means "start over / refresh".
 *  - Return `nextCursor = null` when there is nothing more.
 */
interface SourceAdapter {
    val id: String
    val displayName: String
    val requiresAuth: Boolean

    /** False when the adapter needs build-time configuration that is missing. Unconfigured adapters start disabled. */
    val isConfigured: Boolean get() = true

    suspend fun authenticate(ctx: AuthContext): AuthResult
    suspend fun fetchFeed(cursor: String?, prefs: FeedPrefs): Page<MediaItem>
    suspend fun search(query: String, cursor: String?): Page<MediaItem>
    suspend fun resolveStream(item: MediaItem): StreamInfo
    fun reportUrl(item: MediaItem): String?
}

class SourceException(
    val sourceId: String,
    message: String,
    val isAuthError: Boolean = false,
    cause: Throwable? = null,
    /** HTTP status when the failure was a non-2xx answer (429 = rate-limited). */
    val httpCode: Int? = null,
) : Exception(message, cause)

/** Offset-based paging helper for adapters that hold a full in-memory list. */
internal fun <T> List<T>.pageAt(cursor: String?, size: Int): Page<T> {
    val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val slice = drop(offset).take(size)
    val next = (offset + slice.size).takeIf { slice.isNotEmpty() && it < this.size }?.toString()
    return Page(slice, next)
}

internal fun sha1Hex(input: String, length: Int = 20): String {
    val digest = java.security.MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(length)
}
