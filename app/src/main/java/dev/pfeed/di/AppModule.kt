package dev.pfeed.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pfeed.BuildConfig
import dev.pfeed.data.db.AppDatabase
import dev.pfeed.data.db.AffinityDao
import dev.pfeed.data.db.BlockDao
import dev.pfeed.data.db.CredentialDao
import dev.pfeed.data.db.FollowDao
import dev.pfeed.data.db.ItemDao
import dev.pfeed.data.db.LibraryDao
import dev.pfeed.data.db.SignalDao
import dev.pfeed.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.math.max

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        // OkHttp's default is 5 concurrent requests per host. Video streaming, video preloads and
        // API calls to the same host (RedGIFs, Reddit, a board's CDN) all share this client, so the
        // default made preloads and feed requests queue behind playback.
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 96; maxRequestsPerHost = 16 })
        .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        // coomer / kemono sit behind DDoS-Guard, which serves their API and files only to clients
        // that present themselves as a browser on the site. The app reaches the same public pages a
        // browser does, so for these hosts it sends a browser User-Agent and names the site as the
        // referring page. One interceptor covers the API, thumbnails (Coil) and video (ExoPlayer),
        // since they all share this client; the API's Accept: text/css is set where it's called.
        .addInterceptor { chain ->
            val request = chain.request()
            val site = ArchiveHeaders.siteFor(request.url.host)
            if (site == null) chain.proceed(request)
            else chain.proceed(
                request.newBuilder()
                    .header("User-Agent", ArchiveHeaders.BROWSER_UA)
                    .apply { if (request.header("Referer") == null) header("Referer", "https://$site/") }
                    .build(),
            )
        }
        // Default User-Agent for everything else
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.header("User-Agent") != null) chain.proceed(request)
            else chain.proceed(request.newBuilder().header("User-Agent", "PrivateFeed/${BuildConfig.VERSION_NAME} (Android)").build())
        }
        // gelbooru.com and realbooru.com only serve their images and videos to requests that name
        // the site as the referring page (hotlink protection); without it the file URL returns an
        // HTML page, so nothing from those boards ever displayed. The app does get these links from
        // the site's own pages, so say so. Covers thumbnails (Coil), playback (ExoPlayer), preloads.
        .addInterceptor { chain ->
            val request = chain.request()
            val site = HotlinkReferer.siteFor(request.url.host)
            if (site == null || request.header("Referer") != null) chain.proceed(request)
            else chain.proceed(request.newBuilder().header("Referer", "https://$site/").build())
        }
        // 429 / 503: one quick retry, and only when the server asks for a short wait. Longer
        // back-off is handled per site by the sources themselves; sleeping here for up to 20 s held
        // a network thread (and whatever was waiting on it, including video) for nothing.
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
            if ((response.code == 429 || response.code == 503) && retryAfterSec != null && retryAfterSec <= 2) {
                response.close()
                Thread.sleep(max(retryAfterSec * 1_000L, 500L))
                chain.proceed(chain.request())
            } else {
                response
            }
        }
        .build()

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context, secrets: SecretStore): AppDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, AppDatabase::class.java, "pfeed.db")
            .openHelperFactory(SupportOpenHelperFactory(secrets.databasePassphrase()))
            .build()
    }

    @Provides fun itemDao(db: AppDatabase): ItemDao = db.itemDao()
    @Provides fun libraryDao(db: AppDatabase): LibraryDao = db.libraryDao()
    @Provides fun signalDao(db: AppDatabase): SignalDao = db.signalDao()
    @Provides fun affinityDao(db: AppDatabase): AffinityDao = db.affinityDao()
    @Provides fun followDao(db: AppDatabase): FollowDao = db.followDao()
    @Provides fun blockDao(db: AppDatabase): BlockDao = db.blockDao()
    @Provides fun credentialDao(db: AppDatabase): CredentialDao = db.credentialDao()
}

/** Boards whose file hosts require the board itself as the referring page. */
object HotlinkReferer {
    private val SITES = listOf("gelbooru.com", "realbooru.com")

    fun siteFor(host: String): String? = SITES.firstOrNull { host == it || host.endsWith(".$it") }
}

/**
 * coomer / kemono and their file and thumbnail subdomains (img., n1–n4., data.), which are behind
 * DDoS-Guard and expect browser-looking requests.
 */
object ArchiveHeaders {
    private val SITES = listOf("coomer.st", "kemono.cr", "coomer.su", "coomer.party", "kemono.su", "kemono.party")

    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** The site to name as referrer for [host] (covers img./n*./data. subdomains), or null. */
    fun siteFor(host: String): String? = SITES.firstOrNull { host == it || host.endsWith(".$it") }
}
