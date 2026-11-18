package dev.pfeed.ui.search

import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Casino
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.material.icons.automirrored.filled.MenuBook
import dev.pfeed.ui.feed.Display
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pfeed.data.repo.RecentSearches
import dev.pfeed.data.repo.SearchRepository
import dev.pfeed.data.repo.SignalRepository
import dev.pfeed.data.repo.TransientFeedHolder
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.source.SourceRegistry
import dev.pfeed.ui.Routes
import dev.pfeed.ui.common.MediaGrid
import dev.pfeed.ui.theme.Accent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DurationFilter(val label: String, val range: IntRange?) {
    ANY("Any length", null),
    SHORT("Under 1 min", 0 until 60),
    MEDIUM("1–5 min", 60..300),
    LONG("Over 5 min", 301..Int.MAX_VALUE),
}

/** Order of results: best match (mixed across sites), or by length. */
enum class SortOrder(val label: String) { BEST("Best match"), LONGEST("Longest first"), SHORTEST("Shortest first") }

/** One of the main "what kind of thing" choices; null type = everything. */
enum class TypeFilter(val label: String, val types: Set<MediaType>) {
    ALL("All", emptySet()),
    VIDEOS("Videos", setOf(MediaType.VIDEO, MediaType.EMBED)),
    IMAGES("Images", setOf(MediaType.IMAGE)),
    GIFS("GIFs", setOf(MediaType.GIF)),
    /** Multi-page posts: comics, doujinshi, photo sets. */
    COMICS("Comics", setOf(MediaType.IMAGE)),
}

data class SearchFilters(
    val sources: Set<String> = emptySet(), // empty = all
    val type: TypeFilter = TypeFilter.ALL,
    val duration: DurationFilter = DurationFilter.ANY,
    val sort: SortOrder = SortOrder.BEST,
) {
    /** How many of the filters hidden in the sheet are active (shown as a badge). */
    val sheetCount: Int get() = (if (sources.isNotEmpty()) 1 else 0) + (if (duration != DurationFilter.ANY) 1 else 0) + (if (sort != SortOrder.BEST) 1 else 0)

    /** Any filter on: the endless continuation can't honour them, so it isn't offered. */
    val active: Boolean get() = sheetCount > 0 || type != TypeFilter.ALL

    fun matches(item: MediaItem): Boolean {
        if (sources.isNotEmpty() && item.sourceId !in sources) return false
        if (type.types.isNotEmpty() && item.type !in type.types) return false
        // Images means single pictures; Comics means the multi-page ones.
        if (type == TypeFilter.COMICS && !item.isGallery) return false
        if (type == TypeFilter.IMAGES && item.isGallery) return false
        val range = duration.range ?: return true
        val d = item.durationSec ?: return false
        return d in range
    }
}

