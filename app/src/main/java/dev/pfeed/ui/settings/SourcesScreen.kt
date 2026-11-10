package dev.pfeed.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.data.settings.JsonFeed
import dev.pfeed.source.discovery.DiscoveredSource
import dev.pfeed.ui.common.findActivity
import dev.pfeed.ui.common.openExternal
import dev.pfeed.data.repo.BooruKeys
import androidx.compose.ui.text.input.PasswordVisualTransformation

private enum class QuickAddType(val label: String) {
    SEARCH("🔍 Search"),
    REDDIT("Reddit"),
    MASTODON("Mastodon"),
    BOORU("Booru"),
    DIRECT("Direct link"),
    RSS("RSS / Atom"),
    JSON("JSON API"),
    FOLDER("Local folder"),
}

@Composable
fun SourcesScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val auth by vm.authStatus.collectAsStateWithLifecycle()
    val searchState by vm.searchState.collectAsStateWithLifecycle()
    val keyHosts by vm.booruKeyHosts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var reorder by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BackHeader("Sources", nav)
            val totalFeeds = sources.filter { it.enabled }.sumOf { feedCount(it.adapter.id, s) ?: 1 }
            var healthTick by remember { mutableIntStateOf(0) }
            val skipped = remember(sources, healthTick) { sources.associate { it.adapter.id to vm.skippedFeeds(it.adapter.id) } }
            val totalSkipped = skipped.values.sum()
            val onCount = sources.count { it.enabled }
            Text(
                "$totalFeeds feeds from $onCount sources",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Each source pulls from many sites, subreddits and tags at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { reorder = !reorder }) { Text(if (reorder) "Done" else "Reorder") }
            }
            val checks by vm.checks.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Something not showing up? Test each source from this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.checkSources() }) { Text(if (checks.values.any { it == null }) "Checking…" else "Check sources") }
            }
            if (checks.isNotEmpty() && checks.values.none { it == null }) {
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val working = checks.values.count { it?.ok == true }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$working of ${checks.size} sources working from this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val report = sources.filter { it.adapter.id in checks }.joinToString("\n") { src ->
                            val r = checks[src.adapter.id]
                            "${if (r?.ok == true) "OK  " else "FAIL"} ${src.adapter.displayName}: ${r?.detail}"
                        }
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(report))
                    }) { Text("Copy report") }
                }
            }
            if (totalSkipped > 0) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$totalSkipped feeds kept answering \"not found\" or had nothing to show, so they're skipped for two days, then retried.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.retrySkippedFeeds(); healthTick++ }) { Text("Retry now") }
                }
            }

            // --- enabled / priority / sign-in ---
            sources.forEachIndexed { index, src ->
                val a = src.adapter
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.displayName, style = MaterialTheme.typography.titleMedium)
                            val status = when {
                                !a.isConfigured -> "Needs a build-time setting; not available in this build"
                                auth[a.id] != null -> auth[a.id]!!
                                a.requiresAuth -> "Sign-in required"
                                !src.enabled -> "Off"
                                else -> (feedSummary(a.id, s) ?: "On") +
                                    (skipped[a.id]?.takeIf { it > 0 }?.let { " · $it skipped (not found)" } ?: "")
                            }
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (src.enabled) 0.75f else 0.5f),
                            )
                            val resting = remember(sources, healthTick, checks) { if (src.enabled) vm.restingSites(a.id) else emptyList() }
                            if (resting.isNotEmpty()) {
                                Text(
                                    "Paused for a few minutes (not responding): " + resting.take(4).joinToString(", ") + if (resting.size > 4) " +${resting.size - 4}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (src.enabled && a.id in checks) {
                                val r = checks[a.id]
                                Text(
                                    r?.detail ?: "Checking…",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = when {
                                        r == null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        r.ok -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                        if (reorder) {
                            IconButton(onClick = { vm.moveSource(a.id, -1) }, enabled = index > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                            }
                            IconButton(onClick = { vm.moveSource(a.id, +1) }, enabled = index < sources.lastIndex) {
                                Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                            }
                        } else {
                            Switch(checked = src.enabled, enabled = a.isConfigured, onCheckedChange = { vm.setSourceEnabled(a.id, it) })
                        }
                    }
                    if (a.requiresAuth && a.isConfigured) {
                        OutlinedButton(
                            onClick = { context.findActivity()?.let { vm.authenticate(it, a.id) } },
                            modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
                        ) { Text("Sign in") }
                    }
                }
            }

            Header("Add more")
            QuickAdd(vm, searchState, onPickFolder = { folderPicker.launch(null) })

            Header("Your feeds")
            AddedList(vm, s)

            Header("Optional: board API keys")
            BoardKeys(vm, keyHosts)
            Spacer(Modifier.height(24.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun QuickAdd(vm: SettingsViewModel, searchState: SearchState, onPickFolder: () -> Unit) {
    var kind by remember { mutableStateOf(QuickAddType.SEARCH) }
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuickAddType.entries.forEach { t ->
                FilterChip(selected = kind == t, onClick = { kind = t }, label = { Text(t.label) })
            }
        }
        when (kind) {
            QuickAddType.SEARCH -> SourceSearchPanel(vm, searchState)
            QuickAddType.DIRECT -> OneField("Media or page URL", "https://…/clip.mp4", KeyboardType.Uri) { vm.addDirect(it) }
            QuickAddType.RSS -> OneField("Feed URL", "https://site/feed.xml", KeyboardType.Uri) { vm.addFeed(it) }
            QuickAddType.REDDIT -> OneField("Subreddit name or topic", "e.g. roughsex", KeyboardType.Text) { vm.addReddit(it) }
            QuickAddType.MASTODON -> TwoField("Instance host", "mastodon.social", "Any hashtag", "bdsm") { a, b -> vm.addMastodon(a, b) }
            QuickAddType.BOORU -> TwoField("Booru host", "rule34.xxx", "Any tags (space-separated)", "bondage roughsex") { a, b -> vm.addBooru(a, b) }
            QuickAddType.JSON -> JsonForm(vm)
            QuickAddType.FOLDER -> OutlinedButton(onClick = onPickFolder, modifier = Modifier.padding(vertical = 8.dp)) { Text("Pick a folder…") }
        }
    }
}

@Composable
private fun SourceSearchPanel(vm: SettingsViewModel, state: SearchState) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<DiscoveredSource>() }

    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            "Type any topic — the app searches Reddit, Mastodon, and Booru boards and adds matching sources.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Topic") },
                placeholder = { Text("e.g. bondage, gangbang, milf…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) vm.searchSources(query) }),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { vm.searchSources(query) },
                enabled = query.isNotBlank() && state !is SearchState.Loading,
            ) { Text("Search") }
        }

        Spacer(Modifier.height(8.dp))

        when (state) {
            is SearchState.Idle -> Unit
            is SearchState.Loading -> {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Searching Reddit, Mastodon, Booru boards…", style = MaterialTheme.typography.bodySmall)
                }
            }
            is SearchState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
            is SearchState.Results -> {
                // Sync selection with new results
                LaunchedEffect(state) { selected.clear(); selected.addAll(state.items) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.items.size} sources found for \"${state.query}\"", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { selected.clear() }) { Text("None") }
                        TextButton(onClick = { selected.clear(); selected.addAll(state.items) }) { Text("All") }
                    }
                }

                state.items.forEach { src ->
                    val isSelected = src in selected
                    Card(
                        onClick = { if (isSelected) selected.remove(src) else selected.add(src) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(src.label, style = MaterialTheme.typography.bodyMedium)
                                Text(src.description, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            androidx.compose.material3.Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked -> if (checked) selected.add(src) else selected.remove(src) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.addDiscovered(selected.toList()) },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Add, null)
                        Text(" Add ${selected.size} selected")
                    }
                    OutlinedButton(onClick = { vm.clearSearch() }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun OneField(label: String, placeholder: String, type: KeyboardType, onAdd: (String) -> Boolean) {
    var text by remember(label) { mutableStateOf("") }
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { if (onAdd(text)) text = "" }, enabled = text.isNotBlank()) { Text("Add") }
    }
}

