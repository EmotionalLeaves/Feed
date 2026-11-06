package dev.pfeed.source.net

import dev.pfeed.source.SourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared helper: GET a URL and parse the body as a JSON tree, mapping failures to [SourceException].
 *
 * The call is cancellable: cancelling the coroutine (e.g. a `withTimeout` around it) cancels the
 * underlying OkHttp call, so a slow server can't pin a fetch open past its time budget.
 */
@Singleton
class HttpJson @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    suspend fun get(sourceId: String, url: String, headers: Map<String, String> = emptyMap()): JsonElement {
        val parsed = url.toHttpUrlOrNull() ?: throw SourceException(sourceId, "Invalid URL: $url")
        val builder = Request.Builder().url(parsed).header("Accept", "application/json")
        // Reddit blocks default OkHttp UA; send something browser-like for .json endpoints
        if (parsed.host.contains("reddit.com")) {
            builder.header("User-Agent", "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 PrivateFeed/1.1")
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val call = http.newCall(builder.build())

        val (code, body) = try {
            call.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw SourceException(sourceId, "${parsed.host} timed out", cause = e)
        } catch (e: IOException) {
            throw SourceException(sourceId, "Network error from ${parsed.host}: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
        if (code !in 200..299) {
            throw SourceException(
                sourceId,
                if (code == 429) "${parsed.host} is rate-limiting, backing off" else "HTTP $code from ${parsed.host}",
                isAuthError = code in 401..403,
            )
        }
        // Some boards (rule34.xxx) answer "no results" with an empty body.
        if (body.isBlank()) return JsonArray(emptyList())
        return try {
            withContext(Dispatchers.Default) { json.parseToJsonElement(body) }
        } catch (e: Exception) {
            throw SourceException(sourceId, "Unexpected (non-JSON) response from ${parsed.host}", cause = e)
        }
    }

    /** GET that returns raw text (for HTML pages). Same error handling as [get]. */
    suspend fun getRaw(sourceId: String, url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: throw SourceException(sourceId, "Invalid URL: $url")
        val builder = Request.Builder().url(parsed).header("Accept", "text/html")
        val call = http.newCall(builder.build())
        val (code, body) = try {
            call.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw SourceException(sourceId, "${parsed.host} timed out", cause = e)
        } catch (e: IOException) {
            throw SourceException(sourceId, "Network error from ${parsed.host}: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
        if (code !in 200..299) {
            throw SourceException(
                sourceId,
                if (code == 429) "${parsed.host} is rate-limiting, backing off" else "HTTP $code from ${parsed.host}",
                isAuthError = code in 401..403,
            )
        }
        return body
    }

    private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { it.code to (it.body?.string().orEmpty()) } }
                result.onSuccess { if (cont.isActive) cont.resume(it) }
                    .onFailure { e -> if (cont.isActive) cont.resumeWithException(e as? IOException ?: IOException(e)) }
            }
        })
    }
}