data class SearchUi(
    val query: String = "",
    /** The query the results are for (the field may have been edited since). */
    val searched: String? = null,
    val loading: Boolean = false,
    val pending: Int = 0,
    /** Sources asked (the first update reports them all pending). */
    val total: Int = 0,
    val results: List<MediaItem> = emptyList(),
    val errors: List<String> = emptyList(),
    val elapsedMs: Long? = null,
    val sourcesWithResults: Int = 0,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: SearchRepository,
    private val holder: TransientFeedHolder,
    private val recent: RecentSearches,
    private val signals: SignalRepository,
    private val settingsRepo: dev.pfeed.data.settings.SettingsRepository,
    private val registry: SourceRegistry,
    private val library: dev.pfeed.data.repo.LibraryRepository,
    private val blocks: dev.pfeed.data.repo.BlockRepository,
    val livePreviews: dev.pfeed.ui.common.LivePreviews,
) : ViewModel() {
    private val _topicRows = MutableStateFlow<List<Pair<String, List<MediaItem>>>>(emptyList())
    /**
     * Picture rows for your top interests, from the video sites (the same searches For You runs, so
     * usually already cached): something to tap before typing anything.
     */
    val topicRows: StateFlow<List<Pair<String, List<MediaItem>>>> = _topicRows
    /** Long videos and comics you left partway, most recent first: they carry on from where you stopped. */
    /** Posts you've watched before (from history): marked in the results so you can skip them. */
    val watched: StateFlow<Set<String>> = library.keys(dev.pfeed.data.repo.LibraryKind.HISTORY)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val continueWatching: StateFlow<List<MediaItem>> = library.items(dev.pfeed.data.repo.LibraryKind.HISTORY)
        .map { list ->
            list.filter { dev.pfeed.player.ResumePoints.get(it.key) != null || (it.isGallery && dev.pfeed.player.ComicPages.get(it.key) != null) }.take(12)
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openList(items: List<MediaItem>, index: Int, title: String): String = holder.put(items, index, title = title)

    /** Creators and tags you follow, for typeahead. */
    val followed: StateFlow<List<String>> = library.follows
        .map { f -> (f.creators.map { it.substringBefore('@') } + f.tags.map { it.replace('_', ' ') }).distinct() }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui
    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters

    val recentSearches: StateFlow<List<String>> = recent.items

    private val _interests = MutableStateFlow<List<String>>(emptyList())
    /** Search terms built from what For You has learned you like. */
    val interests: StateFlow<List<String>> = _interests

    val sources: StateFlow<List<Pair<String, String>>> = registry.observe()
        .map { list -> list.filter { it.enabled }.map { it.adapter.id to it.adapter.displayName } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val visible: StateFlow<List<MediaItem>> = combine(_ui, _filters) { ui, f ->
        val kept = ui.results.filter(f::matches)
        // Unknown lengths go last either way.
        when (f.sort) {
            SortOrder.BEST -> kept
            SortOrder.LONGEST -> kept.sortedByDescending { it.durationSec ?: -1 }
            SortOrder.SHORTEST -> kept.sortedBy { it.durationSec ?: Int.MAX_VALUE }
        }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null

    init {
        viewModelScope.launch { recent.load() }
        viewModelScope.launch {
            _interests.value = runCatching { signals.topInterests(limit = 30).first }.getOrDefault(emptyList())
                .map { it.first }
                .filterNot { it.startsWith("@") || it.startsWith("creator:") || dev.pfeed.domain.reco.Features.isMeta(it) }
                .map { it.removePrefix("w:").replace('_', ' ').trim() }
                .filter { it.length in 2..24 }
                .distinct()
                .take(10)
            // Nothing learned yet: popular topics instead, so a new install still has pictures to tap.
            loadTopicRows(_interests.value.ifEmpty { POPULAR.take(TOPIC_ROWS) }.take(TOPIC_ROWS))
        }
    }

    private fun loadTopicRows(topics: List<String>) {
        val tube = registry.byId("tube") ?: return
        topics.forEach { topic ->
            viewModelScope.launch {
                val blocklist = runCatching { blocks.current() }.getOrNull()
                val items = runCatching { tube.search(topic, null).items }.getOrDefault(emptyList())
                    .filter { it.thumbnailUrl != null && (blocklist?.allows(it) != false) }
                    .distinctBy { it.title?.lowercase() ?: it.key }
                    .take(12)
                if (items.size >= 4) _topicRows.update { rows -> (rows + (topic to items)).sortedBy { topics.indexOf(it.first) } }
            }
        }
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q) }

    fun clearQuery() {
        job?.cancel()
        _ui.value = SearchUi()
    }

    /** Run [q] (or the current text). */
    fun search(q: String = _ui.value.query) {
        val query = q.trim()
        if (query.isEmpty()) return
        job?.cancel()
        _ui.update {
            it.copy(query = query, searched = query, loading = true, total = 0, results = emptyList(), errors = emptyList(), elapsedMs = null, sourcesWithResults = 0)
        }
        // What you search for is what you want more of: For You learns it too (not in private mode).
        viewModelScope.launch {
            if (settingsRepo.get().privateMode) return@launch
            recent.add(query)
            runCatching { signals.recordSearch(query) }
        }
        // Combos: "anal + pov" needs every part; "-toys" leaves a word out. The sites are asked for
        // the first part, and the rest is checked against each post's tags and title.
        val combo = Combo.parse(query)
        job = viewModelScope.launch {
            repo.search(combo.ask).collect { r ->
                _ui.update {
                    it.copy(
                        loading = !r.done,
                        pending = r.pending,
                        total = maxOf(it.total, r.pending),
                        results = if (combo.isPlain) r.items else r.items.filter(combo::matches),
                        errors = r.errors,
                        elapsedMs = r.elapsedMs,
                        sourcesWithResults = r.sourcesWithResults,
                    )
                }
            }
            _ui.update { it.copy(loading = false, pending = 0) }
        }
    }

    fun removeRecent(q: String) = viewModelScope.launch { recent.remove(q) }
    fun clearRecent() = viewModelScope.launch { recent.clear() }

    fun toggleSource(id: String) = _filters.update { f -> f.copy(sources = if (id in f.sources) f.sources - id else f.sources + id) }
    fun setType(t: TypeFilter) = _filters.update { it.copy(type = t) }
    fun setDuration(d: DurationFilter) = _filters.update { it.copy(duration = d) }
    fun setSort(o: SortOrder) = _filters.update { it.copy(sort = o) }
    fun resetSheetFilters() = _filters.update { it.copy(sources = emptySet(), duration = DurationFilter.ANY, sort = SortOrder.BEST) }

    private companion object {
        const val TOPIC_ROWS = 3
    }

    /** Opens the results at [index]; past the last one it keeps going as an endless feed of the search. */
    val savedKeys: StateFlow<Set<String>> = library.keys(dev.pfeed.data.repo.LibraryKind.SAVED)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Save (or unsave) a result straight from the grid; saving teaches For You, as it does in the feed. */
    fun toggleSave(item: MediaItem) = viewModelScope.launch {
        if (item.key in savedKeys.value) library.remove(dev.pfeed.data.repo.LibraryKind.SAVED, item)
        else {
            library.add(dev.pfeed.data.repo.LibraryKind.SAVED, item)
            runCatching { signals.record(item, dev.pfeed.domain.reco.Signal(dev.pfeed.domain.reco.SignalType.SAVE)) }
        }
    }

    fun open(index: Int): String = holder.put(
        visible.value,
        index,
        continueWith = _ui.value.searched?.takeIf { !_filters.value.active },
        title = _ui.value.searched?.let { "“$it”" },
    )
}

/** Shown before the first search when nothing has been learned yet. */
/** A search with "+" (all of these) and "-word" (none of this), checked against tags and titles. */
internal data class Combo(val ask: String, val need: List<String>, val without: List<String>) {
    val isPlain: Boolean get() = need.isEmpty() && without.isEmpty()

    fun matches(item: MediaItem): Boolean {
        val text = (item.tags + listOfNotNull(item.title, item.creator)).joinToString(" ").lowercase().replace('_', ' ')
        return need.all { it in text } && without.none { it in text }
    }

    companion object {
        fun parse(query: String): Combo {
            val words = query.split(Regex("\\s+")).filter { it.isNotBlank() }
            val without = words.filter { it.length > 1 && it.startsWith("-") }.map { it.drop(1).lowercase() }
            val rest = words.filterNot { it.length > 1 && it.startsWith("-") }.joinToString(" ")
            val parts = rest.split('+').map { it.trim().lowercase().replace('_', ' ') }.filter { it.isNotEmpty() }
            val ask = parts.firstOrNull() ?: rest.ifBlank { query }
            return Combo(ask, parts.drop(1), without)
        }
    }
}

private val POPULAR = listOf(
    "amateur", "bondage", "rough", "pov", "blowjob", "anal", "bdsm", "cosplay",
    "milf", "deepthroat", "gangbang", "hentai", "femdom", "lingerie", "creampie", "redhead",
)

@Composable
fun SearchScreen(nav: NavController, vm: SearchViewModel = hiltViewModel()) {
    // Grids on this screen come alive: the video nearest the middle plays in its tile.
    androidx.compose.runtime.CompositionLocalProvider(
        dev.pfeed.ui.common.LocalLivePreview provides dev.pfeed.ui.common.rememberLivePreview(vm.livePreviews),
    ) { SearchScreenContent(nav, vm) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreenContent(nav: NavController, vm: SearchViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val visible by vm.visible.collectAsStateWithLifecycle()
    val recent by vm.recentSearches.collectAsStateWithLifecycle()
    val interests by vm.interests.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val topicRows by vm.topicRows.collectAsStateWithLifecycle()
    val followed by vm.followed.collectAsStateWithLifecycle()
    val watchedKeys by vm.watched.collectAsStateWithLifecycle()
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()
    // Press and hold a result: save it, or open it on its site, without leaving the results.
    var holding by remember { mutableStateOf<MediaItem?>(null) }
    holding?.let { item ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val saved = item.key in savedKeys
        @OptIn(ExperimentalMaterial3Api::class)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { holding = null },
            properties = androidx.compose.material3.ModalBottomSheetProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    item.title?.takeIf { it.isNotBlank() } ?: Display.source(item, ""),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                dev.pfeed.ui.common.SettingRow(
                    if (saved) "Remove from Saved" else "Save",
                    icon = if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    onClick = { vm.toggleSave(item); holding = null },
                )
                if (item.pageUrl.isNotBlank()) dev.pfeed.ui.common.SettingRow(
                    "Open on the site",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    iconTint = dev.pfeed.ui.theme.TextLow,
                    onClick = { dev.pfeed.ui.common.openExternal(context, item.pageUrl); holding = null },
                )
                if (item.pageUrl.isNotBlank()) dev.pfeed.ui.common.SettingRow(
                    "Copy link",
                    icon = Icons.Filled.Link,
                    iconTint = dev.pfeed.ui.theme.TextLow,
                    onClick = { dev.pfeed.ui.common.copySensitive(context, "Link", item.pageUrl); holding = null },
                )
            }
        }
    }
    val focus = LocalFocusManager.current
    var showFilters by remember { mutableStateOf(false) }

    val run: (String) -> Unit = { q -> focus.clearFocus(); vm.search(q) }
    // Search tapped again in the tab bar: straight into the search box.
    val searchBox = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        dev.pfeed.ui.BottomBar.reselected.collect { route -> if (route == dev.pfeed.ui.Routes.SEARCH) runCatching { searchBox.requestFocus() } }
    }

    Column(Modifier.fillMaxSize()) {
        // The big title steps aside once you're searching, so results get the room.
        androidx.compose.animation.AnimatedVisibility(
            visible = ui.query.isBlank(),
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            dev.pfeed.ui.common.ScreenHeader("Search")
        }
        if (ui.query.isNotBlank()) Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = ui.query,
                onValueChange = vm::setQuery,
                placeholder = {
                    // Now and then, the trick: combine with +, leave out with -.
                    val tip = remember { (System.currentTimeMillis() / 60_000) % 3 == 0L }
                    Text(if (tip) "Try: pov + redhead -toys" else "Search every site", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = vm::clearQuery) { Icon(Icons.Filled.Close, "Clear") }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    cursorColor = Accent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { run(ui.query) }),
                modifier = Modifier.weight(1f).focusRequester(searchBox),
            )
            IconButton(onClick = { showFilters = true }) {
                BadgedBox(badge = { if (filters.sheetCount > 0) Badge(containerColor = Accent) { Text("${filters.sheetCount}") } }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Filters")
                }
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TypeFilter.entries.forEach { t ->
                FilterChip(
                    selected = filters.type == t,
                    onClick = { vm.setType(t) },
                    label = { Text(t.label) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
        }

        if (ui.searched == null) {
            SuggestionsPane(
                continueWatching = continueWatching,
                topicRows = topicRows,
                onMix = { name, list -> nav.navigate(Routes.viewer(vm.openList(list, 0, "Mix · $name"))) },
                onTopicItem = { topic, index -> nav.navigate(Routes.viewer(vm.openList(topicRows.first { it.first == topic }.second, index, topic))) },
                onContinue = { index -> nav.navigate(Routes.viewer(vm.openList(continueWatching, index, "Continue"))) },
                typed = ui.query,
                followed = followed,
                recent = recent,
                interests = interests,
                onPick = run,
                onRemoveRecent = vm::removeRecent,
                onClearRecent = vm::clearRecent,
            )
        } else {
            ResultsPane(
                watched = watchedKeys,
                onLongPress = { holding = it },
                ui = ui,
                visible = visible,
                filtered = visible.size < ui.results.size,
                onOpen = { index -> nav.navigate(Routes.viewer(vm.open(index))) },
                onResetFilters = { vm.setType(TypeFilter.ALL); vm.resetSheetFilters() },
            )
        }
    }

    if (showFilters) {
        ModalBottomSheet(
            onDismissRequest = { showFilters = false },
            properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            FiltersSheet(
                filters = filters,
                sources = sources,
                onToggleSource = vm::toggleSource,
                onDuration = vm::setDuration,
                onSort = vm::setSort,
                onReset = vm::resetSheetFilters,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionsPane(
    continueWatching: List<MediaItem>,
    topicRows: List<Pair<String, List<MediaItem>>>,
    onMix: (String, List<MediaItem>) -> Unit = { _, _ -> },
    onTopicItem: (String, Int) -> Unit,
    onContinue: (Int) -> Unit,
    typed: String,
    followed: List<String>,
    recent: List<String>,
    interests: List<String>,
    onPick: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        // As you type: matching searches you've made, things you like and common tags.
        val t = typed.trim().lowercase()
        if (t.length >= 2) {
            val vocab = remember(recent, interests, followed) {
                (recent + followed + interests + POPULAR + dev.pfeed.data.settings.SourceCatalog.tubeSections.drop(2) +
                    dev.pfeed.data.settings.SourceCatalog.comicTags.map { it.replace('-', ' ') }).distinctBy { it.lowercase() }
            }
            val matches = (vocab.filter { it.lowercase().startsWith(t) } + vocab.filter { it.lowercase().contains(t) })
                .distinct().filter { it.lowercase() != t }.take(8)
            matches.forEach { q ->
                // What kind of suggestion it is, at a glance: one you searched before, one you follow,
                // or a topic; and the part you've typed in bold.
                val kind = when {
                    recent.any { it.equals(q, true) } -> Icons.Filled.History
                    followed.any { it.equals(q, true) } -> Icons.Filled.Favorite
                    else -> Icons.Filled.Search
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(q) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(kind, contentDescription = null, tint = if (kind == Icons.Filled.Favorite) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
                    val at = q.lowercase().indexOf(t)
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            if (at < 0) append(q) else {
                                append(q.substring(0, at))
                                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White))
                                append(q.substring(at, at + t.length))
                                pop()
                                append(q.substring(at + t.length))
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.NorthWest, contentDescription = null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 8.dp).size(18.dp))
                }
            }
            if (matches.isNotEmpty()) Spacer(Modifier.height(12.dp))
        }
        if (t.isEmpty() && topicRows.isNotEmpty()) {
            Hero(topicRows, onTopicItem)
            Spacer(Modifier.height(22.dp))
        }
        if (t.isEmpty() && continueWatching.isNotEmpty()) {
            SectionTitle("Continue")
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(continueWatching.size, key = { continueWatching[it].key }) { i -> ContinueCard(continueWatching[i]) { onContinue(i) } }
            }
            Spacer(Modifier.height(20.dp))
        }
        // Your mixes: two of your interests woven together, as playlists you can start in one tap.
        val mixes = remember(topicRows) {
            val rows = topicRows.filter { it.second.size >= 4 }
            val pairs = listOf(0 to 1, 2 to 3, 0 to 2, 1 to 3).filter { (a, b) -> a < rows.size && b < rows.size }.take(3)
            pairs.map { (a, b) ->
                val first = rows[a].second; val second = rows[b].second
                val woven = (0 until maxOf(first.size, second.size)).flatMap { i -> listOfNotNull(first.getOrNull(i), second.getOrNull(i)) }
                    .distinctBy { it.key }
                "${rows[a].first} + ${rows[b].first}" to woven
            }
        }
        if (t.isEmpty() && mixes.isNotEmpty()) {
            SectionTitle("Your mixes")
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(mixes.size, key = { mixes[it].first }) { i ->
                    val (name, list) = mixes[i]
                    MixCard(i + 1, name, list) { onMix(name, list) }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        if (t.isEmpty()) topicRows.forEach { (topic, items) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(topic.replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                TextButton(onClick = { onPick(topic) }) { Text("See all") }
            }
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items.size, key = { items[it].key }) { i -> TopicTile(items[i]) { onTopicItem(topic, i) } }
            }
            Spacer(Modifier.height(18.dp))
        }
        if (recent.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Recent", Modifier.weight(1f))
                TextButton(onClick = onClearRecent) { Text("Clear") }
            }
            recent.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(q) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 8.dp))
                    Text(q, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { onRemoveRecent(q) }) {
                        Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        if (interests.isNotEmpty()) {
            SectionTitle("Based on what you like")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                interests.forEach { q -> TopicChip(q, highlighted = true) { onPick(q) } }
            }
            Spacer(Modifier.height(16.dp))
        }
        // Surprise me: somewhere you haven't been. A random topic that isn't one of yours yet.
        SurpriseCard {
            val mine = interests.map { it.lowercase() }.toSet()
            val pool = (dev.pfeed.data.settings.SourceCatalog.redgifsCategories + POPULAR).map { it.lowercase() }.distinct().filter { it !in mine }
            pool.randomOrNull()?.let(onPick)
        }
        Spacer(Modifier.height(22.dp))
        SectionTitle("Popular")
        // Browse cards, two by two, each in a colour of its own (the same colour every time).
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            POPULAR.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { q -> BrowseCard(q, Modifier.weight(1f)) { onPick(q) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A wide card with a die on it: a random topic you haven't explored. */
@Composable
private fun SurpriseCard(onClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val spin = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF312E81), Color(0xFF7C3AED), Color(0xFFDB2777))))
            .clickable {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                scope.launch {
                    spin.animateTo(spin.value + 360f, androidx.compose.animation.core.tween(420))
                    onClick()
                }
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Casino,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(34.dp).graphicsLayer { rotationZ = spin.value },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Surprise me", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = Color.White)
            Text("Something you haven't explored yet", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White.copy(alpha = 0.8f))
    }
}

private val BROWSE_HUES = listOf(338f, 352f, 12f, 24f, 290f, 268f, 248f, 205f, 318f, 190f)

/** A mix: a big card with three of its posts fanned out, its name, and how many it holds. */
/**
 * The top of Search: one big card per thing you like, playing silently, sliding to the next on
 * its own every few seconds. Tap to watch it among the rest of that topic.
 */
@Composable
private fun Hero(topicRows: List<Pair<String, List<MediaItem>>>, onTopicItem: (String, Int) -> Unit) {
    val picks = remember(topicRows) {
        topicRows.take(HERO_MAX).mapNotNull { (topic, items) ->
            val i = items.indexOfFirst { it.type == MediaType.VIDEO || it.type == MediaType.GIF }.takeIf { it >= 0 } ?: 0
            items.getOrNull(i)?.let { Triple(topic, i, it) }
        }
    }
    if (picks.isEmpty()) return
    val pager = androidx.compose.foundation.pager.rememberPagerState { picks.size }
    // Moves on by itself, unless you're holding or swiping it.
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pager, picks.size, dragged) {
        if (dragged || picks.size < 2) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(HERO_DWELL_MS)
            pager.animateScrollToPage((pager.currentPage + 1) % picks.size, animationSpec = androidx.compose.animation.core.tween(650))
        }
    }
    val live = dev.pfeed.ui.common.LocalLivePreview.current
    Column {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val (topic, index, item) = picks[page]
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onTopicItem(topic, index) },
            ) {
                coil.compose.AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = item.title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (live != null && page == pager.settledPage) live(item)
                dev.pfeed.ui.common.MediaMask()
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(
                        topic.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1,
                    )
                    val by = Display.creator(item)?.let { "@$it" } ?: Display.source(item, item.sourceId)
                    Text(by, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                }
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(14.dp).clip(RoundedCornerShape(50)).background(dev.pfeed.ui.theme.BrandGradient)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Watch", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
                }
            }
        }
        // Where you are: a dash for the current card, dots for the rest.
        if (picks.size > 1) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            repeat(picks.size) { i ->
                val on = i == pager.currentPage
                val w by androidx.compose.animation.core.animateDpAsState(if (on) 18.dp else 6.dp, label = "heroDot")
                Box(
                    Modifier.padding(horizontal = 3.dp).height(6.dp).width(w).clip(RoundedCornerShape(50))
                        .background(if (on) Accent else Color.White.copy(alpha = 0.25f)),
                )
            }
        }
    }
}

