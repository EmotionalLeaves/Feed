package dev.pfeed.ui.library

import kotlinx.coroutines.flow.flowOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PlayArrow
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val holder: TransientFeedHolder,
    private val offline: dev.pfeed.data.repo.OfflineStore,
) : ViewModel() {
    /** Saved posts, with thumbnails from the kept copies once they're downloaded (so Saved works offline). */
    val saved: StateFlow<List<MediaItem>> = kotlinx.coroutines.flow.combine(library.items(LibraryKind.SAVED), offline.bytes) { list, _ -> list.map(offline::withLocalThumbnail) }
        // Checks files on disk for every post: off the main thread.
        .flowOn(kotlinx.coroutines.Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val liked: StateFlow<List<MediaItem>> = library.items(LibraryKind.LIKED).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history: StateFlow<List<MediaItem>> = library.items(LibraryKind.HISTORY).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val historyTimes: StateFlow<Map<String, Long>> = library.times(LibraryKind.HISTORY).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val follows: StateFlow<List<FollowEntity>> = library.followRows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collections: StateFlow<List<dev.pfeed.data.repo.LibraryRepository.Collection>> =
        library.collections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** The collection being looked at in Saved, or null for all saved posts. */
    val collection = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val collectionItems: StateFlow<List<MediaItem>> = collection
        .flatMapLatest { name ->
            if (name == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else kotlinx.coroutines.flow.combine(library.collectionItems(name), offline.bytes) { list, _ -> list.map(offline::withLocalThumbnail) }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun renameCollection(from: String, to: String) = viewModelScope.launch {
        library.renameCollection(from, to)
        collection.value = dev.pfeed.data.repo.LibraryRepository.cleanName(to) ?: from
    }
    fun deleteCollection(name: String) = viewModelScope.launch {
        collection.value = null
        library.deleteCollection(name)
    }

    fun open(items: List<MediaItem>, index: Int, title: String) = holder.put(items, index, title = title)
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
    val historyTimes by vm.historyTimes.collectAsStateWithLifecycle()
    val follows by vm.follows.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val collection by vm.collection.collectAsStateWithLifecycle()
    val collectionItems by vm.collectionItems.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    // A collection that's gone (deleted elsewhere, or emptied by unsaving): back to all saved posts.
    LaunchedEffect(collections, collection) {
        val c = collection
        if (c != null && collections.isNotEmpty() && collections.none { it.name == c }) vm.collection.value = null
    }
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // Find in the current list: title, creator and tags.
    fun List<MediaItem>.found(): List<MediaItem> {
        val q = query.trim().lowercase()
        if (!finding || q.isEmpty()) return this
        return filter { item ->
            item.title?.lowercase()?.contains(q) == true || item.creator?.lowercase()?.contains(q) == true ||
                item.tags.any { it.lowercase().replace('_', ' ').contains(q) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Library") {
            if (tab < 3) androidx.compose.material3.IconButton(onClick = { finding = !finding; if (!finding) query = "" }) {
                androidx.compose.material3.Icon(if (finding) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (finding) "Close find" else "Find in library")
            }
            if (tab == 2 && history.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            val open = collection
            if (tab == 0 && open != null) {
                androidx.compose.material3.IconButton(onClick = { renaming = open }) {
                    androidx.compose.material3.Icon(Icons.Filled.Edit, contentDescription = "Rename collection")
                }
                androidx.compose.material3.IconButton(onClick = { deleting = open }) {
                    androidx.compose.material3.Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete collection")
                }
            }
        }
        // Four equal tabs that always fit, even on narrow phones (a scrolling row cut "Following" off).
        LibraryTabs(tab) { tab = it }
        if (finding && tab < 3) {
            androidx.compose.material3.TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Find by title, creator or tag", maxLines = 1) },
                singleLine = true,
                leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.Search, contentDescription = null) },
                shape = RoundedCornerShape(28.dp),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        val toFeed = {
            nav.navigate(Routes.FEED) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        val openGrid: (List<MediaItem>) -> (Int) -> Unit = { list -> { index -> nav.navigate(Routes.viewer(vm.open(list, index, tabs[tab]))) } }
        val shuffle: (List<MediaItem>) -> () -> Unit = { list -> { nav.navigate(Routes.viewer(vm.open(list.shuffled(), 0, tabs[tab] + " · shuffled"))) } }
        when (tab) {
            0 -> Column {
                if (collections.isNotEmpty() || collection != null) CollectionChips(collections, collection, saved.size) { vm.collection.value = it }
                val open = collection
                if (open == null) {
                    saved.found().let { l -> if (saved.isNotEmpty() && l.isEmpty()) NoMatch() else Grid(l, Icons.Filled.BookmarkBorder, "Nothing saved yet", "Tap Save on any post to keep it here. Saved posts are kept on your phone and play offline. Hold a post → Add to collection to sort them into folders.", openGrid(l), onBrowse = toFeed, onShuffle = shuffle(l)) }
                } else {
                    collectionItems.found().let { l -> if (collectionItems.isNotEmpty() && l.isEmpty()) NoMatch() else Grid(l, Icons.Filled.Folder, "Nothing in $open", "Hold a post → Add to collection to put it here.", { index -> nav.navigate(Routes.viewer(vm.open(l, index, open))) }, onShuffle = { nav.navigate(Routes.viewer(vm.open(l.shuffled(), 0, "$open · shuffled"))) }) }
                }
            }
            1 -> liked.found().let { l -> if (liked.isNotEmpty() && l.isEmpty()) NoMatch() else Grid(l, Icons.Filled.FavoriteBorder, "No likes yet", "Double-tap a post or tap the heart. Likes teach For You what you want.", openGrid(l), onBrowse = toFeed, onShuffle = shuffle(l)) }
            2 -> history.found().let { l -> if (history.isNotEmpty() && l.isEmpty()) NoMatch() else Grid(l, Icons.Filled.History, "No history yet", "Everything you watch shows up here.", openGrid(l), sectionOf = { whenWatched(historyTimes[it.key]) }) }
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
                        // Tags open their feed; creators open their page (everything by them, every source).
                        onClick = { nav.navigate(Routes.tag(if (isTag) row.value else "@" + row.value)) },
                        trailing = { TextButton(onClick = { vm.unfollow(row) }) { Text("Unfollow") } },
                    )
                }
            }
        }
    }
    renaming?.let { from ->
        var name by remember(from) { mutableStateOf(from) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            properties = SecureDialog,
            title = { Text("Rename collection") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(dev.pfeed.data.repo.LibraryRepository.MAX_COLLECTION_NAME) },
                    singleLine = true,
                )
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.renameCollection(from, name); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            properties = SecureDialog,
            title = { Text("Delete \"$name\"?") },
            text = { Text("The collection goes; its posts stay in Saved.") },
            confirmButton = { TextButton(onClick = { vm.deleteCollection(name); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
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

/** Saved's folders: All, then each collection with its count. */
@Composable
private fun CollectionChips(
    collections: List<dev.pfeed.data.repo.LibraryRepository.Collection>,
    selected: String?,
    savedCount: Int,
    onSelect: (String?) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            androidx.compose.material3.FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All · $savedCount") },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White),
            )
        }
        items(collections, key = { it.name }) { c ->
            androidx.compose.material3.FilterChip(
                selected = selected == c.name,
                onClick = { onSelect(c.name) },
                label = { Text("${c.name} · ${c.count}", maxLines = 1) },
                leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White),
            )
        }
    }
}

/** History headings: Today, Yesterday, This week, Earlier. */
private fun whenWatched(at: Long?): String {
    if (at == null) return "Earlier"
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> "This week"
        else -> "Earlier"
    }
}

@Composable
private fun Grid(items: List<MediaItem>, icon: ImageVector, title: String, hint: String, onOpen: (Int) -> Unit, onBrowse: (() -> Unit)? = null, sectionOf: ((MediaItem) -> String)? = null, onShuffle: (() -> Unit)? = null) {
    if (items.isEmpty()) {
        EmptyState(icon, title, hint, action = onBrowse?.let { go ->
            {
                dev.pfeed.ui.common.BrandButton("Browse For You", onClick = go)
            }
        })
    } else Column {
        // How many, and the quickest way in: play the whole list as a feed.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${items.size} ${if (items.size == 1) "post" else "posts"}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            if (onShuffle != null && items.size > 2) androidx.compose.material3.IconButton(onClick = onShuffle) {
                androidx.compose.material3.Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = Color.White.copy(alpha = 0.8f))
            }
            dev.pfeed.ui.common.BrandButton("Play all", onClick = { onOpen(0) }, icon = Icons.Filled.PlayArrow, compact = true)
        }
        MediaGrid(items, onOpen, sectionOf = sectionOf)
    }
}

@Composable
private fun NoMatch() = EmptyState(Icons.Filled.Search, "No matches", "Nothing in this list has that in its title, creator or tags.")

@Composable
private fun LibraryTabs(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            tabs.forEachIndexed { i, label ->
                val on = i == selected
                val underline by androidx.compose.animation.core.animateDpAsState(if (on) 28.dp else 0.dp, label = "tab")
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onSelect(i) }.padding(top = 12.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) Color.White else Color.White.copy(alpha = 0.5f),
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(underline).height(3.dp).background(Accent, RoundedCornerShape(2.dp)))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}
