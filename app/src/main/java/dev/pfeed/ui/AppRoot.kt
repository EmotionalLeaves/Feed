package dev.pfeed.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import dev.pfeed.ui.settings.AdvancedScreen
import dev.pfeed.ui.settings.InterestsScreen
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
    const val ADVANCED = "settings/advanced"
    const val INTERESTS = "settings/interests"

    fun tag(tag: String) = "tag/" + Uri.encode(tag)
    fun viewer(token: String) = "viewer/$token"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.FEED, "Feed", Icons.Filled.Home),
    Tab(Routes.SEARCH, "Search", Icons.Filled.Search),
    Tab(Routes.LIBRARY, "Library", Icons.Filled.VideoLibrary),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(lockManager: AppLockManager, onPanic: () -> Unit) {
    val locked by lockManager.locked.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().panicGesture(onPanic)) {
        // The app stays composed under the lock screen (so view-model state survives), but media pauses.
        MainNav(locked = locked)
        if (locked) LockScreen(lockManager)
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

    Scaffold(
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.FEED, modifier = Modifier.padding(padding)) {
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
            composable(Routes.ADVANCED) { AdvancedScreen(nav) }
            composable(Routes.INTERESTS) { InterestsScreen(nav) }
        }
    }
}

private fun NavController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
