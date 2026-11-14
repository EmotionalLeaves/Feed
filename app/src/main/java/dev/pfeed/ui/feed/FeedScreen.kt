package dev.pfeed.ui.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.domain.model.FeedMode
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.ui.Routes
import dev.pfeed.ui.common.findActivity
import dev.pfeed.ui.theme.Accent
import dev.pfeed.ui.theme.HeartRed
import kotlinx.coroutines.flow.distinctUntilChanged

/** Soft shadow so white text stays readable on any image. */
private val TextShadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 1.5f), blurRadius = 6f)
private fun TextStyle.shadowed() = merge(TextStyle(shadow = TextShadow))

@Composable
fun FeedScreen(nav: NavController, locked: Boolean, vm: FeedViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val hint by vm.hint.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val follows by vm.follows.collectAsStateWithLifecycle()
    val landscape by vm.landscape.collectAsStateWithLifecycle()

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !locked

    val pagerState = rememberPagerState(initialPage = vm.startIndex) { items.size }
    val snackbar = remember { SnackbarHostState() }
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(active) { vm.onVisibilityChanged(active) }
    // Keep the screen awake while the feed is on screen (long videos, long comics).
    val hostView = LocalView.current
    DisposableEffect(active) {
        hostView.keepScreenOn = active
        onDispose { hostView.keepScreenOn = false }
    }
    LaunchedEffect(pagerState) {
        // Also fires when a different item slides into the settled slot (a broken item was dropped),
        // so dwell and "seen" tracking always follow what's actually on screen.
        snapshotFlow { pagerState.settledPage to items.getOrNull(pagerState.settledPage)?.key }
            .distinctUntilChanged()
            .collect { (page, key) -> if (key != null) vm.onPageSettled(page) }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LandscapeMode(enabled = landscape)
    BackHandler(enabled = landscape) { vm.setLandscape(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isNotEmpty()) {
            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                key = { index -> items.getOrNull(index)?.key ?: index },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items.getOrNull(page) ?: return@VerticalPager
                FeedPage(
                    item = item,
                    playing = active && page == pagerState.settledPage,
                    muted = muted,
                    liked = item.key in liked,
                    saved = item.key in saved,
                    followingCreator = vm.isFollowingCreator(item, follows),
                    vm = vm,
                    onOpenTag = { tag -> nav.navigate(Routes.tag(tag)) },
                    onMenu = { menuFor = item },
                    landscape = landscape,
                    onLandscape = vm::setLandscape,
                )
            }
            // "Loading more" only when you're actually waiting at the end, not as a constant badge.
            if (loading && pagerState.settledPage >= items.size - 2) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading more…", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else {
            FeedEmptyState(
                loading = loading,
                hint = hint,
                canRefresh = !vm.isViewer,
                onRefresh = vm::refresh,
                onSources = { nav.navigate(Routes.SOURCES) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // First run: show the gestures once, out of the way, until the first swipe.
        if (items.isNotEmpty() && !landscape && !vm.isViewer && vm.tag == null) {
            GestureTips(page = pagerState.settledPage, modifier = Modifier.align(Alignment.Center))
        }

        if (!landscape) TopBar(
            title = vm.tag?.let { if (it.startsWith("@")) it else "#" + it.replace('_', ' ') } ?: vm.viewerTitle,
            showModes = vm.tag == null && !vm.isViewer,
            mode = mode,
            onMode = vm::setMode,
            onBack = if (vm.tag != null || vm.isViewer) ({ nav.popBackStack(); Unit }) else null,
            followTag = vm.tag?.let { t -> (t to vm.isFollowingPage(t, follows)) },
            onFollowTag = { vm.tag?.let(vm::toggleFollowTag) },
        )

        // Confirmations appear under the top bar, clear of the caption and buttons.
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 16.dp, end = 16.dp),
        )
    }

    menuFor?.let { item ->
        ItemMenuSheet(
            item = item,
            vm = vm,
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun FeedEmptyState(
    loading: Boolean,
    hint: String?,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
    onSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading || hint == null) {
            CircularProgressIndicator(Modifier.size(44.dp), color = Accent, strokeWidth = 4.dp, trackColor = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(24.dp))
            Text("Loading your feed", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pulling from thousands of feeds",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                hint,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canRefresh) {
                    Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Try again") }
                }
                OutlinedButton(onClick = onSources) { Text("Sources", color = Color.White) }
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String?,
    showModes: Boolean,
    mode: FeedMode,
    onMode: (FeedMode) -> Unit,
    onBack: (() -> Unit)?,
    followTag: Pair<String, Boolean>?,
    onFollowTag: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }
        if (showModes) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf(FeedMode.FOLLOWING to "Following", FeedMode.FOR_YOU to "For You", FeedMode.LATEST to "Latest").forEach { (m, label) ->
                    ModeTab(label, selected = m == mode) { onMode(m) }
                }
            }
        } else if (title != null) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.shadowed(),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
            )
        }
        if (followTag != null) {
            FollowPill(following = followTag.second, onClick = onFollowTag, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val indicator by animateDpAsState(if (selected) 22.dp else 0.dp, label = "modeIndicator")
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.titleSmall.shadowed(),
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(indicator).height(3.dp).background(Color.White, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun FollowPill(following: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Text(
        if (following) "Following" else "Follow",
        color = Color.White.copy(alpha = if (following) 0.85f else 1f),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(shape)
            .then(if (following) Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), shape) else Modifier.background(Accent))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun FeedPage(
    item: MediaItem,
    playing: Boolean,
    muted: Boolean,
    liked: Boolean,
    saved: Boolean,
    followingCreator: Boolean,
    vm: FeedViewModel,
    onOpenTag: (String) -> Unit,
    onMenu: () -> Unit,
    landscape: Boolean,
    onLandscape: (Boolean) -> Unit,
) {
    var paused by remember(item.key) { mutableStateOf(!vm.autoplayAllowed()) }
    val haptic = LocalHapticFeedback.current
    var heartAt by remember { mutableStateOf<Offset?>(null) }
    var heartTick by remember { mutableIntStateOf(0) }
    var player by remember(item.key) { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var speeding by remember(item.key) { mutableStateOf(false) }
    // What the post really is once its file is known: a board/Reddit post listed as an image can turn
    // out to be an MP4/WebM, which must play as a video (drawn as an image it froze on one frame).
    val stream by produceState<dev.pfeed.domain.model.StreamInfo?>(initialValue = null, item.key) { value = vm.resolve(item) }
    val type = remember(item.key, stream) { Display.effectiveType(item, stream) }
    val isVideo = type == MediaType.VIDEO

    // Hold the right side of a video to play at 2×; release to go back to 1×.
    LaunchedEffect(player, speeding) { player?.setPlaybackSpeed(if (speeding) 2f else 1f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.key) {
                detectTapGestures(
                    onTap = { paused = !paused },
                    onDoubleTap = { offset ->
                        heartAt = offset
                        heartTick++
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.like(item)
                    },
                )
            }
            // Press-and-hold: on a video's right third → 2× speed; elsewhere → the menu.
            // awaitLongPressOrCancellation ignores quick taps and swipes (movement cancels it), so
            // taps, double-taps and the pager's vertical swipe all keep working.
            .pointerInput(item.key, isVideo) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    val rightThird = longPress.position.x > size.width * 0.6f
                    if (isVideo && rightThird) {
                        speeding = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        waitForUpOrCancellation()
                        speeding = false
                    } else {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMenu()
                    }
                }
            },
    ) {
        when (type) {
            MediaType.VIDEO -> VideoPage(item, play = playing && !paused, muted = muted, vm = vm, onPlayer = { player = it })
            MediaType.IMAGE, MediaType.GIF -> if (item.isGallery) GalleryPage(item, vm) else ImagePage(item, vm)
            MediaType.EMBED -> EmbedPage(item, active = playing, vm = vm)
        }

        // Scrims: darken just the edges so the top bar, caption and buttons read on any image.
        if (!landscape) Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(140.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
        )
        if (!landscape) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(320.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )

        if (paused && isVideo && !speeding) {
            Box(
                Modifier.align(Alignment.Center).size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Paused", tint = Color.White, modifier = Modifier.size(56.dp))
            }
        }
        if (speeding) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp),
            ) {
                Text("2×  ▶▶", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
        HeartBurst(heartAt, heartTick)
        if (landscape) {
            IconButton(
                onClick = { onLandscape(false) },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
            ) {
                Icon(Icons.Filled.FullscreenExit, contentDescription = "Exit landscape", tint = Color.White)
            }
        } else Overlay(
            item = item,
            sourceLabel = Display.source(item, vm.sourceName(item.sourceId)),
            creator = remember(item.key) { Display.creator(item) },
            tags = remember(item.key) { Display.tags(item) },
            liked = liked,
            saved = saved,
            muted = muted,
            followingCreator = followingCreator,
            isVideo = isVideo,
            // Turning sideways is for video; a still or a comic page reads fine upright.
            canFullscreen = isVideo || type == MediaType.EMBED,
            onLike = { vm.toggleLike(item) },
            onSave = { vm.toggleSave(item) },
            onMute = vm::toggleMute,
            onFollow = { vm.toggleFollowCreator(item) },
            onFullscreen = { onLandscape(true) },
            onTag = onOpenTag,
            onMenu = onMenu,
        )
        if (isVideo) {
            ScrubBar(
                player = player,
                modifier = Modifier.align(Alignment.BottomCenter),
                // Scrubbing back to see something again is a rewatch: a strong signal for For You.
                onScrubbedBack = { vm.statsFor(item.key).replays++ },
            )
        }
    }
}

/**
 * Landscape mode for the whole feed: the screen turns sideways and the system bars hide, and it
 * stays that way while you swipe from post to post, until you exit (button or Back) or leave the
 * feed. Before, fullscreen was a separate window for one video that ended on every swipe.
 */
@Composable
private fun LandscapeMode(enabled: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(enabled) {
        val activity = context.findActivity()
        if (!enabled || activity == null) return@DisposableEffect onDispose { }
        val previous = activity.requestedOrientation
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = if (previous == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else previous
        }
    }
}

@Composable
private fun Overlay(
    item: MediaItem,
    sourceLabel: String,
    creator: String?,
    tags: List<Pair<String, String>>,
    liked: Boolean,
    saved: Boolean,
    muted: Boolean,
    followingCreator: Boolean,
    isVideo: Boolean,
    canFullscreen: Boolean,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onMute: () -> Unit,
    onFollow: () -> Unit,
    onFullscreen: () -> Unit,
    onTag: (String) -> Unit,
    onMenu: () -> Unit,
) {
    // One row: the caption takes whatever width the button rail leaves, so they can never overlap.
    Row(
        Modifier.fillMaxSize().padding(start = 14.dp, end = 8.dp, bottom = 34.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(Accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    sourceLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            if (creator != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "@$creator",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.shadowed(),
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Tap the name for everything by them, from every source.
                        modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).clickable { onTag("@" + (item.creator ?: creator)) },
                    )
                    Spacer(Modifier.width(10.dp))
                    FollowPill(following = followingCreator, onClick = onFollow)
                }
            }
            item.title?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium.shadowed())
            }
            if (tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { (label, raw) ->
                        Text(
                            "#$label",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.4f))
                                .clickable { onTag(raw) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LikeButton(liked = liked, onLike = onLike)
            RailButton(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) "Saved" else "Save", onSave)
            if (isVideo) {
                RailButton(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) "Muted" else "Sound", onMute)
            }
            if (canFullscreen) RailButton(Icons.Filled.Fullscreen, "Rotate", onFullscreen)
            RailButton(Icons.Filled.MoreHoriz, "More", onMenu)
        }
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    iconScale: Float = 1f,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(2.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(28.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale },
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall.shadowed())
    }
}

