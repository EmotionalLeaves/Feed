package dev.pfeed.source.net

import dev.pfeed.source.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Shared helper: GET a URL and parse the body as a JSON tree, mapping failures to [SourceException]. */
@Singleton
class HttpJson @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    suspend fun get(sourceId: String, url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val parsed = url.toHttpUrlOrNull() ?: throw SourceException(sourceId, "Invalid URL: $url")
            val builder = Request.Builder().url(parsed).header("Accept", "application/json")
            // Reddit blocks default OkHttp UA; send something browser-like for .json endpoints
            if (parsed.host.contains("reddit.com")) {
                builder.header("User-Agent", "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 PrivateFeed/1.1")
            }
            headers.forEach { (k, v) -> builder.header(k, v) }
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SourceException(
                        sourceId,
                        "HTTP ${response.code} from ${parsed.host}",
                        isAuthError = response.code in 401..403,
                    )
                }
                try {
                    json.parseToJsonElement(body)
                } catch (e: Exception) {
                    throw SourceException(sourceId, "Unexpected (non-JSON) response from ${parsed.host}", cause = e)
                }
            }
        }
}
