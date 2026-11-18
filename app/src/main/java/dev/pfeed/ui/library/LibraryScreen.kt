package dev.pfeed.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import dev.pfeed.ui.openTag
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.layout
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
    val livePreviews: dev.pfeed.ui.common.LivePreviews,
) : ViewModel() {
    /** Saved posts, with thumbnails from the kept copies once they're downloaded (so Saved works offline). */
    /**
     * Secret collections: a collection whose name starts with a dot (".mine") is hidden, chip and
     * posts, from Saved and its list. Typing its full name in Find opens it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val secretKeys: kotlinx.coroutines.flow.Flow<Set<String>> = library.collections.flatMapLatest { all ->
        val secret = all.filter { it.name.startsWith(".") }
        if (secret.isEmpty()) kotlinx.coroutines.flow.flowOf(emptySet())
        else kotlinx.coroutines.flow.combine(secret.map { library.collectionItems(it.name) }) { lists -> lists.flatMap { l -> l.map { it.key } }.toSet() }
    }

    val saved: StateFlow<List<MediaItem>> = kotlinx.coroutines.flow.combine(library.items(LibraryKind.SAVED), offline.bytes, secretKeys) { list, _, hidden ->
        list.filter { it.key !in hidden }.map(offline::withLocalThumbnail)
    }
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

    /** Takes a post off one list: Saved (and so its collections), Liked, History, or one collection. */
    fun removeFrom(list: String, item: MediaItem) = viewModelScope.launch {
        when (list) {
            "Saved" -> library.remove(LibraryKind.SAVED, item)
            "Liked" -> library.remove(LibraryKind.LIKED, item)
            "History" -> library.remove(LibraryKind.HISTORY, item)
            else -> library.removeFromCollection(list, item)
        }
    }
    fun unfollow(row: FollowEntity) = viewModelScope.launch { library.unfollow(FollowKind.valueOf(row.kind), row.value) }
    fun clearHistory() = viewModelScope.launch { library.clearHistory() }
}

private val tabs = listOf("Saved", "Liked", "History", "Following")

@Composable
fun LibraryScreen(nav: NavController, vm: LibraryViewModel = hiltViewModel()) {
    // Grids on this screen come alive: the video nearest the middle plays in its tile.
    androidx.compose.runtime.CompositionLocalProvider(
        dev.pfeed.ui.common.LocalLivePreview provides dev.pfeed.ui.common.rememberLivePreview(vm.livePreviews),
    ) { LibraryScreenContent(nav, vm) }
}