/** Like with a little pop when it turns on (not when the page appears already liked). */
@Composable
private fun LikeButton(liked: Boolean, onLike: () -> Unit) {
    val pop = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(liked) }
    LaunchedEffect(liked) {
        if (liked && !previous) {
            pop.animateTo(1.35f, tween(110))
            pop.animateTo(1f, spring(dampingRatio = 0.4f))
        }
        previous = liked
    }
    RailButton(
        icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        label = if (liked) "Liked" else "Like",
        onClick = onLike,
        tint = if (liked) HeartRed else Color.White,
        iconScale = pop.value,
    )
}

/**
 * A one-time card that teaches the gestures (double-tap, hold, long-press). It never takes touches,
 * so it can't get in the way, and it goes away for good after the first swipe or ten seconds.
 */
@Composable
private fun GestureTips(page: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui_hints", android.content.Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(!prefs.getBoolean("gestures_seen", false)) }
    val startPage = remember { page }
    fun done() { if (show) { show = false; prefs.edit().putBoolean("gestures_seen", true).apply() } }
    LaunchedEffect(page) { if (page != startPage) done() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(10_000); done() }
    androidx.compose.animation.AnimatedVisibility(
        visible = show,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(horizontal = 40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(
                "Swipe up" to "next post",
                "Double-tap" to "like",
                "Hold the right side" to "play at 2×",
                "Press and hold" to "more options",
                "Three fingers" to "leave instantly",
            ).forEach { (gesture, what) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(gesture, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(150.dp))
                    Text(what, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

