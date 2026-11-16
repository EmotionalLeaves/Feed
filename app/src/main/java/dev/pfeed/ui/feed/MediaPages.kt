package dev.pfeed.ui.feed

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.input.pointer.positionChange
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
fun Thumbnail(item: MediaItem, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    AsyncImage(
        // Never a video file: the image loader would download the whole clip to draw one frame.
        model = item.thumbnailUrl?.takeUnless { dev.pfeed.source.parse.MediaTypes.classify(it) == dev.pfeed.domain.model.MediaType.VIDEO },
        contentDescription = null,
        contentScale = contentScale,
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
    /** When true, this page gives up the player's video surface (the fullscreen view has it). */
    detached: Boolean = false,
) {
    val pool = vm.playerPool
    val version by pool.changes.collectAsState()
    val player = remember(item.key, version) { pool.acquire(item.key) }
    val stream by produceState<StreamInfo?>(initialValue = null, item.key) { value = vm.resolve(item) }
    var firstFrame by remember(item.key) { mutableStateOf(false) }
    var error by remember(item.key) { mutableStateOf<String?>(null) }
    val stats = remember(item.key) { vm.statsFor(item.key) }

    DisposableEffect(item.key) {
        onDispose {
            // Leaving a long video part-way: remember where (not in private mode).
            player?.takeIf { it.currentMediaItem?.mediaId == item.key && !vm.privateMode.value }?.let { p ->
                dev.pfeed.player.ResumePoints.save(item.key, p.currentPosition, p.duration)
            }
            pool.release(item.key)
        }
    }
    // Hand the player up only once it's showing this item, so the scrub bar never drives a stale one.
    LaunchedEffect(player, stream, firstFrame) { onPlayer(if (firstFrame) player else null) }

    // A "video" whose only file is a still (the clip couldn't be found) would sit on one frame: skip it.
    LaunchedEffect(stream) { if (Display.isStillForVideo(item, stream)) vm.markBroken(item, "video post with only a still") }

    LaunchedEffect(player, stream) {
        val p = player ?: return@LaunchedEffect
        val s = stream as? StreamInfo.Direct ?: return@LaunchedEffect
        if (Display.isStillForVideo(item, s)) return@LaunchedEffect
        if (p.currentMediaItem?.mediaId != item.key) {
            firstFrame = false
            p.setMediaItem(
                ExoMediaItem.Builder()
                    .setMediaId(item.key)
                    .setUri(s.url)
                    .apply { s.mimeType?.let { setMimeType(it) } }
                    .build(),
                // Carry on where you left a long video, if you did.
                dev.pfeed.player.ResumePoints.get(item.key) ?: 0L,
            )
            p.prepare()
        }
    }

    DisposableEffect(player) {
        val p = player
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true; vm.markShown(item) }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // A clip looping on its own only counts as a rewatch when it's long enough that
                // sitting through it again is a choice. Short loops (most RedGIFs clips) repeat
                // passively; counting each loop as a replay made the model over-reward them and
                // drift toward short clips. Dwell and completion already capture those.
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && stats.durationMs >= MIN_LOOP_AS_REPLAY_MS) stats.replays++
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && p != null && p.duration > 0) stats.durationMs = p.duration
            }
            override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName; vm.markBroken(item, "player: ${e.errorCodeName} ${e.cause?.message?.take(80).orEmpty()}") }
            // Headphones unplugged: the player paused itself so nothing played on the speaker.
            // Mute the feed and carry on silently instead of leaving a frozen video.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY && p != null) {
                    vm.muteNow()
                    dev.pfeed.player.PlayerPool.applyMute(p, muted = true)
                    p.playWhenReady = true
                }
            }
        }
        p?.addListener(listener)
        onDispose { p?.removeListener(listener) }
    }

    LaunchedEffect(player, play) { player?.playWhenReady = play }
    // A clip that hasn't shown a frame after this long while on screen is effectively dead: skip it.
    LaunchedEffect(item.key, play, firstFrame) {
        if (play && !firstFrame) {
            kotlinx.coroutines.delay(STALL_TIMEOUT_MS)
            if (!firstFrame) vm.markBroken(item, "no frame after ${STALL_TIMEOUT_MS / 1000}s")
        }
    }
    LaunchedEffect(player, muted) { player?.let { dev.pfeed.player.PlayerPool.applyMute(it, muted) } }

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
            update = { view ->
                val want = if (detached) null else player
                if (view.player !== want) view.player = want
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // Same framing as the video (fit), so there's no jump when the first frame replaces it.
        if (!firstFrame) Thumbnail(item, contentScale = ContentScale.Fit)
        when {
            error != null -> Unit // already being skipped
            stream is StreamInfo.Embed -> EmbedPage(item, active = play, vm = vm)
            !firstFrame && play -> CircularProgressIndicator(Modifier.size(36.dp), color = Color.White.copy(alpha = 0.85f), strokeWidth = 3.dp)
        }
    }
}

