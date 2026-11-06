package dev.pfeed

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import dagger.hilt.android.AndroidEntryPoint
import dev.pfeed.source.auth.OAuthManager
import javax.inject.Inject

/** Catches pfeed://oauth/callback, hands it to [OAuthManager] and returns to the app. */
@AndroidEntryPoint
class OAuthRedirectActivity : ComponentActivity() {

    @Inject lateinit var oauth: OAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        intent?.data?.let(oauth::onRedirect)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
