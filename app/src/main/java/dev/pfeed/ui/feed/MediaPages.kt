package dev.pfeed.ui.feed

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.ui.theme.HeartRed

@Composable
fun Thumbnail(item: MediaItem, modifier: Modifier = Modifier) {
    AsyncImage(
        model = item.thumbnailUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    item: MediaItem,
    play: Boolean,
    muted: Boolean,
    vm: FeedViewModel,
    onPlayer: (androidx.media3.exoplayer.ExoPlayer?) -> Unit = {},
) {
    val pool = vm.playerPool
    val version by pool.changes.collectAsState()
    val player = remember(item.key, version) { pool.acquire(item.key) }
    val stream by produceState<StreamInfo?>(initialValue = null, item.key) { value = vm.resolve(item) }
    var firstFrame by remember(item.key) { mutableStateOf(false) }
    var error by remember(item.key) { mutableStateOf<String?>(null) }
    val stats = remember(item.key) { vm.statsFor(item.key) }

    DisposableEffect(item.key) {
        onDispose { pool.release(item.key) }
    }
    // Hand the player up only once it's showing this item, so the scrub bar never drives a stale one.
    LaunchedEffect(player, stream, firstFrame) { onPlayer(if (firstFrame) player else null) }

    LaunchedEffect(player, stream) {
        val p = player ?: return@LaunchedEffect
        val s = stream as? StreamInfo.Direct ?: return@LaunchedEffect
        if (p.currentMediaItem?.mediaId != item.key) {
            firstFrame = false
            p.setMediaItem(
                ExoMediaItem.Builder()
                    .setMediaId(item.key)
                    .setUri(s.url)
                    .apply { s.mimeType?.let { setMimeType(it) } }
                    .build(),
            )
            p.prepare()
        }
    }

    DisposableEffect(player) {
        val p = player
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) stats.replays++
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && p != null && p.duration > 0) stats.durationMs = p.duration
            }
            override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName }
        }
        p?.addListener(listener)
        onDispose { p?.removeListener(listener) }
    }

    LaunchedEffect(player, play) { player?.playWhenReady = play }
    LaunchedEffect(player, muted) { player?.volume = if (muted) 0f else 1f }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    setKeepContentOnPlayerReset(false)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { view -> if (view.player !== player) view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (!firstFrame) Thumbnail(item)
        when {
            error != null -> Text("Can't play: $error", color = Color.White)
            stream is StreamInfo.Embed -> EmbedPage(item, active = play, vm = vm)
            !firstFrame && play -> CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
        }
    }
}

/**
 * Image viewer that adapts to the aspect ratio:
 *
 * **Tall images** (comics, manga, webcomic strips, aspect > 1.3:1 portrait): shown at full screen
 * width so text is readable; the overflow scrolls vertically. Vertical drags scroll the comic
 * instead of swiping the feed; the pager takes over only at the top/bottom edge of the image.
 *
 * **Normal / wide images**: centred at screen scale.
 *
 * Both support **pinch-to-zoom** (1×–5×) and **pan** while zoomed.
 *
 * The key trick is a [NestedScrollConnection] that intercepts the VerticalPager's pre-scroll: when
 * the image has room to pan, the drag stays inside the image; when it hits the edge, leftover delta
 * passes through and the pager picks it up.
 */