/**
 * Image viewer that adapts to the aspect ratio, with pinch-to-zoom.
 *
 * **Critical constraint**: the VerticalPager owns the single-finger vertical swipe. ImagePage must
 * never consume single-finger drags at 1× zoom, or swiping between posts breaks. Only multi-touch
 * (pinch) is handled here; panning while zoomed goes through [NestedScrollConnection] which
 * forwards leftover delta back to the pager at the edges.
 *
 * **Tall images** (comics, manga): drawn at full screen width; the overflow is reachable by zooming.
 * **Normal / wide images**: centred at screen scale.
 */
/**
 * A multi-page post (comic, photo set): swipe sideways through its pages, each one the normal
 * zoomable image page. Vertical swipes still move the feed. The next two pages are warmed as you
 * read, so turning a page is instant.
 */
@Composable
fun GalleryPage(item: MediaItem, vm: FeedViewModel) {
    // Opens on the page you were reading last time.
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = remember(item.key) { dev.pfeed.player.ComicPages.get(item.key)?.coerceIn(0, item.pages.size - 1) ?: 0 },
        pageCount = { item.pages.size },
    )
    val privateMode by vm.privateMode.collectAsState()
    LaunchedEffect(item.key, pager.currentPage) {
        item.pages.drop(pager.currentPage + 1).take(2).forEach { vm.warmImage(it) }
        vm.onComicPage(item, pager.currentPage)
        if (!privateMode) dev.pfeed.player.ComicPages.save(item.key, pager.currentPage, item.pages.size)
    }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { it },
        ) { index ->
            ImagePage(Display.galleryPage(item, index), vm)
        }
        // Reading position: a thin line along the top edge and a "3 / 24" pill under the top bar.
        val fraction = (pager.currentPage + 1f) / item.pages.size
        Box(
            Modifier.align(Alignment.TopStart).fillMaxWidth(fraction).height(3.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
        )
        // Tap the counter for a strip of every page; tap one to jump there.
        var strip by remember(item.key) { mutableStateOf(false) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 100.dp, end = 14.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = if (strip) 0.7f else 0.45f))
                .clickable { strip = !strip }
                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${pager.currentPage + 1} / ${item.pages.size}",
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
            Icon(
                if (strip) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (strip) "Hide pages" else "All pages",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = strip,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp),
        ) {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (pager.currentPage - 2).coerceAtLeast(0))
            androidx.compose.foundation.lazy.LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).padding(vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(item.pages.size, key = { it }) { i ->
                    val page = remember(item.key, i) { Display.galleryPage(item, i) }
                    val src by produceState<String?>(initialValue = item.pages[i], page.key) {
                        value = (vm.resolve(page) as? StreamInfo.Direct)?.url ?: item.pages[i]
                    }
                    val current = i == pager.currentPage
                    Box(
                        Modifier.width(54.dp).height(76.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
                            .then(if (current) Modifier.border(2.dp, dev.pfeed.ui.theme.BrandGradient, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) else Modifier)
                            .clickable { scope.launch { pager.scrollToPage(i) }; strip = false },
                    ) {
                        AsyncImage(model = src, contentDescription = "Page ${i + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        dev.pfeed.ui.common.MediaMask()
                        Text(
                            "${i + 1}",
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImagePage(item: MediaItem, vm: FeedViewModel) {
    val stream by produceState<StreamInfo?>(initialValue = null, item.key) { value = vm.resolve(item) }
    val url = (stream as? StreamInfo.Direct)?.url ?: item.thumbnailUrl

    var imageSize by remember(item.key) { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }
    var scale by remember(item.key) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.key) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.key) { mutableFloatStateOf(0f) }

    val isTall = imageSize?.let { it.height > it.width * 1.3f } == true
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            // At normal size, one finger belongs to the feed (and a comic's pages): only a pinch zooms.
            // Zoomed in, one finger moves around the image instead: its drags are consumed here, before
            // the pagers see them, so the post or page doesn't change under you. A second finger pinches.
            .pointerInput(item.key) {
                fun panBy(d: Offset) {
                    val img = imageSize ?: return
                    val fw = size.width.toFloat(); val fh = fw * img.height / img.width
                    val mx = ((fw * scale - size.width) / 2f).coerceAtLeast(0f)
                    val my = ((fh * scale - size.height) / 2f).coerceAtLeast(0f)
                    offsetX = (offsetX + d.x).coerceIn(-mx, mx)
                    offsetY = (offsetY + d.y).coerceIn(-my, my)
                }
                awaitEachGesture {
                    // Wait for the first finger
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (scale > 1f) {
                        var pinch = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) { pinch = true; break }
                            val c = event.changes.firstOrNull { it.id == down.id && it.pressed } ?: break
                            val d = c.positionChange()
                            if (d != Offset.Zero) { panBy(d); c.consume() }
                        } while (event.changes.any { it.pressed })
                        if (!pinch) return@awaitEachGesture
                    } else {
                        // Wait for a second finger (pinch) or give up
                        val second = withTimeoutOrNull(300) {
                            do { val event = awaitPointerEvent() } while (event.changes.size < 2)
                            true
                        }
                        if (second != true) return@awaitEachGesture
                    }
                    // Two fingers down: track pinch + pan until all fingers lift
                    var prevCentroid = Offset.Unspecified
                    do {
                        val event = awaitPointerEvent()
                        val centroid = event.changes.map { it.position }.let { pts ->
                            Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
                        }
                        // Compute zoom from the two widest pointers
                        if (event.changes.size >= 2) {
                            val sorted = event.changes.sortedBy { it.position.x }
                            val curSpan = (sorted.last().position - sorted.first().position).getDistance()
                            val prevSpan = (sorted.last().previousPosition - sorted.first().previousPosition).getDistance()
                            if (prevSpan > 1f) {
                                val zoom = curSpan / prevSpan
                                val newScale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                scale = newScale
                                val img = imageSize
                                if (img != null) {
                                    val fw = size.width.toFloat(); val fh = fw * img.height / img.width
                                    val mx = ((fw * newScale - size.width) / 2f).coerceAtLeast(0f)
                                    val my = ((fh * newScale - size.height) / 2f).coerceAtLeast(0f)
                                    val pan = if (prevCentroid != Offset.Unspecified) centroid - prevCentroid else Offset.Zero
                                    offsetX = (offsetX + pan.x).coerceIn(-mx, mx)
                                    offsetY = (offsetY + pan.y).coerceIn(-my, my)
                                }
                            }
                        }
                        prevCentroid = centroid
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    // If the user pinched back to ~1×, snap to exactly 1× and reset offset
                    if (scale < 1.05f) { scale = 1f; offsetX = 0f; offsetY = 0f }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val contentScale = if (isTall) ContentScale.FillWidth else ContentScale.Fit
        // The feed shows the board's sample; zooming in swaps to the original (the sample stays on
        // screen as the placeholder while it loads, so there's no flash).
        val full = item.extras["full"]
        val context = androidx.compose.ui.platform.LocalContext.current
        val model = remember(url, full, scale > ZOOM_FULL_RES) {
            val zoomed = full != null && scale > ZOOM_FULL_RES
            coil.request.ImageRequest.Builder(context)
                .data(if (zoomed) full else url)
                .apply { if (zoomed) placeholderMemoryCacheKey(url) }
                // Never decode beyond 2048 × 8192: a very long strip at full size would be a bitmap
                // too large to draw (Android refuses anything over ~100 MB) and crash the feed.
                .size(MAX_DECODE_W, MAX_DECODE_H)
                .precision(coil.size.Precision.INEXACT)
                .build()
        }
        // A tall page (a long comic page, a strip) at normal size scrolls down: read it top to bottom,
        // and at its end the same swipe carries on to the next post. Zoomed in, it pans as before.
        val img = imageSize
        // Only when the page at screen width is actually taller than the screen (most comic pages fit).
        val readable = isTall && scale == 1f && img != null && viewport.height > 0 &&
            viewport.width.toFloat() * img.height / img.width.coerceAtLeast(1) > viewport.height * 1.05f
        val scroll = androidx.compose.foundation.rememberScrollState()
        Box(
            Modifier.fillMaxSize().then(if (readable) Modifier.verticalScroll(scroll) else Modifier),
            contentAlignment = if (readable) Alignment.TopCenter else Alignment.Center,
        ) {
        AsyncImage(
            model = model,
            contentDescription = item.title,
            contentScale = contentScale,
            onSuccess = { state ->
                val d = state.result.drawable
                imageSize = androidx.compose.ui.unit.IntSize(d.intrinsicWidth, d.intrinsicHeight)
                if (stream != null) vm.markShown(item)
            },
            // Dead image link: drop it from the feed rather than leaving a black page.
            // (A failed full-size load while zoomed isn't a dead post: the sample already showed.)
            onError = { if (stream != null && !(full != null && scale > ZOOM_FULL_RES)) vm.markBroken(item, "image: ${it.result.throwable.message?.take(100)}") },
            modifier = Modifier
                .then(
                    when {
                        readable -> Modifier.fillMaxWidth().aspectRatio(img!!.width.toFloat() / img.height.coerceAtLeast(1))
                        isTall -> Modifier.fillMaxWidth()
                        else -> Modifier.fillMaxSize()
                    },
                )
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
        )
        }
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
                        // The site's player page loaded: it's on screen (for health stats and spacing).
                        webViewClient = SandboxClient(Uri.parse(url).host) { vm.markShown(item) }
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

private class SandboxClient(private val host: String?, private val onLoaded: () -> Unit) : WebViewClient() {
    private var loaded = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.host != host

    override fun onPageFinished(view: WebView, url: String?) {
        if (!loaded && url != null && url != "about:blank") { loaded = true; onLoaded() }
    }
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
            tint = Color.White,
            // Painted in the brand gradient, like the liked heart on the rail.
            modifier = Modifier.size(sizeDp)
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(dev.pfeed.ui.theme.BrandGradient, blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                },
        )
    }
}

private const val MAX_DECODE_W = 2048
private const val MAX_DECODE_H = 8192

/** On screen this long without a single frame: treat as dead and move on. */
private const val STALL_TIMEOUT_MS = 12_000L
/** Clips at least this long count an automatic loop as a deliberate rewatch. */
private const val MIN_LOOP_AS_REPLAY_MS = 15_000L
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
/** Past this zoom the original file replaces the sample. */
private const val ZOOM_FULL_RES = 1.5f

@Composable
fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}
