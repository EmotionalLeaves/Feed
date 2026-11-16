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
        CrashLog.install(this)
        lockManager.attach()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            settings.flow.collect {
                dev.pfeed.source.net.EncryptedDns.always = it.encryptedDns
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

/**
 * Every image, however it's requested (feed, thumbnails, warming the cache), decodes to at most
 * 2048 × 8192 pixels. A huge original requested at its own size became a bitmap Android refuses to
 * draw (over ~100 MB), which crashed the app ("Canvas: trying to draw too large bitmap").
 */
private object MaxSizeInterceptor : coil.intercept.Interceptor {
    private const val MAX_W = 2048
    private const val MAX_H = 8192

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
