package dev.pfeed.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.domain.model.FeedMode
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.ui.Routes
import dev.pfeed.ui.theme.HeartRed

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

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !locked

    val pagerState = rememberPagerState(initialPage = vm.startIndex) { items.size }
    val snackbar = remember { SnackbarHostState() }
    var menuFor by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(active) { vm.onVisibilityChanged(active) }
    LaunchedEffect(pagerState, items.isNotEmpty()) {
        snapshotFlow { pagerState.settledPage }.collect { vm.onPageSettled(it) }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

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
                )
            }
        } else {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                else -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(hint ?: "Nothing here yet.", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!vm.isViewer) Button(onClick = vm::refresh) { Text("Refresh") }
                        TextButton(onClick = { nav.navigate(Routes.SOURCES) }) { Text("Sources") }
                    }
                }
            }
        }

        TopBar(
            title = when {
                vm.tag != null -> "#${vm.tag}"
                vm.isViewer -> null
                else -> null
            },
            showModes = vm.tag == null && !vm.isViewer,
            mode = mode,
            onMode = vm::setMode,
            onBack = if (vm.tag != null || vm.isViewer) ({ nav.popBackStack(); Unit }) else null,
            followTag = vm.tag?.let { t -> (t to (t.lowercase() in follows.tags)) },
            onFollowTag = { vm.tag?.let(vm::toggleFollowTag) },
        )

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    menuFor?.let { item ->
        ItemMenuSheet(
            item = item,
            saved = item.key in saved,
            followingCreator = vm.isFollowingCreator(item, follows),
            vm = vm,
            onDismiss = { menuFor = null },
        )
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        }
        if (title != null) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (showModes) {
            listOf(FeedMode.FOLLOWING to "Following", FeedMode.FOR_YOU to "For You", FeedMode.LATEST to "Latest").forEach { (m, label) ->
                TextButton(onClick = { onMode(m) }) {
                    Text(
                        label,
                        color = if (m == mode) Color.White else Color.White.copy(alpha = 0.55f),
                        fontWeight = if (m == mode) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
        if (followTag != null) {
            TextButton(onClick = onFollowTag) { Text(if (followTag.second) "Following" else "Follow", color = Color.White) }
        }
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
) {
    var paused by remember(item.key) { mutableStateOf(!vm.autoplayAllowed()) }
    var heartAt by remember { mutableStateOf<Offset?>(null) }
    var heartTick by remember { mutableIntStateOf(0) }
    var player by remember(item.key) { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(item.key) {
                detectTapGestures(
                    onTap = { paused = !paused },
                    onDoubleTap = { offset ->
                        heartAt = offset
                        heartTick++
                        vm.like(item)
                    },
                    onLongPress = { onMenu() },
                )
            },
    ) {
        when (item.type) {
            MediaType.VIDEO -> VideoPage(item, play = playing && !paused, muted = muted, vm = vm, onPlayer = { player = it })
            MediaType.IMAGE, MediaType.GIF -> ImagePage(item, vm)
            MediaType.EMBED -> EmbedPage(item, active = playing, vm = vm)
        }
        if (paused && item.type == MediaType.VIDEO) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Paused",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center).size(72.dp),
            )
        }
        HeartBurst(heartAt, heartTick)
        Overlay(
            item = item,
            sourceName = vm.sourceName(item.sourceId),
            liked = liked,
            saved = saved,
            muted = muted,
            followingCreator = followingCreator,
            onLike = { vm.toggleLike(item) },
            onSave = { vm.toggleSave(item) },
            onMute = vm::toggleMute,
            onFollow = { vm.toggleFollowCreator(item) },
            onTag = onOpenTag,
            onMenu = onMenu,
        )
        if (item.type == MediaType.VIDEO) {
            ScrubBar(
                player = player,
                modifier = Modifier.align(Alignment.BottomCenter),
                // Scrubbing back to see something again is a rewatch: a strong signal for For You.
                onScrubbedBack = { vm.statsFor(item.key).replays++ },
            )
        }
    }
}

@Composable
private fun Overlay(
    item: MediaItem,
    sourceName: String,
    liked: Boolean,
    saved: Boolean,
    muted: Boolean,
    followingCreator: Boolean,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onMute: () -> Unit,
    onFollow: () -> Unit,
    onTag: (String) -> Unit,
    onMenu: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onLike) {
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (liked) "Unlike" else "Like",
                    tint = if (liked) HeartRed else Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onSave) {
                Icon(
                    if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (saved) "Unsave" else "Save",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            if (item.type == MediaType.VIDEO) {
                IconButton(onClick = onMute) {
                    Icon(
                        if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (muted) "Unmute" else "Mute",
                        tint = Color.White,
                    )
                }
            }
            IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.8f)
                .padding(start = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color.White.copy(alpha = 0.18f), shape = RoundedCornerShape(4.dp)) {
                    Text(
                        sourceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                item.creator?.let { creator ->
                    Spacer(Modifier.width(8.dp))
                    Text("@$creator", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (followingCreator) "Following" else "Follow",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .background(if (followingCreator) Color.Transparent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .clickable(onClick = onFollow)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            item.title?.let {
                Text(it, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            if (item.tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.tags.take(12).forEach { tag ->
                        Text(
                            "#$tag",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                .clickable { onTag(tag) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
