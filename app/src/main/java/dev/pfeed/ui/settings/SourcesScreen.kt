package dev.pfeed.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.data.repo.BooruKeys
import dev.pfeed.data.settings.AppSettings
import dev.pfeed.data.settings.JsonFeed
import dev.pfeed.data.settings.SourceCatalog
import dev.pfeed.source.RegisteredSource
import dev.pfeed.source.SourceChecker
import dev.pfeed.source.discovery.DiscoveredSource
import dev.pfeed.source.tube.TubeSite
import dev.pfeed.ui.common.RowDivider
import dev.pfeed.ui.common.ScreenHeader
import dev.pfeed.ui.common.Section
import dev.pfeed.ui.common.SettingRow
import dev.pfeed.ui.common.StatusLine
import dev.pfeed.ui.common.findActivity
import dev.pfeed.ui.common.openExternal
import dev.pfeed.ui.theme.Accent
import dev.pfeed.ui.theme.Bad
import dev.pfeed.ui.theme.Good
import dev.pfeed.ui.theme.TextLow
import dev.pfeed.ui.theme.TextMid
import dev.pfeed.ui.theme.Warn

/** Sources that only show what you add yourself; everything else is built in. */
private val USER_FILLED = listOf("rss", "direct", "json", "local")

/** Display order: the big built-in sources first, in roughly the order people use them. */
private val ORDER = listOf("tube", "reddit", "redgifs", "booru", "kemono", "nhentai", "philomena", "mastodon", "api") + USER_FILLED

private fun iconFor(id: String): ImageVector = when (id) {
    "tube" -> Icons.Filled.Videocam
    "reddit" -> Icons.Filled.Forum
    "redgifs" -> Icons.Filled.Gif
    "booru" -> Icons.Filled.Image
    "kemono" -> Icons.Filled.Person
    "nhentai" -> Icons.AutoMirrored.Filled.MenuBook
    "philomena" -> Icons.Filled.Brush
    "mastodon" -> Icons.Filled.Public
    "rss" -> Icons.Filled.RssFeed
    "direct" -> Icons.Filled.Link
    "json" -> Icons.Filled.DataObject
    "local" -> Icons.Filled.Folder
    else -> Icons.Filled.Widgets
}

/**
 * Sources, as one list. Each row is a switch; tap the row to see what it pulls from, add to it,
 * or fix it. "Check" tests everything from this phone, and "Find more" adds sources by topic.
 */
