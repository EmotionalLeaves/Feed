package dev.pfeed.ui.feed

import dev.pfeed.ui.openTag
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.SwipeUp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Autorenew
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import dev.pfeed.domain.model.FeedMode
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ThumbsUpDown
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    // In picture-in-picture the app is paused but still on screen: keep playing there.
    val inPip by Pip.active.collectAsStateWithLifecycle()
    val sleepUp by SleepTimer.upNow.collectAsStateWithLifecycle()
    val sleepEnds by SleepTimer.endsAt.collectAsStateWithLifecycle()
    // The sleep timer runs out: stop, wherever you are in the app's feeds.
    LaunchedEffect(sleepEnds) {
        val end = sleepEnds ?: return@LaunchedEffect
        kotlinx.coroutines.delay((end - System.currentTimeMillis()).coerceAtLeast(0))
        if (SleepTimer.endsAt.value == end) SleepTimer.runOut()
    }
    val active = (lifecycleState.isAtLeast(Lifecycle.State.RESUMED) || (inPip && lifecycleState.isAtLeast(Lifecycle.State.STARTED))) && !locked && !sleepUp
    // Break reminder: count the minutes watched in one go; at the chosen mark, a quiet offer.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(60_000)
            val now = System.currentTimeMillis()
            if (now - WatchStretch.lastTick > STRETCH_RESET_MS) { WatchStretch.minutes = 0; WatchStretch.reminded = false }
            WatchStretch.lastTick = now
            WatchStretch.minutes++
            val mark = vm.breakReminderMin
            if (mark > 0 && WatchStretch.minutes >= mark && !WatchStretch.reminded) {
                WatchStretch.reminded = true
                vm.offer("You've been watching for ${WatchStretch.minutes} min. Time for a break?", "Stop in 15 min") { SleepTimer.start(15) }
            }
        }
    }
    // Feel the sound: pulse the vibrator with the loudness of whatever is playing.
    val feel by FeelTheSound.on.collectAsStateWithLifecycle()
    val feelContext = LocalContext.current
    LaunchedEffect(feel, active) {
        if (!feel || !active) return@LaunchedEffect
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            (feelContext.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") feelContext.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        } ?: return@LaunchedEffect
        if (!vibrator.hasVibrator()) return@LaunchedEffect
        try {
            while (true) {
                val fresh = android.os.SystemClock.elapsedRealtime() - dev.pfeed.player.SmartVolume.envelopeAt < 200
                val amp = if (fresh) FeelTheSound.amplitude(dev.pfeed.player.SmartVolume.envelope) else 0
                if (amp > 0) {
                    vibrator.vibrate(
                        if (vibrator.hasAmplitudeControl()) android.os.VibrationEffect.createOneShot(FEEL_PULSE_MS, amp)
                        else android.os.VibrationEffect.createOneShot(if (amp > 120) FEEL_PULSE_MS else 1, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                    )
                }
                kotlinx.coroutines.delay(FEEL_PULSE_MS)
            }
        } finally {
            vibrator.cancel()
        }
    }

    val pagerState = rememberPagerState(initialPage = vm.startIndex) { items.size }
    val pagerScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }
    // A creator's or a tag's page opens as a profile: a header and a grid of everything in it.
    val creatorPage = vm.tag?.startsWith("@") == true
    val profilePage = vm.tag != null
    var gridView by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(profilePage) }
    var steerOpen by remember { mutableStateOf(false) }
    var thisOrThat by remember { mutableStateOf(false) }
    var wallMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val pinchHaptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) { HeadphonesOnly.openSettings.collect { nav.navigate(dev.pfeed.ui.Routes.SETTINGS) } }
    // Volume keys turn pages here while this feed is on screen (if that's switched on).
    val turnable = active && !gridView && !wallMode && items.isNotEmpty()
    DisposableEffect(turnable, pagerState) {
        val turn: (Int) -> Unit = { d ->
            pagerScope.launch {
                val to = (pagerState.currentPage + d).coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
                pagerState.animateScrollToPage(to)
            }
        }
        if (turnable) VolumeKeys.turner = turn
        onDispose { if (VolumeKeys.turner === turn) VolumeKeys.turner = null }
    }

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
    LaunchedEffect(Unit) {
        vm.offers.collect { (text, action, run) ->
            // With an action, a snackbar defaults to staying until tapped: these go by themselves.
            if (snackbar.showSnackbar(text, actionLabel = action, withDismissAction = false, duration = androidx.compose.material3.SnackbarDuration.Long) == androidx.compose.material3.SnackbarResult.ActionPerformed) run()
        }
    }
    LaunchedEffect(Unit) {
        vm.undoables.collect { (text, undo) ->
            if (snackbar.showSnackbar(text, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long) == androidx.compose.material3.SnackbarResult.ActionPerformed) undo()
        }
    }
    LaunchedEffect(Unit) {
        vm.followHints.collect { item ->
            val name = Display.creator(item) ?: return@collect
            val r = snackbar.showSnackbar("You keep liking @$name", actionLabel = "Follow", duration = androidx.compose.material3.SnackbarDuration.Long)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.toggleFollowCreator(item)
        }
    }
    LaunchedEffect(pagerState.settledPage) { vm.refreshLikedTags() }
    // Swiped on to another post: whatever note was up is about the one before; let it go.
    var notePage by remember { mutableStateOf(-1) }
    LaunchedEffect(snackbar.currentSnackbarData) { notePage = pagerState.settledPage }
    LaunchedEffect(pagerState.settledPage) {
        if (snackbar.currentSnackbarData != null && pagerState.settledPage != notePage) {
            kotlinx.coroutines.delay(1_500)
            if (pagerState.settledPage != notePage) snackbar.currentSnackbarData?.dismiss()
        }
    }
    // Chameleon colour: the app takes the hue of the post you settle on.
    val chameleonContext = LocalContext.current
    LaunchedEffect(pagerState.settledPage, items.size) {
        if (dev.pfeed.ui.theme.Palette.current != dev.pfeed.ui.theme.Palette.CHAMELEON) return@LaunchedEffect
        val thumb = items.getOrNull(pagerState.settledPage)?.thumbnailUrl ?: return@LaunchedEffect
        dominantHue(chameleonContext, thumb)?.let { dev.pfeed.ui.theme.Palette.follow(it) }
    }
    // Just updated: say so once, with the notes a tap away.
    if (vm.isMainFeed) {
        val ctx = LocalContext.current
        LaunchedEffect(Unit) {
            val prefs = ctx.getSharedPreferences("ui_hints", android.content.Context.MODE_PRIVATE)
            val seen = prefs.getString("version_seen", null)
            val now = dev.pfeed.BuildConfig.VERSION_NAME
            if (seen != now) {
                prefs.edit().putString("version_seen", now).apply()
                // Not on a fresh install: there's nothing "new" to someone who just arrived.
                if (seen != null) {
                    kotlinx.coroutines.delay(2_500)
                    vm.offer("Updated to $now", "What's new") { dev.pfeed.ui.common.WhatsNew.showing = true }
                }
            }
        }
    }
    // Back after leaving a long video partway: offer to carry on from there.
    if (vm.isMainFeed) LaunchedEffect(Unit) {
        val (item, at, route) = vm.carryOn() ?: return@LaunchedEffect
        kotlinx.coroutines.delay(1_200)
        val what = item.title?.take(32)?.let { "“$it”" } ?: "your last video"
        vm.offer("Carry on with $what from ${dev.pfeed.ui.common.formatDuration((at / 1000).toInt())}?", "Carry on") {
            nav.navigate(dev.pfeed.ui.Routes.viewer(route))
        }
    }
    // Feed tapped again in the tab bar: back to the top, or a fresh batch if you're already there.
    if (vm.isMainFeed) LaunchedEffect(Unit) {
        dev.pfeed.ui.BottomBar.reselected.collect { route ->
            if (route != dev.pfeed.ui.Routes.FEED) return@collect
            if (pagerState.currentPage > 0) pagerState.animateScrollToPage(0) else vm.refresh()
        }
    }
    // A site turned out to be blocked where you are: say so once, with the way around it.
    if (vm.isMainFeed) LaunchedEffect(Unit) {
        var known = dev.pfeed.source.net.GeoBlock.sites.value
        dev.pfeed.source.net.GeoBlock.sites.collect { now ->
            val fresh = now - known
            known = now
            if (fresh.isNotEmpty() && !dev.pfeed.source.net.AppProxy.config.active) {
                val names = fresh.map { dev.pfeed.ui.settings.siteLabel(it) }.joinToString(", ")
                vm.offer("$names ${if (fresh.size == 1) "is" else "are"} blocked where you are", "Get it back") { nav.navigate(dev.pfeed.ui.Routes.NETWORK) }
            }
        }
    }
    LandscapeMode(enabled = landscape)
    // Clean screen hides the tab bar too (only on the feed; leaving it brings everything back).
    val cleanScreen by CleanScreen.on.collectAsStateWithLifecycle()
    DisposableEffect(cleanScreen, landscape) {
        if (cleanScreen && !landscape) dev.pfeed.ui.BottomBar.hidden = true
        onDispose { if (cleanScreen && !landscape) dev.pfeed.ui.BottomBar.hidden = false }
    }
    // Sideways, the tab bar steps aside for the video.
    DisposableEffect(landscape) {
        dev.pfeed.ui.BottomBar.hidden = landscape
        onDispose { if (landscape) dev.pfeed.ui.BottomBar.hidden = false }
    }
    BackHandler(enabled = landscape) { vm.setLandscape(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isNotEmpty()) {
            // Main feed: pull down on the first post for a fresh batch, as in any feed app.
            val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
            val canPull = vm.isMainFeed && !landscape
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { if (canPull) vm.refresh() },
                state = pull,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    if (canPull) androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                        state = pull,
                        isRefreshing = false,
                        containerColor = Color(0xFF1C1C21),
                        color = Accent,
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 52.dp),
                    )
                },
            ) {
            // Wall mode: the pager steps aside (its pages would hold the players the wall needs).
            if (!wallMode) {
            VerticalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                key = { index -> items.getOrNull(index)?.key ?: index },
                // Pinch in (two fingers together) on the main feed: zoom out to the Wall, as in a photo app.
                // On a tag's or creator's page: back to its grid. A quick two-finger tap anywhere: sound
                // on or off. Watched without taking the touch.
                modifier = Modifier.fillMaxSize().pointerInput(vm.isMainFeed, profilePage) {
                    val canPinch = vm.isMainFeed || profilePage
                    awaitPointerEventScope {
                        var start = 0f
                        var twoAt = 0L
                        var moved = false
                        var maxDown = 0
                        val origin = HashMap<androidx.compose.ui.input.pointer.PointerId, androidx.compose.ui.geometry.Offset>()
                        while (true) {
                            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val down = e.changes.filter { it.pressed }
                            down.forEach { c -> origin.getOrPut(c.id) { c.position }.let { o -> if ((c.position - o).getDistance() > TWO_TAP_SLOP) moved = true } }
                            maxDown = maxOf(maxDown, down.size)
                            // A two-finger tap's lifts aren't single taps on the post (no pause, no like).
                            if (maxDown == 2 && down.size < 2 && !moved && twoAt != 0L && e.changes.first().uptimeMillis - twoAt < TWO_TAP_MS) {
                                e.changes.forEach { if (it.changedToUp()) it.consume() }
                            }
                            if (down.size == 2) {
                                if (twoAt == 0L) twoAt = e.changes.first().uptimeMillis
                                val d = (down[0].position - down[1].position).getDistance()
                                if (start == 0f) start = if (Zoom.active || !canPinch) -1f else d // zoomed in: that pinch is zooming out
                                else if (start > 0f && d < start * PINCH_TO_WALL) {
                                    start = -1f // once per pinch
                                    pinchHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (vm.isMainFeed) wallMode = true else gridView = true
                                }
                            } else if (down.isEmpty()) {
                                val quick = twoAt != 0L && e.changes.first().uptimeMillis - twoAt < TWO_TAP_MS
                                if (maxDown == 2 && quick && !moved) {
                                    pinchHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    vm.toggleMute()
                                }
                                start = 0f; twoAt = 0L; moved = false; maxDown = 0; origin.clear()
                            }
                        }
                    }
                },
            ) { page ->
                val item = items.getOrNull(page) ?: return@VerticalPager
                FeedPage(
                    item = item,
                    playing = active && !gridView && page == pagerState.settledPage,
                    muted = muted,
                    liked = item.key in liked,
                    saved = item.key in saved,
                    followingCreator = vm.isFollowingCreator(item, follows),
                    vm = vm,
                    onOpenTag = { tag -> nav.openTag(tag) },
                    onMenu = { menuFor = item },
                    landscape = landscape,
                    onLandscape = vm::setLandscape,
                    // A full scene finished: on to the next post.
                    onEnded = {
                        if (page == pagerState.settledPage && page + 1 < items.size) {
                            if (HandsFree.on.value) vm.statsFor(item.key).autoAdvanced = true
                            pagerScope.launch { pagerState.animateScrollToPage(page + 1) }
                        }
                    },
                )
            }
            }
            }
            // "Loading more" only when you're actually waiting at the end, not as a constant badge.
            if (loading && pagerState.settledPage >= items.size - 2) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        dev.pfeed.ui.common.BrandSpinner(size = 16.dp, stroke = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading more…", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } else if (loading || hint == null) {
            LoadingPost(Modifier.fillMaxSize().padding(bottom = dev.pfeed.ui.LocalBottomBarInset.current))
        } else {
            FeedEmptyState(
                loading = loading,
                hint = hint,
                canRefresh = !vm.isViewer,
                onRefresh = vm::refresh,
                onSources = { nav.navigate(Routes.SOURCES) },
                // An empty Following feed needs something to follow, not a retry.
                onBrowse = if (mode == FeedMode.FOLLOWING && vm.isMainFeed) ({ vm.setMode(FeedMode.FOR_YOU) }) else null,
                // Nothing loading (often: no connection) but saved posts are on the phone: offer them.
                onSaved = if (saved.isNotEmpty() && !vm.isViewer) ({ nav.navigate(Routes.LIBRARY) }) else null,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        val isPrivate by vm.privateMode.collectAsStateWithLifecycle()
        if (isPrivate && !landscape) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 6.dp + dev.pfeed.ui.LocalBottomBarInset.current)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("Private", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            }
        }

        // First run: show the gestures once, out of the way, until the first swipe.
        if (items.isNotEmpty() && !landscape && !wallMode && vm.isMainFeed) {
            GestureTips(page = pagerState.settledPage, modifier = Modifier.align(Alignment.Center))
        }

        val cleanOn by CleanScreen.on.collectAsStateWithLifecycle()
        if (!landscape && !wallMode && !cleanOn && !Pip.active.collectAsStateWithLifecycle().value) TopBar(
            title = vm.tag?.let { if (it.startsWith("@")) it else "#" + it.replace('_', ' ') } ?: vm.viewerTitle,
            showModes = vm.isMainFeed,
            mode = mode,
            onMode = vm::setMode,
            mood = vm.mood.collectAsStateWithLifecycle().value,
            onMood = if (vm.isMainFeed) vm::setMood else null,
            showing = vm.showing.collectAsStateWithLifecycle().value,
            onShowing = if (vm.isMainFeed) vm::setShowing else null,
            onAskSteer = if (vm.isMainFeed) ({ steerOpen = true }) else null,
            onThisOrThat = if (vm.isMainFeed) ({ thisOrThat = true }) else null,
            onBack = if (!vm.isMainFeed) ({ nav.popBackStack(); Unit }) else null,
            followTag = vm.tag?.let { t -> (t to vm.isFollowingPage(t, follows)) },
            onFollowTag = { vm.tag?.let(vm::toggleFollowTag) },
            onGrid = if (vm.tag != null && items.size >= 4) ({ gridView = true }) else null,
            onWall = if (vm.isMainFeed) ({ wallMode = true }) else null,
            // Only where there's room beside the three feed tabs.
            onSearch = if (vm.isMainFeed && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 360) ({
                nav.navigate(Routes.SEARCH) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }) else null,
        )

        // For You being steered in words: say so under the tabs, with a way out.
        val steering by vm.steering.collectAsStateWithLifecycle()
        if (vm.isMainFeed && mode == FeedMode.FOR_YOU && steering.isNotEmpty() && !landscape && !wallMode && !cleanOn) {
            SteerChip(
                steering, onEdit = { steerOpen = true }, onClear = vm::clearSteer,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 54.dp, start = 48.dp, end = 48.dp),
            )
        }
        val asked by vm.thisOrThatRequest.collectAsStateWithLifecycle()
        LaunchedEffect(asked) { if (asked) { vm.thisOrThatRequest.value = false; thisOrThat = true } }
        if (thisOrThat) ThisOrThat(vm) { n ->
            thisOrThat = false
            if (n > 0) { vm.setMode(FeedMode.FOR_YOU); vm.refresh() }
        }
        if (steerOpen) SteerDialog(steering, onSteer = { vm.steer(it); vm.setMode(FeedMode.FOR_YOU) }, onClear = vm::clearSteer, onDismiss = { steerOpen = false })

        // The page as a grid: everything loaded so far (and more as you scroll); tap one to watch from there.
        if (gridView && !landscape) {
            // On a creator's profile the grid is the page itself, so back leaves it.
            BackHandler(enabled = !profilePage) { gridView = false }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(bottom = dev.pfeed.ui.LocalBottomBarInset.current)) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (profilePage) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else IconButton(onClick = { gridView = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the feed") }
                    Text(
                        "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (profilePage) {
                        if (items.isNotEmpty()) IconButton(onClick = { gridView = false }) { Icon(Icons.Filled.ViewDay, "Watch as a feed") }
                    } else Text(
                        "${items.size} posts",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
                val tag = vm.tag.orEmpty()
                val profile: (@Composable () -> Unit)? = if (creatorPage) ({
                    CreatorHeader(
                        name = tag.removePrefix("@"),
                        items = items,
                        loading = loading,
                        following = vm.isFollowingPage(tag, follows),
                        onFollow = { vm.toggleFollowTag(tag) },
                        onPlay = { gridView = false; pagerScope.launch { pagerState.scrollToPage(0) } },
                        onShuffle = { gridView = false; pagerScope.launch { pagerState.scrollToPage(items.indices.random()) } },
                        similar = androidx.compose.runtime.produceState(emptyList<String>(), tag, items.size / 10) {
                            value = vm.similarCreators(tag.removePrefix("@"), items)
                        }.value,
                        onOpenCreator = { nav.openTag("@$it") },
                    )
                }) else if (profilePage) ({
                    TagHeader(
                        tag = tag,
                        items = items,
                        loading = loading,
                        following = vm.isFollowingPage(tag, follows),
                        related = run {
                            // On the map first (what means the same), then what turns up alongside it here.
                            val near = androidx.compose.runtime.produceState(emptyList<String>(), tag) { value = vm.neighbours(tag) }.value
                            val seen = remember(items.size, tag) { relatedTags(items, tag) }
                            remember(near, seen) {
                                (near.map { it.replace('_', ' ') to it } + seen)
                                    .distinctBy { dev.pfeed.domain.reco.Features.normalizeTag(it.second) }.take(10)
                            }
                        },
                        onFollow = { vm.toggleFollowTag(tag) },
                        onOpenTag = { nav.openTag(it) },
                        onPlay = { gridView = false; pagerScope.launch { pagerState.scrollToPage(0) } },
                        onShuffle = { gridView = false; pagerScope.launch { pagerState.scrollToPage(items.indices.random()) } },
                    )
                }) else null
                dev.pfeed.ui.common.MediaGrid(
                    items = items,
                    onOpen = { index -> gridView = false; pagerScope.launch { pagerState.scrollToPage(index) } },
                    onNearEnd = vm::loadMore,
                    livePreview = dev.pfeed.ui.common.rememberLivePreview(vm.livePreviews, playing = active),
                    header = profile,
                )
            }
        }

        // Time's up: a dim screen over everything, nothing playing, until you choose.
        if (sleepUp) {
            BackHandler { SleepTimer.keepWatching() }
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(enabled = false) {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(16.dp))
                Text("Time's up", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text("Your sleep timer stopped playback", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.height(24.dp))
                dev.pfeed.ui.common.BrandButton("Keep watching", onClick = { SleepTimer.keepWatching() })
            }
        }

        if (wallMode && !landscape && items.isNotEmpty()) {
            BackHandler { wallMode = false }
            Box(Modifier.fillMaxSize().padding(bottom = dev.pfeed.ui.LocalBottomBarInset.current)) {
                Wall(
                    items = items,
                    vm = vm,
                    active = active,
                    onPick = { index -> wallMode = false; pagerScope.launch { pagerState.scrollToPage(index) } },
                    onClose = { wallMode = false },
                )
            }
        }

        // Clean screen: after a touch, a small way back for a couple of seconds.
        if (cleanOn && !landscape) {
            var showExit by remember { mutableStateOf(true) }
            LaunchedEffect(dev.pfeed.ui.BottomBar.lastTouch) { showExit = true; kotlinx.coroutines.delay(2_500); showExit = false }
            androidx.compose.animation.AnimatedVisibility(
                visible = showExit,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            ) {
                Text(
                    "Show everything",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f))
                        .clickable { CleanScreen.set(false) }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // Following: the people you follow as a row of round avatars under the tabs, one tap from their page.
        val handsFreeNow by HandsFree.on.collectAsStateWithLifecycle()
        if (mode == FeedMode.FOLLOWING && vm.isMainFeed && !landscape && !wallMode && !handsFreeNow && follows.creators.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 54.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(follows.creators.sorted(), key = { it }) { name ->
                    Column(
                        Modifier.width(52.dp).clip(RoundedCornerShape(10.dp)).clickable { nav.openTag("@$name") },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        dev.pfeed.ui.common.InitialAvatar(name, size = 40.dp, modifier = Modifier.border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape))
                        Spacer(Modifier.height(3.dp))
                        Text(name, color = Color.White, style = MaterialTheme.typography.labelSmall.shadowed(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // Hands-free is on: say so, with a quick way to stop it.
        val handsFreeOn by HandsFree.on.collectAsStateWithLifecycle()
        if (handsFreeOn && !landscape && !gridView && !wallMode && items.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.TopStart).statusBarsPadding()
                    .padding(start = 12.dp, top = if (vm.tag?.startsWith("@") == false) 92.dp else 56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { HandsFree.set(false); vm.offer("Hands-free off", "Turn back on") { HandsFree.set(true) } }
                    .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Autorenew, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Hands-free", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Close, contentDescription = "Stop hands-free", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
            }
        }

        // Incognito: say so for as long as it's on (below the hands-free pill if that's showing too).
        val incognito by dev.pfeed.domain.privacy.Incognito.on.collectAsStateWithLifecycle()
        if (incognito && !landscape && !gridView && !wallMode && !CleanScreen.on.collectAsStateWithLifecycle().value) {
            Row(
                Modifier.align(Alignment.TopStart).statusBarsPadding()
                    .padding(start = 12.dp, top = (if (vm.tag?.startsWith("@") == false) 92.dp else 56.dp) + if (handsFreeOn) 32.dp else 0.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF2A2A35).copy(alpha = 0.85f))
                    .clickable { dev.pfeed.domain.privacy.Incognito.set(false); vm.offer("Incognito off: watching is remembered again", "Undo") { dev.pfeed.domain.privacy.Incognito.set(true) } }
                    .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Incognito", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Close, contentDescription = "Turn incognito off", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
            }
        }

        // The sleep timer, counting down (minutes), under the other pills.
        if (sleepEnds != null && !landscape && !gridView && !wallMode && !CleanScreen.on.collectAsStateWithLifecycle().value) {
            var left by remember { mutableIntStateOf(SleepTimer.minutesLeft() ?: 0) }
            LaunchedEffect(sleepEnds) { while (true) { left = SleepTimer.minutesLeft() ?: 0; kotlinx.coroutines.delay(15_000) } }
            Row(
                Modifier.align(Alignment.TopStart).statusBarsPadding()
                    .padding(start = 12.dp, top = (if (vm.tag?.startsWith("@") == false) 92.dp else 56.dp) + (if (handsFreeOn) 32.dp else 0.dp) + (if (incognito) 32.dp else 0.dp))
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { val was = left; SleepTimer.cancel(); vm.offer("Sleep timer off", "Undo") { SleepTimer.start(was.coerceAtLeast(1)) } }
                    .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stops in $left min", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Close, contentDescription = "Turn the sleep timer off", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp))
            }
        }

        // A tag page: the tags that show up most alongside it, to hop sideways to.
        val pageTag = vm.tag
        if (pageTag != null && !pageTag.startsWith("@") && !landscape && !gridView && items.size >= 6) {
            val related = remember(items.size, pageTag) { relatedTags(items, pageTag) }
            if (related.isNotEmpty()) Row(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 54.dp)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                related.forEach { (label, raw) ->
                    Text(
                        "#$label",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                            .clickable { nav.openTag(raw) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // Confirmations appear under the top bar, clear of the caption and buttons.
        dev.pfeed.ui.common.AppSnackbarHost(
            snackbar,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp, start = 16.dp, end = 16.dp),
        )

        // Very first run: what are you into? (skippable; shapes For You from the first screen). On top of everything.
        val newUser by vm.isNewUser.collectAsStateWithLifecycle()
        if (vm.isMainFeed && !landscape && (newUser == true || FirstRun.forceInterests)) Box(Modifier.fillMaxSize().padding(bottom = dev.pfeed.ui.LocalBottomBarInset.current)) { InterestPicker(onDone = vm::pickInterests) }
    }

    menuFor?.let { item ->
        ItemMenuSheet(
            item = item,
            vm = vm,
            onDismiss = { menuFor = null },
            onMoreLikeThis = { nav.openTag(vm.similarRoute(item)) },
            onOpenPage = { nav.openTag(it) },
            onForTheRoad = { vm.takeForTheRoad(items.indexOfFirst { it.key == item.key }.coerceAtLeast(0)) },
        )
    }
}

/**
 * While the first posts load: the shape of a post (caption lines, the button rail) under a slow
 * shimmer, so the screen looks like the feed arriving rather than a spinner on black.
 */
@Composable
private fun LoadingPost(modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.LinearEasing)),
        label = "shimmerX",
    )
    val base = Color.White.copy(alpha = 0.06f)
    val shine = Brush.linearGradient(
        listOf(base, Color.White.copy(alpha = 0.14f), base),
        start = Offset(x * 1000f, 0f), end = Offset(x * 1000f + 600f, 600f),
    )
    Box(modifier.background(Color(0xFF0C0C0F))) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 40.dp, end = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(84.dp).height(20.dp).clip(RoundedCornerShape(50)).background(shine))
            Box(Modifier.width(150.dp).height(18.dp).clip(RoundedCornerShape(6.dp)).background(shine))
            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(shine))
            Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(shine))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { Box(Modifier.width(56.dp).height(26.dp).clip(RoundedCornerShape(50)).background(shine)) }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            repeat(4) { Box(Modifier.size(44.dp).clip(CircleShape).background(shine)) }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Loading your feed", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Pulling from thousands of feeds", color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FeedEmptyState(
    loading: Boolean,
    hint: String?,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
    onSources: () -> Unit,
    onBrowse: (() -> Unit)? = null,
    onSaved: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading || hint == null) {
            dev.pfeed.ui.common.BrandSpinner(size = 44.dp, stroke = 4.dp)
            Spacer(Modifier.height(24.dp))
            Text("Loading your feed", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pulling from thousands of feeds",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Box(
                Modifier.size(84.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.22f), Color.Transparent))),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    Icon(
                        if (onBrowse != null) Icons.Filled.PersonAdd else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                hint,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onBrowse != null) {
                    dev.pfeed.ui.common.BrandButton("Browse For You", onClick = onBrowse)
                } else {
                    if (canRefresh) {
                        dev.pfeed.ui.common.BrandButton("Try again", onClick = onRefresh)
                    }
                    OutlinedButton(onClick = onSources) { Text("Sources", color = Color.White) }
                }
            }
            if (onSaved != null && onBrowse == null) {
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.TextButton(onClick = onSaved) {
                    Text("Watch your saved posts (they work offline)", color = Color.White.copy(alpha = 0.8f))
                }
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
    mood: FeedViewModel.Mood = FeedViewModel.Mood.BALANCED,
    onMood: ((FeedViewModel.Mood) -> Unit)? = null,
    showing: FeedViewModel.Showing? = null,
    onShowing: ((FeedViewModel.Showing) -> Unit)? = null,
    /** Main feed: tell For You what you want in words. */
    onAskSteer: (() -> Unit)? = null,
    /** Main feed: quick this-or-that choices that teach For You. */
    onThisOrThat: (() -> Unit)? = null,
    onBack: (() -> Unit)?,
    followTag: Pair<String, Boolean>?,
    onFollowTag: () -> Unit,
    /** Tag and creator pages: see everything loaded as a grid. */
    onGrid: (() -> Unit)? = null,
    /** Main feed: a search button at the top right, as in other short-video apps. */
    onSearch: (() -> Unit)? = null,
    /** Main feed: open the Wall (four clips at once). */
    onWall: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        } else if (onWall != null) {
            IconButton(onClick = onWall, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                Icon(Icons.Filled.ViewModule, "Wall: four clips at once", tint = Color.White)
            }
        }
        if (showModes) {
            // One white bar under the feed you're on, sliding (and resizing) to the next one you pick.
            val centers = remember { androidx.compose.runtime.mutableStateMapOf<FeedMode, Float>() }
            var moodOpen by remember { mutableStateOf(false) }
            val target = centers[mode]
            val bar = remember { androidx.compose.animation.core.Animatable(Float.NaN) }
            LaunchedEffect(target) {
                val t = target ?: return@LaunchedEffect
                // First placement: straight there; after that, slide.
                if (bar.value.isNaN()) bar.snapTo(t) else bar.animateTo(t, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f))
            }
            val barX = bar.value
            Box(Modifier.align(Alignment.Center)) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    listOf(FeedMode.FOLLOWING to "Following", FeedMode.FOR_YOU to "For You", FeedMode.LATEST to "Latest").forEach { (m, label) ->
                        // Measured on this wrapper, whose parent is the row the bar slides along (the
                        // tab itself sits at 0 inside it, which put every tab's centre in the same place).
                        Box(Modifier.onGloballyPositioned { c -> centers[m] = c.positionInParent().x + c.size.width / 2f }) {
                            ModeTab(
                                label,
                                selected = m == mode,
                                // Hold For You: choose its mood.
                                onLongClick = if (m == FeedMode.FOR_YOU && onMood != null) ({ moodOpen = true }) else null,
                            ) { onMode(m) }
                            if (m == FeedMode.FOR_YOU && onMood != null) {
                                androidx.compose.material3.DropdownMenu(expanded = moodOpen, onDismissRequest = { moodOpen = false }) {
                                    Text(
                                        "FOR YOU FEELS…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    )
                                    if (onAskSteer != null) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("Tell it what you want…", fontWeight = FontWeight.Bold)
                                                    Text("\"more redheads, less cosplay\"", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Accent) },
                                            onClick = { moodOpen = false; onAskSteer() },
                                        )
                                    }
                                    if (onThisOrThat != null) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("This or that", fontWeight = FontWeight.Bold)
                                                    Text("Ten quick picks that teach it a lot", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Filled.ThumbsUpDown, contentDescription = null, tint = Accent) },
                                            onClick = { moodOpen = false; onThisOrThat() },
                                        )
                                    }
                                    FeedViewModel.Mood.entries.forEach { md ->
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(md.label, fontWeight = if (md == mood) FontWeight.Bold else FontWeight.Normal)
                                                    Text(md.note, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                                                }
                                            },
                                            leadingIcon = { if (md == mood) Icon(Icons.Filled.Check, contentDescription = null, tint = Accent) else Spacer(Modifier.size(24.dp)) },
                                            onClick = { moodOpen = false; onMood(md) },
                                        )
                                    }
                                    if (onShowing != null) {
                                        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                        Text(
                                            "SHOWING",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                        )
                                        FeedViewModel.Showing.entries.forEach { w ->
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { Text(w.label, fontWeight = if (w == showing) FontWeight.Bold else FontWeight.Normal) },
                                                leadingIcon = { if (w == showing) Icon(Icons.Filled.Check, contentDescription = null, tint = Accent) else Spacer(Modifier.size(24.dp)) },
                                                onClick = { moodOpen = false; onShowing(w) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (!barX.isNaN()) Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 7.dp)
                        .graphicsLayer { translationX = bar.value - 11.dp.toPx() }
                        .width(22.dp)
                        .height(3.dp)
                        .background(Color.White, RoundedCornerShape(2.dp)),
                )
            }
        } else if (title != null) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.shadowed(),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = if (onGrid != null) 120.dp else 64.dp),
            )
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = if (onSearch != null) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onSearch != null) {
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search", tint = Color.White) }
            }
            if (onGrid != null) {
                IconButton(onClick = onGrid) { Icon(Icons.Filled.GridView, "Show as a grid", tint = Color.White) }
            }
            if (followTag != null) FollowPill(following = followTag.second, onClick = onFollowTag)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val tint by androidx.compose.animation.animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.6f), label = "modeTint")
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 17.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.titleSmall.shadowed(),
        )
    }
}

