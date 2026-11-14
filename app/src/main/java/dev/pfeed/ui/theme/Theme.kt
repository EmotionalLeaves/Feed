package dev.pfeed.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand accent: used for the one primary action on a screen, selection, and progress. */
val Accent = Color(0xFFFF3D68)
val AccentSoft = Color(0x33FF3D68)
val HeartRed = Color(0xFFFF2D55)
val Good = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)
val Bad = Color(0xFFF87171)

/** Text tiers on the dark background. */
val TextHigh = Color(0xFFF4F4F6)
val TextMid = Color(0xB3F4F4F6)
val TextLow = Color(0x73F4F4F6)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFA1A1AA),
    background = Color(0xFF09090B),
    onBackground = TextHigh,
    surface = Color(0xFF09090B),
    onSurface = TextHigh,
    surfaceVariant = Color(0xFF1C1C21),
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Color(0xFF0C0C0F),
    surfaceContainerLow = Color(0xFF111114),
    surfaceContainer = Color(0xFF151519),
    surfaceContainerHigh = Color(0xFF1C1C21),
    surfaceContainerHighest = Color(0xFF26262C),
    outline = Color(0x33FFFFFF),
    outlineVariant = Color(0x1AFFFFFF),
    error = Bad,
)

private val base = Typography()

/** Heavier, tighter type than the Material defaults: headings read as headings. */
private val Type = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall.copy(lineHeight = 17.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium),
)

/** Small caps-style label above a group of settings. */
val SectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PFeedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, shapes = AppShapes, content = content)
}