@Composable
fun SourcesScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var openId by remember { mutableStateOf<String?>(null) }
    var sheetMessage by remember { mutableStateOf<String?>(null) }
    var healthTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        vm.messages.collect { m -> if (openId != null) sheetMessage = m else snackbar.showSnackbar(m) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }

    val listed = remember(sources) {
        sources.filter { it.adapter.isConfigured }.sortedBy { ORDER.indexOf(it.adapter.id).let { i -> if (i < 0) 99 else i } }
    }
    val on = listed.filter { it.enabled }
    val feeds = on.sumOf { feedCount(it.adapter.id, s) ?: 1 }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Sources",
                subtitle = "${"%,d".format(feeds)} feeds from ${on.size} sources",
                onBack = { nav.popBackStack() },
            )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            HealthCard(
                sources = listed,
                checks = checks,
                skipped = remember(listed, healthTick) { listed.sumOf { vm.skippedFeeds(it.adapter.id) } },
                onCheck = vm::checkSources,
                onRetrySkipped = { vm.retrySkippedFeeds(); healthTick++ },
            )

            FindMore(vm)

            Section("Built in") {
                val built = listed.filter { it.adapter.id !in USER_FILLED }
                built.forEachIndexed { i, src ->
                    if (i > 0) RowDivider()
                    SourceRow(src, s, checks[src.adapter.id], checks.containsKey(src.adapter.id), vm, healthTick) { openId = src.adapter.id }
                }
            }
            Section("Your own", footer = "Add a feed, a link, a folder on this phone or any JSON API. Tap one to add to it.") {
                val own = listed.filter { it.adapter.id in USER_FILLED }
                own.forEachIndexed { i, src ->
                    if (i > 0) RowDivider()
                    SourceRow(src, s, checks[src.adapter.id], checks.containsKey(src.adapter.id), vm, healthTick) { openId = src.adapter.id }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    val open = listed.firstOrNull { it.adapter.id == openId }
    if (open != null) {
        SourceSheet(
            src = open,
            s = s,
            check = checks[open.adapter.id],
            vm = vm,
            message = sheetMessage,
            onPickFolder = { folderPicker.launch(null) },
            onDismiss = { openId = null; sheetMessage = null; healthTick++ },
        )
    }
}

/** One line of status for a source row: the most important thing about it right now. */
private data class RowStatus(val text: String, val color: Color)

private fun statusOf(
    src: RegisteredSource,
    s: AppSettings,
    check: SourceChecker.Result?,
    checking: Boolean,
    resting: List<String>,
    auth: String?,
): RowStatus {
    val id = src.adapter.id
    return when {
        !src.enabled -> RowStatus("Off", TextLow)
        checking && check == null -> RowStatus("Checking…", TextLow)
        check != null && !check.ok -> RowStatus(check.detail, Bad)
        resting.isNotEmpty() -> RowStatus("Paused a few minutes: ${resting.take(2).joinToString(", ")} not responding", Warn)
        auth != null -> RowStatus(auth, TextMid)
        src.adapter.requiresAuth -> RowStatus("Sign in to use", Warn)
        check != null -> RowStatus(check.detail, Good)
        else -> RowStatus(feedSummary(id, s) ?: "On", Good)
    }
}

@Composable
private fun SourceRow(
    src: RegisteredSource,
    s: AppSettings,
    check: SourceChecker.Result?,
    checking: Boolean,
    vm: SettingsViewModel,
    tick: Int,
    onOpen: () -> Unit,
) {
    val auth by vm.authStatus.collectAsStateWithLifecycle()
    val resting = remember(src, tick, check) { if (src.enabled) vm.restingSites(src.adapter.id) else emptyList() }
    val status = statusOf(src, s, check, checking, resting, auth[src.adapter.id])
    SettingRow(
        title = src.adapter.displayName,
        icon = iconFor(src.adapter.id),
        iconTint = if (src.enabled) Accent else TextLow,
        below = { StatusLine(status.text, status.color) },
        onClick = onOpen,
        trailing = {
            Switch(
                checked = src.enabled,
                onCheckedChange = { vm.setSourceEnabled(src.adapter.id, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White),
            )
        },
    )
}

@Composable
private fun HealthCard(
    sources: List<RegisteredSource>,
    checks: Map<String, SourceChecker.Result?>,
    skipped: Int,
    onCheck: () -> Unit,
    onRetrySkipped: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val running = checks.isNotEmpty() && checks.values.any { it == null }
    val done = checks.isNotEmpty() && !running
    val working = checks.values.count { it?.ok == true }
    Section("Health") {
        Column(Modifier.padding(16.dp)) {
            when {
                running -> {
                    val finished = checks.values.count { it != null }
                    Text("Checking ${finished + 1} of ${checks.size}…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { finished / checks.size.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        color = Accent,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                done -> {
                    val all = working == checks.size
                    Text(
                        if (all) "All ${checks.size} sources working" else "$working of ${checks.size} sources working",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (all) Good else Warn,
                    )
                    Text(
                        if (all) "Tested from this phone just now." else "The rest are listed below with what went wrong. Most fix themselves within minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                    )
                }
                else -> {
                    Text("Is everything loading?", style = MaterialTheme.typography.titleMedium)
                    Text("Test every source from this phone and connection.", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCheck, enabled = !running, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                    Text(if (done) "Check again" else "Check sources")
                }
                if (done) {
                    OutlinedButton(onClick = {
                        val report = sources.filter { it.adapter.id in checks }.joinToString("\n") { src ->
                            val r = checks[src.adapter.id]
                            "${if (r?.ok == true) "OK  " else "FAIL"} ${src.adapter.displayName}: ${r?.detail}"
                        }
                        clipboard.setText(AnnotatedString(report))
                    }) { Text("Copy report") }
                }
            }
        }
        if (skipped > 0) {
            RowDivider(indent = false)
            SettingRow(
                "$skipped feeds skipped",
                subtitle = "They kept answering \"not found\". Retried automatically in two days.",
                trailing = { TextButton(onClick = onRetrySkipped) { Text("Retry now") } },
            )
        }
    }
}

/** Add sources by topic: searches Reddit, Mastodon and the boards and offers what it finds. */
@Composable
private fun FindMore(vm: SettingsViewModel) {
    val state by vm.searchState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<DiscoveredSource>() }
    Section("Find more", footer = if (state is SearchState.Idle) "Type a topic. Matching subreddits, hashtags and board tags are found for you." else null) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Any topic, e.g. cosplay") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) vm.searchSources(query) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.searchSources(query) },
                enabled = query.isNotBlank() && state !is SearchState.Loading,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("Find") }
        }
        when (val st = state) {
            is SearchState.Idle -> Unit
            is SearchState.Loading -> Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                Spacer(Modifier.width(12.dp))
                Text("Looking on Reddit, Mastodon and the boards…", style = MaterialTheme.typography.bodySmall, color = TextMid)
            }
            is SearchState.Error -> Text(st.message, color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
            is SearchState.Results -> {
                LaunchedEffect(st) { selected.clear(); selected.addAll(st.items) }
                RowDivider(indent = false)
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${st.items.size} found for “${st.query}”", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (selected.size == st.items.size) selected.clear() else { selected.clear(); selected.addAll(st.items) } }) {
                        Text(if (selected.size == st.items.size) "Select none" else "Select all")
                    }
                }
                st.items.forEach { src ->
                    val isSel = src in selected
                    SettingRow(
                        src.label,
                        subtitle = src.description,
                        onClick = { if (isSel) selected.remove(src) else selected.add(src) },
                        trailing = {
                            Checkbox(
                                checked = isSel,
                                onCheckedChange = { c -> if (c) selected.add(src) else selected.remove(src) },
                                colors = CheckboxDefaults.colors(checkedColor = Accent),
                            )
                        },
                    )
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.addDiscovered(selected.toList()); query = "" },
                        enabled = selected.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add ${selected.size}")
                    }
                    OutlinedButton(onClick = { vm.clearSearch() }) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * Everything about one source: on/off, health, sign-in, what it pulls from (with remove), and the
 * one way to add to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(
    src: RegisteredSource,
    s: AppSettings,
    check: SourceChecker.Result?,
    vm: SettingsViewModel,
    message: String?,
    onPickFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val a = src.adapter
    val context = LocalContext.current
    val auth by vm.authStatus.collectAsStateWithLifecycle()
    val keyHosts by vm.booruKeyHosts.collectAsStateWithLifecycle()
    val resting = remember(src) { vm.restingSites(a.id) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.displayName, style = MaterialTheme.typography.headlineSmall)
                    Text(feedSummary(a.id, s) ?: "", style = MaterialTheme.typography.bodyMedium, color = TextMid)
                }
                Switch(
                    checked = src.enabled,
                    onCheckedChange = { vm.setSourceEnabled(a.id, it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White),
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (check != null) StatusLine(check.detail, if (check.ok) Good else Bad)
                if (resting.isNotEmpty()) StatusLine("Paused a few minutes, not responding: ${resting.joinToString(", ")}", Warn)
                auth[a.id]?.let { StatusLine(it, TextMid) }
                val skipped = vm.skippedFeeds(a.id)
                if (skipped > 0) StatusLine("$skipped feeds skipped for answering \"not found\"", TextLow)
                message?.let { StatusLine(it, Warn) }
            }
            if (a.requiresAuth) {
                Button(
                    onClick = { context.findActivity()?.let { vm.authenticate(it, a.id) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) { Text("Sign in") }
            }

            AddTo(a.id, vm, onPickFolder)
            if (a.id == "booru") BoardKeys(vm, keyHosts) { openExternal(context, it) }
            Entries(a.id, s, vm)
            BuiltInContents(a.id)
        }
    }
}

/** The single "add" control for a source, shaped by what that source takes. */
@Composable
private fun AddTo(id: String, vm: SettingsViewModel, onPickFolder: () -> Unit) {
    when (id) {
        "reddit" -> AddSection("Add a subreddit") { OneField("Subreddit", "e.g. gonewild", KeyboardType.Text) { vm.addReddit(it) } }
        "booru" -> AddSection("Add a board feed") { TwoField("Board", "rule34.xxx", "Tags (optional)", "cosplay solo") { h, t -> vm.addBooru(h, t) } }
        "mastodon" -> AddSection("Add a hashtag feed") { TwoField("Server", "mastodon.social", "Hashtag (optional)", "nsfw") { h, t -> vm.addMastodon(h, t) } }
        "rss" -> AddSection("Add a feed") { OneField("Feed URL", "https://site/feed.xml", KeyboardType.Uri) { vm.addFeed(it) } }
        "direct" -> AddSection("Add a link") { OneField("Video, image or page URL", "https://…/clip.mp4", KeyboardType.Uri) { vm.addDirect(it) } }
        "json" -> AddSection("Add a JSON API") { JsonForm(vm) }
        "local" -> AddSection("Add a folder") {
            Button(onClick = onPickFolder, colors = ButtonDefaults.buttonColors(containerColor = Accent), modifier = Modifier.padding(12.dp)) {
                Icon(Icons.Filled.Folder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choose a folder")
            }
        }
        else -> Unit
    }
}

@Composable
private fun AddSection(title: String, content: @Composable () -> Unit) {
    Section(title) { content() }
}

/** What a built-in source pulls from, for the ones whose list isn't editable. */
@Composable
private fun BuiltInContents(id: String) {
    val lines: List<String> = when (id) {
        "tube" -> TubeSite.ALL.map { it.label }.let { listOf(it.joinToString(" · ")) } +
            "Each in ${SourceCatalog.tubeSections.size} sections: newest, top and ${SourceCatalog.tubeSections.size - 2} categories."
        "redgifs" -> listOf("${SourceCatalog.redgifsTerms.size} trending category feeds.")
        "nhentai" -> listOf("${SourceCatalog.nhentaiQueries.size} tag feeds. Every gallery opens as a comic you swipe through.")
        "kemono" -> listOf("Creator posts from coomer and kemono, including image sets as galleries.")
        "philomena" -> listOf("derpibooru.org and furbooru.org.")
        else -> emptyList()
    }
    if (lines.isEmpty()) return
    Section("Pulls from") {
        lines.forEachIndexed { i, l ->
            if (i > 0) RowDivider(indent = false)
            Text(l, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
    }
}

/** The editable list behind a source (subreddits, board feeds…), filterable, each removable. */
@Composable
private fun Entries(id: String, s: AppSettings, vm: SettingsViewModel) {
    val entries: List<Pair<String, () -> Unit>> = when (id) {
        "reddit" -> s.redditSubs.map { sub -> "r/$sub" to { vm.removeReddit(sub); Unit } }
        "booru" -> s.booruFeeds.map { f -> f.label to { vm.removeBooru(f); Unit } }
        "mastodon" -> s.mastodonFeeds.map { f -> f.label to { vm.removeMastodon(f); Unit } }
        "rss" -> s.rssFeeds.map { u -> u to { vm.removeFeed(u); Unit } }
        "direct" -> s.directUrls.map { u -> u to { vm.removeDirect(u); Unit } }
        "json" -> s.jsonFeeds.map { f -> f.name to { vm.removeJson(f); Unit } }
        "local" -> s.localFolders.map { u -> (Uri.parse(u).lastPathSegment?.substringAfter(':') ?: u) to { vm.removeFolder(u); Unit } }
        else -> return
    }
    var filter by remember(id) { mutableStateOf("") }
    val shown = entries.filter { filter.isBlank() || it.first.contains(filter, ignoreCase = true) }
    Section(if (entries.isEmpty()) "Nothing added yet" else "${"%,d".format(entries.size)} in this source") {
        if (entries.size > 12) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Find one") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
        shown.take(MAX_SHOWN).forEachIndexed { i, (label, remove) ->
            if (i > 0) RowDivider(indent = false)
            SettingRow(
                label,
                trailing = { IconButton(onClick = remove) { Icon(Icons.Filled.Close, contentDescription = "Remove", tint = TextLow) } },
            )
        }
        if (shown.size > MAX_SHOWN) {
            Text(
                "and ${"%,d".format(shown.size - MAX_SHOWN)} more. Type above to find one.",
                style = MaterialTheme.typography.bodySmall,
                color = TextLow,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private const val MAX_SHOWN = 50

@Composable
private fun OneField(label: String, placeholder: String, type: KeyboardType, onAdd: (String) -> Boolean) {
    var text by remember(label) { mutableStateOf("") }
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank() && onAdd(text)) text = "" }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { if (onAdd(text)) text = "" }, enabled = text.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Add") }
    }
}

@Composable
private fun TwoField(label1: String, ph1: String, label2: String, ph2: String, onAdd: (String, String) -> Boolean) {
    var a by remember(label1) { mutableStateOf("") }
    var b by remember(label1) { mutableStateOf("") }
    Column(Modifier.padding(12.dp)) {
        OutlinedTextField(
            value = a, onValueChange = { a = it }, label = { Text(label1) }, placeholder = { Text(ph1) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = b, onValueChange = { b = it }, label = { Text(label2) }, placeholder = { Text(ph2) },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (onAdd(a, b)) { a = ""; b = "" } }, enabled = a.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Add") }
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

    Column(Modifier.padding(12.dp)) {
        Text(
            "Point at any JSON endpoint and say where the fields are. {query} and {cursor} in a URL are filled in.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMid,
        )
        Field("Name", name) { name = it }
        Field("URL", url, "https://host/api?tag={query}&page={cursor}") { url = it }
        Field("List of items at", itemsPath, "e.g. data.children") { itemsPath = it }
        Field("Media URL at", mediaPath, "e.g. url") { mediaPath = it }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Fewer fields" else "More fields") }
        AnimatedVisibility(advanced) {
            Column {
                Field("Each item inside", wrapPath, "e.g. data") { wrapPath = it }
                Field("Id at", idPath) { idPath = it }
                Field("Title at", titlePath) { titlePath = it }
                Field("Creator at", creatorPath) { creatorPath = it }
                Field("Thumbnail at", thumbPath) { thumbPath = it }
                Field("Tags at", tagsPath) { tagsPath = it }
                Field("Page link at", pagePath) { pagePath = it }
                Field("Search URL", searchTemplate, "https://host/search?q={query}") { searchTemplate = it }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Add API") }
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}

/** Optional keys for the boards that answer faster with one (they work without, from their pages). */
@Composable
private fun BoardKeys(vm: SettingsViewModel, configured: Set<String>, onOpen: (String) -> Unit) {
    Section(
        "Faster boards (optional)",
        footer = "These boards work without a key. With a free one they load faster: make an account, open its options page, " +
            "tick \"Generate New Key\", save, and paste the &api_key=…&user_id=… line.",
    ) {
        BooruKeys.NEEDS_KEY.toList().forEachIndexed { i, (host, optionsUrl) ->
            if (i > 0) RowDivider()
            var text by remember(host) { mutableStateOf("") }
            val has = host in configured
            SettingRow(
                host,
                subtitle = if (has) "Key saved" else "No key",
                icon = Icons.Filled.Key,
                iconTint = if (has) Good else TextLow,
                trailing = {
                    if (has) TextButton(onClick = { vm.clearBooruKey(host) }) { Text("Remove") }
                    else TextButton(onClick = { onOpen(optionsUrl) }) { Text("Get a key") }
                },
            )
            if (!has) {
                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("&api_key=…&user_id=…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { if (vm.saveBooruKey(host, text)) text = "" }, enabled = text.isNotBlank()) { Text("Save") }
                }
            }
        }
    }
}

/** How many feeds/entries a source pulls from, or null if it has no countable list. */
internal fun feedCount(id: String, s: AppSettings): Int? = when (id) {
    "reddit" -> s.redditSubs.size
    "booru" -> s.booruFeeds.size
    "mastodon" -> s.mastodonFeeds.size
    "rss" -> s.rssFeeds.size
    "direct" -> s.directUrls.size
    "json" -> s.jsonFeeds.size
    "local" -> s.localFolders.size
    "kemono" -> s.kemonoHosts.size
    "redgifs" -> SourceCatalog.redgifsTerms.size
    "tube" -> TubeSite.ALL.size * SourceCatalog.tubeSections.size
    "nhentai" -> SourceCatalog.nhentaiQueries.size
    else -> null
}

/** One line about what a source is pulling from. */
internal fun feedSummary(id: String, s: AppSettings): String? = when (id) {
    "reddit" -> "${"%,d".format(s.redditSubs.size)} subreddits"
    "booru" -> {
        val sites = s.booruFeeds.map { it.host.lowercase().removePrefix("www.") }.distinct().size
        "${"%,d".format(s.booruFeeds.size)} tag feeds on $sites boards"
    }
    "mastodon" -> {
        val servers = s.mastodonFeeds.map { it.host.lowercase() }.distinct().size
        "${"%,d".format(s.mastodonFeeds.size)} hashtag feeds on $servers servers"
    }
    "kemono" -> s.kemonoHosts.joinToString(" + ").ifBlank { null }
    "redgifs" -> "${SourceCatalog.redgifsTerms.size} trending categories"
    "tube" -> "${TubeSite.ALL.size} video sites"
    "nhentai" -> "${SourceCatalog.nhentaiQueries.size} comic feeds"
    "philomena" -> "derpibooru + furbooru"
    "rss" -> if (s.rssFeeds.isEmpty()) "Add any RSS or Atom feed" else "${s.rssFeeds.size} feeds"
    "direct" -> if (s.directUrls.isEmpty()) "Paste a video or image link" else "${s.directUrls.size} links"
    "json" -> if (s.jsonFeeds.isEmpty()) "Map any JSON API" else "${s.jsonFeeds.size} APIs"
    "local" -> if (s.localFolders.isEmpty()) "Pick a folder on this phone" else "${s.localFolders.size} folders"
    else -> null
}