private const val HERO_MAX = 6
private const val HERO_DWELL_MS = 6_500L

@Composable
private fun MixCard(number: Int, name: String, items: List<MediaItem>, onClick: () -> Unit) {
    val hue = remember(name) { BROWSE_HUES[(name.hashCode() and 0x7fffffff) % BROWSE_HUES.size] }
    Box(
        Modifier
            .width(168.dp)
            .height(214.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.hsl(hue, 0.6f, 0.42f), Color.hsl((hue + 40f) % 360f, 0.65f, 0.16f))))
            .clickable(onClick = onClick),
    ) {
        // Three covers, fanned like a hand of cards.
        items.take(3).forEachIndexed { i, item ->
            val angle = listOf(-9f, 0f, 9f)[i]
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp)
                    .graphicsLayer { rotationZ = angle; translationX = (i - 1) * 26.dp.toPx(); translationY = if (i == 1) 0f else 8.dp.toPx() }
                    .width(80.dp)
                    .height(108.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                coil.compose.AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (dev.pfeed.ui.common.LocalBlurPreviews.current) Modifier.blur(16.dp) else Modifier),
                )
                dev.pfeed.ui.common.MediaMask()
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text("Mix $number", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
            Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("${items.size} posts", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(12.dp).size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.Black, modifier = Modifier.size(24.dp)) }
    }
}

