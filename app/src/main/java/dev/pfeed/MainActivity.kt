package dev.pfeed

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.map
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.pfeed.player.PlayerPool
import dev.pfeed.security.AppLockManager
import dev.pfeed.ui.AppRoot
import dev.pfeed.ui.theme.PFeedTheme
import javax.inject.Inject

/** FragmentActivity (not ComponentActivity) because BiometricPrompt needs a FragmentManager. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var lockManager: AppLockManager
    @Inject lateinit var mediaHealth: dev.pfeed.data.repo.MediaHealth
    @Inject lateinit var playerPool: PlayerPool
    /** For the on-device health test: the same check Settings → Sources runs. */
    @Inject lateinit var sourceChecker: dev.pfeed.source.SourceChecker
    @Inject lateinit var sourceRegistry: dev.pfeed.source.SourceRegistry
    @Inject lateinit var tubeAdapter: dev.pfeed.source.tube.TubeAdapter
    @Inject lateinit var httpJson: dev.pfeed.source.net.HttpJson
    @Inject lateinit var searchRepository: dev.pfeed.data.repo.SearchRepository
    @Inject lateinit var library: dev.pfeed.data.repo.LibraryRepository
    @Inject lateinit var feedSessions: dev.pfeed.data.repo.FeedSessionFactory
    @Inject lateinit var settingsRepository: dev.pfeed.data.settings.SettingsRepository
    @Inject lateinit var offlineStore: dev.pfeed.data.repo.OfflineStore
    @Inject lateinit var settingsExporter: dev.pfeed.data.repo.SettingsExporter

    override fun onCreate(savedInstanceState: Bundle?) {
        // No screenshots, no screen recording, blank thumbnail in Recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // The app is always dark: force light status/nav-bar icons even when the phone is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(dev.pfeed.ui.AgeGate.EXTRA_CONFIRMED, false) == true) dev.pfeed.ui.AgeGate.confirm(this)
        setContent {
            PFeedTheme {
                val blurFlow = androidx.compose.runtime.remember { settingsRepository.flow.map { it.blurPreviews } }
                val blur by blurFlow.collectAsState(initial = false)
                androidx.compose.runtime.CompositionLocalProvider(dev.pfeed.ui.common.LocalBlurPreviews provides blur) {
                    AppRoot(lockManager = lockManager, onPanic = ::panic)
                }
            }
        }
    }

    /**
     * Picture-in-picture (Settings → Playback, off by default): leaving the app while a video plays
     * keeps it going in a small window. Locked, or nothing playing: the app just goes to the background.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        if (lockManager.locked.value) return
        val playing = playerPool.anyPlaying() ?: return
        val pipOn = kotlinx.coroutines.runBlocking { settingsRepository.get().pictureInPicture }
        if (!pipOn) return
        val ratio = playing.let { (w, h) ->
            if (w <= 0 || h <= 0) android.util.Rational(9, 16)
            else android.util.Rational(w, h).let { r ->
                // Android only takes ratios between 1:2.39 and 2.39:1.
                val v = w.toFloat() / h
                when { v > 2.39f -> android.util.Rational(239, 100); v < 1 / 2.39f -> android.util.Rational(100, 239); else -> r }
            }
        }
        runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        dev.pfeed.ui.feed.Pip.set(isInPictureInPictureMode)
    }

    private var lastPanicAt = 0L

    /**
     * Panic detection at the window level: sees every touch before any view does, including native
     * views embedded in the feed (video surfaces, web embeds) that the Compose-level detector can miss.
     */
    /** Volume up while the feed is muted turns the sound on, as in other video apps (and still raises the volume). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) dev.pfeed.ui.feed.VolumeKeys.up.tryEmit(Unit)
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount >= 3) panic()
        return super.dispatchTouchEvent(ev)
    }

    /** Panic: lock immediately and jump to the home screen. Debounced: both detectors may fire for one gesture. */
    private fun panic() {
        val now = SystemClock.elapsedRealtime()
        if (lastPanicAt != 0L && now - lastPanicAt < 1_000L) return
        lastPanicAt = now
        lockManager.lockNow()
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onDestroy() {
        if (isFinishing) playerPool.releaseAll()
        super.onDestroy()
    }
}