@Composable
fun ImagePage(item: MediaItem, vm: FeedViewModel) {
    val stream by produceState<StreamInfo?>(initialValue = null, item.key) { value = vm.resolve(item) }
    val url = (stream as? StreamInfo.Direct)?.url ?: item.thumbnailUrl

    var imageSize by remember(item.key) { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }
    var scale by remember(item.key) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.key) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.key) { mutableFloatStateOf(0f) }
    var containerW by remember(item.key) { mutableFloatStateOf(1080f) }
    var containerH by remember(item.key) { mutableFloatStateOf(1920f) }

    /** Max pan in each direction at the current scale. */
    fun bounds(): Pair<Float, Float> {
        val img = imageSize ?: return 0f to 0f
        val fitWidth = containerW
        val fitHeight = fitWidth * img.height / img.width
        val scaledW = fitWidth * scale
        val scaledH = fitHeight * scale
        return ((scaledW - containerW) / 2f).coerceAtLeast(0f) to ((scaledH - containerH) / 2f).coerceAtLeast(0f)
    }

    val nestedScroll = remember(item.key) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // If zoomed or the image is tall enough to pan, consume vertical delta here.
                val (maxX, maxY) = bounds()
                if (maxY <= 0f && scale <= 1.01f) return Offset.Zero // nothing to scroll
                val dy = available.y
                val newY = (offsetY + dy).coerceIn(-maxY, maxY)
                val consumed = newY - offsetY
                offsetY = newY
                // Horizontal panning while zoomed
                val dx = available.x
                val newX = (offsetX + dx).coerceIn(-maxX, maxX)
                val consumedX = newX - offsetX
                offsetX = newX
                return Offset(consumedX, consumed)
            }
        }
    }

    val isTall = imageSize?.let { it.height > it.width * 1.3f } == true

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { containerW = it.width.toFloat(); containerH = it.height.toFloat() }
            .nestedScroll(nestedScroll)
            .pointerInput(item.key) {
                // Double-tap toggles between 1× and 2.5× zoom, centred on the tap point.
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.1f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        } else {
                            scale = 2.5f
                            // Pan toward the tap point
                            val img = imageSize ?: return@detectTapGestures
                            val fitWidth = size.width.toFloat()
                            val fitHeight = fitWidth * img.height / img.width
                            val maxX = ((fitWidth * 2.5f - size.width) / 2f).coerceAtLeast(0f)
                            val maxY = ((fitHeight * 2.5f - size.height) / 2f).coerceAtLeast(0f)
                            offsetX = ((size.width / 2f - tapOffset.x) * 1.5f).coerceIn(-maxX, maxX)
                            offsetY = ((size.height / 2f - tapOffset.y) * 1.5f).coerceIn(-maxY, maxY)
                        }
                    },
                )
            }
            .pointerInput(item.key) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    scale = newScale
                    val img = imageSize ?: return@detectTransformGestures
                    val fitWidth = size.width.toFloat()
                    val fitHeight = fitWidth * img.height / img.width
                    val scaledW = fitWidth * newScale
                    val scaledH = fitHeight * newScale
                    val maxX = ((scaledW - size.width) / 2f).coerceAtLeast(0f)
                    val maxY = ((scaledH - size.height) / 2f).coerceAtLeast(0f)
                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                    offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val contentScale = if (isTall) ContentScale.FillWidth else ContentScale.Fit
        AsyncImage(
            model = url,
            contentDescription = item.title,
            contentScale = contentScale,
            onSuccess = { state ->
                val d = state.result.drawable
                imageSize = androidx.compose.ui.unit.IntSize(d.intrinsicWidth, d.intrinsicHeight)
            },
            modifier = Modifier
                .then(if (isTall) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
        )
    }
}

/**
 * Official embed players only, in a locked-down WebView: JS on (players need it), no file/content access,
 * no third-party cookies, no popups, no navigation away from the embed's host. The host window already
 * has FLAG_SECURE. The WebView is sized 16:9 so vertical swipes outside it still move the feed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbedPage(item: MediaItem, active: Boolean, vm: FeedViewModel) {
    val stream by produceState<StreamInfo?>(initialValue = null, item.key) { value = vm.resolve(item) }
    val url = when (val s = stream) {
        is StreamInfo.Embed -> s.url
        is StreamInfo.Direct -> s.url
        null -> null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (active && url != null) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(AndroidColor.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setGeolocationEnabled(false)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webChromeClient = WebChromeClient()
                        webViewClient = SandboxClient(Uri.parse(url).host)
                        loadUrl(url)
                    }
                },
                onRelease = { web ->
                    web.stopLoading()
                    web.loadUrl("about:blank")
                    web.destroy()
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        } else {
            Thumbnail(item)
        }
    }
}

private class SandboxClient(private val host: String?) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.host != host
}

/** Double-tap heart. [tick] restarts the animation on every double tap. */
@Composable
fun HeartBurst(position: Offset?, tick: Int) {
    if (position == null || tick == 0) return
    val scale = remember(tick) { Animatable(0f) }
    val alpha = remember(tick) { Animatable(1f) }
    LaunchedEffect(tick) {
        scale.snapTo(0f)
        alpha.snapTo(1f)
        scale.animateTo(1.5f, spring(dampingRatio = 0.35f, stiffness = 800f))
        scale.animateTo(1.2f, spring(dampingRatio = 0.5f))
        alpha.animateTo(0f, tween(durationMillis = 400))
    }
    val sizeDp = 100.dp
    val half = with(LocalDensity.current) { (sizeDp / 2).roundToPx() }
    Box(
        Modifier
            .offset { IntOffset(position.x.toInt() - half, position.y.toInt() - half) }
            .size(sizeDp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
        contentAlignment = Alignment.Center,
    ) {
        // Glow behind the heart
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(sizeDp).graphicsLayer { scaleX = 1.25f; scaleY = 1.25f },
        )
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = HeartRed,
            modifier = Modifier.size(sizeDp),
        )
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

@Composable
fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}
