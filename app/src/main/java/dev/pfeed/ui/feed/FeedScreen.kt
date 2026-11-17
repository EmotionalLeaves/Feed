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
    val active = (lifecycleState.isAtLeast(Lifecycle.State.RESUMED) || (inPip && lifecycleState.isAtLeast(Lifecycle.State.STARTED))) && !locked

    val pagerState = rememberPagerState(initialPage = vm.startIndex) { items.size }
    val pagerScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }
    // A creator's page opens as their profile: a header and a grid of everything by them.
    val creatorPage = vm.tag?.startsWith("@") == true
    var gridView by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(creatorPage) }
    var wallMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

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
            if (snackbar.showSnackbar(text, actionLabel = action) == androidx.compose.material3.SnackbarResult.ActionPerformed) run()
        }
    }
    LaunchedEffect(Unit) {
        vm.undoables.collect { (text, undo) ->
            if (snackbar.showSnackbar(text, actionLabel = "Undo") == androidx.compose.material3.SnackbarResult.ActionPerformed) undo()
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
                modifier = Modifier.fillMaxSize(),
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

        // The page as a grid: everything loaded so far (and more as you scroll); tap one to watch from there.
        if (gridView && !landscape) {
            // On a creator's profile the grid is the page itself, so back leaves it.
            BackHandler(enabled = !creatorPage) { gridView = false }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(bottom = dev.pfeed.ui.LocalBottomBarInset.current)) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (creatorPage) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else IconButton(onClick = { gridView = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the feed") }
                    Text(
                        if (creatorPage) "" else vm.tag?.let { "#" + it.replace('_', ' ') }.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (creatorPage) {
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

        // A tag page: the tags that show up most alongside it, to hop sideways to.
        val pageTag = vm.tag
        if (pageTag != null && !pageTag.startsWith("@") && !landscape && !gridView && items.size >= 6) {
            val related = remember(items.size, pageTag) {
                val self = dev.pfeed.domain.reco.Features.normalizeTag(pageTag)
                items.take(60).flatMap { Display.tags(it, max = 12) }
                    .groupBy { dev.pfeed.domain.reco.Features.normalizeTag(it.second) }
                    .filterKeys { it != self }
                    .entries.sortedByDescending { it.value.size }
                    .take(8)
                    .map { it.value.first() }
            }
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
                        ModeTab(
                            label,
                            selected = m == mode,
                            modifier = Modifier.onGloballyPositioned { c -> centers[m] = c.positionInParent().x + c.size.width / 2f },
                        ) { onMode(m) }
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
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint by androidx.compose.animation.animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.6f), label = "modeTint")
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 17.dp),
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
    var heartTick by remember { mutableIntStateOf(0) }
    var player by remember(item.key) { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var speeding by remember(item.key) { mutableStateOf(false) }
    var galleryTurn by remember(item.key) { mutableStateOf<Pair<Int, Long>?>(null) }
    // Sideways swipe in progress (for the hints at the edges), and saves made by swiping right.
    var swipeDx by remember(item.key) { mutableFloatStateOf(0f) }
    var saveFlash by remember(item.key) { mutableIntStateOf(0) }
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
            LikeButton(liked = liked, onLike = onLike)
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
private fun RailButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    iconScale: Float = 1f,
    /** On: the icon is painted in the brand gradient (a liked heart, a saved bookmark). */
    branded: Boolean = false,
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
            .clickable(interactionSource = press, indication = null, onClick = onClick)
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
        tint = Color.White,
        iconScale = pop.value,
        branded = liked,
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
