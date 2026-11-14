package dev.pfeed.ui.search

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
import androidx.compose.material3.SuggestionChip
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

/** One of the main "what kind of thing" choices; null type = everything. */
enum class TypeFilter(val label: String, val types: Set<MediaType>) {
    ALL("All", emptySet()),
    VIDEOS("Videos", setOf(MediaType.VIDEO, MediaType.EMBED)),
    IMAGES("Images", setOf(MediaType.IMAGE)),
    GIFS("GIFs", setOf(MediaType.GIF)),
}

data class SearchFilters(
    val sources: Set<String> = emptySet(), // empty = all
    val type: TypeFilter = TypeFilter.ALL,
    val duration: DurationFilter = DurationFilter.ANY,
) {
    /** How many of the filters hidden in the sheet are active (shown as a badge). */
    val sheetCount: Int get() = (if (sources.isNotEmpty()) 1 else 0) + (if (duration != DurationFilter.ANY) 1 else 0)

    /** Any filter on: the endless continuation can't honour them, so it isn't offered. */
    val active: Boolean get() = sheetCount > 0 || type != TypeFilter.ALL

    fun matches(item: MediaItem): Boolean {
        if (sources.isNotEmpty() && item.sourceId !in sources) return false
        if (type.types.isNotEmpty() && item.type !in type.types) return false
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
    registry: SourceRegistry,
) : ViewModel() {
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

    val visible: StateFlow<List<MediaItem>> = combine(_ui, _filters) { ui, f -> ui.results.filter(f::matches) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null

    init {
        viewModelScope.launch { recent.load() }
        viewModelScope.launch {
            _interests.value = runCatching { signals.topInterests(limit = 30).first }.getOrDefault(emptyList())
                .map { it.first }
                .filterNot { it.startsWith("@") || it.startsWith("creator:") }
                .map { it.removePrefix("w:").replace('_', ' ').trim() }
                .filter { it.length in 2..24 }
                .distinct()
                .take(10)
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
            it.copy(query = query, searched = query, loading = true, results = emptyList(), errors = emptyList(), elapsedMs = null, sourcesWithResults = 0)
        }
        viewModelScope.launch { recent.add(query) }
        job = viewModelScope.launch {
            repo.search(query).collect { r ->
                _ui.update {
                    it.copy(
                        loading = !r.done,
                        pending = r.pending,
                        results = r.items,
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
    fun resetSheetFilters() = _filters.update { it.copy(sources = emptySet(), duration = DurationFilter.ANY) }

    /** Opens the results at [index]; past the last one it keeps going as an endless feed of the search. */
    fun open(index: Int): String = holder.put(
        visible.value,
        index,
        continueWith = _ui.value.searched?.takeIf { !_filters.value.active },
        title = _ui.value.searched?.let { "“$it”" },
    )
}

/** Shown before the first search when nothing has been learned yet. */
private val POPULAR = listOf(
    "amateur", "bondage", "rough", "pov", "blowjob", "anal", "bdsm", "cosplay",
    "milf", "deepthroat", "gangbang", "hentai", "femdom", "lingerie", "creampie", "redhead",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavController, vm: SearchViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val visible by vm.visible.collectAsStateWithLifecycle()
    val recent by vm.recentSearches.collectAsStateWithLifecycle()
    val interests by vm.interests.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var showFilters by remember { mutableStateOf(false) }

    val run: (String) -> Unit = { q -> focus.clearFocus(); vm.search(q) }

    Column(Modifier.fillMaxSize()) {
        dev.pfeed.ui.common.ScreenHeader("Search")
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = ui.query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search every source") },
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
                modifier = Modifier.weight(1f),
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
                recent = recent,
                interests = interests,
                onPick = run,
                onRemoveRecent = vm::removeRecent,
                onClearRecent = vm::clearRecent,
            )
        } else {
            ResultsPane(
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
        ) {
            FiltersSheet(
                filters = filters,
                sources = sources,
                onToggleSource = vm::toggleSource,
                onDuration = vm::setDuration,
                onReset = vm::resetSheetFilters,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionsPane(
    recent: List<String>,
    interests: List<String>,
    onPick: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                interests.forEach { q -> SuggestionChip(onClick = { onPick(q) }, label = { Text(q) }) }
            }
            Spacer(Modifier.height(16.dp))
        }
        SectionTitle("Popular")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            POPULAR.forEach { q -> SuggestionChip(onClick = { onPick(q) }, label = { Text(q) }) }
        }
        Spacer(Modifier.height(24.dp))
    }
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
    onOpen: (Int) -> Unit,
    onResetFilters: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
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
                Button(
                    onClick = { onOpen(0) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play all")
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                visible.isNotEmpty() -> MediaGrid(visible, onOpen = onOpen)
                ui.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
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
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (filters.sheetCount > 0) TextButton(onClick = onReset) { Text("Reset") }
        }
        SectionTitle("Length")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DurationFilter.entries.forEach { d ->
                FilterChip(selected = filters.duration == d, onClick = { onDuration(d) }, label = { Text(d.label) })
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
                FilterChip(selected = id in filters.sources, onClick = { onToggleSource(id) }, label = { Text(name) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