@Composable
private fun FollowPill(following: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    // Following: a quick pop and a tick, then it settles into the quiet outlined style.
    val pop = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(following) }
    LaunchedEffect(following) {
        if (following && !previous) {
            pop.animateTo(1.12f, tween(100))
            pop.animateTo(1f, spring(dampingRatio = 0.45f))
        }
        previous = following
    }
    Row(
        modifier
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(shape)
            .then(if (following) Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), shape) else Modifier.background(dev.pfeed.ui.theme.BrandGradient))
            .clickable(onClick = onClick)
            .animateContentSize()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (following) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            if (following) "Following" else "Follow",
            color = Color.White.copy(alpha = if (following) 0.85f else 1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
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
    onEnded: () -> Unit = {},
) {
    var paused by remember(item.key) { mutableStateOf(!vm.autoplayAllowed()) }
    val haptic = LocalHapticFeedback.current
    var heartAt by remember { mutableStateOf<Offset?>(null) }
    var loveTick by remember(item.key) { mutableIntStateOf(0) }
    var heartTick by remember { mutableIntStateOf(0) }
    var player by remember(item.key) { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    // Loop a part: A is set from the hold menu (where you are), B with a tap on the pill; then it
    // plays A→B over and over until you clear it.
    var loopA by remember(item.key) { mutableStateOf<Long?>(null) }
    var loopB by remember(item.key) { mutableStateOf<Long?>(null) }
    val loopRequest by LoopPart.request.collectAsStateWithLifecycle()
    LaunchedEffect(loopRequest, player) {
        if (loopRequest == item.key && player != null) {
            loopA = player?.currentPosition
            loopB = null
            LoopPart.request.value = null
        }
    }
    LaunchedEffect(loopA, loopB, player) {
        val a = loopA ?: return@LaunchedEffect
        val b = loopB ?: return@LaunchedEffect
        val p = player ?: return@LaunchedEffect
        while (true) {
            val at = p.currentPosition
            if (at >= b || at < a - LOOP_SLACK_MS) p.seekTo(a)
            kotlinx.coroutines.delay(LOOP_TICK_MS)
        }
    }
    var speeding by remember(item.key) { mutableStateOf(false) }
    var galleryTurn by remember(item.key) { mutableStateOf<Pair<Int, Long>?>(null) }
    // Sideways swipe in progress (for the hints at the edges), and saves made by swiping right.
    var swipeDx by remember(item.key) { mutableFloatStateOf(0f) }
    var saveFlash by remember(item.key) { mutableIntStateOf(0) }
    // Brightness or volume being slid at an edge (full screen): which, and how much (0..1).
    var edgeSlide by remember(item.key) { mutableStateOf<Pair<Int, Float>?>(null) }
    val context = LocalContext.current
    val savedNow by androidx.compose.runtime.rememberUpdatedState(saved)
    /** Which edge was just double-tapped to skip (−1 back, 1 ahead), and when: shows a brief "10 s" badge. */
    var seekFlash by remember(item.key) { mutableStateOf<Pair<Int, Long>?>(null) }
    // Seconds skipped by double-taps in a row on the same edge (10, 20, 30…), as in video players.
    var seekRun by remember(item.key) { mutableIntStateOf(0) }
    // What the post really is once its file is known: a board/Reddit post listed as an image can turn
    // out to be an MP4/WebM, which must play as a video (drawn as an image it froze on one frame).
    val stream by produceState<dev.pfeed.domain.model.StreamInfo?>(initialValue = null, item.key) { value = vm.resolve(item) }
    val type = remember(item.key, stream) { Display.effectiveType(item, stream) }
    val isVideo = type == MediaType.VIDEO

    // Hold the right side of a video to play at 2×; release to go back to 1×.
    // Holding the right side doubles whatever speed is picked in the menu (1× → 2×, 1.5× → 3×).
    val chosenSpeed by PlaybackSpeed.value.collectAsStateWithLifecycle()
    LaunchedEffect(player, speeding, chosenSpeed) { player?.setPlaybackSpeed(if (speeding) (chosenSpeed * 2f).coerceAtMost(3f) else chosenSpeed) }

    // Hands-free: a picture stays up a few seconds, then the feed moves on (tap pauses the countdown).
    val handsFree by HandsFree.on.collectAsStateWithLifecycle()
    val imageCountdown = handsFree && playing && !paused && !item.isGallery && (type == MediaType.IMAGE || type == MediaType.GIF)
    val countdown = remember(item.key) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(imageCountdown) {
        if (!imageCountdown) { countdown.snapTo(0f); return@LaunchedEffect }
        countdown.snapTo(0f)
        countdown.animateTo(1f, androidx.compose.animation.core.tween(HandsFree.IMAGE_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing))
        onEnded()
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.key, landscape, isVideo) {
                detectTapGestures(
                    // A comic: tap the left or right edge to turn the page (as in a reader app).
                    onTap = { offset ->
                        val edge = when {
                            offset.x < size.width * EDGE_TAP -> -1
                            offset.x > size.width * (1 - EDGE_TAP) -> 1
                            else -> 0
                        }
                        // Straight after a double-tap skip, more taps on that side keep skipping.
                        val run = seekFlash
                        val p = player
                        val side = if (offset.x < size.width * 0.3f) -1 else if (offset.x > size.width * 0.7f) 1 else 0
                        if (run != null && p != null && side == run.first && System.currentTimeMillis() - run.second < SEEK_RUN_MS) {
                            p.seekTo((p.currentPosition + side * SEEK_STEP_MS).coerceIn(0L, (p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)))
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            seekRun += (SEEK_STEP_MS / 1000).toInt()
                            seekFlash = side to System.currentTimeMillis()
                            return@detectTapGestures
                        }
                        if (item.isGallery && edge != 0 && !landscape) galleryTurn = edge to System.nanoTime() else paused = !paused
                    },
                    onDoubleTap = { offset ->
                        // A long video (or any video sideways): double-tap the left or right edge to skip
                        // 10 s, as in video players; again and again to skip further. The middle is a like.
                        val p = player
                        val edge = when {
                            offset.x < size.width * 0.3f -> -1
                            offset.x > size.width * 0.7f -> 1
                            else -> 0
                        }
                        val long = (p?.duration ?: 0L) >= LONG_VIDEO_MS || (item.durationSec ?: 0) * 1000L >= LONG_VIDEO_MS
                        if ((landscape || long) && isVideo && p != null && edge != 0) {
                            val to = (p.currentPosition + edge * SEEK_STEP_MS).coerceIn(0L, (p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
                            p.seekTo(to)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            val now = System.currentTimeMillis()
                            val last = seekFlash
                            seekRun = if (last != null && last.first == edge && now - last.second < SEEK_RUN_MS) seekRun + (SEEK_STEP_MS / 1000).toInt() else (SEEK_STEP_MS / 1000).toInt()
                            seekFlash = edge to now
                            return@detectTapGestures
                        }
                        heartAt = offset
                        heartTick++
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.like(item)
                    },
                )
            }
            // Sideways (full screen), as in video players: slide up or down along the left edge for
            // brightness, along the right edge for volume. The middle still moves between posts.
            .pointerInput(item.key, landscape) {
                if (!landscape) return@pointerInput
                val activity = context.findActivity()
                val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val edge = when {
                        down.position.x < size.width * EDGE_SLIDE -> -1
                        down.position.x > size.width * (1 - EDGE_SLIDE) -> 1
                        else -> 0
                    }
                    if (edge == 0) return@awaitEachGesture
                    val maxVol = audio?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15
                    var value = if (edge < 0) {
                        activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                    } else (audio?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0).toFloat() / maxVol
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        val dy = c.position.y - c.previousPosition.y
                        if (dy != 0f) {
                            value = (value - dy / (size.height * 0.8f)).coerceIn(0f, 1f)
                            if (edge < 0) activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = value.coerceAtLeast(0.01f) } }
                            else audio?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (value * maxVol).roundToInt(), 0)
                            edgeSlide = edge to value
                            c.consume()
                        }
                    }
                    edgeSlide = null
                }
            }
            // Sideways swipes (not on a comic, which turns pages that way):
            //  · left: their page, everything by them across every source, as in other short-video apps;
            //  · right: save it, with a bookmark that flies in.
            // Child gestures that use the drag (zoomed pan, the scrub bar) consume it first.
            .pointerInput(item.key, landscape, type) {
                if (landscape || item.isGallery || type == MediaType.EMBED) return@pointerInput
                val creator = Display.creator(item)
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        val far = SWIPE_TO_CREATOR_DP.dp.toPx()
                        when {
                            dx < -far && creator != null -> {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOpenTag("@$creator")
                            }
                            dx > far -> {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (!savedNow) vm.toggleSave(item)
                                saveFlash++
                            }
                        }
                        swipeDx = 0f
                    },
                    onDragCancel = { swipeDx = 0f },
                ) { change, amount -> dx += amount; swipeDx = dx; change.consume() }
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
        if (!landscape && type != MediaType.EMBED && !item.isGallery) AmbientBackdrop(item)
        when (type) {
            MediaType.VIDEO -> VideoPage(item, play = playing && !paused, muted = muted, vm = vm, onPlayer = { player = it }, onEnded = onEnded)
            MediaType.IMAGE, MediaType.GIF ->
                if (item.isGallery) GalleryPage(item, vm, active = playing, autoFlip = handsFree && playing && !paused, onEnded = onEnded, turn = galleryTurn)
                else ImagePage(item, vm)
            // A site's own player keeps its controls clear of the tab bar.
            MediaType.EMBED -> Box(Modifier.fillMaxSize().padding(bottom = if (landscape) 0.dp else dev.pfeed.ui.LocalBottomBarInset.current)) { EmbedPage(item, active = playing, vm = vm) }
        }
        dev.pfeed.ui.common.MediaMask()

        // Scrims: darken just the edges so the top bar, caption and buttons read on any image.
        val clean by CleanScreen.on.collectAsStateWithLifecycle()
        val pipNow by Pip.active.collectAsStateWithLifecycle()
        val bare = landscape || clean || pipNow
        val barInset = if (bare) 0.dp else dev.pfeed.ui.LocalBottomBarInset.current
        if (!bare) Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(140.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
        )
        if (!bare) Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(320.dp + barInset)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )
        // Everything drawn on the post sits above the tab bar, which the post itself runs under.
        Box(Modifier.fillMaxSize().padding(bottom = barInset)) {
        if (imageCountdown) Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(countdown.value).height(2.dp)
                .background(Color.White.copy(alpha = 0.7f)),
        )

        if (paused && isVideo && !speeding) {
            Box(
                Modifier.align(Alignment.Center).size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Paused", tint = Color.White, modifier = Modifier.size(56.dp))
            }
            // Paused on a longer video: where you are in it, under the play button.
            val p = player
            if (p != null && p.duration >= 60_000) {
                Text(
                    "${formatTime(p.currentPosition)} / ${formatTime(p.duration)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.Center).padding(top = 132.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        seekFlash?.let { (edge, at) ->
            LaunchedEffect(at) { kotlinx.coroutines.delay(SEEK_RUN_MS); if (seekFlash?.second == at) seekFlash = null }
            // A soft half-moon swells from that edge, with the running total in it.
            val swell = remember(at) { Animatable(0f) }
            LaunchedEffect(at) { swell.snapTo(0f); swell.animateTo(1f, tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val r = size.height * (0.55f + 0.1f * swell.value)
                val cx = if (edge < 0) -r * 0.55f else size.width + r * 0.55f
                drawCircle(Color.White.copy(alpha = 0.10f + 0.06f * (1f - swell.value)), radius = r, center = androidx.compose.ui.geometry.Offset(cx, size.height / 2))
            }
            Column(
                Modifier.align(if (edge < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 30.dp)
                    .graphicsLayer { val k = 0.85f + 0.15f * swell.value; scaleX = k; scaleY = k },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    if (edge < 0) Icons.Filled.FastRewind else Icons.Filled.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
                Text("$seekRun s", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (speeding) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp),
            ) {
                Text("${PlaybackSpeed.label((chosenSpeed * 2f).coerceAtMost(3f))}  ▶▶", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
        // A speed other than 1× is picked: a small reminder on videos.
        if (isVideo && chosenSpeed != 1f && !speeding && !landscape) {
            Text(
                PlaybackSpeed.label(chosenSpeed),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 60.dp, end = 14.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        HeartBurst(heartAt, heartTick)
        if (loveTick > 0) Box(Modifier.align(Alignment.Center)) { LoveBurst(loveTick) }
        edgeSlide?.let { (edge, v) ->
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (edge < 0) Icons.Filled.Brightness6 else Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(v).background(Color.White))
                }
            }
        }
        // The loop being marked, or playing.
        val a = loopA
        if (a != null && !landscape) {
            val b = loopB
            fun t(ms: Long) = dev.pfeed.ui.common.formatDuration((ms / 1000).toInt())
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 100.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (b == null) dev.pfeed.ui.theme.BrandGradient else androidx.compose.ui.graphics.SolidColor(Color.Black.copy(alpha = 0.55f)))
                    .clickable {
                        if (b == null) {
                            val now = player?.currentPosition ?: return@clickable
                            if (now > a + LOOP_MIN_MS) { loopB = now; haptic.performHapticFeedback(HapticFeedbackType.LongPress); player?.seekTo(a) }
                            else vm.offer("Let it play a little further, then tap", "Cancel") { loopA = null }
                        } else { loopA = null; loopB = null }
                    }
                    .padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Repeat, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (b == null) "From ${t(a)} · tap to end the loop here" else "Looping ${t(a)}–${t(b)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (b != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Close, contentDescription = "Stop looping", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                }
            }
        }
        // While swiping sideways: what letting go will do, growing at the edge you're pulling toward.
        if (swipeDx != 0f && !landscape) {
            val far = with(androidx.compose.ui.platform.LocalDensity.current) { SWIPE_TO_CREATOR_DP.dp.toPx() }
            val pull = (kotlin.math.abs(swipeDx) / far).coerceIn(0f, 1f)
            val right = swipeDx > 0
            Row(
                Modifier.align(if (right) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 18.dp)
                    .graphicsLayer { alpha = pull; scaleX = 0.7f + 0.3f * pull; scaleY = 0.7f + 0.3f * pull }
                    .clip(RoundedCornerShape(50)).background(if (pull >= 1f) Accent else Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (right) Icons.Filled.Bookmark else Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (right) (if (saved) "Saved" else "Save") else "Their page", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
        // Saved by swiping right: a big bookmark pops in the middle and fades.
        if (saveFlash > 0) {
            val pop = remember { Animatable(0f) }
            LaunchedEffect(saveFlash) { pop.snapTo(0f); pop.animateTo(1f, tween(650)) }
            val v = pop.value
            Icon(
                Icons.Filled.Bookmark,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(96.dp)
                    .graphicsLayer {
                        val grow = if (v < 0.35f) v / 0.35f else 1f
                        scaleX = 0.6f + 0.5f * grow; scaleY = 0.6f + 0.5f * grow
                        alpha = if (v < 0.6f) 1f else (1f - v) / 0.4f
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(dev.pfeed.ui.theme.BrandGradient, blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                    },
            )
        }
        if (landscape) {
            IconButton(
                onClick = { onLandscape(false) },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
            ) {
                Icon(Icons.Filled.FullscreenExit, contentDescription = "Exit landscape", tint = Color.White)
            }
        } else if (!clean && !pipNow) Overlay(
            item = item,
            sourceLabel = Display.source(item, vm.sourceName(item.sourceId)),
            creator = remember(item.key) { Display.creator(item) },
            tags = remember(item.key) { Display.tags(item) },
            likedTags = vm.likedTags.collectAsStateWithLifecycle().value,
            liked = liked,
            saved = saved,
            muted = muted,
            followingCreator = followingCreator,
            isVideo = isVideo,
            // Turning sideways is for video; a still or a comic page reads fine upright.
            canFullscreen = isVideo || type == MediaType.EMBED,
            onLike = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.toggleLike(item) },
            onSuperLike = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.love(item)
                loveTick++
            },
            onSave = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.toggleSave(item) },
            onMute = vm::toggleMute,
            onFollow = { vm.toggleFollowCreator(item) },
            onFullscreen = { onLandscape(true) },
            onTag = onOpenTag,
            onMenu = onMenu,
        )
        if (isVideo && !clean && !pipNow) {
            ScrubBar(
                player = player,
                modifier = Modifier.align(Alignment.BottomCenter),
                // Scrubbing back to see something again is a rewatch: a strong signal for For You.
                onScrubbedBack = { vm.statsFor(item.key).replays++ },
            )
        }
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
    /** Tags you like (normalised): lit pink on the post, so you see why it's here at a glance. */
    likedTags: Set<String> = emptySet(),
    liked: Boolean,
    saved: Boolean,
    muted: Boolean,
    followingCreator: Boolean,
    isVideo: Boolean,
    canFullscreen: Boolean,
    onLike: () -> Unit,
    /** Hold the heart: love it (like, save to Favourites, a strong "more of this"). */
    onSuperLike: (() -> Unit)? = null,
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
                // When it was posted, if the source said (useful in Latest).
                remember(item.key) { Display.age(item) }?.let { age ->
                    Text(" · $age", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            if (creator != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    dev.pfeed.ui.common.InitialAvatar(creator, size = 30.dp, modifier = Modifier.clickable { onTag("@" + (item.creator ?: creator)) })
                    Spacer(Modifier.width(8.dp))
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
                // Two lines; tap for the rest (and again to fold it away).
                var expanded by remember(item.key) { mutableStateOf(false) }
                var cut by remember(item.key) { mutableStateOf(false) }
                Text(
                    it,
                    color = Color.White,
                    maxLines = if (expanded) 10 else 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.shadowed(),
                    onTextLayout = { r -> if (!expanded) cut = r.hasVisualOverflow },
                    modifier = Modifier
                        .animateContentSize()
                        .then(if (cut || expanded) Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { expanded = !expanded } else Modifier),
                )
            }
            if (tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Tags you like first, lit in the brand colour.
                    val ordered = remember(tags, likedTags) { tags.sortedByDescending { dev.pfeed.domain.reco.Features.normalizeTag(it.second) in likedTags } }
                    ordered.forEach { (label, raw) ->
                        val yours = dev.pfeed.domain.reco.Features.normalizeTag(raw) in likedTags
                        Text(
                            "#$label",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (yours) Accent.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.4f))
                                .clickable { onTag(raw) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LikeButton(liked = liked, onLike = onLike, onSuperLike = onSuperLike)
            SaveButton(saved = saved, onSave = onSave)
            if (isVideo) {
                RailButton(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (muted) "Muted" else "Sound", onMute)
            }
            if (canFullscreen) RailButton(Icons.Filled.Fullscreen, "Rotate", onFullscreen)
            RailButton(Icons.Filled.MoreHoriz, "More", onMenu)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun RailButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    iconScale: Float = 1f,
    /** On: the icon is painted in the brand gradient (a liked heart, a saved bookmark). */
    branded: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    // A little give when pressed, like a physical button.
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val squeeze by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.88f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 900f), label = "railPress")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(interactionSource = press, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(2.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.24f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(28.dp)
                    .graphicsLayer {
                        scaleX = iconScale; scaleY = iconScale
                        // Offscreen so the gradient below only paints where the icon is.
                        if (branded) compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    }
                    .then(
                        if (branded) Modifier.drawWithContent {
                            drawContent()
                            drawRect(dev.pfeed.ui.theme.BrandGradient, blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop)
                        } else Modifier,
                    ),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall.shadowed())
    }
}

/** Save with the same little pop as Like when it turns on. */
/** The tags that show up most alongside [pageTag] in [items], to hop sideways to. */
private fun relatedTags(items: List<MediaItem>, pageTag: String): List<Pair<String, String>> {
    val self = dev.pfeed.domain.reco.Features.normalizeTag(pageTag)
    return items.take(60).flatMap { Display.tags(it, max = 12) }
        .groupBy { dev.pfeed.domain.reco.Features.normalizeTag(it.second) }
        .filterKeys { it != self }
        .entries.sortedByDescending { it.value.size }
        .take(8)
        .map { it.value.first() }
}

/** The top of a tag's page: the tag big, how much there is, follow it, and where to go next. */
@Composable
private fun TagHeader(
    tag: String,
    items: List<MediaItem>,
    loading: Boolean,
    following: Boolean,
    related: List<Pair<String, String>>,
    onFollow: () -> Unit,
    onOpenTag: (String) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val sites = remember(items.size) { items.map { Display.source(it, it.sourceId) }.distinct().size }
    val videos = remember(items.size) { items.count { it.type == MediaType.VIDEO } }
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            // A big gradient tile with the hash in it, where a creator has their avatar.
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(dev.pfeed.ui.theme.BrandGradient),
                contentAlignment = Alignment.Center,
            ) {
                Text("#", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tag.replace('_', ' '),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (items.isEmpty()) (if (loading) "Looking on every site…" else "Nothing here right now")
                    else "${items.size}${if (loading) "+" else ""} posts · $videos videos · $sites ${if (sites == 1) "site" else "sites"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (following) {
                androidx.compose.material3.OutlinedButton(onClick = onFollow, shape = RoundedCornerShape(50)) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Following")
                }
            } else dev.pfeed.ui.common.BrandButton("Follow", onClick = onFollow, icon = Icons.Filled.Add, compact = true)
            if (items.isNotEmpty()) {
                androidx.compose.material3.FilledTonalButton(onClick = onPlay, shape = RoundedCornerShape(50)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play all")
                }
                IconButton(onClick = onShuffle) { Icon(Icons.Filled.Shuffle, "Shuffle", tint = Color.White.copy(alpha = 0.8f)) }
            }
        }
        if (related.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                related.forEach { (label, raw) ->
                    Text(
                        "#$label",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
                            .clickable { onOpenTag(raw) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        if (items.isEmpty() && loading) {
            Spacer(Modifier.height(48.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { dev.pfeed.ui.common.BrandSpinner() }
        }
    }
}

/** The top of a creator's profile: who, how much, from where, and the two things to do. */
@Composable
private fun CreatorHeader(
    name: String,
    items: List<MediaItem>,
    loading: Boolean,
    following: Boolean,
    onFollow: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    similar: List<String> = emptyList(),
    onOpenCreator: (String) -> Unit = {},
) {
    val sites = remember(items.size) { items.map { Display.source(it, it.sourceId) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val videos = remember(items.size) { items.count { it.type == MediaType.VIDEO } }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // A gradient ring around their initial, like a story ring.
        Box(
            Modifier.size(96.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dev.pfeed.ui.theme.BrandGradient).padding(3.dp)
                .clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.background).padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            dev.pfeed.ui.common.InitialAvatar(name, 84.dp)
        }
        Spacer(Modifier.height(12.dp))
        Text("@$name", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        // Counts, as on any profile.
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            listOf(
                (if (loading && items.isEmpty()) "…" else "${items.size}${if (loading) "+" else ""}") to "posts",
                "$videos" to "videos",
                "${sites.size}" to if (sites.size == 1) "site" else "sites",
            ).forEach { (n, what) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(what, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f))
                }
            }
        }
        if (sites.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "On " + sites.take(3).joinToString(", ") + if (sites.size > 3) " and ${sites.size - 3} more" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (following) {
                androidx.compose.material3.OutlinedButton(onClick = onFollow, shape = RoundedCornerShape(50)) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Following")
                }
            } else dev.pfeed.ui.common.BrandButton("Follow", onClick = onFollow, icon = Icons.Filled.Add, compact = true)
            if (items.isNotEmpty()) {
                androidx.compose.material3.FilledTonalButton(onClick = onPlay, shape = RoundedCornerShape(50)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play all")
                }
                IconButton(onClick = onShuffle) { Icon(Icons.Filled.Shuffle, "Shuffle", tint = Color.White.copy(alpha = 0.8f)) }
            }
        }
        // Who else makes this kind of thing (by where their posts sit on your map), tap to open.
        if (similar.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                "SIMILAR CREATORS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                for (c in similar) item(key = c) {
                    Column(
                        Modifier.width(64.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpenCreator(c) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        dev.pfeed.ui.common.InitialAvatar(c.substringBefore('@'), 52.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(c.substringBefore('@'), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (items.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            if (loading) dev.pfeed.ui.common.BrandSpinner()
            else Text("Nothing by them right now", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun SaveButton(saved: Boolean, onSave: () -> Unit) {
    val pop = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) {
        if (saved && !previous) {
            pop.animateTo(1.3f, tween(110))
            pop.animateTo(1f, spring(dampingRatio = 0.45f))
        }
        previous = saved
    }
    RailButton(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, if (saved) "Saved" else "Save", onSave, iconScale = pop.value, branded = saved)
}

/** Like with a little pop when it turns on (not when the page appears already liked). */
@Composable
private fun LikeButton(liked: Boolean, onLike: () -> Unit, onSuperLike: (() -> Unit)? = null) {
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
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
        tint = Color.White,
        iconScale = pop.value,
        branded = liked,
        onLongClick = onSuperLike?.let { act -> { act(); scope.launch { pop.animateTo(1.8f, tween(140)); pop.animateTo(1f, spring(dampingRatio = 0.35f)) } } },
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
        // Sized to its text, never a fixed column: it reads cleanly on any width, and sits clear of the rail.
        Column(
            Modifier.padding(start = 20.dp, end = 84.dp).widthIn(max = 300.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE6141418))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Quick gestures", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            listOf(
                Triple(Icons.Filled.SwipeUp, "Swipe up", "Next post"),
                Triple(Icons.Filled.SwipeLeft, "Swipe left", "Everything by them"),
                Triple(Icons.Filled.SwipeRight, "Swipe right", "Save it"),
                Triple(Icons.Filled.Favorite, "Double-tap", "Like"),
                Triple(Icons.Filled.FastForward, "Hold the right side", "Play at 2×"),
                Triple(Icons.Filled.TouchApp, "Press and hold", "More options"),
                Triple(Icons.Filled.PanTool, "Three fingers", "Leave instantly"),
            ).forEach { (icon, gesture, what) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(Accent.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(gesture, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(what, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}


/** How far left a swipe must go to open the creator's page. */
private const val FEEL_PULSE_MS = 45L
private const val EDGE_SLIDE = 0.18f
private const val STRETCH_RESET_MS = 10 * 60_000L
private const val PINCH_TO_WALL = 0.6f
private const val TWO_TAP_MS = 260L
private const val TWO_TAP_SLOP = 24f
private const val LOOP_SLACK_MS = 1_500L
private const val LOOP_TICK_MS = 80L
private const val LOOP_MIN_MS = 1_000L
private const val LONG_VIDEO_MS = 90_000L
private const val SEEK_RUN_MS = 800L
private const val SWIPE_TO_CREATOR_DP = 90
/** How much of each side of a comic page turns the page when tapped. */
private const val EDGE_TAP = 0.22f
private const val SEEK_STEP_MS = 10_000L

/** Turned off by the instrumented tests, which need the feed itself on screen. */
object FirstRun {
    @Volatile var askInterests = true
    /** Tests: show the picker even though something has been learned. */
    @Volatile var forceInterests = false
}

/**
 * Shown once, on the very first launch: tap a few topics and For You starts from them instead of
 * from nothing. Skipping is fine; it learns from watching either way.
 */
@Composable
private fun InterestPicker(onDone: (List<String>) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui_hints", android.content.Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(FirstRun.askInterests && !prefs.getBoolean("interests_asked", false)) }
    if (!show) return
    fun finish(topics: List<String>) {
        prefs.edit().putBoolean("interests_asked", true).apply()
        show = false
        onDone(topics)
    }
    dev.pfeed.ui.common.TopicPicker(
        title = "What are you into?",
        subtitle = "Pick a few and For You starts from them. It keeps learning from what you watch.",
        onDone = ::finish,
        onSkip = { finish(emptyList()) },
    )
}

/**
 * The main hue of a picture (for the Chameleon colour): a tiny copy, each pixel's hue weighted by
 * how colourful and bright it is, so skin and backgrounds pull more than grey. Null if it's all grey.
 */
private suspend fun dominantHue(context: android.content.Context, url: String): Float? {
    val result = coil.Coil.imageLoader(context).execute(
        coil.request.ImageRequest.Builder(context).data(url).size(24, 24).allowHardware(false).build(),
    )
    val bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
    var x = 0.0; var y = 0.0; var weight = 0.0
    val hsv = FloatArray(3)
    for (i in 0 until bmp.width) for (j in 0 until bmp.height) {
        android.graphics.Color.colorToHSV(bmp.getPixel(i, j), hsv)
        val w = (hsv[1] * hsv[2]).toDouble()
        if (w < 0.08) continue
        val rad = Math.toRadians(hsv[0].toDouble())
        x += kotlin.math.cos(rad) * w; y += kotlin.math.sin(rad) * w; weight += w
    }
    if (weight < 1.0) return null
    return ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360).toFloat()
}
