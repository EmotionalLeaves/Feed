package dev.pfeed

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import dev.pfeed.security.AppLockManager
import dev.pfeed.work.MaintenanceWorker
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FeedApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var lockManager: AppLockManager
    @Inject lateinit var settings: dev.pfeed.data.settings.SettingsRepository
    @Inject lateinit var offline: dev.pfeed.data.repo.OfflineStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        dev.pfeed.player.ResumePoints.init(this)
        dev.pfeed.player.ComicPages.init(this)
        dev.pfeed.source.net.GeoBlock.init(this)
        CrashLog.install(this)
        lockManager.attach()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            settings.flow.collect {
                dev.pfeed.source.net.EncryptedDns.always = it.encryptedDns
                dev.pfeed.source.net.AppProxy.configure(proxyConfig(it))
                val height = if (it.dataSaver) 480 else 1080
                dev.pfeed.source.tube.TubeSite.maxHeight = height
                dev.pfeed.player.PlayerPool.maxHeight = height
            }
        }
        MaintenanceWorker.schedule(this)
        offline.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(okHttpClient)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            add(VideoFrameDecoder.Factory())
            add(MaxSizeInterceptor)
        }
        .crossfade(true)
        .build()
}

/** Settings → Blocked where you are, as the network layer wants it. */
internal fun proxyConfig(s: dev.pfeed.data.settings.AppSettings): dev.pfeed.source.net.AppProxy.Config = when (s.proxyType) {
    "http" -> dev.pfeed.source.net.AppProxy.Config(java.net.Proxy.Type.HTTP, s.proxyHost.trim(), s.proxyPort, s.proxyUser, s.proxyPassword, s.proxyEverything)
    "socks" -> dev.pfeed.source.net.AppProxy.Config(java.net.Proxy.Type.SOCKS, s.proxyHost.trim(), s.proxyPort, s.proxyUser, s.proxyPassword, s.proxyEverything)
    "orbot" -> dev.pfeed.source.net.AppProxy.Config(java.net.Proxy.Type.SOCKS, "127.0.0.1", 9050, everything = s.proxyEverything)
    else -> dev.pfeed.source.net.AppProxy.Config()
}

/**
 * Every image, however it's requested (feed, thumbnails, warming the cache), is asked for at most
 * 1280 × 4096 pixels. Decoding may come out up to twice that each way (it samples by powers of two),
 * so the largest possible bitmap stays near 80 MB, under the ~100 MB Android will draw. A huge
 * original asked for at 2048 × 8192 came out at 140 MB and crashed the feed.
 */
private object MaxSizeInterceptor : coil.intercept.Interceptor {
    private const val MAX_W = 1280
    private const val MAX_H = 4096

    override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): coil.request.ImageResult {
        val s = chain.size
        fun cap(d: coil.size.Dimension, max: Int): coil.size.Dimension = when (d) {
            is coil.size.Dimension.Pixels -> coil.size.Dimension(minOf(d.px, max))
            else -> coil.size.Dimension(max)
        }
        val capped = coil.size.Size(cap(s.width, MAX_W), cap(s.height, MAX_H))
        return if (capped == s) chain.proceed(chain.request) else chain.withSize(capped).proceed(chain.request)
    }
}
