package dev.pfeed.ui

import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Home
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.pfeed.ui.theme.Accent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.pfeed.security.AppLockManager
import dev.pfeed.ui.feed.FeedScreen
import dev.pfeed.ui.library.LibraryScreen
import dev.pfeed.ui.lock.LockScreen
import dev.pfeed.ui.search.SearchScreen
import dev.pfeed.ui.settings.TasteScreen
import dev.pfeed.ui.settings.BlocklistScreen
import dev.pfeed.ui.settings.SettingsScreen
import dev.pfeed.ui.settings.SourcesScreen

object Routes {
    const val FEED = "feed"
    const val TAG = "tag/{tag}"
    const val VIEWER = "viewer/{token}"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val SOURCES = "settings/sources"
    const val BLOCKLIST = "settings/blocklist"
    const val TASTE = "settings/taste"
    const val NETWORK = "settings/network"

    fun tag(tag: String) = "tag/" + Uri.encode(tag)
    fun viewer(token: String) = "viewer/$token"
}

/** [icon] when selected, [outline] otherwise: the current tab reads at a glance, as in most modern apps. */
private data class Tab(val route: String, val label: String, val icon: ImageVector, val outline: ImageVector)

private val tabs = listOf(
    Tab(Routes.FEED, "Feed", Icons.Filled.Home, Icons.Outlined.Home),
    Tab(Routes.SEARCH, "Search", Icons.Filled.Search, Icons.Outlined.Search),
    Tab(Routes.LIBRARY, "Library", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AppRoot(lockManager: AppLockManager, onPanic: () -> Unit) {
    val locked by lockManager.locked.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var adult by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(AgeGate.confirmed(context)) }
    Box(Modifier.fillMaxSize().panicGesture(onPanic)) {
        // The app stays composed under the lock screen (so view-model state survives), but media pauses.
        // Behind the age gate it's the same: the feed loads, nothing plays.
        MainNav(locked = locked || !adult)
        if (!adult) {
            AgeGateScreen(onConfirm = { AgeGate.confirm(context); adult = true })
            return@Box
        }
        if (dev.pfeed.ui.common.WhatsNew.showing) dev.pfeed.ui.common.WhatsNewDialog { dev.pfeed.ui.common.WhatsNew.showing = false }
        // The lock screen sits outside the Scaffold, so give it the app's text colour itself
        // (without this its title drew in the default black, invisible on the dark background).
        // Someone typed wrong PINs while you were away: say so once you're in.
        val intrusions by lockManager.intrusions.collectAsStateWithLifecycle()
        if (!locked && intrusions.isNotEmpty()) {
            val fmt = androidx.compose.runtime.remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { lockManager.intrusionsSeen() },
                properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
                icon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.GppMaybe, contentDescription = null) },
                title = { androidx.compose.material3.Text(if (intrusions.size == 1) "1 wrong PIN while you were away" else "${intrusions.size} wrong PINs while you were away") },
                text = {
                    androidx.compose.material3.Text(
                        "Last one: " + fmt.format(java.util.Date(intrusions.last())) +
                            (if (intrusions.size > 1) "\nFirst one: " + fmt.format(java.util.Date(intrusions.first())) else "") +
                            "\n\nIf that wasn't you, someone tried to open the app.",
                    )
                },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { lockManager.intrusionsSeen() }) { androidx.compose.material3.Text("OK") } },
            )
        }
        if (locked) androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) { LockScreen(lockManager) }
    }
}

/** Three or more simultaneous fingers anywhere = panic. Observes on the Initial pass without consuming. */
private fun Modifier.panicGesture(onPanic: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.count { it.pressed }
                if (down > 0) {
                    val now = System.currentTimeMillis()
                    if (now - BottomBar.lastTouch > 500) BottomBar.lastTouch = now // a few updates a second at most
                }
                if (down >= 3 && !fired) {
                    fired = true
                    onPanic()
                }
                if (down == 0) fired = false
            }
        }
    },
)

