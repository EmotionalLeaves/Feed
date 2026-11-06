package dev.pfeed.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF4D6D)
val HeartRed = Color(0xFFFF2D55)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF9AA0B4),
    background = Color(0xFF0B0B0F),
    surface = Color(0xFF14141A),
    surfaceVariant = Color(0xFF22222A),
    onBackground = Color(0xFFECECF1),
    onSurface = Color(0xFFECECF1),
)

@Composable
fun PFeedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
