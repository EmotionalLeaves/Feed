package dev.pfeed

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
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
    @Inject lateinit var playerPool: PlayerPool

    override fun onCreate(savedInstanceState: Bundle?) {
        // No screenshots, no screen recording, blank thumbnail in Recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PFeedTheme {
                AppRoot(lockManager = lockManager, onPanic = ::panic)
            }
        }
    }

    /** Panic: lock immediately and jump to the home screen. */
    private fun panic() {
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
