package dev.pfeed.ui.feed

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.StreamInfo

/**
 * Wall: the feed as a 2 × 2 grid of clips all playing at once (muted), a screen at a time; swipe up
 * for the next four. Tap one to watch it full screen in the feed, from there on.
 * Uses the same four-player pool as the feed (the feed's own pages aren't on screen meanwhile).
 */
@Composable
fun Wall(items: List<MediaItem>, vm: FeedViewModel, active: Boolean, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val pages = (items.size + PER_PAGE - 1) / PER_PAGE
    val pager = rememberPagerState { pages }
    LaunchedEffect(pager.settledPage, items.size) {
        // Keep the feed loading ahead, as swiping it would.
        if ((pager.settledPage + 2) * PER_PAGE >= items.size) vm.loadMore()
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0, key = { it }) { page ->
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 52.dp, start = 3.dp, end = 3.dp, bottom = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (row in 0 until 2) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (col in 0 until 2) {
                            val index = page * PER_PAGE + row * 2 + col
                            val item = items.getOrNull(index)
                            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Color(0xFF16161A))) {
                                if (item != null) WallTile(item, vm, playing = active && page == pager.settledPage) { onPick(index) }
                            }
                        }
                    }
                }
            }
        }
        // Header: what this is, and the way out.
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Back to the feed", tint = Color.White) }
            Text("Wall", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Tap one to watch it", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun WallTile(item: MediaItem, vm: FeedViewModel, playing: Boolean, onClick: () -> Unit) {
    val stream by produceState<StreamInfo?>(initialValue = null, item.key) { value = vm.resolve(item) }
    val isVideo = Display.effectiveType(item, stream) == MediaType.VIDEO
    val pool = vm.playerPool
    val version by pool.changes.collectAsState()
    // Only the tiles on the screen you're looking at hold a player.
    val player = remember(item.key, version, playing, isVideo) { if (playing && isVideo) pool.acquire(item.key) else null }
    var firstFrame by remember(item.key) { mutableStateOf(false) }
    DisposableEffect(item.key, playing, isVideo) {
        onDispose { if (playing && isVideo) pool.release(item.key) }
    }
    LaunchedEffect(player, stream) {
        val p = player ?: return@LaunchedEffect
        val s = stream as? StreamInfo.Direct ?: return@LaunchedEffect
        // Small decodes: four at once on a phone.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(Int.MAX_VALUE, WALL_MAX_HEIGHT).build()
        if (p.currentMediaItem?.mediaId != item.key) {
            firstFrame = false
            p.setMediaItem(ExoMediaItem.Builder().setMediaId(item.key).setUri(s.url).apply { s.mimeType?.let { setMimeType(it) } }.build())
            p.prepare()
        }
        p.repeatMode = Player.REPEAT_MODE_ONE
        dev.pfeed.player.PlayerPool.applyMute(p, muted = true)
        p.playWhenReady = true
    }
    DisposableEffect(player) {
        val p = player
        val l = object : Player.Listener { override fun onRenderedFirstFrame() { firstFrame = true } }
        p?.addListener(l)
        onDispose { p?.removeListener(l); p?.playWhenReady = false }
    }
    Box(Modifier.fillMaxSize().clickable(onClick = onClick)) {
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { view -> if (view.player !== player) view.player = player },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!firstFrame || player == null) Thumbnail(item)
        dev.pfeed.ui.common.MediaMask()
        // Who and how long, quietly along the bottom.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.3f).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                Display.creator(item)?.let { "@$it" } ?: Display.source(item, ""),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            item.durationSec?.let {
                Spacer(Modifier.padding(start = 4.dp))
                Text(dev.pfeed.ui.common.formatDuration(it), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private const val PER_PAGE = 4
private const val WALL_MAX_HEIGHT = 480
