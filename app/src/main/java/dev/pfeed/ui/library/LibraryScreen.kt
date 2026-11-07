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
import dev.pfeed.data.db.BlockEntity
import dev.pfeed.data.db.FollowEntity
import dev.pfeed.data.repo.BlockRepository
import dev.pfeed.data.repo.FollowKind
import dev.pfeed.data.repo.LibraryKind
import dev.pfeed.data.repo.LibraryRepository
import dev.pfeed.data.repo.TransientFeedHolder
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.ui.Routes
import dev.pfeed.ui.common.MediaGrid
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val blocks: BlockRepository,
    private val holder: TransientFeedHolder,
) : ViewModel() {
    val saved: StateFlow<List<MediaItem>> = library.items(LibraryKind.SAVED).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val liked: StateFlow<List<MediaItem>> = library.items(LibraryKind.LIKED).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history: StateFlow<List<MediaItem>> = library.items(LibraryKind.HISTORY).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val follows: StateFlow<List<FollowEntity>> = library.followRows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val blocked: StateFlow<List<BlockEntity>> = blocks.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(items: List<MediaItem>, index: Int) = holder.put(items, index)
    fun unfollow(row: FollowEntity) = viewModelScope.launch { library.unfollow(FollowKind.valueOf(row.kind), row.value) }
    fun unblock(row: BlockEntity) = viewModelScope.launch { blocks.unblock(row.kind, row.value) }
    fun clearHistory() = viewModelScope.launch { library.clearHistory() }
}

private val tabs = listOf("Saved", "Liked", "History", "Following", "Blocked")

@Composable
fun LibraryScreen(nav: NavController, vm: LibraryViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val saved by vm.saved.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val follows by vm.follows.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()

    val counts = listOf(saved.size, liked.size, history.size, follows.size, blocked.size)

    Column(Modifier.fillMaxSize()) {
        Text(
            "Library",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
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
            0 -> Grid(saved, Icons.Filled.BookmarkBorder, "Nothing saved yet", "Tap Save on anything in the feed to keep it here.", openGrid(saved))
            1 -> Grid(liked, Icons.Filled.FavoriteBorder, "No likes yet", "Double-tap a post, or tap the heart, to like it.", openGrid(liked))
            2 -> Column {
                if (history.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear history") }
                }
                Grid(history, Icons.Filled.History, "No history", "Posts you watch show up here.", openGrid(history))
            }
            3 -> LazyColumn {
                if (follows.isEmpty()) item { Empty(Icons.Filled.PersonAdd, "Not following anything", "Tap Follow next to a creator, or open a tag and follow it.") }
                items(follows, key = { it.kind + ":" + it.value }) { row ->
                    val isTag = row.kind == FollowKind.TAG.name
                    ListItem(
                        headlineContent = { Text(if (isTag) "#${row.value.replace('_', ' ')}" else "@${row.value}") },
                        supportingContent = { Text(if (isTag) "Tag" else "Creator") },
                        leadingContent = { Icon(if (isTag) Icons.Filled.Tag else Icons.Filled.Person, contentDescription = null) },
                        trailingContent = { TextButton(onClick = { vm.unfollow(row) }) { Text("Unfollow") } },
                        modifier = if (isTag) Modifier.clickable { nav.navigate(Routes.tag(row.value)) } else Modifier,
                    )
                }
            }
            else -> LazyColumn {
                if (blocked.isEmpty()) item { Empty(Icons.Filled.Block, "Nothing blocked", "Hide a tag or creator from the ⋯ menu on any post.") }
                items(blocked, key = { it.kind + ":" + it.value }) { row ->
                    ListItem(
                        headlineContent = {
                            Text(
                                when (row.kind) {
                                    "TAG" -> "#${row.value.replace('_', ' ')}"
                                    "CREATOR" -> "@${row.value}"
                                    else -> "A post you hid"
                                },
                            )
                        },
                        supportingContent = {
                            Text(
                                when (row.kind) {
                                    "ITEM" -> "Not interested"
                                    "TAG" -> "Blocked tag"
                                    else -> "Blocked creator"
                                },
                            )
                        },
                        trailingContent = { TextButton(onClick = { vm.unblock(row) }) { Text("Unblock") } },
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
    if (items.isEmpty()) Empty(icon, title, hint) else MediaGrid(items, onOpen)
}

@Composable
private fun Empty(icon: ImageVector, title: String, hint: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
    }
}
