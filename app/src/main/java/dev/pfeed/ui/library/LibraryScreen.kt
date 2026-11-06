package dev.pfeed.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    val saved by vm.saved.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val follows by vm.follows.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        val openGrid: (List<MediaItem>) -> (Int) -> Unit = { list -> { index -> nav.navigate(Routes.viewer(vm.open(list, index))) } }
        when (tab) {
            0 -> Grid(saved, "Nothing saved yet. Long-press an item → Save.", openGrid(saved))
            1 -> Grid(liked, "Double-tap anything in the feed to like it.", openGrid(liked))
            2 -> Column {
                TextButton(onClick = { vm.clearHistory() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear history") }
                Grid(history, "No history.", openGrid(history))
            }
            3 -> LazyColumn {
                if (follows.isEmpty()) item { Empty("You're not following anyone or any tag yet.") }
                items(follows, key = { it.kind + ":" + it.value }) { row ->
                    ListItem(
                        headlineContent = { Text(if (row.kind == FollowKind.TAG.name) "#${row.value}" else "@${row.value}") },
                        supportingContent = { Text(row.kind.lowercase()) },
                        trailingContent = { TextButton(onClick = { vm.unfollow(row) }) { Text("Unfollow") } },
                        modifier = if (row.kind == FollowKind.TAG.name) Modifier.clickable { nav.navigate(Routes.tag(row.value)) } else Modifier,
                    )
                }
            }
            else -> LazyColumn {
                if (blocked.isEmpty()) item { Empty("Nothing blocked.") }
                items(blocked, key = { it.kind + ":" + it.value }) { row ->
                    ListItem(
                        headlineContent = {
                            Text(
                                when (row.kind) {
                                    "TAG" -> "#${row.value}"
                                    "CREATOR" -> "@${row.value}"
                                    else -> "Hidden item"
                                },
                            )
                        },
                        supportingContent = { Text(if (row.kind == "ITEM") "not interested" else row.kind.lowercase()) },
                        trailingContent = { IconButton(onClick = { vm.unblock(row) }) { Icon(Icons.Filled.Close, "Unblock") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Grid(items: List<MediaItem>, empty: String, onOpen: (Int) -> Unit) {
    if (items.isEmpty()) Empty(empty) else MediaGrid(items, onOpen)
}

@Composable
private fun Empty(text: String) {
    Text(text, modifier = Modifier.padding(24.dp))
}
