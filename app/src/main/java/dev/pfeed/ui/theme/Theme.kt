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

/**
 * The app's colour, chosen in Settings → Appearance. Two stops: the accent itself and the warmer
 * (or cooler) second stop of the brand gradient. Held in snapshot state, so everything that reads
 * [Accent] or [BrandGradient] repaints the moment you pick another.
 */
enum class Palette(val label: String, private val fixedMain: Color, private val fixedSecond: Color) {
    /** Chameleon: takes its colour from the post on screen (see [Palette.follow]). */
    CHAMELEON("Chameleon", Color(0xFFFF3D68), Color(0xFFFF7A45)),
    ROSE("Rose", Color(0xFFFF3D68), Color(0xFFFF7A45)),
    VIOLET("Violet", Color(0xFF8B5CF6), Color(0xFFEC4899)),
    OCEAN("Ocean", Color(0xFF3B82F6), Color(0xFF22D3EE)),
    EMERALD("Emerald", Color(0xFF10B981), Color(0xFF0EA5E9)),
    AMBER("Amber", Color(0xFFF97316), Color(0xFFEF4444)),
    CRIMSON("Crimson", Color(0xFFDC2626), Color(0xFF9333EA));

    val main: Color get() = if (this == CHAMELEON) liveMain.value else fixedMain
    val second: Color get() = if (this == CHAMELEON) liveSecond.value else fixedSecond
    val gradient: androidx.compose.ui.graphics.Brush get() = androidx.compose.ui.graphics.Brush.linearGradient(listOf(main, second))

    companion object {
        private val chosen = androidx.compose.runtime.mutableStateOf(ROSE)
        private val liveMain = androidx.compose.runtime.mutableStateOf(Color(0xFFFF3D68))
        private val liveSecond = androidx.compose.runtime.mutableStateOf(Color(0xFFFF7A45))
        val current: Palette get() = chosen.value
        fun set(name: String?) { chosen.value = entries.firstOrNull { it.name.equals(name, true) } ?: ROSE }

        /** Chameleon: the colour of what's on screen, made bright enough to read white text on. */
        fun follow(hue: Float) {
            val h = ((hue % 360f) + 360f) % 360f
            liveMain.value = Color.hsv(h, 0.72f, 0.92f)
            liveSecond.value = Color.hsv((h + 28f) % 360f, 0.68f, 0.95f)
        }
    }
}

/** Brand accent: used for the one primary action on a screen, selection, and progress. */
val Accent: Color get() = Palette.current.main
val AccentSoft: Color get() = Palette.current.main.copy(alpha = 0.2f)
/** The second stop of the brand gradient. */
val AccentWarm: Color get() = Palette.current.second

/**
 * The app's signature gradient. Used only where it counts (the main action on a screen,
 * Follow, the like) so it stays distinctive rather than decorative.
 */
val BrandGradient: androidx.compose.ui.graphics.Brush get() = Palette.current.gradient
val HeartRed = Color(0xFFFF2D55)
val Good = Color(0xFF34D399)
val Warn = Color(0xFFFBBF24)
val Bad = Color(0xFFF87171)

/** Text tiers on the dark background. */
val TextHigh = Color(0xFFF4F4F6)
val TextMid = Color(0xB3F4F4F6)
val TextLow = Color(0x73F4F4F6)

private fun scheme(p: Palette) = darkColorScheme(
    primary = p.main,
    primaryContainer = p.main.copy(alpha = 0.2f),
    onPrimary = Color.White,
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

/**
 * Plus Jakarta Sans (SIL Open Font License, see assets/OFL-PlusJakartaSans.txt): a rounder, more
 * characterful face than the system font, bundled so it looks the same on every phone. Loaded off the
 * main thread (the system font stands in for the first moments) so it never slows the app's start.
 */
val Jakarta = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(dev.pfeed.R.font.jakarta_regular, FontWeight.Normal, loadingStrategy = androidx.compose.ui.text.font.FontLoadingStrategy.Async),
    androidx.compose.ui.text.font.Font(dev.pfeed.R.font.jakarta_medium, FontWeight.Medium, loadingStrategy = androidx.compose.ui.text.font.FontLoadingStrategy.Async),
    androidx.compose.ui.text.font.Font(dev.pfeed.R.font.jakarta_semibold, FontWeight.SemiBold, loadingStrategy = androidx.compose.ui.text.font.FontLoadingStrategy.Async),
    androidx.compose.ui.text.font.Font(dev.pfeed.R.font.jakarta_bold, FontWeight.Bold, loadingStrategy = androidx.compose.ui.text.font.FontLoadingStrategy.Async),
    androidx.compose.ui.text.font.Font(dev.pfeed.R.font.jakarta_extrabold, FontWeight.ExtraBold, loadingStrategy = androidx.compose.ui.text.font.FontLoadingStrategy.Async),
    androidx.compose.ui.text.font.Font(dev.pfeed.R.font.jakarta_extrabold, FontWeight.Black, loadingStrategy = androidx.compose.ui.text.font.FontLoadingStrategy.Async),
)

private val base = Typography().let { t ->
    fun TextStyle.j() = copy(fontFamily = Jakarta)
    Typography(
        displayLarge = t.displayLarge.j(), displayMedium = t.displayMedium.j(), displaySmall = t.displaySmall.j(),
        headlineLarge = t.headlineLarge.j(), headlineMedium = t.headlineMedium.j(), headlineSmall = t.headlineSmall.j(),
        titleLarge = t.titleLarge.j(), titleMedium = t.titleMedium.j(), titleSmall = t.titleSmall.j(),
        bodyLarge = t.bodyLarge.j(), bodyMedium = t.bodyMedium.j(), bodySmall = t.bodySmall.j(),
        labelLarge = t.labelLarge.j(), labelMedium = t.labelMedium.j(), labelSmall = t.labelSmall.j(),
    )
}

/** Heavier, tighter type than the Material defaults: headings read as headings. */
private val Type = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    // Material's default tracking (0.25–0.5 sp) reads dated and airy; modern UI type sits close to 0.
    bodyLarge = base.bodyLarge.copy(letterSpacing = 0.1.sp),
    bodyMedium = base.bodyMedium.copy(letterSpacing = 0.05.sp),
    bodySmall = base.bodySmall.copy(lineHeight = 17.sp, letterSpacing = 0.05.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.05.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

/** Small caps-style label above a group of settings. */
val SectionLabel = TextStyle(fontFamily = Jakarta, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PFeedTheme(content: @Composable () -> Unit) {
    val p = Palette.current
    val main = p.main
    val colors = androidx.compose.runtime.remember(p, main) { scheme(p) }
    MaterialTheme(colorScheme = colors, typography = Type, shapes = AppShapes, content = content)
}
