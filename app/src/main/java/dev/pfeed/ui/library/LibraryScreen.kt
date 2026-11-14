package dev.pfeed.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.pfeed.ui.common.SecureDialog
import dev.pfeed.ui.theme.Accent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pfeed.data.db.FollowEntity
import dev.pfeed.data.repo.FollowKind
import dev.pfeed.data.repo.LibraryKind
import dev.pfeed.data.repo.LibraryRepository
import dev.pfeed.data.repo.TransientFeedHolder
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.ui.Routes
import dev.pfeed.ui.common.EmptyState
import dev.pfeed.ui.common.MediaGrid
import dev.pfeed.ui.common.ScreenHeader
import dev.pfeed.ui.common.SettingRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val holder: TransientFeedHolder,
) : ViewModel() {
    val saved: StateFlow<List<MediaItem>> = library.items(LibraryKind.SAVED).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val liked: StateFlow<List<MediaItem>> = library.items(LibraryKind.LIKED).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history: StateFlow<List<MediaItem>> = library.items(LibraryKind.HISTORY).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val follows: StateFlow<List<FollowEntity>> = library.followRows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(items: List<MediaItem>, index: Int) = holder.put(items, index)
    fun unfollow(row: FollowEntity) = viewModelScope.launch { library.unfollow(FollowKind.valueOf(row.kind), row.value) }
    fun clearHistory() = viewModelScope.launch { library.clearHistory() }
}

private val tabs = listOf("Saved", "Liked", "History", "Following")

@Composable
fun LibraryScreen(nav: NavController, vm: LibraryViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val saved by vm.saved.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val follows by vm.follows.collectAsStateWithLifecycle()
    val counts = listOf(saved.size, liked.size, history.size, follows.size)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Library") {
            if (tab == 2 && history.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 8.dp,
            containerColor = Color.Transparent,
            divider = {},
            indicator = { positions ->
                if (tab < positions.size) {
                    TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[tab]), color = Accent)
                }
            },
        ) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(if (counts[i] > 0) "$label  ${counts[i]}" else label) },
                    selectedContentColor = Color.White,
                    unselectedContentColor = Color.White.copy(alpha = 0.55f),
                )
            }
        }
        val openGrid: (List<MediaItem>) -> (Int) -> Unit = { list -> { index -> nav.navigate(Routes.viewer(vm.open(list, index))) } }
        when (tab) {
            0 -> Grid(saved, Icons.Filled.BookmarkBorder, "Nothing saved yet", "Tap Save on any post to keep it here for later.", openGrid(saved))
            1 -> Grid(liked, Icons.Filled.FavoriteBorder, "No likes yet", "Double-tap a post or tap the heart. Likes teach For You what you want.", openGrid(liked))
            2 -> Grid(history, Icons.Filled.History, "No history yet", "Everything you watch shows up here.", openGrid(history))
            else -> LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                if (follows.isEmpty()) item {
                    EmptyState(Icons.Filled.PersonAdd, "Not following anyone", "Tap Follow next to a creator, or open a tag and follow it. Their posts fill the Following feed.")
                }
                items(follows, key = { it.kind + ":" + it.value }) { row ->
                    val isTag = row.kind == FollowKind.TAG.name
                    SettingRow(
                        if (isTag) "#${row.value.replace('_', ' ')}" else "@${row.value}",
                        subtitle = if (isTag) "Tag" else "Creator",
                        icon = if (isTag) Icons.Filled.Tag else Icons.Filled.Person,
                        // Tags open their feed; creators open a feed searching every source for them.
                        onClick = { nav.navigate(Routes.tag(row.value)) },
                        trailing = { TextButton(onClick = { vm.unfollow(row) }) { Text("Unfollow") } },
                    )
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            properties = SecureDialog,
            title = { Text("Clear watch history?") },
            text = { Text("Posts you've already seen can show up in your feed again.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Grid(items: List<MediaItem>, icon: ImageVector, title: String, hint: String, onOpen: (Int) -> Unit) {
    if (items.isEmpty()) EmptyState(icon, title, hint) else MediaGrid(items, onOpen)
}
