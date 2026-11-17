package dev.pfeed.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pfeed.ui.common.BrandButton
import dev.pfeed.ui.theme.BrandGradient

/**
 * Adults only: asked once, the first time the app opens, before anything is shown.
 * Leaving closes the app; nothing is remembered until you confirm.
 */
object AgeGate {
    private const val PREFS = "ui_hints"
    private const val KEY = "adult_confirmed"

    /** Instrumented tests turn this off so every test doesn't start on the gate. */
    @Volatile var required: Boolean = true

    /** A launch with this extra set confirms it (used by CI's startup and monkey runs). */
    const val EXTRA_CONFIRMED = "dev.pfeed.ADULT_CONFIRMED"

    fun confirmed(context: Context): Boolean =
        !required || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun confirm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}

@Composable
fun AgeGateScreen(onConfirm: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            // Opaque to touches: nothing underneath reacts while this is up.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(BrandGradient), contentAlignment = Alignment.Center) {
                Text("18+", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(28.dp))
            Text("Adults only", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(12.dp))
            Text(
                "This app shows sexually explicit pictures and videos. You must be 18 or older, " +
                    "and old enough where you live, to use it.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(36.dp))
            BrandButton("I'm 18 or older", onClick = onConfirm, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { (context as? Activity)?.finishAndRemoveTask() }, modifier = Modifier.fillMaxWidth()) {
                Text("Leave", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "By going on you confirm that viewing this content is legal where you are.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            )
        }
    }
}
