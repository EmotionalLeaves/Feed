package dev.pfeed.ui.common

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.border
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import coil.compose.AsyncImage
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType

/** Dialogs get their own window; force FLAG_SECURE on them too. */
val SecureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun openExternal(context: Context, url: String) {
    try {
        val uri = Uri.parse(url)
        if (uri.scheme == "http" || uri.scheme == "https") {
            CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF).build().launchUrl(context, uri)
        } else {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MediaGrid(
    items: List<MediaItem>,
    onOpen: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** A short label for the tile's corner (where it's from), or null for none. */
    label: ((MediaItem) -> String?)? = null,
    /** Groups the grid under headings ("Today", "Yesterday"…); null for one plain grid. */
    sectionOf: ((MediaItem) -> String)? = null,
    /** Called as the last rows come into view, to fetch more. */
    onNearEnd: (() -> Unit)? = null,
    /** Posts you've already watched: dimmed, with a small eye, so new ones stand out. */
    seen: ((MediaItem) -> Boolean)? = null,
    /** Press and hold a tile: actions for that post (remove it from a list, and so on). */
    onLongPress: ((MediaItem) -> Unit)? = null,
    contentTopPadding: androidx.compose.ui.unit.Dp = 2.dp,
    /**
     * Plays a post silently in its tile. When given, the grid brings itself to life: once scrolling
     * stops, the video nearest the middle of the screen starts playing in place, and every few
     * seconds the spotlight moves on to the next one in view. Null keeps every tile still.
     */
    livePreview: (@Composable (MediaItem) -> Unit)? = LocalLivePreview.current,
    /** Picking several (keys of the picked ones): taps pick and unpick instead of opening. Null when not picking. */
    picked: Set<String>? = null,
    onPick: ((MediaItem) -> Unit)? = null,
    /** Full-width content above the tiles that scrolls with them (a profile, say). */
    header: (@Composable () -> Unit)? = null,
) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // Peek: in grids without a hold menu, holding a tile shows it big, playing, until you let go.
    var peeking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<MediaItem?>(null) }
    val canPeek = onLongPress == null && picked == null
    var spot by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Any?>(null) }
    val byKey = androidx.compose.runtime.remember(items) { items.associateBy { it.key } }
    if (livePreview != null) {
        androidx.compose.runtime.LaunchedEffect(gridState, byKey) {
            androidx.compose.runtime.snapshotFlow { gridState.isScrollInProgress }.collectLatest { scrolling ->
                if (scrolling) { spot = null; return@collectLatest }
                kotlinx.coroutines.delay(PREVIEW_SETTLE_MS)
                val info = gridState.layoutInfo
                val top = info.viewportStartOffset
                val bottom = info.viewportEndOffset
                val middle = (top + bottom) / 2
                // Videos whose tile is wholly on screen, nearest the middle first.
                val order = info.visibleItemsInfo
                    .filter { it.offset.y >= top - 1 && it.offset.y + it.size.height <= bottom + 1 }
                    .filter { byKey[it.key]?.type.let { t -> t == MediaType.VIDEO || t == MediaType.GIF } }
                    .sortedBy { kotlin.math.abs(it.offset.y + it.size.height / 2 - middle) }
                    .map { it.key }
                if (order.isEmpty()) { spot = null; return@collectLatest }
                var i = 0
                while (true) {
                    spot = order[i % order.size]
                    kotlinx.coroutines.delay(PREVIEW_DWELL_MS)
                    i++
                }
            }
        }
    }
    peeking?.let { PeekOverlay(it, livePreview) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = contentTopPadding, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (header != null) item(key = "header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
        var lastSection: String? = null
        items.forEachIndexed { index, post ->
            // Optional date (or other) sections: a full-width heading where the section changes.
            val section = sectionOf?.invoke(post)
            if (section != null && section != lastSection) {
                lastSection = section
                item(key = "section:$section", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(
                        section,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
                    )
                }
            }
            item(key = post.key) {
                GridTile(
                    post, index, if (picked != null && onPick != null) ({ _: Int -> onPick(post) }) else onOpen, label,
                    seen = seen?.invoke(post) == true,
                    onLongPress = if (picked != null && onPick != null) ({ onPick(it) }) else onLongPress,
                    onPeek = if (canPeek) ({ p -> peeking = p }) else null,
                    live = if (livePreview != null && spot == post.key && picked == null) ({ livePreview(post) }) else null,
                    picked = picked?.contains(post.key),
                )
                if (onNearEnd != null && index >= items.size - NEAR_END_TILES) androidx.compose.runtime.LaunchedEffect(items.size) { onNearEnd() }
            }
        }
    }
}

