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
import androidx.compose.ui.draw.blur
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

/**
 * Behind a post that doesn't fill the screen (a sideways video, a square picture): its own picture,
 * blurred and dimmed, filling the bars instead of flat black. The blur is done once, on a tiny copy
 * (see [SoftBlur]), and simply stretched: a live blur effect redrawn every frame under a playing
 * video cost far too much.
 */
@Composable
fun AmbientBackdrop(item: MediaItem) {
    val url = item.thumbnailUrl?.takeUnless { dev.pfeed.source.parse.MediaTypes.classify(it) == dev.pfeed.domain.model.MediaType.VIDEO } ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(url) {
        coil.request.ImageRequest.Builder(context).data(url).size(96).transformations(SoftBlur).memoryCacheKey("$url#soft").build()
    }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
    }
}

/** Shrinks a picture to a few dozen pixels and box-blurs it: stretched to full screen it reads as a soft glow. */
private object SoftBlur : coil.transform.Transformation {
    override val cacheKey = "softblur1"
    override suspend fun transform(input: android.graphics.Bitmap, size: coil.size.Size): android.graphics.Bitmap {
        val w = 24
        val h = (input.height * w / input.width.coerceAtLeast(1)).coerceIn(8, 64)
        val small = android.graphics.Bitmap.createScaledBitmap(input.copy(android.graphics.Bitmap.Config.ARGB_8888, false), w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(px, w, h, 2) }
        return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val out = IntArray(px.size)
        for (pass in 0..1) {
            val src = if (pass == 0) px else out
            val dst = if (pass == 0) out else px
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val xx = if (pass == 0) (x + k).coerceIn(0, w - 1) else x
                    val yy = if (pass == 0) y else (y + k).coerceIn(0, h - 1)
                    val c = src[yy * w + xx]
                    a += c ushr 24; rr += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff; n++
                }
                dst[y * w + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    item: MediaItem,
    play: Boolean,
    muted: Boolean,
    vm: FeedViewModel,
    onPlayer: (androidx.media3.exoplayer.ExoPlayer?) -> Unit = {},
    /** A full scene (3 min or longer) reached its end: move on rather than loop it. */
    onEnded: () -> Unit = {},
    /** When true, this page gives up the player's video surface (the fullscreen view has it). */
    detached: Boolean = false,
) {
    val pool = vm.playerPool
    val version by pool.changes.collectAsState()
    val player = remember(item.key, version) { pool.acquire(item.key) }
    val resolved by produceState<StreamInfo?>(initialValue = null, item.key) { value = vm.resolve(item) }
    // A copy of the same video from another site, once this one has failed to play.
    var copy by remember(item.key) { mutableStateOf<StreamInfo?>(null) }
    var triedCopy by remember(item.key) { mutableStateOf(false) }
    val stream = copy ?: resolved
    val copyScope = androidx.compose.runtime.rememberCoroutineScope()
    var firstFrame by remember(item.key) { mutableStateOf(false) }
    var error by remember(item.key) { mutableStateOf<String?>(null) }
    val stats = remember(item.key) { vm.statsFor(item.key) }
    val handsFree by HandsFree.on.collectAsState()
    val ended by androidx.compose.runtime.rememberUpdatedState(onEnded)

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
        if (p.currentMediaItem?.mediaId != item.key || p.currentMediaItem?.localConfiguration?.uri?.toString() != s.url) {
            firstFrame = false
            p.setMediaItem(
                ExoMediaItem.Builder()
                    .setMediaId(item.key)
                    .setUri(s.url)
                    .apply { s.mimeType?.let { setMimeType(it) } }
                    .build(),
                // Carry on where you left a long video, if you did; else past the intro, if asked.
                dev.pfeed.player.ResumePoints.get(item.key) ?: vm.introSkipMs(item),
            )
            p.prepare()
        }
    }

    // Hands-free switched on or off mid-video: play once or loop from here on.
    LaunchedEffect(player, handsFree) {
        val p = player ?: return@LaunchedEffect
        if (p.currentMediaItem?.mediaId == item.key && p.duration > 0) p.repeatMode = repeatFor(p.duration, handsFree)
    }

    DisposableEffect(player) {
        val p = player
        var loops = 0
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true; vm.markShown(item) }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // A clip looping on its own only counts as a rewatch when it's long enough that
                // sitting through it again is a choice. Short loops (most RedGIFs clips) repeat
                // passively; counting each loop as a replay made the model over-reward them and
                // drift toward short clips. Dwell and completion already capture those.
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && stats.durationMs >= MIN_LOOP_AS_REPLAY_MS) stats.replays++
                // Hands-free: a short clip moves on once it has looped for a while.
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && HandsFree.on.value && p != null &&
                    p.currentMediaItem?.mediaId == item.key && p.duration > 0
                ) {
                    loops++
                    if (loops * p.duration >= HandsFree.SHORT_VIDEO_TOTAL_MS) ended()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && p != null && p.duration > 0) {
                    stats.durationMs = p.duration
                    // Clips loop (as in any short-video feed); a full scene plays once and then the
                    // feed moves on, instead of starting it over.
                    val wanted = repeatFor(p.duration, HandsFree.on.value)
                    if (p.repeatMode != wanted) p.repeatMode = wanted
                }
                if (state == Player.STATE_ENDED && p != null && p.repeatMode == Player.REPEAT_MODE_OFF) {
                    p.repeatMode = Player.REPEAT_MODE_ONE // the pooled player loops again for the next post
                    ended()
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                val reason = "player: ${e.errorCodeName} ${e.cause?.message?.take(80).orEmpty()}"
                if (triedCopy) { error = e.errorCodeName; vm.markBroken(item, reason); return }
                // First failure: look for the same video on another site before giving up on it.
                triedCopy = true
                copyScope.launch {
                    val other = vm.findElsewhere(item)
                    if (other != null && other != resolved) { error = null; copy = other } else { error = e.errorCodeName; vm.markBroken(item, reason) }
                }
            }
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
    // Reopened a long video partway: say where it carries on from, with a way back to the start.
    // Once, when it's actually on screen (the next post is prepared ahead, before you get to it).
    var offeredResume by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(player, play) {
        val p = player ?: return@LaunchedEffect
        val at = dev.pfeed.player.ResumePoints.get(item.key) ?: run {
            // Started past the intro: say so, with the way back.
            val skip = vm.introSkipMs(item)
            if (skip > 0 && play && !offeredResume) {
                offeredResume = true
                vm.offer("Skipped the intro", "From the start") { p.seekTo(0) }
            }
            return@LaunchedEffect
        }
        if (play && !offeredResume) {
            offeredResume = true
            vm.offer("Carrying on from ${dev.pfeed.ui.common.formatDuration((at / 1000).toInt())}", "Start over") { p.seekTo(0) }
        }
    }
    // A clip that hasn't shown a frame after this long while on screen is effectively dead: skip it.
    LaunchedEffect(item.key, play, firstFrame) {
        if (play && !firstFrame) {
            kotlinx.coroutines.delay(STALL_TIMEOUT_MS)
            if (!firstFrame) vm.markBroken(item, "no frame after ${STALL_TIMEOUT_MS / 1000}s")
        }
    }
    LaunchedEffect(player, muted) { player?.let { dev.pfeed.player.PlayerPool.applyMute(it, muted) } }

    // "Save this frame": copy what's on the video surface right now into Saved (a "Frames" collection).
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val grab by FrameGrab.request.collectAsState()
    val grabContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(grab) {
        if (grab != item.key) return@LaunchedEffect
        FrameGrab.request.value = null
        val surface = playerView?.videoSurfaceView as? android.view.SurfaceView
        val size = player?.videoSize
        if (surface == null || size == null || size.width == 0 || android.os.Build.VERSION.SDK_INT < 24) {
            vm.offer("Couldn't capture this one", "OK") {}
            return@LaunchedEffect
        }
        val bitmap = android.graphics.Bitmap.createBitmap(size.width, size.height, android.graphics.Bitmap.Config.ARGB_8888)
        val ok = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            android.view.PixelCopy.request(surface, bitmap, { r -> cont.resume(r == android.view.PixelCopy.SUCCESS) {} }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        if (!ok) { vm.offer("Couldn't capture this one", "OK") {}; return@LaunchedEffect }
        val at = player?.currentPosition ?: 0L
        vm.saveFrame(grabContext, item, bitmap, at)
    }
    // Pinch to zoom into a video (up to 4×), one finger to move around while zoomed, as with pictures.
    var vScale by remember(item.key) { mutableFloatStateOf(1f) }
    var vX by remember(item.key) { mutableFloatStateOf(0f) }
    var vY by remember(item.key) { mutableFloatStateOf(0f) }
    DisposableEffect(item.key) { onDispose { if (vScale > 1f) Zoom.active = false } }
    Box(
        Modifier.fillMaxSize().pointerInput(item.key) {
            fun clampPan() {
                val mx = size.width * (vScale - 1f) / 2f
                val my = size.height * (vScale - 1f) / 2f
                vX = vX.coerceIn(-mx, mx); vY = vY.coerceIn(-my, my)
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (vScale > 1f) {
                    var pinch = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) { pinch = true; break }
                        val c = event.changes.firstOrNull { it.id == down.id && it.pressed } ?: break
                        val d = c.positionChange()
                        if (d != Offset.Zero) { vX += d.x; vY += d.y; clampPan(); c.consume() }
                    } while (event.changes.any { it.pressed })
                    if (!pinch) return@awaitEachGesture
                } else {
                    val second = withTimeoutOrNull(300) {
                        do { val event = awaitPointerEvent() } while (event.changes.count { it.pressed } < 2)
                        true
                    }
                    if (second != true) return@awaitEachGesture
                }
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        val cur = (pressed[0].position - pressed[1].position).getDistance()
                        val prev = (pressed[0].previousPosition - pressed[1].previousPosition).getDistance()
                        // Only zooming in from 1× here; pinching in at 1× is the Wall's gesture.
                        if (prev > 1f && (vScale > 1f || cur > prev)) {
                            vScale = (vScale * cur / prev).coerceIn(1f, VIDEO_MAX_ZOOM)
                            val c0 = (pressed[0].position + pressed[1].position) / 2f
                            val c1 = (pressed[0].previousPosition + pressed[1].previousPosition) / 2f
                            vX += (c0 - c1).x; vY += (c0 - c1).y
                            clampPan()
                            Zoom.active = vScale > 1.01f
                            event.changes.forEach { it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
                if (vScale < 1.05f) { vScale = 1f; vX = 0f; vY = 0f }
                Zoom.active = vScale > 1.01f
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    playerView = this
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
                // The zoom, on the view itself so the video surface follows it.
                view.scaleX = vScale; view.scaleY = vScale
                view.translationX = vX; view.translationY = vY
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        // Same framing as the video (fit), so there's no jump when the first frame replaces it.
        if (!firstFrame) Thumbnail(item, contentScale = ContentScale.Fit)
        when {
            error != null -> Unit // already being skipped
            stream is StreamInfo.Embed -> EmbedPage(item, active = play, vm = vm)
            !firstFrame && play -> dev.pfeed.ui.common.BrandSpinner()
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
fun GalleryPage(
    item: MediaItem,
    vm: FeedViewModel,
    active: Boolean = true,
    /** Hands-free: flip a page every few seconds, then [onEnded] after the last. */
    autoFlip: Boolean = false,
    onEnded: () -> Unit = {},
    /** A tap on the left (-1) or right (1) edge, with when it happened: turn the page. */
    turn: Pair<Int, Long>? = null,
) {
    // Opens on the page you were reading last time.
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = remember(item.key) { dev.pfeed.player.ComicPages.get(item.key)?.coerceIn(0, item.pages.size - 1) ?: 0 },
        pageCount = { item.pages.size },
    )
    val privateMode by vm.privateMode.collectAsState()
    // Reopened partway: say which page, with a way back to the first (once, when it's on screen).
    var offeredPage by remember(item.key) { mutableStateOf(false) }
    val startPage = remember(item.key) { pager.currentPage }
    val pageScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(active) {
        if (active && startPage > 0 && !offeredPage) {
            offeredPage = true
            vm.offer("Carrying on from page ${startPage + 1} of ${item.pages.size}", "From the start") { pageScope.launch { pager.scrollToPage(0) } }
        }
    }
    LaunchedEffect(item.key, pager.currentPage) {
        item.pages.drop(pager.currentPage + 1).take(2).forEach { vm.warmImage(it) }
        vm.onComicPage(item, pager.currentPage)
        if (!privateMode) dev.pfeed.player.ComicPages.save(item.key, pager.currentPage, item.pages.size)
    }
    LaunchedEffect(turn) {
        val (dir, _) = turn ?: return@LaunchedEffect
        val to = (pager.currentPage + dir).coerceIn(0, item.pages.size - 1)
        if (to != pager.currentPage) pager.animateScrollToPage(to)
    }
    LaunchedEffect(autoFlip, pager.settledPage) {
        if (!autoFlip) return@LaunchedEffect
        kotlinx.coroutines.delay(HandsFree.GALLERY_PAGE_MS)
        if (pager.settledPage + 1 < item.pages.size) pager.animateScrollToPage(pager.settledPage + 1) else onEnded()
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
                                Zoom.active = newScale > 1.01f
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
                    Zoom.active = scale > 1.01f
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
                // Never ask beyond 1280 × 4096 (see MaxSizeInterceptor): a very long strip at full size
                // would be a bitmap too large to draw (Android refuses anything over ~100 MB).
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

/** Holding the heart ("love it"): a fountain of hearts. [tick] restarts it. */
@Composable
fun LoveBurst(tick: Int) {
    // Loved: a fountain of hearts from the middle, each its own way, fading as they go.
    val t = remember(tick) { Animatable(0f) }
    LaunchedEffect(tick) { t.snapTo(0f); t.animateTo(1f, tween(1100, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
    val hearts = remember(tick) { List(LOVE_HEARTS) { i -> Triple(-150f + 300f * i / (LOVE_HEARTS - 1) + (Math.random() * 30 - 15).toFloat(), 0.6f + Math.random().toFloat() * 0.7f, 220f + Math.random().toFloat() * 160f) } }
    val v = t.value
    Box(contentAlignment = Alignment.Center) {
        hearts.forEach { (angle, size, reach) ->
            val rad = Math.toRadians(angle.toDouble() - 90)
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size((44 * size).dp).graphicsLayer {
                    translationX = (kotlin.math.cos(rad) * reach * v).toFloat()
                    translationY = (kotlin.math.sin(rad) * reach * v).toFloat() - 60f * v
                    val grow = if (v < 0.2f) v / 0.2f else 1f
                    scaleX = grow; scaleY = grow
                    alpha = if (v < 0.6f) 1f else (1f - v) / 0.4f
                    rotationZ = angle * 0.3f
                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                }.drawWithContent {
                    drawContent()
                    drawRect(dev.pfeed.ui.theme.BrandGradient, blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                },
            )
        }
        // The big one, in the middle.
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(120.dp).graphicsLayer {
                val pop = if (v < 0.25f) 0.5f + 2.4f * v else 1.1f
                scaleX = pop; scaleY = pop
                alpha = if (v < 0.5f) 1f else (1f - v) / 0.5f
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }.drawWithContent {
                drawContent()
                drawRect(dev.pfeed.ui.theme.BrandGradient, blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
            },
        )
    }
}

private const val LOVE_HEARTS = 9

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

private const val AUTO_NEXT_MIN_MS = 3 * 60_000L

/** Clips loop; a full scene (or, hands-free, anything but a short clip) plays once. */
private fun repeatFor(durationMs: Long, handsFree: Boolean): Int =
    if (durationMs >= AUTO_NEXT_MIN_MS || (handsFree && durationMs >= HandsFree.PLAY_ONCE_MIN_MS)) Player.REPEAT_MODE_OFF
    else Player.REPEAT_MODE_ONE
private const val MAX_DECODE_W = 1280
private const val MAX_DECODE_H = 4096

/** On screen this long without a single frame: treat as dead and move on. */
private const val STALL_TIMEOUT_MS = 12_000L
/** Clips at least this long count an automatic loop as a deliberate rewatch. */
private const val MIN_LOOP_AS_REPLAY_MS = 15_000L
private const val MIN_ZOOM = 1f
private const val VIDEO_MAX_ZOOM = 4f
private const val MAX_ZOOM = 5f
/** Past this zoom the original file replaces the sample. */
private const val ZOOM_FULL_RES = 1.5f

@Composable
fun CenteredHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Whether a picture or video on screen is zoomed in (the Wall's pinch leaves it alone then). */
object Zoom {
    @Volatile var active = false
}
