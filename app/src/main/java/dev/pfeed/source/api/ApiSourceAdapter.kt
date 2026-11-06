package dev.pfeed.source.api

import dev.pfeed.BuildConfig
import dev.pfeed.data.db.CredentialDao
import dev.pfeed.data.db.CredentialEntity
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.SourceAdapter
import dev.pfeed.source.SourceException
import dev.pfeed.source.auth.OAuthConfig
import dev.pfeed.source.auth.OAuthManager
import dev.pfeed.source.auth.TokenSet
import dev.pfeed.source.rss.FeedParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "first fully working API adapter" slot. All plumbing is real (Retrofit, OAuth+PKCE via Custom Tabs,
 * token storage in the encrypted DB, refresh on 401, clear errors), but the target service is not chosen
 * yet: the spec requires asking the owner before contacting any new domain. Configure it in
 * local.properties (api.*) and adapt [ApiService] to the chosen source's documented API.
 * With no base URL configured the adapter reports itself unconfigured and stays disabled.
 */
@Singleton
class ApiSourceAdapter @Inject constructor(
    http: OkHttpClient,
    private val json: Json,
    private val oauth: OAuthManager,
    private val credentials: CredentialDao,
) : SourceAdapter {

    private val baseUrl = BuildConfig.API_BASE_URL.trim()
    private val oauthConfig = OAuthConfig(
        authUrl = BuildConfig.API_AUTH_URL.trim(),
        tokenUrl = BuildConfig.API_TOKEN_URL.trim(),
        clientId = BuildConfig.API_CLIENT_ID.trim(),
        scopes = BuildConfig.API_SCOPES.trim(),
    )

    override val id = "api"
    override val displayName = BuildConfig.API_DISPLAY_NAME.ifBlank { "API source" }
    override val requiresAuth = oauthConfig.authUrl.isNotEmpty()
    override val isConfigured = baseUrl.startsWith("https://")

    @Volatile private var tokens: TokenSet? = null
    private val tokenMutex = Mutex()

    private val service: ApiService? by lazy {
        if (!isConfigured) return@lazy null
        val client = http.newBuilder().addInterceptor { chain ->
            val t = tokens
            val req = if (t != null) chain.request().newBuilder().header("Authorization", "Bearer ${t.accessToken}").build()
            else chain.request()
            chain.proceed(req)
        }.build()
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }

    override suspend fun authenticate(ctx: AuthContext): AuthResult {
        if (!isConfigured) return AuthResult.Failure("Not configured. See SOURCES.md.")
        if (!requiresAuth) return AuthResult.NotRequired
        return try {
            val t = oauth.authorize(ctx.activity, oauthConfig)
            save(t)
            AuthResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "Sign-in failed")
        }
    }

    override suspend fun fetchFeed(cursor: String?, prefs: FeedPrefs): Page<MediaItem> = call {
        val page = feed(cursor, prefs.tag, PAGE_SIZE)
        Page(page.items.mapNotNull(::toMediaItem), page.next)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> = call {
        val page = search(query, cursor, PAGE_SIZE)
        Page(page.items.mapNotNull(::toMediaItem), page.next)
    }

    override suspend fun resolveStream(item: MediaItem): StreamInfo {
        item.extras["stream"]?.let { return StreamInfo.Direct(it, item.extras["mime"]) }
        item.extras["embed"]?.let { return StreamInfo.Embed(it) }
        val s = call { stream(item.id) }
        return if (s.embed) StreamInfo.Embed(s.url) else StreamInfo.Direct(s.url, s.mimeType)
    }

    override fun reportUrl(item: MediaItem): String? = item.extras["report"] ?: item.pageUrl

    private fun toMediaItem(a: ApiItem): MediaItem? {
        val type = when (a.kind.lowercase()) {
            "video" -> MediaType.VIDEO
            "image" -> MediaType.IMAGE
            "gif" -> MediaType.GIF
            "embed" -> MediaType.EMBED
            else -> return null
        }
        return MediaItem(
            id = a.id,
            sourceId = id,
            type = type,
            title = a.title,
            creator = a.creator,
            tags = a.tags,
            durationSec = a.durationSec,
            thumbnailUrl = a.thumbnailUrl,
            pageUrl = a.pageUrl,
            publishedAt = a.publishedAt?.let(FeedParser::parseDate),
            extras = buildMap {
                a.streamUrl?.let { put("stream", it) }
                a.embedUrl?.let { put("embed", it) }
                a.reportUrl?.let { put("report", it) }
            },
        )
    }

    private suspend fun <T> call(block: suspend ApiService.() -> T): T {
        val api = service ?: throw SourceException(id, "Not configured. See SOURCES.md.")
        if (requiresAuth) ensureTokens()
        return try {
            api.block()
        } catch (e: HttpException) {
            if (e.code() == 401 && requiresAuth) {
                refreshOrFail()
                try {
                    api.block()
                } catch (e2: HttpException) {
                    throw httpError(e2)
                }
            } else {
                throw httpError(e)
            }
        } catch (e: IOException) {
            throw SourceException(id, "Network error: ${e.message ?: e.javaClass.simpleName}", cause = e)
        } catch (e: SerializationException) {
            throw SourceException(id, "Unexpected response format", cause = e)
        }
    }

    private suspend fun ensureTokens() {
        tokenMutex.withLock {
            val t = tokens ?: credentials.get(id)?.let { runCatching { json.decodeFromString(TokenSet.serializer(), it.json) }.getOrNull() }
                ?: throw SourceException(id, "Sign in required (Settings → Sources).", isAuthError = true)
            tokens = t
            if (t.isExpired()) refreshLocked()
        }
    }

    private suspend fun refreshOrFail() {
        tokenMutex.withLock { refreshLocked() }
    }

    private suspend fun refreshLocked() {
        val refreshToken = tokens?.refreshToken
        if (refreshToken == null) {
            clear()
            throw SourceException(id, "Session expired. Sign in again (Settings → Sources).", isAuthError = true)
        }
        try {
            save(oauth.refresh(oauthConfig, refreshToken))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            clear()
            throw SourceException(id, "Session expired. Sign in again (Settings → Sources).", isAuthError = true, cause = e)
        }
    }

    private suspend fun save(t: TokenSet) {
        tokens = t
        credentials.upsert(CredentialEntity(id, json.encodeToString(TokenSet.serializer(), t)))
    }

    private suspend fun clear() {
        tokens = null
        credentials.delete(id)
    }

    private fun httpError(e: HttpException) = when (e.code()) {
        401, 403 -> SourceException(id, "Access denied (HTTP ${e.code()}). Check your sign-in.", isAuthError = true, cause = e)
        429 -> SourceException(id, "Rate limited. Try again later.", cause = e)
        else -> SourceException(id, "Server error (HTTP ${e.code()})", cause = e)
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}