@Composable
private fun MainNav(locked: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val currentTab = tabs.firstOrNull { route == it.route || route?.startsWith(it.route + "/") == true }
    // Feed screens are full-bleed: media runs behind the status bar, the bottom bar turns black.
    val immersive = route == Routes.FEED || route == Routes.TAG || route == Routes.VIEWER

    // Typing (search, a collection name): the keyboard has the bottom of the screen, not the bar.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val typing = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    val pip by dev.pfeed.ui.feed.Pip.active.collectAsStateWithLifecycle()
    val barVisible = currentTab != null && !BottomBar.hidden && !typing && !pip
    // Reading down a list tucks the bar away; any scroll back up (or a new screen) brings it back.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val barScroll = androidx.compose.runtime.remember(density) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                val full = with(density) { BAR_HEIGHT.toPx() * 1.6f }
                BottomBar.collapse = (BottomBar.collapse - available.y / full).coerceIn(0f, 1f)
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(route, immersive) { BottomBar.collapse = 0f }
    Scaffold(
        // Insets are handled per screen and by the bar itself.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (barVisible) SlimNavBar(current = currentTab, immersive = immersive, onSelect = { nav.navigateTab(it.route) })
        },
    ) { padding ->
        androidx.compose.runtime.CompositionLocalProvider(
            LocalBottomBarInset provides when {
                !immersive -> 0.dp
                barVisible -> padding.calculateBottomPadding()
                else -> androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            },
        ) {
        NavHost(
            nav,
            startDestination = Routes.FEED,
            // The feed runs full-bleed under the bar (its captions and buttons sit above it, see
            // LocalBottomBarInset); other screens stop at the bar and below the status bar.
            modifier = if (immersive) Modifier
            else Modifier.padding(bottom = padding.calculateBottomPadding()).then(if (barVisible) Modifier else Modifier.navigationBarsPadding()).statusBarsPadding()
                .nestedScroll(barScroll),
            // Quick fades between screens: snappy, and no sliding panels over full-screen video.
            enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
            exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) },
            popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) },
            popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) },
        ) {
            composable(Routes.FEED) { FeedScreen(nav = nav, locked = locked) }
            composable(Routes.TAG, arguments = listOf(navArgument("tag") { type = NavType.StringType })) {
                FeedScreen(nav = nav, locked = locked)
            }
            composable(Routes.VIEWER, arguments = listOf(navArgument("token") { type = NavType.StringType })) {
                FeedScreen(nav = nav, locked = locked)
            }
            composable(Routes.SEARCH) { SearchScreen(nav) }
            composable(Routes.LIBRARY) { LibraryScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.SOURCES) { SourcesScreen(nav) }
            composable(Routes.BLOCKLIST) { BlocklistScreen(nav) }
            composable(Routes.TASTE) { TasteScreen(nav) }
            composable(Routes.NETWORK) { dev.pfeed.ui.settings.NetworkScreen(nav) }
        }
        // Shared links: open each one in the viewer as it arrives.
        val shared by SharedLink.pending.collectAsStateWithLifecycle()
        val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
        androidx.compose.runtime.LaunchedEffect(shared, locked) {
            if (locked) return@LaunchedEffect // waits for the PIN
            val text = shared ?: return@LaunchedEffect
            SharedLink.pending.value = null
            val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(appContext, LinkEntryPoint::class.java)
            val url = entry.opener().findUrl(text)
            val item = url?.let { runCatching { entry.opener().open(it) }.getOrNull() }
            if (item == null) {
                android.widget.Toast.makeText(appContext, "That doesn't look like a link", android.widget.Toast.LENGTH_SHORT).show()
                return@LaunchedEffect
            }
            nav.navigate(Routes.viewer(entry.holder().put(listOf(item), 0, title = "Shared link")))
        }
        // Dim: a veil over everything that lets every touch through.
        val dim by dev.pfeed.ui.feed.Dimmer.level.collectAsStateWithLifecycle()
        if (dim > 0f) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = dim)))
        // A downloaded update that needs a tap, just above the tab bar.
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding() + 8.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            dev.pfeed.update.UpdateBanner()
        }
        }
    }
}

