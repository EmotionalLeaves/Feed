package dev.pfeed.ui.feed

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Bookmark
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
    /** The post after this one: shown as "Up next" as a full scene nears its end. */
    upNext: MediaItem? = null,
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
    // The last seconds of a full scene: what's next, and whether you'd rather stay.
    var stayAtEnd by remember(item.key) { mutableStateOf(false) }
    var endingIn by remember(item.key) { mutableStateOf<Long?>(null) }

    DisposableEffect(item.key) {
        onDispose {
            // Leaving a long video part-way: remember where (not in private mode).
            player?.takeIf { it.currentMediaItem?.mediaId == item.key && !vm.privateMode.value && item.extras[dev.pfeed.source.live.LiveCamsAdapter.LIVE] != "1" }?.let { p ->
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
                // Live: wherever the broadcast is now.
                // Highlights: straight to the good part.
                if (item.extras[dev.pfeed.source.live.LiveCamsAdapter.LIVE] == "1") androidx.media3.common.C.TIME_UNSET
                else (if (vm.fromMoments) dev.pfeed.player.Moments.of(item.key).firstOrNull() else null)
                    ?: (if (Highlights.on.value) vm.highlightStartMs(item) else null)
                    ?: vm.tagMomentMs(item)?.second
                    ?: dev.pfeed.player.ResumePoints.get(item.key) ?: vm.introSkipMs(item),
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
                    // "Stay here" on the Up next card: start it over, paused, instead of moving on.
                    if (stayAtEnd) { p.seekTo(0); p.pause(); stayAtEnd = false } else ended()
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

    LaunchedEffect(player, play, firstFrame) {
        endingIn = null
        val p = player ?: return@LaunchedEffect
        if (!play || !firstFrame) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(500)
            if (p.currentMediaItem?.mediaId != item.key) continue
            NowPlaying.key = item.key
            NowPlaying.positionMs = p.currentPosition
            val d = p.duration
            // Well into a short cut of a studio scene: see whether the full scene is on another site.
            if (d > 0 && p.currentPosition > d * LONGER_AT && !detached) vm.offerLonger(item)
            // A single file (not adaptive) playing soft, below 720p, a few seconds in: see whether
            // another site has it sharper. (Adaptive streams start low and climb on their own.)
            val file = (stream as? StreamInfo.Direct)?.let { it.mimeType == "video/mp4" || it.url.substringBefore('?').endsWith(".mp4") || it.url.contains(".mp4/") } == true
            if (file && d > 0 && p.currentPosition > SHARPER_AT_MS && !detached) {
                val v = p.videoSize
                if (v.width > 0 && v.height > 0) vm.offerSharper(item, minOf(v.width, v.height), p.currentPosition, d)
            }
            val left = d - p.currentPosition
            endingIn = if (d >= UP_NEXT_MIN_MS && p.repeatMode == Player.REPEAT_MODE_OFF && left in 0..UP_NEXT_MS) left else null
        }
    }
    // Which of its named acts you sit through and which you jump over: learned per act when you leave.
    val actTally = remember(item.key, stream) {
        dev.pfeed.domain.reco.VideoExtras.of(item.key)?.chapters?.takeIf { it.size >= 2 }?.let { dev.pfeed.domain.reco.ActWatch.Tally(it) }
    }
    LaunchedEffect(player, play, firstFrame, actTally) {
        val p = player ?: return@LaunchedEffect
        val t = actTally ?: return@LaunchedEffect
        if (!play || !firstFrame) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(ACT_TICK_MS)
            if (p.currentMediaItem?.mediaId == item.key) t.tick(p.currentPosition, p.isPlaying, ACT_TICK_MS)
        }
    }

    // Highlights: a long video plays for a short stretch, then the feed moves on, unless you take
    // over (seek, tap a chapter, or "Keep watching").
    val highlightsOn by Highlights.on.collectAsState()
    var highlightPlayed by remember(item.key) { mutableStateOf(0L) }
    var highlightSpan by remember(item.key) { mutableStateOf(0L) }
    var highlightLeft by remember(item.key) { mutableStateOf<Long?>(null) }
    var keepWatching by remember(item.key) { mutableStateOf(false) }
    DisposableEffect(item.key, actTally) {
        onDispose {
            // Highlights jumps over acts by design: that says nothing about them, unless you took over.
            val t = actTally
            if (t != null && (!Highlights.on.value || keepWatching)) vm.actsWatched(item, t, stats.durationMs.takeIf { it > 0 } ?: ((item.durationSec ?: 0) * 1000L))
        }
    }
    LaunchedEffect(player, play, highlightsOn, firstFrame, keepWatching) {
        highlightLeft = null
        val p = player ?: return@LaunchedEffect
        val d = item.durationSec ?: return@LaunchedEffect
        if (!highlightsOn || !play || !firstFrame || keepWatching || d < Highlights.MIN_SEC ||
            item.extras[dev.pfeed.source.live.LiveCamsAdapter.LIVE] == "1") return@LaunchedEffect
        // Starting this one's highlight: be at its good part (a scene found since it was lined up can move it).
        if (highlightPlayed == 0L && p.currentMediaItem?.mediaId == item.key && !(vm.fromMoments && dev.pfeed.player.Moments.of(item.key).isNotEmpty())) {
            vm.highlightStartMs(item)?.takeIf { it > 5_000 && kotlin.math.abs(it - p.currentPosition) > 4_000 }?.let { p.seekTo(it) }
        }
        // One scene long, when the scenes are known (within reason); else the standard stretch.
        if (highlightSpan == 0L) {
            val from = p.currentPosition
            highlightSpan = dev.pfeed.domain.reco.Scenes.next(vm.sceneCuts(item), from)
                ?.let { (it - from).coerceIn(Highlights.MIN_SPAN_MS, Highlights.MAX_SPAN_MS) } ?: Highlights.SPAN_MS
        }
        var last = p.currentPosition
        while (true) {
            kotlinx.coroutines.delay(HIGHLIGHT_TICK_MS)
            if (p.currentMediaItem?.mediaId != item.key) continue
            val pos = p.currentPosition
            val step = pos - last
            last = pos
            // A jump the clock can't explain: you moved it yourself.
            if (step < -1_000 || step > HIGHLIGHT_TICK_MS * 4 + 1_000) { keepWatching = true; return@LaunchedEffect }
            if (p.isPlaying) highlightPlayed += HIGHLIGHT_TICK_MS
            highlightLeft = (highlightSpan - highlightPlayed).coerceAtLeast(0L)
            if (highlightPlayed >= highlightSpan) {
                stats.autoAdvanced = true
                ended()
                return@LaunchedEffect
            }
        }
    }
    // Reopened a long video partway: say where it carries on from, with a way back to the start.
    // Once, when it's actually on screen (the next post is prepared ahead, before you get to it).
    var offeredResume by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(player, play) {
        val p = player ?: return@LaunchedEffect
        if (Highlights.on.value && (item.durationSec ?: 0) >= Highlights.MIN_SEC) return@LaunchedEffect
        if (vm.fromMoments && dev.pfeed.player.Moments.of(item.key).isNotEmpty()) return@LaunchedEffect
        // Opened from a search or tag page for an act it marks: started there; say so.
        vm.tagMomentMs(item)?.let { (act, _) ->
            if (play && !offeredResume) {
                offeredResume = true
                vm.offer("Started at the ${act.lowercase()}", "From the start") { p.seekTo(0) }
            }
            return@LaunchedEffect
        }
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

    // "Save this moment": mark where this video is now.
    val momentAsk by MomentGrab.request.collectAsState()
    LaunchedEffect(momentAsk) {
        if (momentAsk != item.key) return@LaunchedEffect
        MomentGrab.request.value = null
        val p = player?.takeIf { it.currentMediaItem?.mediaId == item.key } ?: return@LaunchedEffect
        vm.saveMoment(item, p.currentPosition)
    }
    // Scene cuts from the site's preview frames, once you've stayed a few seconds on a long video.
    val sceneContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(item.key, stream, firstFrame, play) {
        // Highlights wants them before the video starts (it opens on a scene), so the one lined up next gets them now.
        val early = Highlights.on.value && !play && stream != null
        if (!early && (!firstFrame || !play)) return@LaunchedEffect
        // On mobile data, only when asked for ("Next scene", Highlights).
        if (vm.onMeteredNetwork && !Highlights.on.value) return@LaunchedEffect
        val tl = dev.pfeed.domain.reco.VideoExtras.of(item.key)?.timeline ?: return@LaunchedEffect
        if (dev.pfeed.domain.reco.Scenes.of(item.key) != null) return@LaunchedEffect
        if (!early) kotlinx.coroutines.delay(SCENES_AFTER_MS)
        val d = player?.duration?.takeIf { it > 0 } ?: ((item.durationSec ?: 0) * 1000L)
        runCatching { SceneFinder.find(sceneContext, item.key, tl, d) }
    }
    // A moment picked from outside the page (the menu's scenes strip).
    val seekAsk by SeekRequest.request.collectAsState()
    LaunchedEffect(seekAsk) {
        val (key, at) = seekAsk ?: return@LaunchedEffect
        if (key != item.key) return@LaunchedEffect
        SeekRequest.request.value = null
        player?.takeIf { it.currentMediaItem?.mediaId == item.key }?.seekTo(at)
    }
    // "Next scene": jump to the next cut (or act).
    val sceneAsk by SceneSkip.request.collectAsState()
    LaunchedEffect(sceneAsk) {
        if (sceneAsk != item.key) return@LaunchedEffect
        SceneSkip.request.value = null
        val p = player?.takeIf { it.currentMediaItem?.mediaId == item.key } ?: return@LaunchedEffect
        // Not found yet (on mobile data they wait to be asked for): find them now.
        val tl = dev.pfeed.domain.reco.VideoExtras.of(item.key)?.timeline
        if (vm.sceneCuts(item).isEmpty() && tl != null && dev.pfeed.domain.reco.Scenes.of(item.key) == null) {
            runCatching { SceneFinder.find(sceneContext, item.key, tl, p.duration.takeIf { it > 0 } ?: ((item.durationSec ?: 0) * 1000L)) }
        }
        val next = dev.pfeed.domain.reco.Scenes.next(vm.sceneCuts(item), p.currentPosition)
        if (next != null) p.seekTo(next)
        else vm.offer(if (vm.sceneCuts(item).isEmpty()) "Scenes aren't known for this video yet" else "That was the last scene", "Skip 30 s") { p.seekTo(p.currentPosition + 30_000) }
    }
    // "Save a clip": the next 30 s from here, cut into a file.
    val clipAsk by ClipGrab.request.collectAsState()
    LaunchedEffect(clipAsk) {
        if (clipAsk != item.key) return@LaunchedEffect
        ClipGrab.request.value = null
        val p = player?.takeIf { it.currentMediaItem?.mediaId == item.key } ?: return@LaunchedEffect
        val s = stream as? StreamInfo.Direct ?: return@LaunchedEffect
        // A few seconds before now too: you reach for the menu just after the good bit starts.
        vm.saveClip(item, s, (p.currentPosition - CLIP_LEAD_MS).coerceAtLeast(0L), ClipGrab.lengthMs.also { ClipGrab.lengthMs = 30_000L })
    }
    val clipAtAsk by ClipGrab.at.collectAsState()
    LaunchedEffect(clipAtAsk) {
        val (key, at) = clipAtAsk ?: return@LaunchedEffect
        if (key != item.key) return@LaunchedEffect
        ClipGrab.at.value = null
        val s = stream as? StreamInfo.Direct ?: return@LaunchedEffect
        vm.saveClip(item, s, at)
    }
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
        // A long video whose site keeps count of what people watch: offer to skip to the best part,
        // for the first few seconds (only while you're before it).
        // The site's heatmap if it keeps one; else the time its commenters point to most.
        val crowdTime = remember(item.key, stream) {
            dev.pfeed.domain.reco.VideoExtras.of(item.key)?.comments?.let { dev.pfeed.domain.reco.CommentMoments.of(it, item.durationSec) }?.firstOrNull()
        }
        val siteBest = remember(item.key, stream) { dev.pfeed.domain.reco.BestPart.of(item.key) } ?: crowdTime
        // The act you like most, when the site names its acts: a better "best part" than the crowd's.
        val favourite by produceState<Pair<String, Long>?>(null, item.key, stream) { value = vm.favouriteAct(item) }
        // A moment you saved in it beats both.
        val momentsVersion by dev.pfeed.player.Moments.changes.collectAsState()
        val moment = remember(item.key, momentsVersion) { dev.pfeed.player.Moments.of(item.key).firstOrNull() }
        val best = moment ?: favourite?.second ?: siteBest
        var offerBest by remember(item.key) { mutableStateOf(true) }
        // Its chapters (positions, as the site marks them): a row to jump straight to one, for a while.
        val chapters = remember(item.key, stream) { dev.pfeed.domain.reco.VideoExtras.of(item.key)?.chapters.orEmpty() }
        var offerChapters by remember(item.key) { mutableStateOf(true) }
        if (chapters.size >= 2 && firstFrame && play && offerChapters && !detached) {
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(CHAPTERS_MS); offerChapters = false }
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                items(chapters.size) { i ->
                    val (name, ms) = chapters[i]
                    val yours = favourite?.first.equals(name, ignoreCase = true)
                    androidx.compose.material3.Text(
                        (if (yours) "♥ " else "") + name + " · " + dev.pfeed.ui.common.formatDuration((ms / 1000).toInt()),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                            .background(if (yours) androidx.compose.ui.graphics.Color(0xCCE5395B) else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                            .clickable { player?.seekTo(ms); offerChapters = false; offerBest = false }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        if (best != null && firstFrame && play && offerBest && !detached) {
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(BEST_OFFER_MS); offerBest = false }
            if ((player?.currentPosition ?: 0L) < best - 5_000) androidx.compose.foundation.layout.Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                    .clickable { player?.seekTo(best); offerBest = false }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fav = favourite.takeIf { moment == null }
                androidx.compose.material3.Icon(
                    when {
                        moment != null -> androidx.compose.material.icons.Icons.Filled.Bookmark
                        fav != null -> androidx.compose.material.icons.Icons.Filled.Favorite
                        else -> androidx.compose.material.icons.Icons.Filled.LocalFireDepartment
                    },
                    contentDescription = null,
                    tint = when {
                        moment != null -> androidx.compose.ui.graphics.Color(0xFFFFB547)
                        fav != null -> androidx.compose.ui.graphics.Color(0xFFFF5C7A)
                        else -> androidx.compose.ui.graphics.Color(0xFFFF7A45)
                    },
                    modifier = Modifier.size(18.dp),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                androidx.compose.material3.Text(
                    when {
                        moment != null -> "Your moment · "
                        fav != null -> "Jump to ${fav.first.lowercase()}, your favourite · "
                        siteBest == crowdTime && crowdTime != null && dev.pfeed.domain.reco.BestPart.of(item.key) == null -> "Commenters point to "
                        else -> "Skip to the best part · "
                    } +
                        dev.pfeed.ui.common.formatDuration((best / 1000).toInt()),
                    color = androidx.compose.ui.graphics.Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                )
            }
        }
        // A live room: say so, with how many are watching and where.
        if (item.extras[dev.pfeed.source.live.LiveCamsAdapter.LIVE] == "1" && !detached) androidx.compose.foundation.layout.Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 96.dp, end = 12.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFFFF3B4E)))
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            val viewers = item.extras["viewers"]?.toIntOrNull()?.takeIf { it > 0 }
            val site = when {
                item.id.startsWith("cam4/") -> "Cam4"
                item.id.startsWith("f4f/") -> "Flirt4Free"
                else -> "CamSoda"
            }
            androidx.compose.material3.Text(
                "LIVE" + (viewers?.let { " · ${if (it >= 1000) "%.1fk".format(it / 1000.0) else it.toString()} watching" } ?: "") + " · " + site,
                color = androidx.compose.ui.graphics.Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            )
        }
        // Up next: the last seconds of a full scene say what comes after it (or let you stay).
        val next = upNext
        val ending = endingIn
        val handsFreeNow by HandsFree.on.collectAsState()
        val sideways by vm.landscape.collectAsState()
        val upNextOn = vm.upNextCardOn
        if (next != null && ending != null && upNextOn && !detached && !handsFreeNow && !sideways && highlightLeft == null) androidx.compose.foundation.layout.Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp, start = 24.dp, end = 24.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            coil.compose.AsyncImage(
                model = next.thumbnailUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(96.dp, 54.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f, fill = false)) {
                androidx.compose.material3.Text(
                    if (stayAtEnd) "Staying on this one" else "Up next in ${(ending + 999) / 1000}s",
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                )
                androidx.compose.material3.Text(
                    next.title ?: next.creator ?: "",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Text(
                if (stayAtEnd) "Go on" else "Stay",
                color = androidx.compose.ui.graphics.Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f))
                    .clickable { stayAtEnd = !stayAtEnd }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        // At a full scene's end: more with the performer it named, from every site.
        val endPerson = remember(item.key) { dev.pfeed.domain.reco.Mentions.inTitle(item).firstOrNull() }
        if (endPerson != null && next != null && ending != null && upNextOn && !detached && !handsFreeNow && !sideways && highlightLeft == null) androidx.compose.foundation.layout.Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 222.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))
                .clickable { vm.openPageRequest.value = "@$endPerson" }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Person, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            androidx.compose.material3.Text("More with $endPerson", color = androidx.compose.ui.graphics.Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        }
        // Highlights: how long this one has left, with a way to stay on it.
        val left = highlightLeft
        if (left != null && !detached) androidx.compose.foundation.layout.Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 196.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                .clickable { keepWatching = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { 1f - left.toFloat() / highlightSpan.coerceAtLeast(1L) },
                modifier = Modifier.size(16.dp),
                color = androidx.compose.ui.graphics.Color(0xFFFF5C7A),
                trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f),
                strokeWidth = 2.dp,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Text(
                "Highlight · ${(left + 999) / 1000}s  ·  Keep watching",
                color = androidx.compose.ui.graphics.Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private const val BEST_OFFER_MS = 9_000L
private const val HIGHLIGHT_TICK_MS = 250L
private const val ACT_TICK_MS = 500L
/** How far into a video before looking for a longer cut of it. */
private const val LONGER_AT = 0.4
private const val SHARPER_AT_MS = 8_000L
private const val SCENES_AFTER_MS = 6_000L
private const val CLIP_LEAD_MS = 6_000L
/** "Up next" shows this long before a full scene ends. */
private const val UP_NEXT_MS = 8_000L
private const val UP_NEXT_MIN_MS = 3 * 60_000L
private const val CHAPTERS_MS = 12_000L

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
