package dev.pfeed.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.data.settings.JsonFeed
import dev.pfeed.ui.common.findActivity

private enum class QuickAddType(val label: String) {
    DIRECT("Direct link"),
    RSS("RSS / Atom"),
    REDDIT("Reddit"),
    MASTODON("Mastodon"),
    BOORU("Booru"),
    JSON("JSON API"),
    FOLDER("Local folder"),
}

@Composable
fun SourcesScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val auth by vm.authStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BackHeader("Sources", nav)
            Text(
                "Order is priority: when the same item appears in two sources, the higher one wins. " +
                    "Turn a source on, then add entries below.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )

            // --- enabled / priority / sign-in ---
            sources.forEachIndexed { index, src ->
                val a = src.adapter
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.displayName, style = MaterialTheme.typography.titleMedium)
                            val status = when {
                                !a.isConfigured -> "Not configured (see SOURCES.md)"
                                auth[a.id] != null -> auth[a.id]!!
                                a.requiresAuth -> "Sign-in required"
                                else -> if (src.enabled) "Enabled" else "Disabled"
                            }
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.moveSource(a.id, -1) }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                        }
                        IconButton(onClick = { vm.moveSource(a.id, +1) }, enabled = index < sources.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                        }
                        Switch(checked = src.enabled, enabled = a.isConfigured, onCheckedChange = { vm.setSourceEnabled(a.id, it) })
                    }
                    if (a.requiresAuth && a.isConfigured) {
                        OutlinedButton(
                            onClick = { context.findActivity()?.let { vm.authenticate(it, a.id) } },
                            modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
                        ) { Text("Sign in") }
                    }
                }
            }

            Header("Quick add")
            QuickAdd(vm, onPickFolder = { folderPicker.launch(null) })

            Header("Added")
            AddedList(vm, s)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun QuickAdd(vm: SettingsViewModel, onPickFolder: () -> Unit) {
    var kind by remember { mutableStateOf(QuickAddType.DIRECT) }
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuickAddType.entries.forEach { t ->
                FilterChip(selected = kind == t, onClick = { kind = t }, label = { Text(t.label) })
            }
        }
        when (kind) {
            QuickAddType.DIRECT -> OneField("Media or page URL", "https://…/clip.mp4", KeyboardType.Uri) { vm.addDirect(it) }
            QuickAddType.RSS -> OneField("Feed URL", "https://site/feed.xml", KeyboardType.Uri) { vm.addFeed(it) }
            QuickAddType.REDDIT -> OneField("Subreddit", "e.g. AnimalsBeingBros", KeyboardType.Text) { vm.addReddit(it) }
            QuickAddType.MASTODON -> TwoField("Instance host", "mastodon.social", "Hashtag (optional)", "art") { a, b -> vm.addMastodon(a, b) }
            QuickAddType.BOORU -> TwoField("Booru host", "safebooru.donmai.us", "Tags (optional)", "landscape") { a, b -> vm.addBooru(a, b) }
            QuickAddType.JSON -> JsonForm(vm)
            QuickAddType.FOLDER -> OutlinedButton(onClick = onPickFolder, modifier = Modifier.padding(vertical = 8.dp)) { Text("Pick a folder…") }
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

@Composable
private fun AddedList(vm: SettingsViewModel, s: dev.pfeed.data.settings.AppSettings) {
    val empty = s.directUrls.isEmpty() && s.rssFeeds.isEmpty() && s.redditSubs.isEmpty() &&
        s.mastodonFeeds.isEmpty() && s.booruFeeds.isEmpty() && s.jsonFeeds.isEmpty() && s.localFolders.isEmpty()
    if (empty) {
        Text("Nothing added yet.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        return
    }
    s.directUrls.forEach { Entry("Direct", it) { vm.removeDirect(it) } }
    s.rssFeeds.forEach { Entry("RSS", it) { vm.removeFeed(it) } }
    s.redditSubs.forEach { Entry("Reddit", "r/$it") { vm.removeReddit(it) } }
    s.mastodonFeeds.forEach { f -> Entry("Mastodon", f.label) { vm.removeMastodon(f) } }
    s.booruFeeds.forEach { f -> Entry("Booru", f.label) { vm.removeBooru(f) } }
    s.jsonFeeds.forEach { f -> Entry("JSON", f.name) { vm.removeJson(f) } }
    s.localFolders.forEach { uri ->
        Entry("Folder", Uri.parse(uri).lastPathSegment?.substringAfter(':') ?: uri) { vm.removeFolder(uri) }
    }
}

@Composable
private fun Entry(kind: String, label: String, onDelete: () -> Unit) {
    ListItem(
        overlineContent = { Text(kind) },
        headlineContent = { Text(label, maxLines = 2) },
        trailingContent = { IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove") } },
    )
}
