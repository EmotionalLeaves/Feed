package dev.pfeed.ui.feed

import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.SecureFlagPolicy
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.ui.common.openExternal

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemMenuSheet(
    item: MediaItem,
    vm: FeedViewModel,
    onDismiss: () -> Unit,
    /** Opens an endless feed built around this post (its creator and tags). */
    onMoreLikeThis: (() -> Unit)? = null,
    /** Opens a tag's or creator's page ("Why you're seeing this"). */
    onOpenPage: ((String) -> Unit)? = null,
    /** Downloads this and the next posts of the feed to watch offline. */
    onForTheRoad: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var pickTag by remember { mutableStateOf<String?>(null) } // "block" while picking a tag to hide
    val creator = remember(item.key) { Display.creator(item) }
    val tags = remember(item.key) { Display.tags(item, max = 24) }
    val source = Display.source(item, vm.sourceName(item.sourceId))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            val mode = pickTag
            if (mode == "collect") {
                CollectionPicker(item, vm, onBack = { pickTag = null }, onDone = onDismiss)
                return@Column
            }
            if (mode == "sleep") {
                ListItem(
                    headlineContent = { Text("Stop playing after…", fontWeight = FontWeight.Bold) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { pickTag = null },
                )
                HorizontalDivider()
                FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SleepTimer.choices.forEach { m ->
                        androidx.compose.material3.FilterChip(
                            selected = false,
                            onClick = { SleepTimer.start(m); onDismiss() },
                            label = { Text(if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min") },
                        )
                    }
                    if (SleepTimer.endsAt.collectAsStateWithLifecycle().value != null) {
                        androidx.compose.material3.FilterChip(selected = true, onClick = { SleepTimer.cancel(); onDismiss() }, label = { Text("Turn off") })
                    }
                }
                return@Column
            }
            if (mode != null) {
                ListItem(
                    headlineContent = { Text("Hide posts tagged…", fontWeight = FontWeight.Bold) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { pickTag = null },
                )
                HorizontalDivider()
                FlowRow(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.forEach { (label, raw) ->
                        AssistChip(
                            onClick = {
                                vm.blockTag(raw)
                                onDismiss()
                            },
                            label = { Text("#$label") },
                        )
                    }
                }
                return@Column
            }

            // What you're acting on.
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))) { Thumbnail(item); dev.pfeed.ui.common.MediaMask() }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(creator?.let { "@$it" } ?: source, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (creator != null) source else (item.title ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Why it's here, in plain words: what you follow first, then what you like. Tap one to go there.
            val why by androidx.compose.runtime.produceState(initialValue = emptyList<FeedViewModel.Why>(), item.key) { value = vm.whyThis(item) }
            if (why.isNotEmpty()) {
                Text(
                    "WHY YOU'RE SEEING THIS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
                FlowRow(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    why.forEach { w ->
                        Text(
                            w.text,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (w.strong) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (w.strong) dev.pfeed.ui.theme.BrandGradient else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
                                .then(if (w.page != null && onOpenPage != null) Modifier.clickable { onDismiss(); onOpenPage(w.page) } else Modifier)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            HorizontalDivider()

            // The everyday actions as one row of round buttons; the "less of this" ones as a list below.
            val handsFree by HandsFree.on.collectAsState()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp),
            ) {
                QuickAction(Icons.Filled.CreateNewFolder, "Collection", modifier = Modifier.weight(1f)) { pickTag = "collect" }
                QuickAction(Icons.Filled.Autorenew, if (handsFree) "Stop hands-free" else "Hands-free", lit = handsFree, modifier = Modifier.weight(1f)) { HandsFree.set(!handsFree); onDismiss() }
                if (item.type == dev.pfeed.domain.model.MediaType.VIDEO) {
                    // Tap to step through 1×, 1.25×, 1.5×, 2×, 0.75×; it stays for every video.
                    val speed by PlaybackSpeed.value.collectAsState()
                    QuickAction(Icons.Filled.Speed, "Speed ${PlaybackSpeed.label(speed)}", lit = speed != 1f, modifier = Modifier.weight(1f)) { PlaybackSpeed.next() }
                }
                QuickAction(Icons.Filled.Fullscreen, "Clean screen", modifier = Modifier.weight(1f)) { CleanScreen.set(true); onDismiss() }
                QuickAction(Icons.AutoMirrored.Filled.OpenInNew, "Open site", modifier = Modifier.weight(1f)) { openExternal(context, item.pageUrl); onDismiss() }
            }
            HorizontalDivider()
            if (onMoreLikeThis != null) {
                ListItem(
                    headlineContent = { Text("More like this", fontWeight = FontWeight.Bold) },
                    supportingContent = { Text("An endless feed built around this post: its creator and tags") },
                    leadingContent = {
                        Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dev.pfeed.ui.theme.BrandGradient), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
                        }
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { onDismiss(); onMoreLikeThis() },
                )
            }
            if (item.type == dev.pfeed.domain.model.MediaType.VIDEO) {
                MenuRow("Save this frame", Icons.Filled.PhotoCamera, "Keep this exact moment as a picture, in Saved → Frames") { FrameGrab.request.value = item.key; onDismiss() }
                MenuRow("Loop a part", Icons.Filled.Repeat, "Starts here; tap when it reaches the end you want") { LoopPart.request.value = item.key; onDismiss() }
                val feel by FeelTheSound.on.collectAsStateWithLifecycle()
                MenuRow(
                    if (feel) "Stop feeling the sound" else "Feel the sound",
                    Icons.Filled.Vibration,
                    if (feel) "The phone stops vibrating with the audio" else "The phone vibrates with the audio, stronger as it gets louder, sound on or off",
                ) { FeelTheSound.set(!feel); onDismiss() }
            }
            if (onForTheRoad != null) MenuRow("Take 20 for the road", Icons.Filled.FlightTakeoff, "This and the next posts, downloaded to watch offline") { onForTheRoad(); onDismiss() }
            val sleepLeft = SleepTimer.endsAt.collectAsStateWithLifecycle().value?.let { SleepTimer.minutesLeft() }
            MenuRow("Sleep timer", Icons.Filled.Bedtime, if (sleepLeft != null) "Stops in $sleepLeft min" else "Stop playing after a while") { pickTag = "sleep" }
            val incognito by dev.pfeed.domain.privacy.Incognito.on.collectAsStateWithLifecycle()
            MenuRow(
                if (incognito) "Leave incognito" else "Go incognito",
                Icons.Filled.VisibilityOff,
                if (incognito) "Watching is remembered again" else "Until you turn it off: no History, nothing learned, no seen marks",
            ) {
                dev.pfeed.domain.privacy.Incognito.set(!incognito)
                android.widget.Toast.makeText(context, if (incognito) "Incognito off" else "Incognito: nothing you watch is remembered", android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            MenuRow("Not interested", Icons.Filled.ThumbDown, "Show fewer posts like this") { vm.notInterested(item); onDismiss() }
            if (tags.isNotEmpty()) MenuRow("Hide a tag…", Icons.Filled.Block, "Never show posts with that tag") { pickTag = "block" }
            if (creator != null) {
                MenuRow("Block @$creator", Icons.Filled.PersonOff, "Never show their posts") { vm.blockCreator(item); onDismiss() }
            }
            MenuRow("Hide $source", Icons.Filled.VisibilityOff, "Never show posts from it") { vm.hideOrigin(item, source); onDismiss() }
            if (item.pageUrl.isNotBlank()) MenuRow("Copy link", Icons.Filled.Link) {
                dev.pfeed.ui.common.copySensitive(context, "Link", item.pageUrl)
                onDismiss()
            }
            // Kept on the phone already: copy it out to Downloads (visible to the gallery and other apps).
            if (vm.canExport(item)) MenuRow("Export to Downloads", Icons.Filled.Download, "Outside this app: other apps can see it") {
                vm.exportToDownloads(item)
                onDismiss()
            }
            val report = vm.reportUrl(item)
            if (report != null) {
                MenuRow("Report to $source", Icons.Filled.Flag) { openExternal(context, report); onDismiss() }
            }
        }
    }
}

/** A round button with its name under it, for the menu's top row. */
@Composable
private fun QuickAction(icon: ImageVector, label: String, lit: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .then(
                    if (lit) Modifier.background(dev.pfeed.ui.theme.BrandGradient)
                    else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        // Rows sit on the sheet's own colour (the default surface showed as darker bands).
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Tick the collections this post belongs in, or start a new one with it. */
@Composable
private fun CollectionPicker(item: MediaItem, vm: FeedViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val collections by vm.collections.collectAsState()
    val inThem by androidx.compose.runtime.produceState<Set<String>?>(initialValue = null, item.key) { value = vm.collectionsOf(item) }
    var newName by remember { mutableStateOf("") }
    ListItem(
        headlineContent = { Text("Add to collection", fontWeight = FontWeight.Bold) },
        leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onBack),
    )
    HorizontalDivider()
    val current = inThem ?: return
    // Secret collections (".name") aren't listed, unless this post is already in one: type the
    // full name below to add to one.
    collections.filter { !it.name.startsWith(".") || it.name in current }.forEach { c ->
        val checked = c.name in current
        ListItem(
            headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(if (c.count == 1) "1 post" else "${c.count} posts") },
            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
            trailingContent = {
                androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null)
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.clickable { vm.setInCollection(item, c.name, add = !checked); onDone() },
        )
    }
    // A new collection, starting with this post.
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(
            value = newName,
            onValueChange = { newName = it.take(dev.pfeed.data.repo.LibraryRepository.MAX_COLLECTION_NAME) },
            placeholder = { Text(if (collections.isEmpty()) "Name your first collection" else "New collection (start with . to hide it)") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                if (newName.isNotBlank()) { vm.setInCollection(item, newName, add = true); onDone() }
            }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.TextButton(
            enabled = newName.isNotBlank(),
            onClick = { vm.setInCollection(item, newName, add = true); onDone() },
        ) { Text("Create") }
    }
    Spacer(Modifier.size(8.dp))
}