@Composable
private fun PeekOverlay(item: MediaItem, live: (@Composable (MediaItem) -> Unit)?) {
    androidx.compose.ui.window.Popup(alignment = Alignment.Center, properties = androidx.compose.ui.window.PopupProperties(focusable = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        val pop = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0.85f) }
        androidx.compose.runtime.LaunchedEffect(Unit) { pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f)) }
        Box(
            Modifier.fillMaxWidth(0.86f).aspectRatio(3f / 4f)
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black),
        ) {
            AsyncImage(model = item.thumbnailUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (live != null && !LocalBlurPreviews.current) live(item)
            MediaMask()
            item.title?.takeIf { it.isNotBlank() }?.let { t ->
                Text(
                    t,
                    color = Color.White,
                    maxLines = 2,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(14.dp),
                )
            }
        }
    }
}

private const val NEAR_END_TILES = 9
private const val PREVIEW_SETTLE_MS = 450L
private const val PREVIEW_DWELL_MS = 5_000L

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun GridTile(
    item: MediaItem,
    index: Int,
    onOpen: (index: Int) -> Unit,
    label: ((MediaItem) -> String?)?,
    seen: Boolean = false,
    onLongPress: ((MediaItem) -> Unit)? = null,
    live: (@Composable () -> Unit)? = null,
    /** Null when not picking; else whether this one is picked. */
    picked: Boolean? = null,
    /** Hold to peek: called with the post while held, null when let go. */
    onPeek: ((MediaItem?) -> Unit)? = null,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onPeek != null) Modifier.pointerInput(item.key) {
                    detectTapGestures(
                        onTap = { onOpen(index) },
                        onLongPress = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onPeek(item)
                        },
                        onPress = { tryAwaitRelease(); onPeek(null) },
                    )
                } else Modifier.combinedClickable(
                    onClick = { onOpen(index) },
                    onLongClick = onLongPress?.let { act -> { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); act(item) } },
                ),
            ),
    ) {
        // Placeholder glyph shows while the thumbnail loads (or if it never does).
        Icon(
            if (item.type == MediaType.IMAGE) Icons.Filled.Image else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.15f),
            modifier = Modifier.align(Alignment.Center).size(28.dp),
        )
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (LocalBlurPreviews.current) Modifier.blur(28.dp) else Modifier),
        )
        // The live preview, playing in place over the still (never while previews are blurred).
        if (live != null && !LocalBlurPreviews.current) live()
        MediaMask()
        // Picking: a ring in the corner, filled and ticked when picked, the tile dimmed when not.
        if (picked != null) {
            if (picked) Box(Modifier.fillMaxSize().border(3.dp, dev.pfeed.ui.theme.Accent, shape))
            else Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (picked) dev.pfeed.ui.theme.Accent else Color.Black.copy(alpha = 0.35f))
                    .border(1.5.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (picked) Icon(Icons.Filled.Check, contentDescription = "Picked", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        val badge = when (item.type) {
            MediaType.VIDEO, MediaType.EMBED -> item.durationSec?.let(::formatDuration) ?: ""
            MediaType.GIF -> "GIF"
            MediaType.IMAGE -> null
        }
        val source = label?.invoke(item)
        // One quiet line along the bottom: where it's from on the left, how long on the right.
        if (badge != null || source != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.32f)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
            )
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    source.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!badge.isNullOrEmpty()) {
                    Text(badge, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                } else if (badge != null && item.type != MediaType.GIF) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
        if (seen) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Icon(
                Icons.Filled.Visibility,
                contentDescription = "Watched",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(18.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(3.dp),
            )
        }
        // A saved post that's on the phone: opens without a connection.
        if (item.extras[dev.pfeed.data.repo.OfflineStore.KEPT] != null) {
            Icon(
                Icons.Filled.DownloadDone,
                contentDescription = "On this phone",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(2.dp),
            )
        }
        // Part-watched long video: how far you got (it carries on from there).
        val resumeAt = remember(item.key) { dev.pfeed.player.ResumePoints.get(item.key) }
        val total = item.durationSec?.times(1000L)
        if (resumeAt != null && total != null && total > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((resumeAt.toFloat() / total).coerceIn(0.02f, 1f)).background(dev.pfeed.ui.theme.Accent))
            }
        }
    }
}

fun formatDuration(sec: Int): String = if (sec >= 3600) {
    "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
} else {
    "%d:%02d".format(sec / 60, sec % 60)
}

/**
 * Design screenshots (taken by the instrumented test and uploaded from CI) cover every post's
 * picture with a flat panel, so they show the app's own layout and none of the content.
 */
object MaskMedia {
    @Volatile var on = false
}

@androidx.compose.runtime.Composable
fun MediaMask(modifier: Modifier = Modifier) {
    if (MaskMedia.on) Box(modifier.fillMaxSize().background(Color(0xFF3A3A44)))
}

/** Blur grid previews (Settings → Privacy). Provided at the top of the app from settings. */
val LocalBlurPreviews = androidx.compose.runtime.staticCompositionLocalOf { false }


/** Copies [text]; marked sensitive, so Android 13+ doesn't show it in the clipboard preview. */
fun copySensitive(context: Context, label: String, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
    val clip = android.content.ClipData.newPlainText(label, text)
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
