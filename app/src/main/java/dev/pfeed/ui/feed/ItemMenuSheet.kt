package dev.pfeed.ui.feed

import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.LocalFireDepartment
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
    onComingUp: (() -> Unit)? = null,
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
            if (mode == "comments") {
                ListItem(
                    headlineContent = { Text(if (item.sourceId == dev.pfeed.source.parse.RedditParser.SOURCE_ID) "Comments on Reddit" else "Comments", fontWeight = FontWeight.Bold) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { pickTag = null },
                )
                HorizontalDivider()
                val comments by androidx.compose.runtime.produceState<List<dev.pfeed.source.reddit.RedditAdapter.Comment>?>(initialValue = null, item.key) { value = vm.comments(item) }
                val list = comments
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { dev.pfeed.ui.common.BrandSpinner() }
                    list.isEmpty() -> Text("No comments to show", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(20.dp))
                    else -> list.forEach { c ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val reddit = item.sourceId == dev.pfeed.source.parse.RedditParser.SOURCE_ID
                                Text(if (reddit) "u/${c.author}" else c.author, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                                if (reddit) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    Text("${c.score}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(c.body, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                            // Times in it ("12:31"): tap one to go there.
                            val times = remember(c.body) { dev.pfeed.domain.reco.CommentMoments.of(listOf(c.author to c.body), item.durationSec) }
                            if (times.isNotEmpty() && item.type == dev.pfeed.domain.model.MediaType.VIDEO) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                times.take(4).forEach { at ->
                                    Text(
                                        "▶ " + dev.pfeed.ui.common.formatDuration((at / 1000).toInt()),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f))
                                            .clickable { SeekRequest.request.value = item.key to at; onDismiss() }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                return@Column
            }
            if (mode == "clip") {
                ListItem(
                    headlineContent = { Text("Clip how much?", fontWeight = FontWeight.Bold) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { pickTag = null },
                )
                HorizontalDivider()
                FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15 to "15 s", 30 to "30 s", 60 to "1 min", 120 to "2 min").forEach { (sec, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = sec == 30,
                            onClick = { ClipGrab.lengthMs = sec * 1000L; ClipGrab.request.value = item.key; onDismiss() },
                            label = { Text(label) },
                        )
                    }
                }
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
            // The video's scenes as pictures, when they're known: tap one to go there.
            val sceneTimeline = remember(item.key) { dev.pfeed.domain.reco.VideoExtras.of(item.key)?.timeline }
            val sceneCuts = remember(item.key) { dev.pfeed.domain.reco.Scenes.of(item.key).orEmpty() }
            if (sceneTimeline != null && sceneCuts.size >= 2) {
                Text(
                    "Scenes",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                )
                SceneStrip(sceneTimeline, sceneCuts, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)) { at ->
                    SeekRequest.request.value = item.key to at
                    onDismiss()
                }
                HorizontalDivider()
            }
            if (item.type == dev.pfeed.domain.model.MediaType.VIDEO) {
                val highlights by Highlights.on.collectAsState()
                ListItem(
                    headlineContent = { Text(if (highlights) "Stop highlights" else "Highlights", fontWeight = FontWeight.Bold) },
                    supportingContent = {
                        Text(
                            if (highlights) "Long videos play in full again"
                            else "Long videos open at the act you like most (or the part people rewatch), play 45 seconds, then move on",
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = if (highlights) androidx.compose.ui.graphics.Color(0xFFFF5C7A) else androidx.compose.material3.LocalContentColor.current) },
                    modifier = Modifier.clickable { Highlights.set(!highlights); onDismiss() },
                )
            }
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
                if ((item.durationSec ?: 0) * 1000L >= SceneFinder.MIN_DURATION_MS) {
                    MenuRow("Next scene", Icons.Filled.SkipNext, "Skips to where the video cuts to something new") { SceneSkip.request.value = item.key; onDismiss() }
                }
                // The act on screen right now, when the site names it: more of exactly that.
                val actNow = remember(item.key) {
                    val chapters = dev.pfeed.domain.reco.VideoExtras.of(item.key)?.chapters.orEmpty()
                    if (NowPlaying.key == item.key) dev.pfeed.domain.reco.VideoExtras.chapterAt(chapters, NowPlaying.positionMs) else null
                }
                if (actNow != null && onOpenPage != null) MenuRow("More ${actNow.lowercase()}", Icons.Filled.Search, "Videos of what's on screen now, as their sites name it") {
                    onOpenPage(actNow.lowercase()); onDismiss()
                }
                if (item.extras[dev.pfeed.source.live.LiveCamsAdapter.LIVE] != "1" && (item.durationSec ?: 0) > 45) {
                    val bestAt by androidx.compose.runtime.produceState<Long?>(null, item.key) { value = vm.bestClipStartMs(item) }
                    bestAt?.let { at ->
                        MenuRow("Clip the best part · " + dev.pfeed.ui.common.formatDuration((at / 1000).toInt()), Icons.Filled.LocalFireDepartment, "30 seconds from there, saved without scrubbing to it") {
                            ClipGrab.at.value = item.key to at; onDismiss()
                        }
                    }
                    MenuRow("Save a clip", Icons.Filled.ContentCut, "From just before now, kept on your phone as a clip of its own (Saved → Clips)") { pickTag = "clip" }
                }
                MenuRow("Save this moment", Icons.Filled.BookmarkAdd, "Marks where you are: it shows on the scrub bar, and Saved → Moments plays the video from there") { MomentGrab.request.value = item.key; onDismiss() }
                MenuRow("Save this frame", Icons.Filled.PhotoCamera, "Keep this exact moment as a picture, in Saved → Frames") { FrameGrab.request.value = item.key; onDismiss() }
                // The scene playing now, start to cut, when the scenes (or acts) are known.
                val sceneNow = remember(item.key) {
                    val cuts = (listOf(0L) + vm.sceneCuts(item)).distinct().sorted()
                    if (NowPlaying.key != item.key || cuts.size < 2) null
                    else {
                        val at = NowPlaying.positionMs
                        val start = cuts.lastOrNull { it <= at } ?: 0L
                        cuts.firstOrNull { it > at }?.let { end -> start to end }
                    }
                }
                sceneNow?.let { (a, b) ->
                    MenuRow("Loop this scene · " + dev.pfeed.ui.common.formatDuration((a / 1000).toInt()) + "–" + dev.pfeed.ui.common.formatDuration((b / 1000).toInt()), Icons.Filled.Repeat, "Plays the scene on screen over and over, until you clear the loop") {
                        LoopPart.range.value = Triple(item.key, a, b); onDismiss()
                    }
                }
                MenuRow("Loop a part", Icons.Filled.Repeat, "Starts here; tap when it reaches the end you want") { LoopPart.request.value = item.key; onDismiss() }
                val feel by FeelTheSound.on.collectAsStateWithLifecycle()
                MenuRow(
                    if (feel) "Stop feeling the sound" else "Feel the sound",
                    Icons.Filled.Vibration,
                    if (feel) "The phone stops vibrating with the audio" else "The phone vibrates with the audio, stronger as it gets louder, sound on or off",
                ) { FeelTheSound.set(!feel); onDismiss() }
            }
            val later by vm.watchLater.collectAsState()
            val isLater = item.key in later
            MenuRow(if (isLater) "Remove from Watch later" else "Watch later", Icons.Filled.WatchLater,
                if (isLater) null else "Keep it to come back to, without downloading it (Saved → Watch later)") { vm.toggleWatchLater(item); onDismiss() }
            if (onComingUp != null) MenuRow("Coming up", Icons.Filled.QueueMusic, "What's next in the feed: jump ahead, or pass some over before they come") { onComingUp(); onDismiss() }
            if (onForTheRoad != null) MenuRow("Take 20 for the road", Icons.Filled.FlightTakeoff, "This and the next posts, downloaded to watch offline") { onForTheRoad(); onDismiss() }
            val siteComments = remember(item.key) { dev.pfeed.domain.reco.VideoExtras.of(item.key)?.comments?.size ?: 0 }
            if (item.sourceId == dev.pfeed.source.parse.RedditParser.SOURCE_ID && "/comments/" in item.pageUrl) {
                MenuRow("Comments", Icons.Filled.ChatBubbleOutline, "What people said under it on Reddit") { pickTag = "comments" }
            } else if (siteComments > 0) {
                MenuRow("Comments ($siteComments)", Icons.Filled.ChatBubbleOutline, "What people said under it on its site") { pickTag = "comments" }
            }
            val dim by Dimmer.level.collectAsStateWithLifecycle()
            MenuRow(
                if (dim == 0f) "Dim the screen" else "Dim: ${(dim * 100).toInt()}% (tap for more, then off)",
                Icons.Filled.Brightness4,
                "Darker than your phone's lowest brightness, for watching in the dark",
            ) { Dimmer.next() }
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