@Composable
private fun TwoField(
    label1: String,
    ph1: String,
    label2: String,
    ph2: String,
    onAdd: (String, String) -> Boolean,
) {
    var a by remember(label1) { mutableStateOf("") }
    var b by remember(label1) { mutableStateOf("") }
    Column(Modifier.padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = a, onValueChange = { a = it }, label = { Text(label1) }, placeholder = { Text(ph1) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = b, onValueChange = { b = it }, label = { Text(label2) }, placeholder = { Text(ph2) },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            Button(onClick = { if (onAdd(a, b)) { a = ""; b = "" } }, enabled = a.isNotBlank()) { Text("Add") }
        }
    }
}

@Composable
private fun JsonForm(vm: SettingsViewModel) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var itemsPath by remember { mutableStateOf("") }
    var mediaPath by remember { mutableStateOf("url") }
    var advanced by remember { mutableStateOf(false) }
    var wrapPath by remember { mutableStateOf("") }
    var idPath by remember { mutableStateOf("id") }
    var titlePath by remember { mutableStateOf("title") }
    var creatorPath by remember { mutableStateOf("") }
    var thumbPath by remember { mutableStateOf("") }
    var tagsPath by remember { mutableStateOf("") }
    var pagePath by remember { mutableStateOf("") }
    var searchTemplate by remember { mutableStateOf("") }
    var typeHint by remember { mutableStateOf("auto") }

    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            "Point at any JSON endpoint and say where the fields live. {query} and {cursor} in the URLs are filled in.",
            style = MaterialTheme.typography.bodySmall,
        )
        Field("Name", name) { name = it }
        Field("URL template", url, "https://host/api?tag={query}&page={cursor}") { url = it }
        Field("Items path", itemsPath, "e.g. data.children") { itemsPath = it }
        Field("Media path", mediaPath, "e.g. url or data.url") { mediaPath = it }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Fewer options" else "More options") }
        AnimatedVisibility(advanced) {
            Column {
                Field("Item wrapper path", wrapPath, "e.g. data (applied before fields)") { wrapPath = it }
                Field("Id path", idPath) { idPath = it }
                Field("Title path", titlePath) { titlePath = it }
                Field("Creator path", creatorPath) { creatorPath = it }
                Field("Thumbnail path", thumbPath) { thumbPath = it }
                Field("Tags path", tagsPath) { tagsPath = it }
                Field("Page URL path", pagePath) { pagePath = it }
                Field("Search URL template", searchTemplate, "https://host/search?q={query}") { searchTemplate = it }
                Text("Media type", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto", "video", "image", "gif", "embed").forEach { t ->
                        FilterChip(selected = typeHint == t, onClick = { typeHint = t }, label = { Text(t) })
                    }
                }
            }
        }
        Button(
            onClick = {
                val ok = vm.addJson(
                    JsonFeed(
                        name = name.trim(),
                        urlTemplate = url.trim(),
                        itemsPath = itemsPath.trim(),
                        mediaPath = mediaPath.trim().ifBlank { "url" },
                        searchTemplate = searchTemplate.trim().ifBlank { null },
                        wrapPath = wrapPath.trim(),
                        idPath = idPath.trim().ifBlank { "id" },
                        titlePath = titlePath.trim().ifBlank { "title" },
                        creatorPath = creatorPath.trim(),
                        thumbnailPath = thumbPath.trim(),
                        tagsPath = tagsPath.trim(),
                        pagePath = pagePath.trim(),
                        typeHint = typeHint,
                    ),
                )
                if (ok) { name = ""; url = ""; itemsPath = ""; mediaPath = "url" }
            },
            enabled = name.isNotBlank() && url.isNotBlank() && itemsPath.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Add JSON source") }
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/**
 * Feeds grouped by kind, collapsed by default. There are hundreds of them (subreddits, board tags…);
 * drawing them all at once made this screen slow and impossible to scan.
 */
@Composable
private fun AddedList(vm: SettingsViewModel, s: dev.pfeed.data.settings.AppSettings) {
    val groups = listOf(
        FeedGroup("Subreddits", s.redditSubs.map { sub -> "r/$sub" to { vm.removeReddit(sub) } }),
        FeedGroup("Image board feeds", s.booruFeeds.map { f -> f.label to { vm.removeBooru(f) } }),
        FeedGroup("Mastodon feeds", s.mastodonFeeds.map { f -> f.label to { vm.removeMastodon(f) } }),
        FeedGroup("RSS feeds", s.rssFeeds.map { u -> u to { vm.removeFeed(u) } }),
        FeedGroup("Direct links", s.directUrls.map { u -> u to { vm.removeDirect(u) } }),
        FeedGroup("JSON APIs", s.jsonFeeds.map { f -> f.name to { vm.removeJson(f) } }),
        FeedGroup("Folders", s.localFolders.map { u -> (Uri.parse(u).lastPathSegment?.substringAfter(':') ?: u) to { vm.removeFolder(u) } }),
    ).filter { it.entries.isNotEmpty() }
    if (groups.isEmpty()) {
        Text("Nothing added yet.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    groups.forEach { g -> FeedGroupSection(g) }
}

private class FeedGroup(val title: String, val entries: List<Pair<String, () -> Any?>>)

@Composable
private fun FeedGroupSection(group: FeedGroup) {
    var open by remember(group.title) { mutableStateOf(false) }
    var filter by remember(group.title) { mutableStateOf("") }
    ListItem(
        headlineContent = { Text(group.title) },
        supportingContent = { Text("${group.entries.size}") },
        trailingContent = { Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand") },
        modifier = Modifier.clickable { open = !open },
    )
    if (open) {
        if (group.entries.size > 12) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Filter ${group.title.lowercase()}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val shown = group.entries.filter { filter.isBlank() || it.first.contains(filter, ignoreCase = true) }
        shown.take(MAX_SHOWN).forEach { (label, remove) -> Entry(label) { remove() } }
        if (shown.size > MAX_SHOWN) {
            Text(
                "+${shown.size - MAX_SHOWN} more — type above to find one",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private const val MAX_SHOWN = 60

@Composable
private fun Entry(label: String, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, maxLines = 2) },
        trailingContent = { IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove") } },
    )
}

/**
 * rule34.xxx and gelbooru.com only answer API requests that carry a free account's key. Their feeds
 * are skipped until a key is pasted here.
 */
@Composable
private fun BoardKeys(vm: SettingsViewModel, configured: Set<String>) {
    val context = LocalContext.current
    Text(
        "rule34.xxx and gelbooru.com work out of the box. For faster loading and more results, " +
            "add a free API key: make an account, open its options page, tick \"Generate New Key\", " +
            "save, then paste the &api_key=…&user_id=… line here.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    BooruKeys.NEEDS_KEY.forEach { (host, optionsUrl) ->
        var text by remember(host) { mutableStateOf("") }
        val has = host in configured
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(host, style = MaterialTheme.typography.titleMedium)
                Text(if (has) "Key saved — faster API access" else "No key — using public pages (slower)", style = MaterialTheme.typography.bodySmall)
                if (has) {
                    TextButton(onClick = { vm.clearBooruKey(host) }) { Text("Remove key") }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("&api_key=…&user_id=…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { if (vm.saveBooruKey(host, text)) text = "" }, enabled = text.isNotBlank()) { Text("Save") }
                        OutlinedButton(onClick = { openExternal(context, optionsUrl) }) { Text("Get a key") }
                    }
                }
            }
        }
    }
}

/** How many feeds/entries a source pulls from, or null if it has no countable list. */
private fun feedCount(id: String, s: dev.pfeed.data.settings.AppSettings): Int? = when (id) {
    "reddit" -> s.redditSubs.size
    "booru" -> s.booruFeeds.size
    "mastodon" -> s.mastodonFeeds.size
    "rss" -> s.rssFeeds.size
    "direct" -> s.directUrls.size
    "json" -> s.jsonFeeds.size
    "local" -> s.localFolders.size
    "kemono" -> s.kemonoHosts.size
    "redgifs" -> dev.pfeed.data.settings.SourceCatalog.redgifsTerms.size
    "tube" -> dev.pfeed.source.tube.TubeSite.ALL.size * dev.pfeed.data.settings.SourceCatalog.tubeSections.size
    "nhentai" -> dev.pfeed.data.settings.SourceCatalog.nhentaiQueries.size
    else -> null
}

/** One line under each source: what it's actually pulling from. */
private fun feedSummary(id: String, s: dev.pfeed.data.settings.AppSettings): String? = when (id) {
    "reddit" -> "${s.redditSubs.size} subreddits"
    "booru" -> {
        val sites = s.booruFeeds.map { it.host.lowercase().removePrefix("www.") }.distinct().size
        "${s.booruFeeds.size} tag feeds on $sites sites"
    }
    "mastodon" -> {
        val servers = s.mastodonFeeds.map { it.host.lowercase() }.distinct().size
        "${s.mastodonFeeds.size} hashtag feeds on $servers servers"
    }
    "kemono" -> s.kemonoHosts.joinToString(" + ").ifBlank { null }
    "redgifs" -> "${dev.pfeed.data.settings.SourceCatalog.redgifsTerms.size} trending category feeds"
    "tube" -> dev.pfeed.source.tube.TubeSite.ALL.joinToString(", ") { it.label } + " · ${dev.pfeed.data.settings.SourceCatalog.tubeSections.size} sections each"
    "nhentai" -> "${dev.pfeed.data.settings.SourceCatalog.nhentaiQueries.size} tag feeds, each gallery a comic"
    "philomena" -> "derpibooru.org + furbooru.org"
    "rss" -> if (s.rssFeeds.isEmpty()) "Add any RSS/Atom feed below" else "${s.rssFeeds.size} feeds"
    "direct" -> if (s.directUrls.isEmpty()) "Paste a video or image link below" else "${s.directUrls.size} links"
    "json" -> if (s.jsonFeeds.isEmpty()) "Advanced: map any JSON API" else "${s.jsonFeeds.size} APIs"
    "local" -> if (s.localFolders.isEmpty()) "Pick a folder on your phone below" else "${s.localFolders.size} folders"
    else -> null
}