@Composable
private fun LibraryScreenContent(nav: NavController, vm: LibraryViewModel) {
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
    // Library tapped again in the tab bar: out of a collection, back to everything saved.
    LaunchedEffect(Unit) {
        dev.pfeed.ui.BottomBar.reselected.collect { route -> if (route == dev.pfeed.ui.Routes.LIBRARY) vm.collection.value = null }
    }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    // Press and hold a tile: what you can do with that post, in the list you're looking at.
    var holding by remember { mutableStateOf<Pair<MediaItem, String>?>(null) }
    // Picking several posts in one list (its name, and the picked posts by key) to remove at once.
    var picking by remember { mutableStateOf<Pair<String, Map<String, MediaItem>>?>(null) }
    androidx.activity.compose.BackHandler(enabled = picking != null) { picking = null }
    // A collection that's gone (deleted elsewhere, or emptied by unsaving): back to all saved posts.
    LaunchedEffect(collections, collection) {
        val c = collection
        if (c != null && collections.isNotEmpty() && collections.none { it.name == c }) vm.collection.value = null
    }
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // Find in the current list: title, creator and tags.
    // Order for Saved, Liked and collections (History stays by when you watched).
    var order by rememberSaveable { mutableStateOf(LibOrder.NEWEST.name) }
    var orderMenu by remember { mutableStateOf(false) }
    fun List<MediaItem>.found(): List<MediaItem> {
        val q = query.trim().lowercase()
        val hit = if (!finding || q.isEmpty()) this else filter { item ->
            item.title?.lowercase()?.contains(q) == true || item.creator?.lowercase()?.contains(q) == true ||
                item.tags.any { it.lowercase().replace('_', ' ').contains(q) }
        }
        return if (tab == 2) hit else when (LibOrder.valueOf(order)) {
            LibOrder.NEWEST -> hit
            LibOrder.OLDEST -> hit.asReversed()
            LibOrder.LONGEST -> hit.sortedByDescending { it.durationSec ?: -1 }
            LibOrder.SHORTEST -> hit.sortedBy { it.durationSec ?: Int.MAX_VALUE }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Library") {
            if (tab < 3) androidx.compose.material3.IconButton(onClick = { finding = !finding; if (!finding) query = "" }) {
                androidx.compose.material3.Icon(if (finding) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (finding) "Close find" else "Find in library")
            }
            picking?.let { (list, chosen) ->
                Text("${chosen.size} picked", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(end = 4.dp))
                TextButton(enabled = chosen.isNotEmpty(), onClick = { chosen.values.forEach { vm.removeFrom(list, it) }; picking = null }) {
                    Text("Remove", color = dev.pfeed.ui.theme.Bad)
                }
                TextButton(onClick = { picking = null }) { Text("Done") }
                return@ScreenHeader
            }
            if (tab < 2) Box {
                androidx.compose.material3.IconButton(onClick = { orderMenu = true }) {
                    androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Order: " + LibOrder.valueOf(order).label)
                }
                androidx.compose.material3.DropdownMenu(expanded = orderMenu, onDismissRequest = { orderMenu = false }) {
                    LibOrder.entries.forEach { o ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(o.label, fontWeight = if (o.name == order) androidx.compose.ui.text.font.FontWeight.Bold else null) },
                            leadingIcon = { if (o.name == order) androidx.compose.material3.Icon(Icons.Filled.Check, contentDescription = null, tint = Accent) },
                            onClick = { order = o.name; orderMenu = false },
                        )
                    }
                }
            }
            if (tab == 2 && history.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            val open = collection
            if (tab == 0 && open != null && !open.startsWith("#")) {
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
        LaunchedEffect(tab, collection) { picking = null }
        val pickNow = picking
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPicking provides pickNow?.let { (list, chosen) ->
                chosen.keys to { item: MediaItem ->
                    picking = list to (if (item.key in chosen) chosen - item.key else chosen + (item.key to item))
                }
            },
        ) {
        when (tab) {
            0 -> Column {
                // Smart folders: Saved sorted by its most common tags, without lifting a finger.
                val autoTags = remember(saved) {
                    saved.flatMap { item -> dev.pfeed.ui.feed.Display.tags(item, 12).map { it.first.lowercase() }.distinct() }
                        .groupingBy { it }.eachCount()
                        .filter { it.value >= AUTO_TAG_MIN }
                        .entries.sortedByDescending { it.value }
                        .take(AUTO_TAG_MAX)
                        .map { it.key to it.value }
                }
                // Secret collections show only while Find holds their exact name.
                val shown = collections.filter { !it.name.startsWith(".") || it.name.equals(query.trim(), ignoreCase = true) || it.name == collection }
                LaunchedEffect(query, collections) {
                    collections.firstOrNull { it.name.startsWith(".") && it.name.equals(query.trim(), ignoreCase = true) }?.let { vm.collection.value = it.name; query = "" }
                }
                if (shown.isNotEmpty() || collection != null || autoTags.isNotEmpty()) CollectionChips(shown, collection, saved.size, autoTags) { vm.collection.value = it }
                val open = collection
                if (open != null && open.startsWith("#")) {
                    val tag = open.removePrefix("#")
                    val l = saved.filter { item -> dev.pfeed.ui.feed.Display.tags(item, 12).any { it.first.equals(tag, ignoreCase = true) } }.found()
                    Grid(l, Icons.Filled.BookmarkBorder, "Nothing tagged $open", "", { index -> nav.navigate(Routes.viewer(vm.open(l, index, open))) }, onShuffle = { nav.navigate(Routes.viewer(vm.open(l.shuffled(), 0, "$open · shuffled"))) }, onLongPress = { holding = it to "Saved" })
                } else if (open == null) {
                    saved.found().let { l -> if (saved.isNotEmpty() && l.isEmpty()) NoMatch() else Grid(l, Icons.Filled.BookmarkBorder, "Nothing saved yet", "Tap Save on any post to keep it here. Saved posts are kept on your phone and play offline. Hold a post → Add to collection to sort them into folders.", openGrid(l), onBrowse = toFeed, onShuffle = shuffle(l), onLongPress = { holding = it to "Saved" }, onMoreLike = { moreLikeThese(l); toFeed() }) }
                } else {
                    collectionItems.found().let { l -> if (collectionItems.isNotEmpty() && l.isEmpty()) NoMatch() else Grid(l, Icons.Filled.Folder, "Nothing in $open", "Hold a post → Add to collection to put it here.", { index -> nav.navigate(Routes.viewer(vm.open(l, index, open))) }, onShuffle = { nav.navigate(Routes.viewer(vm.open(l.shuffled(), 0, "$open · shuffled"))) }, onLongPress = { holding = it to open }, onMoreLike = { moreLikeThese(l); toFeed() }) }
                }
            }
            1 -> liked.found().let { l -> if (liked.isNotEmpty() && l.isEmpty()) NoMatch() else Grid(l, Icons.Filled.FavoriteBorder, "No likes yet", "Double-tap a post or tap the heart. Likes teach For You what you want.", openGrid(l), onBrowse = toFeed, onShuffle = shuffle(l), onLongPress = { holding = it to "Liked" }, onMoreLike = { moreLikeThese(l); toFeed() }) }
            2 -> history.found().let { l -> if (history.isNotEmpty() && l.isEmpty()) NoMatch() else Grid(l, Icons.Filled.History, "No history yet", "Everything you watch shows up here.", openGrid(l), sectionOf = { whenWatched(historyTimes[it.key]) }, onLongPress = { holding = it to "History" }, header = if (query.isBlank()) ({ WeekRecap(history, historyTimes, onOpenTag = { nav.openTag(it) }) }) else null) }
            else -> LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                if (follows.isEmpty()) item {
                    EmptyState(Icons.Filled.PersonAdd, "Not following anyone", "Tap Follow next to a creator, or open a tag and follow it. Their posts fill the Following feed.")
                }
                items(follows, key = { it.kind + ":" + it.value }) { row ->
                    val isTag = row.kind == FollowKind.TAG.name
                    // Tags open their feed; creators open their page (everything by them, every source).
                    FollowRow(
                        name = if (isTag) "#${row.value.replace('_', ' ')}" else "@${row.value}",
                        kind = if (isTag) "Tag" else "Creator",
                        isTag = isTag,
                        onOpen = { nav.openTag(if (isTag) row.value else "@" + row.value) },
                        onUnfollow = { vm.unfollow(row) },
                    )
                }
            }
        }
        }
    }
    holding?.let { (item, list) ->
        val context = androidx.compose.ui.platform.LocalContext.current
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { holding = null },
            properties = androidx.compose.material3.ModalBottomSheetProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    item.title?.takeIf { it.isNotBlank() } ?: dev.pfeed.ui.feed.Display.source(item, ""),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                SettingRow(
                    if (list in listOf("Saved", "Liked", "History")) "Remove from $list" else "Remove from \"$list\"",
                    icon = Icons.Filled.DeleteOutline,
                    iconTint = dev.pfeed.ui.theme.Bad,
                    onClick = { vm.removeFrom(list, item); holding = null },
                )
                SettingRow(
                    "Pick several…",
                    subtitle = "Tap more posts, then remove them all at once",
                    icon = Icons.Filled.Checklist,
                    iconTint = dev.pfeed.ui.theme.TextLow,
                    onClick = { picking = list to mapOf(item.key to item); holding = null },
                )
                if (item.pageUrl.isNotBlank()) SettingRow(
                    "Open on the site",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    iconTint = dev.pfeed.ui.theme.TextLow,
                    onClick = { dev.pfeed.ui.common.openExternal(context, item.pageUrl); holding = null },
                )
                if (item.pageUrl.isNotBlank()) SettingRow(
                    "Copy link",
                    icon = Icons.Filled.Link,
                    iconTint = dev.pfeed.ui.theme.TextLow,
                    onClick = { dev.pfeed.ui.common.copySensitive(context, "Link", item.pageUrl); holding = null },
                )
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
    autoTags: List<Pair<String, Int>> = emptyList(),
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
                // The latest post in it, as a little round cover (a folder until it has one).
                leadingIcon = {
                    if (c.cover != null) {
                        Box(Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            coil.compose.AsyncImage(
                                model = c.cover,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().then(if (dev.pfeed.ui.common.LocalBlurPreviews.current) Modifier.blur(6.dp) else Modifier),
                            )
                            dev.pfeed.ui.common.MediaMask()
                        }
                    } else {
                        androidx.compose.material3.Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White),
            )
        }
        // The smart folders: your most saved tags, sorted for you.
        items(autoTags, key = { "#" + it.first }) { (tag, count) ->
            androidx.compose.material3.FilterChip(
                selected = selected == "#$tag",
                onClick = { onSelect("#$tag") },
                label = { Text("#$tag · $count", maxLines = 1) },
                leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White),
            )
        }
    }
}

