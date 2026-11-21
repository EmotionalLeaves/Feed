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
    @Inject lateinit var updater: dev.pfeed.update.Updater
    @Inject lateinit var tasteEngine: dev.pfeed.data.repo.TasteEngineStore
    @Inject lateinit var relatedStore: dev.pfeed.data.repo.RelatedStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @Volatile private var dataSaver = false
    /** Data saver only while on mobile data (and the network is metered right now). */
    @Volatile private var dataSaverOnMobile = false
    @Volatile private var metered = false
    @Volatile private var bestQuality = false

    /** 480p with Data saver on, or while the battery is low (battery saver); 1080p otherwise. */
    private fun applyQuality() {
        val height = if (dataSaver || (dataSaverOnMobile && metered) || dev.pfeed.player.Battery.saving) 480 else if (bestQuality) 2160 else 1080
        dev.pfeed.source.tube.TubeSite.maxHeight = height
        dev.pfeed.player.PlayerPool.maxHeight = height
    }

    private var readyScheduled: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        dev.pfeed.player.ResumePoints.init(this)
        // Wi-Fi or mobile data: what "Data saver on mobile data" goes by, as the phone moves between them.
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            metered = cm.isActiveNetworkMetered
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    val now = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    if (now != metered) { metered = now; applyQuality() }
                }
            })
        }
        dev.pfeed.player.Moments.init(this)
        dev.pfeed.player.ActPrefs.init(this)
        dev.pfeed.player.ComicPages.init(this)
        dev.pfeed.source.net.GeoBlock.init(this)
        CrashLog.install(this)
        lockManager.attach()
        // Create the UI's shared state holders here, on the main thread, before any screen draws:
        // created first from the settings collector below (another thread), a screen already
        // composing couldn't read them ("state created after the snapshot was taken") and crashed.
        dev.pfeed.ui.theme.Palette.current.main
        dev.pfeed.ui.lock.LockLook.calculator.value
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            settings.flow.collect {
                dev.pfeed.ui.theme.Palette.set(it.accent)
                dev.pfeed.player.SmartVolume.enabled = it.smartVolume
                dev.pfeed.domain.privacy.Incognito.always = it.privateMode
                dev.pfeed.ui.lock.LockLook.calculator.value = it.calculatorLock
                dev.pfeed.ui.feed.VolumeKeys.turnPages = it.volumeKeysTurnPages
                dev.pfeed.source.net.EncryptedDns.always = it.encryptedDns
                dev.pfeed.source.net.AppProxy.configure(proxyConfig(it))
                dataSaver = it.dataSaver
                dataSaverOnMobile = it.dataSaverOnMobile
                bestQuality = it.bestQuality
                dev.pfeed.player.Battery.saverOn = it.batterySaver
                if (it.readyAhead != readyScheduled) { readyScheduled = it.readyAhead; dev.pfeed.work.ReadyWorker.schedule(this@FeedApp, it.readyAhead) }
                applyQuality()
            }
        }
        dev.pfeed.player.Battery.init(this) { applyQuality() }
        // Updates from GitHub: look as the app comes up; a downloaded one that can go in without a
        // prompt is installed once you've left the app, so it never closes in front of you.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                updater.auto()
                dev.pfeed.data.repo.FollowingNews.load(this@FeedApp)
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                tasteEngine.flush()
                Thread { relatedStore.save(); dev.pfeed.domain.reco.CoStars.save() }.start()
                dev.pfeed.data.repo.DataMeter.month()
                updater.install(quiet = true)
            }
        })
        dev.pfeed.data.repo.DataMeter.init(this)
        Thread { relatedStore.load(); dev.pfeed.domain.reco.CoStars.load(filesDir) }.start()
        MaintenanceWorker.schedule(this)
        dev.pfeed.work.UpdateWorker.schedule(this)
        dev.pfeed.work.WeeklyMixWorker.schedule(this)
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
        // Decoded pictures kept in memory: a quarter of what the app may use, never more than 128 MB
        // (on a phone with a very large heap, Coil's default would let it grow well past that).
        .memoryCache {
            val bytes = (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(128L * 1024 * 1024)
            coil.memory.MemoryCache.Builder(this).maxSizeBytes(bytes.toInt()).build()
        }
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
 * 1280 × 4096 pixels, and decoded to fit within that (about 20 MB at most), well under the ~100 MB
 * Android will draw. A huge
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
        // Capped requests are decoded to fit the cap exactly: sampling by powers of two alone can leave
        // a bitmap up to four times the cap's pixels (80 MB), and a few of those warming at once is
        // how memory spiked past 800 MB in a search session on the test phone.
        return if (capped == s) chain.proceed(chain.request)
        else chain.withSize(capped).proceed(chain.request.newBuilder().precision(coil.size.Precision.EXACT).build())
    }
}
