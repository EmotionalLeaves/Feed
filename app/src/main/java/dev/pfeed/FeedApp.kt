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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
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
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(okHttpClient)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            add(VideoFrameDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