/** A topic to browse, as a colour card with its name. */
@Composable
private fun BrowseCard(topic: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Hues picked to sit well beside the app's pink and orange (no swampy greens).
    val hue = remember(topic) { BROWSE_HUES[(topic.hashCode() and 0x7fffffff) % BROWSE_HUES.size] }
    Box(
        modifier
            .height(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color.hsl(hue, 0.55f, 0.36f), Color.hsl((hue + 35f) % 360f, 0.6f, 0.2f)),
                ),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            topic,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** A post in a topic row: its picture, how long, and where it's from. */
@Composable
private fun TopicTile(item: MediaItem, onClick: () -> Unit) {
    Box(Modifier.width(112.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick)) {
        coil.compose.AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = item.title,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (dev.pfeed.ui.common.LocalBlurPreviews.current) Modifier.blur(24.dp) else Modifier),
        )
        dev.pfeed.ui.common.MediaMask()
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.4f).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
        // Same quiet line as the result grid: where it's from, then how long.
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Display.source(item, ""), color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            item.durationSec?.let { Text(dev.pfeed.ui.common.formatDuration(it), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
        }
    }
}

/** A video you left partway: its picture with how far you got, and how much is left. */
@Composable
private fun ContinueCard(item: MediaItem, onClick: () -> Unit) {
    // A video: time watched of its length. A comic: the page you're on of its pages.
    val page = remember(item.key) { if (item.isGallery) dev.pfeed.player.ComicPages.get(item.key) else null }
    val at = if (page != null) page + 1L else remember(item.key) { dev.pfeed.player.ResumePoints.get(item.key) ?: 0L }
    val total = if (page != null) item.pages.size.toLong() else (item.durationSec ?: 0) * 1000L
    Column(Modifier.width(176.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            coil.compose.AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = item.title,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (dev.pfeed.ui.common.LocalBlurPreviews.current) Modifier.blur(24.dp) else Modifier),
            )
            dev.pfeed.ui.common.MediaMask()
            Box(Modifier.align(Alignment.Center).size(38.dp).background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (page != null) Icons.AutoMirrored.Filled.MenuBook else Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            if (total > 0) {
                Text(
                    if (page != null) "Page ${page + 1} of ${item.pages.size}" else dev.pfeed.ui.common.formatDuration(((total - at) / 1000).toInt().coerceAtLeast(0)) + " left",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((at.toFloat() / total).coerceIn(0.02f, 1f)).background(dev.pfeed.ui.theme.BrandGradient))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title ?: Display.source(item, ""), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.9f))
    }
}

