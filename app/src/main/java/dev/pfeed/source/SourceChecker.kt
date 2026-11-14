package dev.pfeed.source

import dev.pfeed.domain.model.FeedMode
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.net.HttpJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Check sources": asks one source for posts and tries to open one of their files, from this phone
 * and connection, and says in plain words what happened. Sites behave differently per network and
 * country, so this is the ground truth for "why isn't X showing up".
 */
@Singleton
class SourceChecker @Inject constructor(private val http: HttpJson) {

    data class Result(val ok: Boolean, val detail: String)

    suspend fun check(a: SourceAdapter): Result {
        val listed: List<MediaItem> = try {
            withTimeoutOrNull(LIST_TIMEOUT_MS) {
                a.fetchFeed(null, FeedPrefs(FeedMode.LATEST)).items.ifEmpty { a.search(PROBE_QUERY, null).items }
            } ?: return Result(false, "Didn't answer within ${LIST_TIMEOUT_MS / 1000} s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result(false, e.message?.take(140) ?: e.javaClass.simpleName)
        }
        if (listed.isEmpty()) {
            // Sources that only show what you add yourself are fine when empty.
            return if (a.id in USER_FILLED) Result(true, "Nothing added to it yet") else Result(false, "Answered, but with no posts")
        }

        // Try up to three posts' files: one dead link shouldn't condemn a source.
        var tried = 0
        for (item in listed.take(3)) {
            val url = try {
                (withTimeoutOrNull(FILE_TIMEOUT_MS) { a.resolveStream(item) } as? StreamInfo.Direct)?.url
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            if (!url.startsWith("http")) return Result(true, "${listed.size} posts (on this device)")
            tried++
            if (withTimeoutOrNull(FILE_TIMEOUT_MS) { http.reachable(url) } == true) {
                return Result(true, "Working: ${listed.size} posts, files load")
            }
        }
        return if (tried == 0) Result(false, "Lists ${listed.size} posts, but none had a file to open")
        else Result(false, "Lists ${listed.size} posts, but their files aren't loading (the site's file servers, or blocked on this network)")
    }

    companion object {
        private const val PROBE_QUERY = "sex"
        private val USER_FILLED = setOf("rss", "local", "direct", "json", "api")
        private const val LIST_TIMEOUT_MS = 30_000L
        private const val FILE_TIMEOUT_MS = 10_000L
    }
}
