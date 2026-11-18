package dev.pfeed.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pfeed.domain.reco.Features
import dev.pfeed.domain.reco.TasteEngine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/** One colour per interest, so a taste's tags glow alike. */
private val GroupColors = listOf(
    Color(0xFFFF5C8A), Color(0xFF8C7CFF), Color(0xFF3FC6FF), Color(0xFF39D98A),
    Color(0xFFFFB547), Color(0xFFFF7A45), Color(0xFFE36BFF), Color(0xFF7FE0D4),
)

/**
 * Your taste as a night sky: every tag a star, placed so tags that keep company sit together, sized
 * by how much you like it and coloured by the interest it belongs to; each interest is a soft glow
 * at its centre, bigger for a bigger share of For You. Tags you've turned away from are dim red.
 * Tap a star to open that tag.
 */
@Composable
fun TasteMapView(map: TasteEngine.TasteMap, onOpenTag: (String) -> Unit, modifier: Modifier = Modifier, onSun: ((Int) -> Unit)? = null) {
    val measurer = rememberTextMeasurer()
    val rise = remember { Animatable(0f) }
    LaunchedEffect(map) { rise.snapTo(0f); rise.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    val twinkle by rememberInfiniteTransition(label = "twinkle").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    // Strongest tags get names; the rest stay as points so the sky doesn't turn into a word cloud.
    val named = remember(map) { map.stars.sortedByDescending { abs(it.liking) + if (it.group >= 0) 0.3 else 0.0 }.take(18).map { it.tag }.toSet() }
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

    Box(
        modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.radialGradient(listOf(Color(0xFF1B1530), Color(0xFF07060C)))),
    ) {
        Canvas(
            Modifier.fillMaxWidth().height(320.dp).pointerInput(map) {
                detectTapGestures { tap ->
                    val pad = 36.dp.toPx()
                    fun at(x: Float, y: Float) = Offset(pad + (x + 1f) / 2f * (size.width - 2 * pad), pad + (y + 1f) / 2f * (size.height - 2 * pad))
                    // The glow at an interest's centre plays it as a mix.
                    if (onSun != null) map.suns.firstOrNull { val p = at(it.x, it.y); hypot(p.x - tap.x, p.y - tap.y) < 22.dp.toPx() }?.let { onSun(it.group); return@detectTapGestures }
                    map.stars.minByOrNull { val p = at(it.x, it.y); hypot(p.x - tap.x, p.y - tap.y) }
                        ?.takeIf { val p = at(it.x, it.y); hypot(p.x - tap.x, p.y - tap.y) < 32.dp.toPx() }
                        ?.let { onOpenTag(it.tag) }
                }
            },
        ) {
            val pad = 36.dp.toPx()
            fun at(x: Float, y: Float) = Offset(pad + (x + 1f) / 2f * (size.width - 2 * pad), pad + (y + 1f) / 2f * (size.height - 2 * pad))
            val p = rise.value
            // Faint background dust, fixed per map.
            repeat(60) { i ->
                val x = ((i * 73 + 17) % 101) / 100f; val y = ((i * 37 + 53) % 97) / 96f
                drawCircle(Color.White.copy(alpha = 0.06f + 0.05f * sin((twinkle + i * 0.13f) * 2 * PI.toFloat()).coerceAtLeast(0f)), 1.dp.toPx(), Offset(x * size.width, y * size.height))
            }
            // Each interest: a glow at its centre, and threads to its stars.
            map.suns.forEach { sun ->
                val c = GroupColors[sun.group % GroupColors.size]
                val at = at(sun.x, sun.y)
                val r = (40 + 90 * sun.share).dp.toPx() * p
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.35f), Color.Transparent), center = at, radius = r.coerceAtLeast(1f)), r, at)
                map.stars.filter { it.group == sun.group }.forEach { s ->
                    drawLine(c.copy(alpha = 0.18f * p), at, at(s.x, s.y), strokeWidth = 1.dp.toPx())
                }
            }
            map.stars.forEachIndexed { i, s ->
                // Stars drift out from the middle as the map opens.
                val target = at(s.x, s.y)
                val centre = Offset(size.width / 2, size.height / 2)
                val pos = centre + (target - centre) * p
                val c = when {
                    s.liking < -0.2 -> Color(0xFFB04050)
                    s.group >= 0 -> GroupColors[s.group % GroupColors.size]
                    else -> Color(0xFFDAD6F0)
                }
                val glow = 0.75f + 0.25f * sin((twinkle + i * 0.37f) * 2 * PI.toFloat())
                val r = (2.5f + 4.5f * abs(s.liking).toFloat()).dp.toPx()
                drawCircle(c.copy(alpha = 0.25f * glow * p), r * 2.6f, pos)
                drawCircle(c.copy(alpha = glow * p), r, pos)
                if (s.liking < -0.2) drawCircle(c.copy(alpha = 0.6f * p), r * 1.8f, pos, style = Stroke(1.dp.toPx()))
                if (s.tag in named) {
                    val text = Features.label(s.tag)
                    val layout = measurer.measure(text, labelStyle.copy(color = c.copy(alpha = 0.95f * p)))
                    val tx = (pos.x - layout.size.width / 2).coerceIn(4f, size.width - layout.size.width - 4f)
                    val ty = (pos.y + r + 3.dp.toPx()).coerceAtMost(size.height - layout.size.height - 2f)
                    drawText(layout, topLeft = Offset(tx, ty))
                }
            }
        }
    }
}