/** How much of the bottom of a full-bleed screen the tab bar covers (0 elsewhere): feed overlays sit above it. */
val LocalBottomBarInset = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/** Screens can hide the tab bar (the feed does, sideways). */
/** A link shared into the app, waiting to be opened (MainActivity sets it; AppRoot plays it). */
object SharedLink {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface LinkEntryPoint {
    fun opener(): dev.pfeed.source.LinkOpener
    fun holder(): dev.pfeed.data.repo.TransientFeedHolder
}

object BottomBar {
    var hidden by androidx.compose.runtime.mutableStateOf(false)
    /** Last touch anywhere: over the feed the bar dims while you just watch, and comes back on touch. */
    var lastTouch by androidx.compose.runtime.mutableLongStateOf(0L)
    /** 0 = shown, 1 = tucked away: lists scrolled down push the bar off the bottom; scrolling back up brings it back. */
    var collapse by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** The current tab tapped again (its route): the feed jumps back to the top, or refreshes if it's there. */
    val reselected = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
}

/**
 * The tab bar: slim, no pill, no slab. Over the feed it's see-through on a soft fade, so videos run
 * to the bottom edge; elsewhere it sits on the page colour. The current tab is bright with a small
 * accent bar; the others are dimmed.
 */
@Composable
private fun SlimNavBar(current: Tab?, immersive: Boolean, onSelect: (Tab) -> Unit) {
    val background = if (immersive) {
        // Light: the post already darkens its own bottom edge for the caption.
        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)))
    } else {
        androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.background)
    }
    // Over the feed the bar fades back while you watch (0 = awake, 1 = dimmed, 2 = out of sight)
    // and returns the moment you touch the screen.
    var stage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(BottomBar.lastTouch, immersive) {
        stage = 0
        if (!immersive) return@LaunchedEffect
        // Hands-free: nobody's touching the screen on purpose, so the bar settles back sooner.
        val quick = dev.pfeed.ui.feed.HandsFree.on.value
        kotlinx.coroutines.delay(if (quick) IDLE_DIM_MS / 2 else IDLE_DIM_MS)
        stage = 1
        kotlinx.coroutines.delay(if (quick) IDLE_HIDE_MS / 2 else IDLE_HIDE_MS)
        stage = 2
    }
    val fade by androidx.compose.animation.core.animateFloatAsState(
        when (stage) { 0 -> 1f; 1 -> 0.35f; else -> 0f },
        androidx.compose.animation.core.tween(if (stage == 0) 150 else 900),
        label = "barFade",
    )
    // A touch on a bar that's out of sight only brings it back; it doesn't switch tabs by surprise.
    var asleepAtTouch by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val collapse = if (immersive) 0f else BottomBar.collapse
    val selectedIndex = tabs.indexOf(current).coerceAtLeast(0)
    val indicatorX by androidx.compose.animation.core.animateFloatAsState(
        selectedIndex.toFloat(), androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f), label = "tabIndicator",
    )
    Box(
        Modifier
            // Tucked away: the bar reports less height (so the page gets the room) and slides down.
            .clipToBounds()
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                val shown = (p.height * (1f - collapse)).toInt()
                layout(p.width, shown) { p.place(0, 0) }
            }
            .fillMaxWidth()
            .graphicsLayer { alpha = fade }
            .background(background)
            .navigationBarsPadding()
            .height(BAR_HEIGHT)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.any { it.pressed && it.previousPressed.not() }) asleepAtTouch = stage == 2
                    }
                }
            },
    ) {
        // On a tablet or a wide screen the tabs stay together in the middle instead of spreading to the edges.
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxHeight().widthIn(max = MAX_BAR_WIDTH).fillMaxWidth().align(androidx.compose.ui.Alignment.Center),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = tab == current
                val tint by androidx.compose.animation.animateColorAsState(
                    if (selected) Color.White else Color.White.copy(alpha = if (immersive) 0.62f else 0.5f), label = "tabTint",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            role = androidx.compose.ui.semantics.Role.Tab,
                            onClickLabel = tab.label,
                        ) {
                            if (asleepAtTouch) { asleepAtTouch = false; return@clickable }
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            if (selected) BottomBar.reselected.tryEmit(tab.route) else onSelect(tab)
                        }
                        .androidx_semantics(tab.label, selected),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(if (selected) tab.icon else tab.outline, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
                    // Over the feed only the current tab is named: fewer words on top of the video.
                    val labelAlpha by androidx.compose.animation.core.animateFloatAsState(if (selected || !immersive) 1f else 0f, label = "tabLabel")
                    Text(
                        tab.label,
                        color = tint,
                        modifier = Modifier.graphicsLayer { alpha = labelAlpha },
                        fontSize = 10.5.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(5.dp))
                }
            }
        }
        // One accent mark that slides to the tab you pick.
        if (current != null) {
            Box(
                Modifier
                    .align(androidx.compose.ui.Alignment.BottomStart)
                    .padding(bottom = 4.dp)
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        val rowWidth = minOf(constraints.maxWidth, MAX_BAR_WIDTH.roundToPx())
                        val start = (constraints.maxWidth - rowWidth) / 2f
                        val slot = rowWidth / tabs.size.toFloat()
                        layout(constraints.maxWidth, p.height) { p.place((start + slot * (indicatorX + 0.5f) - p.width / 2f).toInt(), 0) }
                    }
                    .width(14.dp)
                    .height(2.dp)
                    .background(Accent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** Tabs read as tabs to TalkBack (and to tests): selectable, with the label as their text. */
private fun Modifier.androidx_semantics(label: String, selected: Boolean): Modifier =
    this.then(Modifier.semantics(mergeDescendants = true) { this.selected = selected; this.contentDescription = label })

private val BAR_HEIGHT = 52.dp
private val MAX_BAR_WIDTH = 520.dp
private const val IDLE_DIM_MS = 3_500L
/** After dimming, how much longer before the bar is out of sight entirely. */
private const val IDLE_HIDE_MS = 8_000L

/**
 * Opens a tag or creator page. Pages opened from pages (a creator, then one of their tags, then
 * another creator…) can stack without end, each holding its own feed in memory; past a few deep,
 * the new page takes the current one's place, so Back still steps through the last few.
 */
@android.annotation.SuppressLint("RestrictedApi") // reading the back stack's depth; nothing is changed through it
fun NavController.openTag(tag: String) {
    val depth = currentBackStack.value.count { it.destination.route == Routes.TAG || it.destination.route == Routes.VIEWER }
    val top = currentBackStackEntry?.destination
    navigate(Routes.tag(tag)) {
        if (depth >= MAX_PAGE_DEPTH && top != null && (top.route == Routes.TAG || top.route == Routes.VIEWER)) {
            popUpTo(top.id) { inclusive = true }
        }
    }
}

private const val MAX_PAGE_DEPTH = 4

private fun NavController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