/** A filled pill (no outline): reads as a tappable topic, the way current apps draw them. Your own interests are tinted. */
@Composable
private fun TopicChip(text: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (highlighted) Color.White else Color.White.copy(alpha = 0.88f),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) Accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(vertical = 10.dp),
    )
}

@Composable
private fun ResultsPane(
    ui: SearchUi,
    visible: List<MediaItem>,
    filtered: Boolean,
    watched: Set<String> = emptySet(),
    onLongPress: ((MediaItem) -> Unit)? = null,
    onOpen: (Int) -> Unit,
    onResetFilters: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // How many sites have answered, filling in the brand gradient as they do.
        if (ui.loading) {
            val answered by androidx.compose.animation.core.animateFloatAsState(
                if (ui.total > 0) 1f - ui.pending.toFloat() / ui.total else 0f,
                label = "searchProgress",
            )
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(answered.coerceIn(0.04f, 1f)).background(dev.pfeed.ui.theme.BrandGradient))
            }
        }
        if (visible.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("${visible.size} results")
                        if (ui.loading && ui.pending > 0) append(" · still searching…")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                dev.pfeed.ui.common.BrandButton("Play all", onClick = { onOpen(0) }, icon = Icons.Filled.PlayArrow, compact = true)
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                // Results come from every site at once: each tile says which.
                visible.isNotEmpty() -> MediaGrid(visible, onOpen = onOpen, label = { Display.source(it, it.sourceId) }, seen = { it.key in watched }, onLongPress = onLongPress)
                ui.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    dev.pfeed.ui.common.BrandSpinner(size = 40.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Searching every source…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                else -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (filtered) "Nothing matches these filters" else "No results for “${ui.searched}”",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (filtered) "${ui.results.size} results are hidden by your filters."
                        else "Try a single word, or a different spelling.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                    )
                    if (filtered) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onResetFilters) { Text("Clear filters") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersSheet(
    filters: SearchFilters,
    sources: List<Pair<String, String>>,
    onToggleSource: (String) -> Unit,
    onDuration: (DurationFilter) -> Unit,
    onSort: (SortOrder) -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (filters.sheetCount > 0) TextButton(onClick = onReset) { Text("Reset") }
        }
        SectionTitle("Order")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortOrder.entries.forEach { o ->
                FilterChip(
                    selected = filters.sort == o, onClick = { onSort(o) }, label = { Text(o.label) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White),
                )
            }
        }
        SectionTitle("Length")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DurationFilter.entries.forEach { d ->
                FilterChip(
                    selected = filters.duration == d, onClick = { onDuration(d) }, label = { Text(d.label) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White),
                )
            }
        }
        SectionTitle("Sources")
        Text(
            if (filters.sources.isEmpty()) "Searching all sources. Pick some to search only those." else "Only the selected sources.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sources.forEach { (id, name) ->
                FilterChip(
                    selected = id in filters.sources, onClick = { onToggleSource(id) }, label = { Text(name) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
