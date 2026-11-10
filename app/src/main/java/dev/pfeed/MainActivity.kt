package dev.pfeed

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // No screenshots, no screen recording, blank thumbnail in Recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // The app is always dark: force light status/nav-bar icons even when the phone is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            PFeedTheme {
                AppRoot(lockManager = lockManager, onPanic = ::panic)
            }
        }
    }

    private var lastPanicAt = 0L

    /**
     * Panic detection at the window level: sees every touch before any view does, including native
     * views embedded in the feed (video surfaces, web embeds) that the Compose-level detector can miss.
     */
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
