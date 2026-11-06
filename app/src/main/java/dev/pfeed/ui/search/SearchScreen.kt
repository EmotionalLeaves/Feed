package dev.pfeed.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pfeed.data.repo.SearchRepository
import dev.pfeed.data.repo.TransientFeedHolder
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.source.SourceRegistry
import dev.pfeed.ui.Routes
import dev.pfeed.ui.common.MediaGrid
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
    SHORT("< 1 min", 0 until 60),
    MEDIUM("1–5 min", 60..300),
    LONG("> 5 min", 301..Int.MAX_VALUE),
}

data class SearchFilters(
    val sources: Set<String> = emptySet(), // empty = all
    val types: Set<MediaType> = emptySet(), // empty = all
    val duration: DurationFilter = DurationFilter.ANY,
) {
    fun matches(item: MediaItem): Boolean {
        if (sources.isNotEmpty() && item.sourceId !in sources) return false
        if (types.isNotEmpty() && item.type !in types) return false
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
    registry: SourceRegistry,
) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui
    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters

    val sources: StateFlow<List<Pair<String, String>>> = registry.observe()
        .map { list -> list.filter { it.enabled }.map { it.adapter.id to it.adapter.displayName } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val visible: StateFlow<List<MediaItem>> = combine(_ui, _filters) { ui, f -> ui.results.filter(f::matches) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null

    fun setQuery(q: String) = _ui.update { it.copy(query = q) }

    fun search() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        _ui.update { it.copy(searched = q, loading = true, results = emptyList(), errors = emptyList(), elapsedMs = null, sourcesWithResults = 0) }
        job = viewModelScope.launch {
            repo.search(q).collect { r ->
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

    fun toggleSource(id: String) = _filters.update { f -> f.copy(sources = f.sources.toggle(id)) }
    fun toggleType(t: MediaType) = _filters.update { f -> f.copy(types = f.types.toggle(t)) }
    fun setDuration(d: DurationFilter) = _filters.update { it.copy(duration = d) }

    fun open(index: Int): String = holder.put(visible.value, index)

    private fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v
}

@Composable
fun SearchScreen(nav: NavController, vm: SearchViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val visible by vm.visible.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Search all sources") },
            singleLine = true,
            trailingIcon = { IconButton(onClick = { focus.clearFocus(); vm.search() }) { Icon(Icons.Filled.Search, "Search") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sources.forEach { (id, name) ->
                FilterChip(selected = id in filters.sources, onClick = { vm.toggleSource(id) }, label = { Text(name) })
            }
            listOf(MediaType.VIDEO to "Video", MediaType.IMAGE to "Image", MediaType.GIF to "GIF", MediaType.EMBED to "Embed").forEach { (t, label) ->
                FilterChip(selected = t in filters.types, onClick = { vm.toggleType(t) }, label = { Text(label) })
            }
            DurationFilter.entries.forEach { d ->
                FilterChip(selected = filters.duration == d, onClick = { vm.setDuration(d) }, label = { Text(d.label) })
            }
        }
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        val status = buildString {
            if (ui.searched != null) {
                append("${visible.size} results")
                if (ui.sourcesWithResults > 0) append(" from ${ui.sourcesWithResults} source${if (ui.sourcesWithResults == 1) "" else "s"}")
                if (ui.loading) append(" · still searching ${ui.pending}…")
                else ui.elapsedMs?.let { append(" · ${"%.1f".format(it / 1000.0)} s") }
                if (ui.errors.isNotEmpty()) append(" · ${ui.errors.size} didn't answer")
            }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Box(Modifier.fillMaxSize()) {
            when {
                visible.isNotEmpty() -> MediaGrid(visible, onOpen = { index -> nav.navigate(Routes.viewer(vm.open(index))) })
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.searched != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (ui.results.isNotEmpty()) "Nothing matches the filters above." else "No results for \"${ui.searched}\".",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (ui.results.isNotEmpty()) "Clear a filter to see the ${ui.results.size} results."
                        else "Try one or two words, or a tag like rough_sex. rule34 and gelbooru need a key (Settings → Sources).",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (ui.errors.isNotEmpty()) {
                        Text(ui.errors.joinToString("\n"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}