private const val AUTO_TAG_MIN = 3
private const val AUTO_TAG_MAX = 6

/** A followed creator or tag: a round initial (or #) in a colour of its own, the name, and Unfollow. */
@Composable
private fun FollowRow(name: String, kind: String, isTag: Boolean, onOpen: () -> Unit, onUnfollow: () -> Unit) {
    val hue = remember(name) { ((name.lowercase().hashCode() and 0x7fffffff) % 360).toFloat() }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isTag) Box(
            Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.hsl(hue, 0.45f, 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("#", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        } else dev.pfeed.ui.common.InitialAvatar(name, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(kind, style = MaterialTheme.typography.bodySmall, color = dev.pfeed.ui.theme.TextLow)
        }
        androidx.compose.material3.OutlinedButton(
            onClick = onUnfollow,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            modifier = Modifier.height(34.dp),
        ) { Text("Unfollow", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f)) }
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
private fun Grid(items: List<MediaItem>, icon: ImageVector, title: String, hint: String, onOpen: (Int) -> Unit, onBrowse: (() -> Unit)? = null, sectionOf: ((MediaItem) -> String)? = null, onShuffle: (() -> Unit)? = null, onLongPress: ((MediaItem) -> Unit)? = null, header: (@Composable () -> Unit)? = null, onMoreLike: (() -> Unit)? = null) {
    if (items.isEmpty()) {
        EmptyState(icon, title, hint, action = onBrowse?.let { go ->
            {
                dev.pfeed.ui.common.BrandButton("Browse For You", onClick = go)
            }
        })
    } else Column {
        header?.invoke()
        // How many, and the quickest way in: play the whole list as a feed.
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${items.size} ${if (items.size == 1) "post" else "posts"}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            if (onMoreLike != null && items.size >= 3) androidx.compose.material3.IconButton(onClick = onMoreLike) {
                androidx.compose.material3.Icon(Icons.Filled.AutoAwesome, contentDescription = "More like these, in For You", tint = Color.White.copy(alpha = 0.8f))
            }
            if (onShuffle != null && items.size > 2) androidx.compose.material3.IconButton(onClick = onShuffle) {
                androidx.compose.material3.Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = Color.White.copy(alpha = 0.8f))
            }
            dev.pfeed.ui.common.BrandButton("Play all", onClick = { onOpen(0) }, icon = Icons.Filled.PlayArrow, compact = true)
        }
        val pick = LocalPicking.current
        MediaGrid(items, onOpen, sectionOf = sectionOf, onLongPress = onLongPress, picked = pick?.first, onPick = pick?.second)
    }
}

