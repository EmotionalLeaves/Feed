package dev.pfeed.source.auth

import android.app.Activity
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class OAuthConfig(
    val authUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val scopes: String,
    val redirectUri: String = REDIRECT_URI,
) {
    companion object {
        const val REDIRECT_URI = "pfeed://oauth/callback"
    }
}

@Serializable
data class TokenSet(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long? = null,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()) = expiresAtMs != null && nowMs > expiresAtMs - 60_000
}

class OAuthException(message: String) : Exception(message)

/** OAuth 2.0 authorization-code flow with PKCE, run in a Custom Tab. No client secret is ever stored. */
@Singleton
class OAuthManager @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {
    private data class Pending(val state: String, val result: CompletableDeferred<Uri>)

    @Volatile
    private var pending: Pending? = null

    suspend fun authorize(activity: Activity, cfg: OAuthConfig): TokenSet {
        val verifier = randomUrlSafe(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(16)
        val deferred = CompletableDeferred<Uri>()
        pending = Pending(state, deferred)

        val uri = Uri.parse(cfg.authUrl).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", cfg.clientId)
            .appendQueryParameter("redirect_uri", cfg.redirectUri)
            .appendQueryParameter("scope", cfg.scopes)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF).build().launchUrl(activity, uri)

        val redirect = try {
            withTimeout(5 * 60_000L) { deferred.await() }
        } finally {
            pending = null
        }
        redirect.getQueryParameter("error")?.let { throw OAuthException("Sign-in refused: $it") }
        if (redirect.getQueryParameter("state") != state) throw OAuthException("Sign-in failed: state mismatch")
        val code = redirect.getQueryParameter("code") ?: throw OAuthException("Sign-in failed: no code returned")

        return tokenRequest(
            cfg.tokenUrl,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", cfg.redirectUri)
                .add("client_id", cfg.clientId)
                .add("code_verifier", verifier)
                .build(),
        )
    }

    suspend fun refresh(cfg: OAuthConfig, refreshToken: String): TokenSet = tokenRequest(
        cfg.tokenUrl,
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", cfg.clientId)
            .build(),
    ).let { if (it.refreshToken == null) it.copy(refreshToken = refreshToken) else it }

    /** Called by [dev.pfeed.OAuthRedirectActivity]. */
    fun onRedirect(uri: Uri) {
        pending?.result?.complete(uri)
    }

    private suspend fun tokenRequest(url: String, body: FormBody): TokenSet = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(body).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw OAuthException("Token request failed (HTTP ${response.code})")
            val parsed = try {
                json.decodeFromString(TokenResponse.serializer(), text)
            } catch (e: Exception) {
                throw IOException("Unexpected token response", e)
            }
            TokenSet(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                expiresAtMs = parsed.expiresIn?.let { System.currentTimeMillis() + it * 1000L },
            )
        }
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
    )

    private fun randomUrlSafe(bytes: Int) = b64url(ByteArray(bytes).also(SecureRandom()::nextBytes))
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
