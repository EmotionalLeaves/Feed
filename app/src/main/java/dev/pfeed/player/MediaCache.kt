package dev.pfeed.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Size-capped LRU disk cache for video. The size is read once per process (SimpleCache can't be
 * resized live), so a change in Settings applies on next launch.
 */
@OptIn(UnstableApi::class)
@Singleton
class MediaCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    var configuredSizeMb: Int
        get() = prefs.getInt(KEY_SIZE, DEFAULT_MB)
        set(value) { prefs.edit().putInt(KEY_SIZE, value).apply() }

    val cache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(configuredSizeMb.toLong() * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        )
    }

    /** http(s) through the cache; content:// and file:// go straight to disk via DefaultDataSource. */
    val cacheDataSourceFactory: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttp))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    val dataSourceFactory: DataSource.Factory by lazy {
        DefaultDataSource.Factory(context, cacheDataSourceFactory)
    }

    fun clear() {
        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
    }

    companion object {
        private const val KEY_SIZE = "cache_mb"
        const val DEFAULT_MB = 1024
    }
}