@Composable
private fun NoMatch() = EmptyState(Icons.Filled.Search, "No matches", "Nothing in this list has that in its title, creator or tags.")

@Composable
private fun LibraryTabs(selected: Int, onSelect: (Int) -> Unit) {
    // One accent bar that slides to the picked tab (as in the tab bar), instead of one per tab.
    val at by androidx.compose.animation.core.animateFloatAsState(
        selected.toFloat(), androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f), label = "libraryTab",
    )
    Column {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { i, label ->
                    val on = i == selected
                    val tint by androidx.compose.animation.animateColorAsState(if (on) Color.White else Color.White.copy(alpha = 0.5f), label = "libraryTabTint")
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onSelect(i) }.padding(top = 12.dp, bottom = 19.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 8.dp)
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        val slot = constraints.maxWidth / tabs.size.toFloat()
                        layout(constraints.maxWidth, p.height) { p.place((slot * (at + 0.5f) - p.width / 2f).toInt(), 0) }
                    }
                    .width(28.dp)
                    .height(3.dp)
                    .background(Accent, RoundedCornerShape(2.dp)),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** What you watched over the last seven days, from History. */
private data class Week(val posts: Int, val minutes: Int, val creator: String?, val creatorCount: Int, val tags: List<Pair<String, String>>, val days: List<Int>)

