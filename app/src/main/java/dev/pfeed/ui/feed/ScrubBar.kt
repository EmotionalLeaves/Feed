package dev.pfeed.ui.feed

import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.delay
import coil.imageLoader

/**
 * Shorts-style progress bar along the bottom of a video. Normally a thin line; drag anywhere along
 * it (or tap a spot) to scrub: the bar thickens, a thumb and "0:12 / 0:45" appear, and the video
 * follows your finger. Dragging is horizontal so it never fights the vertical feed swipe.
 *
 * [onScrubbedBack] fires when a scrub lands earlier than where the video was, which the feed
 * counts as a rewatch (a strong "I like this" signal).
 */
@OptIn(UnstableApi::class)
@Composable
fun ScrubBar(
    player: ExoPlayer?,
    modifier: Modifier = Modifier,
    onScrubbedBack: () -> Unit = {},
    /** The video's preview frames, chapters and watch counts, where its site gives them. */
    extras: dev.pfeed.domain.reco.VideoExtras.Info? = null,
    /** Moments you saved in this video: always marked on the bar. */
    moments: List<Long> = emptyList(),
) {
    if (player == null) return
    var duration by remember(player) { mutableLongStateOf(0L) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var dragging by remember(player) { mutableStateOf(false) }
    var dragFraction by remember(player) { mutableFloatStateOf(0f) }
    var startPosition by remember(player) { mutableLongStateOf(0L) }
    var lastSeekAt by remember(player) { mutableLongStateOf(0L) }

    LaunchedEffect(player) {
        while (true) {
            if (!dragging) {
                duration = player.duration.takeIf { it > 0 } ?: 0L
                position = player.currentPosition.coerceAtLeast(0L)
            }
            delay(if (player.isPlaying) 50 else 200)
        }
    }
    if (duration <= 0L) return

    fun seekTo(fraction: Float, final: Boolean) {
        val now = System.currentTimeMillis()
        if (!final && now - lastSeekAt < SEEK_THROTTLE_MS) return
        lastSeekAt = now
        // While dragging, jump to the nearest keyframe so the picture keeps up with the finger;
        // on release, land exactly where the finger stopped.
        player.setSeekParameters(if (final) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo((fraction * duration).toLong())
    }

    fun finish() {
        seekTo(dragFraction, final = true)
        val target = (dragFraction * duration).toLong()
        if (target < startPosition - REWIND_THRESHOLD_MS) onScrubbedBack()
        position = target
        dragging = false
    }

    val fraction = if (dragging) dragFraction else (position.toFloat() / duration).coerceIn(0f, 1f)
    val trackHeight by animateDpAsState(if (dragging) 6.dp else 2.dp, label = "scrubTrack")

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(TOUCH_HEIGHT)
            .semantics { contentDescription = "Video progress" }
            .pointerInput(player) {
                detectTapGestures { o ->
                    startPosition = player.currentPosition
                    dragFraction = (o.x / size.width).coerceIn(0f, 1f)
                    finish()
                }
            }
            .pointerInput(player) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        startPosition = player.currentPosition
                        dragFraction = (o.x / size.width).coerceIn(0f, 1f)
                        seekTo(dragFraction, final = false)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        seekTo(dragFraction, final = false)
                    },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                )
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        val width = maxWidth
        val at = (dragFraction * duration).toLong()
        val chapter = extras?.chapters?.let { dev.pfeed.domain.reco.VideoExtras.chapterAt(it, at) }
        // The frame at the finger, from the site's preview sheets, above the time.
        val tl = extras?.timeline
        val sheet = tl?.takeIf { dragging }?.at(at)
        if (sheet != null && tl != null) {
            val previewW = 160.dp; val previewH = 90.dp
            val x = (width * dragFraction - previewW / 2).coerceIn(0.dp, (width - previewW).coerceAtLeast(0.dp))
            ScrubPreview(sheet, tl, Modifier.align(Alignment.TopStart).offset(x = x, y = (-36).dp - previewH - 8.dp).size(previewW, previewH))
        }
        // How much each stretch is watched, as a soft wave above the bar while scrubbing.
        val heat = extras?.heat.orEmpty()
        if (dragging && heat.size >= 10) {
            val peak = heat.drop(1).maxOrNull()?.coerceAtLeast(1) ?: 1
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(22.dp).align(Alignment.BottomStart).offset(y = (-8).dp)) {
                val step = size.width / heat.size
                heat.forEachIndexed { i, h ->
                    val v = (h.toFloat() / peak).coerceIn(0f, 1f)
                    drawRect(Color.White.copy(alpha = 0.18f + 0.3f * v), topLeft = androidx.compose.ui.geometry.Offset(i * step, size.height * (1 - v)), size = androidx.compose.ui.geometry.Size(step * 0.8f, size.height * v))
                }
            }
        }
        if (dragging) {
            Text(
                (chapter?.let { "$it · " } ?: "") + "${formatTime(at)} / ${formatTime(duration)}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-36).dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.BottomStart)
                .background(Color.White.copy(alpha = 0.3f)),
        ) {
            // White while watching (quiet); the brand gradient while you scrub.
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction)
                    .then(if (dragging) Modifier.background(dev.pfeed.ui.theme.BrandGradient) else Modifier.background(Color.White)),
            )
        }
        // Where each chapter starts: small marks on the bar (tap one to go there).
        extras?.chapters?.takeIf { it.isNotEmpty() && dragging }?.forEach { (_, ms) ->
            Box(
                Modifier.align(Alignment.BottomStart).offset(x = width * (ms.toFloat() / duration).coerceIn(0f, 1f) - 1.dp, y = (-1).dp)
                    .size(2.dp, 10.dp).background(Color(0xFFFFB547)),
            )
        }
        // Your saved moments: small amber dots just above the bar.
        moments.forEach { ms ->
            Box(
                Modifier.align(Alignment.BottomStart)
                    .offset(x = width * (ms.toFloat() / duration).coerceIn(0f, 1f) - 3.dp, y = -trackHeight - 2.dp)
                    .size(6.dp).clip(CircleShape).background(Color(0xFFFFB547)),
            )
        }
        if (dragging) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = width * fraction - THUMB / 2, y = (THUMB - 6.dp) / 2)
                    .size(THUMB)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

private val TOUCH_HEIGHT = 28.dp
private val THUMB = 14.dp
private const val SEEK_THROTTLE_MS = 60L
private const val REWIND_THRESHOLD_MS = 1_000L

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** One frame cut out of a site's preview sheet (a grid of small frames). */
@Composable
private fun ScrubPreview(sheet: Triple<String, Int, Int>, timeline: dev.pfeed.domain.reco.VideoExtras.Timeline, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var loaded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sheet.first) {
        if (loaded == sheet.first) return@LaunchedEffect
        val result = context.imageLoader.execute(coil.request.ImageRequest.Builder(context).data(sheet.first).allowHardware(false).build())
        val bmp = (result as? coil.request.SuccessResult)?.drawable?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
        if (bmp != null) { image = bmp.asImageBitmap(); loaded = sheet.first }
    }
    val img = image ?: return
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
        val cw = img.width / timeline.cols; val ch = img.height / timeline.rows
        drawImage(
            img,
            srcOffset = androidx.compose.ui.unit.IntOffset(sheet.second * cw, sheet.third * ch),
            srcSize = androidx.compose.ui.unit.IntSize(cw, ch),
            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}