private fun week(history: List<MediaItem>, times: Map<String, Long>, now: Long = System.currentTimeMillis()): Week? {
    val dayMs = 86_400_000L
    val recent = history.filter { (times[it.key] ?: 0L) > now - 7 * dayMs }
    if (recent.size < WEEK_MIN_POSTS) return null
    val seconds = recent.sumOf { it.durationSec ?: if (it.type == dev.pfeed.domain.model.MediaType.IMAGE) 6 else 20 }
    val creators = recent.mapNotNull { dev.pfeed.ui.feed.Display.creator(it) }.groupingBy { it }.eachCount()
    val top = creators.maxByOrNull { it.value }
    val tags = recent.flatMap { dev.pfeed.ui.feed.Display.tags(it, 6) }
        .groupBy { it.first.lowercase() }
        .entries.sortedByDescending { it.value.size }
        .take(3).map { it.value.first() }
    // Posts per day, oldest first, for the little bars.
    val days = (6 downTo 0).map { back ->
        val end = now - back * dayMs
        recent.count { val t = times[it.key] ?: 0L; t > end - dayMs && t <= end }
    }
    return Week(recent.size, (seconds / 60).toInt(), top?.key?.takeIf { top.value >= 2 }, top?.value ?: 0, tags, days)
}

private const val WEEK_MIN_POSTS = 5

@Composable
private fun WeekRecap(history: List<MediaItem>, times: Map<String, Long>, onOpenTag: (String) -> Unit) {
    val w = remember(history, times) { week(history, times) } ?: return
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(dev.pfeed.ui.theme.Accent.copy(alpha = 0.32f), dev.pfeed.ui.theme.AccentWarm.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f))))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("YOUR WEEK", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), letterSpacing = 1.4.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(timeLabel(w.minutes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("· ${w.posts} posts", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            // Seven little bars, one a day, today on the right.
            val peak = (w.days.maxOrNull() ?: 0).coerceAtLeast(1)
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                w.days.forEachIndexed { i, n ->
                    Box(
                        Modifier.width(6.dp).fillMaxHeight(0.12f + 0.88f * n / peak)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(if (i == w.days.lastIndex) dev.pfeed.ui.theme.BrandGradient else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.35f)))),
                    )
                }
            }
        }
        if (w.creator != null || w.tags.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState())) {
                w.creator?.let { c ->
                    Row(
                        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.3f))
                            .clickable { onOpenTag("@$c") }.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dev.pfeed.ui.common.InitialAvatar(c, 24.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Most: @$c", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
                    }
                }
                w.tags.forEach { (label, tag) ->
                    Text(
                        "#$label",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.3f))
                            .clickable { onOpenTag(tag) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun timeLabel(minutes: Int): String = when {
    minutes < 60 -> "${minutes.coerceAtLeast(1)} min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** While picking several posts: the picked keys and what a tap on a post does. */
private val LocalPicking = androidx.compose.runtime.staticCompositionLocalOf<Pair<Set<String>, (MediaItem) -> Unit>?> { null }

private enum class LibOrder(val label: String) {
    NEWEST("Newest first"), OLDEST("Oldest first"), LONGEST("Longest first"), SHORTEST("Shortest first"),
}

/**
 * For You steered toward what a list of posts has in common (its most shared tags), for a while:
 * a collection becomes a mix.
 */
private fun moreLikeThese(items: List<MediaItem>) {
    val tags = items.take(200)
        .flatMap { dev.pfeed.domain.reco.Features.of(it).filterNot { f -> dev.pfeed.domain.reco.Features.isMeta(f) || f.startsWith("w:") } }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
    if (tags.isNotEmpty()) dev.pfeed.data.repo.SteerState.set(tags.map { dev.pfeed.domain.reco.Steer.Directive(dev.pfeed.domain.reco.Steer.Kind.MORE, it) })
}
