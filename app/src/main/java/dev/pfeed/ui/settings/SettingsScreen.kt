package dev.pfeed.ui.settings

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import dev.pfeed.ui.theme.Accent
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SegmentedButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.BuildConfig
import dev.pfeed.data.settings.Disguise
import dev.pfeed.ui.Routes
import dev.pfeed.ui.common.RadioOption
import dev.pfeed.ui.common.RowDivider
import dev.pfeed.ui.common.ScreenHeader
import dev.pfeed.ui.common.SecureDialog
import dev.pfeed.ui.common.Section
import dev.pfeed.ui.common.SettingRow
import dev.pfeed.ui.common.SwitchSetting
import dev.pfeed.ui.theme.Good
import dev.pfeed.ui.theme.TextLow
import dev.pfeed.ui.theme.Warn

private val cacheSteps = listOf(256, 512, 1024, 2048, 4096, 8192)
private val lockSteps = listOf(0 to "Immediately", 30 to "After 30 seconds", 60 to "After 1 minute", 300 to "After 5 minutes")
private val historySteps = listOf(7, 30, 90, 365)

private sealed interface PendingDialog {
    data object ChangePin : PendingDialog
    data object EnableLock : PendingDialog
    data object DisableLock : PendingDialog
    data object PickLockTimeout : PendingDialog
    data object PickDisguise : PendingDialog
    data object PickHistory : PendingDialog
    data object PickCache : PendingDialog
    data object Gestures : PendingDialog
    data object EraseAll : PendingDialog
    data class ExportPassword(val uri: Uri) : PendingDialog
    data class ImportPassword(val uri: Uri) : PendingDialog
}

/**
 * Settings, in one screen. Three places to go (Sources, Your taste, Hidden & blocked) and every
 * other setting right here, so nothing is more than one tap deep.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val lockOn by vm.lockEnabled.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val blocked by vm.blockEntries.collectAsStateWithLifecycle()
    val offlineBytes by vm.offlineBytes.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dialog by remember { mutableStateOf<PendingDialog?>(null) }
    var showWhatsNew by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) dialog = PendingDialog.ExportPassword(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) dialog = PendingDialog.ImportPassword(uri)
    }

    val disguiseOptions = listOf(Disguise.NONE to "Feed", Disguise.NOTES to "Notes", Disguise.CALCULATOR to "Calculator", Disguise.BROWSER to "Browser")
    val on = sources.filter { it.enabled }
    val feeds = on.sumOf { feedCount(it.adapter.id, s) ?: 1 }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Settings")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            Section {
                SettingRow(
                    "Sources",
                    subtitle = "${"%,d".format(feeds)} feeds from ${on.size} sources",
                    icon = Icons.Filled.Dns,
                    chevron = true,
                    onClick = { nav.navigate(Routes.SOURCES) },
                )
                RowDivider()
                SettingRow(
                    "Your taste",
                    subtitle = "What For You has learned, and how it ranks",
                    icon = Icons.Filled.AutoAwesome,
                    iconTint = Warn,
                    chevron = true,
                    onClick = { nav.navigate(Routes.TASTE) },
                )
                RowDivider()
                val hiddenCount = blocked.count { it.kind != "ITEM" }
                SettingRow(
                    "Hidden & blocked",
                    subtitle = if (hiddenCount == 0) "Tags, creators and sources you never want to see" else null,
                    value = if (hiddenCount > 0) "$hiddenCount" else null,
                    icon = Icons.Filled.Block,
                    iconTint = TextLow,
                    chevron = true,
                    onClick = { nav.navigate(Routes.BLOCKLIST) },
                )
            }

            // What the main feeds show: everything by default; switch a kind off to leave it out.
            Section("Feed shows", footer = "Clips are under 3 minutes; full videos are 3 minutes and up. Kinds and lengths apply to the main feeds; search and tag pages still show them all.") {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    listOf("video" to "Videos", "image" to "Pictures", "gif" to "GIFs", "comic" to "Comics").forEach { (type, label) ->
                        val shown = type !in s.hiddenFeedTypes
                        androidx.compose.material3.FilterChip(
                            selected = shown,
                            onClick = { vm.setFeedType(type, !shown) },
                            label = { Text(label) },
                            leadingIcon = if (shown) ({ androidx.compose.material3.Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White),
                        )
                    }
                }
                if ("video" !in s.hiddenFeedTypes) {
                    RowDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Video length", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val options = listOf("any" to "Any", "short" to "Clips", "long" to "Full videos")
                            options.forEachIndexed { index, (value, label) ->
                                SegmentedButton(
                                    selected = s.videoLength == value,
                                    onClick = { vm.setVideoLength(value) },
                                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, options.size),
                                    icon = {},
                                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                )
                            }
                        }
                    }
                }
                RowDivider()
                SwitchSetting(
                    "Hide AI-generated art",
                    s.hideAiArt,
                    subtitle = "Stable Diffusion, NovelAI, Midjourney and the like, by their tags, titles and AI-only boards. Everywhere, search included.",
                    icon = Icons.Filled.AutoAwesome,
                    onChange = vm::setHideAiArt,
                )
            }

            Section("Playback") {
                SwitchSetting("Start muted", s.defaultMute, icon = Icons.AutoMirrored.Filled.VolumeOff, onChange = vm::setDefaultMute)
                RowDivider()
                SwitchSetting(
                    "Autoplay on mobile data",
                    s.autoplayOnMobileData,
                    subtitle = if (s.autoplayOnMobileData) null else "Videos wait for a tap when you're off Wi-Fi",
                    icon = Icons.Filled.SignalCellularAlt,
                    onChange = vm::setAutoplayOnMobile,
                )
                RowDivider()
                SwitchSetting(
                    "Data saver",
                    s.dataSaver,
                    subtitle = if (s.dataSaver) "Videos up to 480p" else "Videos up to 1080p",
                    icon = Icons.Filled.DataSaverOn,
                    onChange = vm::setDataSaver,
                )
            }

            // Space on the phone: what's kept for good, and what's cached to replay quickly.
            Section("Storage") {
                SettingRow(
                    "Video cache",
                    subtitle = "More space, fewer re-downloads",
                    value = formatMb(s.cacheSizeMb),
                    icon = Icons.Filled.SdStorage,
                    onClick = { dialog = PendingDialog.PickCache },
                    trailing = {
                        TextButton(onClick = {
                            vm.clearVideoCache()
                            scope.launch { snackbar.showSnackbar("Video cache cleared") }
                        }) { Text("Clear") }
                    },
                )
                RowDivider()
                SwitchSetting(
                    "Keep saved posts offline",
                    s.keepSavedOffline,
                    subtitle = if (s.keepSavedOffline) "Saved posts download (videos on Wi-Fi) and play without a connection" +
                        (if (offlineBytes > 0) " · ${formatBytes(offlineBytes)}" else "")
                    else "Off: saved posts stream like everything else",
                    icon = Icons.Filled.DownloadForOffline,
                    onChange = vm::setKeepSavedOffline,
                )
            }

            Section(
                "Privacy",
                footer = "Touch the screen with three fingers at any time to leave the app instantly" +
                    if (lockOn) " and lock it." else ".",
            ) {
                SwitchSetting(
                    "App lock",
                    lockOn,
                    subtitle = if (lockOn) null else "Ask for a PIN when the app opens",
                    icon = Icons.Filled.Lock,
                    iconTint = Good,
                ) { want -> dialog = if (want) PendingDialog.EnableLock else PendingDialog.DisableLock }
                if (lockOn) {
                    RowDivider()
                    SettingRow(
                        "Lock again",
                        value = lockSteps.firstOrNull { it.first == s.lockTimeoutSec }?.second ?: "${s.lockTimeoutSec} s",
                        icon = Icons.Filled.Timer,
                        iconTint = Good,
                        onClick = { dialog = PendingDialog.PickLockTimeout },
                    )
                    RowDivider()
                    SettingRow("Change PIN", icon = Icons.Filled.Password, iconTint = Good, chevron = true, onClick = { dialog = PendingDialog.ChangePin })
                }
                RowDivider()
                SettingRow(
                    "Home screen icon",
                    value = disguiseOptions.firstOrNull { it.first == s.disguise }?.second ?: "Feed",
                    icon = Icons.Filled.Apps,
                    iconTint = Good,
                    onClick = { dialog = PendingDialog.PickDisguise },
                )
                RowDivider()
                SwitchSetting(
                    "Private mode",
                    s.privateMode,
                    subtitle = if (s.privateMode) "On: nothing goes into history or search history, and For You isn't learning"
                    else "Browse without adding to history or teaching For You",
                    icon = Icons.Filled.VisibilityOff,
                    iconTint = Good,
                    onChange = vm::setPrivateMode,
                )
                RowDivider()
                SwitchSetting(
                    "Blur previews",
                    s.blurPreviews,
                    subtitle = "Library and search thumbnails stay blurred until you open one",
                    icon = Icons.Filled.BlurOn,
                    iconTint = Good,
                    onChange = vm::setBlurPreviews,
                )
                RowDivider()
                SwitchSetting(
                    "Encrypted DNS",
                    s.encryptedDns,
                    subtitle = if (s.encryptedDns) "Every site is looked up privately over 1.1.1.1"
                    else "Used automatically when your network blocks a site. On: always, so the network can't see which sites you open",
                    icon = Icons.Filled.Shield,
                    iconTint = Good,
                    onChange = vm::setEncryptedDns,
                )
                RowDivider()
                val geoBlocked by dev.pfeed.source.net.GeoBlock.sites.collectAsStateWithLifecycle()
                SettingRow(
                    "Blocked where you are",
                    subtitle = when {
                        s.proxyType != "off" -> "Proxy on (${if (s.proxyType == "orbot") "Orbot" else s.proxyType.uppercase()})" + if (geoBlocked.isNotEmpty()) " · ${geoBlocked.size} site${if (geoBlocked.size == 1) "" else "s"} routed through it" else ""
                        geoBlocked.isNotEmpty() -> "${geoBlocked.size} site${if (geoBlocked.size == 1) "" else "s"} blocked in your state or country. Get them back with a proxy"
                        else -> "Sites that block your state or country, and a proxy to get around it"
                    },
                    icon = Icons.Filled.VpnLock,
                    iconTint = if (geoBlocked.isNotEmpty() && s.proxyType == "off") dev.pfeed.ui.theme.Bad else Good,
                    chevron = true,
                    onClick = { nav.navigate(dev.pfeed.ui.Routes.NETWORK) },
                )
                RowDivider()
                SettingRow(
                    "Keep watch history",
                    value = "${s.historyRetentionDays} days",
                    icon = Icons.Filled.History,
                    iconTint = Good,
                    onClick = { dialog = PendingDialog.PickHistory },
                )
                RowDivider()
                SettingRow(
                    "Erase everything",
                    subtitle = "Saves, likes, history, what For You learned, sources and settings",
                    icon = Icons.Filled.DeleteForever,
                    iconTint = dev.pfeed.ui.theme.Bad,
                    titleColor = dev.pfeed.ui.theme.Bad,
                    onClick = { dialog = PendingDialog.EraseAll },
                )
            }

            Section("Backup", footer = "Settings, hidden things, follows, saved and liked posts and collections, in one file encrypted with a password you choose. Your PIN, sign-ins and history aren't included; saved videos download again after an import.") {
                SettingRow("Export backup", icon = Icons.Filled.Upload, iconTint = TextLow, onClick = { exportLauncher.launch("feed-backup.json") })
                RowDivider()
                SettingRow("Import backup", icon = Icons.Filled.Download, iconTint = TextLow, onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) })
            }

            val context = androidx.compose.ui.platform.LocalContext.current
            val clipContext = androidx.compose.ui.platform.LocalContext.current
            var crash by remember { mutableStateOf(dev.pfeed.CrashLog.read(context)) }
            Section("Help", footer = if (crash != null) "The app closed unexpectedly last time. The report has the app and Android versions and where it failed, nothing you were viewing." else null) {
                SettingRow("Gestures", subtitle = "Everything you can do on a post", icon = Icons.Filled.TouchApp, iconTint = TextLow, chevron = true, onClick = { dialog = PendingDialog.Gestures })
                if (crash != null) {
                    RowDivider()
                    SettingRow(
                        "Copy crash report",
                        icon = Icons.Filled.BugReport,
                        iconTint = dev.pfeed.ui.theme.Bad,
                        onClick = { dev.pfeed.ui.common.copySensitive(clipContext, "Crash report", crash!!) },
                        trailing = { TextButton(onClick = { dev.pfeed.CrashLog.clear(context); crash = null }) { Text("Clear") } },
                    )
                }
            }

            Text(
                "Feed ${BuildConfig.VERSION_NAME} · What's new",
                style = MaterialTheme.typography.labelSmall,
                color = TextLow,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).clickable { showWhatsNew = true }.padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
        dev.pfeed.ui.common.AppSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showWhatsNew) {
        val assets = androidx.compose.ui.platform.LocalContext.current.assets
        val notes = remember { runCatching { assets.open("whats_new.md").bufferedReader().readText() }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { showWhatsNew = false },
            properties = dev.pfeed.ui.common.SecureDialog,
            title = { Text("What's new") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    notes.lines().forEach { line ->
                        when {
                            line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                            line.startsWith("- ") -> Text("• " + line.removePrefix("- ").replace("**", ""), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                            line.isBlank() -> {}
                            else -> Text(line.replace("**", ""), style = MaterialTheme.typography.bodySmall, color = dev.pfeed.ui.theme.TextMid)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showWhatsNew = false }) { Text("Done") } },
        )
    }

    when (val d = dialog) {
        PendingDialog.ChangePin -> ChangePinDialog(onDismiss = { dialog = null }) { cur, new -> vm.changePin(cur, new); dialog = null }
        PendingDialog.EnableLock -> TwoPinDialog(
            title = "Set a PIN",
            first = "PIN (4–8 digits)",
            second = "Confirm PIN",
            action = "Turn on",
            onDismiss = { dialog = null },
        ) { a, b -> vm.enableLock(a, b); dialog = null }
        PendingDialog.DisableLock -> OnePinDialog(title = "Turn off app lock", label = "Current PIN", action = "Turn off", onDismiss = { dialog = null }) {
            vm.disableLock(it); dialog = null
        }
        PendingDialog.PickLockTimeout -> ChoiceDialog(
            title = "Lock again",
            note = "How long the app can be in the background before it asks for the PIN.",
            options = lockSteps,
            selected = s.lockTimeoutSec,
            onDismiss = { dialog = null },
        ) { vm.setLockTimeout(it); dialog = null }
        PendingDialog.PickDisguise -> ChoiceDialog(
            title = "Home screen icon",
            note = "The icon and name shown on your home screen.",
            options = disguiseOptions,
            selected = s.disguise,
            onDismiss = { dialog = null },
        ) { vm.setDisguise(it); dialog = null }
        PendingDialog.PickHistory -> ChoiceDialog(
            title = "Keep watch history",
            note = "Posts you've seen don't come back while they're in history. Older history is deleted.",
            options = historySteps.map { it to "$it days" },
            selected = s.historyRetentionDays,
            onDismiss = { dialog = null },
        ) { vm.setHistoryRetention(it); dialog = null }
        PendingDialog.PickCache -> ChoiceDialog(
            title = "Video cache",
            note = "Space kept for videos you've watched, so they don't download again. Takes effect next launch.",
            options = cacheSteps.map { it to formatMb(it) },
            selected = s.cacheSizeMb,
            onDismiss = { dialog = null },
        ) { vm.setCacheSize(it); dialog = null }
        is PendingDialog.ExportPassword -> PasswordDialog("Encrypt export", confirmTwice = true, onDismiss = { dialog = null }) {
            vm.exportSettings(d.uri, it); dialog = null
        }
        is PendingDialog.ImportPassword -> PasswordDialog("Decrypt import", confirmTwice = false, onDismiss = { dialog = null }) {
            vm.importSettings(d.uri, it); dialog = null
        }
        PendingDialog.Gestures -> AlertDialog(
            onDismissRequest = { dialog = null },
            properties = SecureDialog,
            title = { Text("Gestures") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    listOf(
                        "Swipe up / down" to "Next or previous post",
                        "Tap" to "Pause or play",
                        "Double-tap" to "Like",
                        "Hold the right side of a video" to "Play at 2× while held",
                        "Press and hold" to "More: why it's here, hide, block, copy or open the link, export",
                        "Swipe left on a post" to "Everything by its creator, from every source",
                        "Swipe left / right on a comic" to "Turn the page (tap the page count for every page)",
                        "Pinch" to "Zoom; zoomed in, one finger moves around",
                        "Pull down on the first post" to "Fresh posts",
                        "Sideways: double-tap an edge" to "Skip 10 s back or ahead",
                        "Volume up" to "Sound on",
                        "Tap @name or #tag" to "Everything by them, or with that tag",
                        "Three fingers anywhere" to "Leave the app instantly",
                    ).forEach { (g, what) ->
                        Column {
                            Text(g, style = MaterialTheme.typography.titleSmall)
                            Text(what, style = MaterialTheme.typography.bodyMedium, color = dev.pfeed.ui.theme.TextMid)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } },
        )
        PendingDialog.EraseAll -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            AlertDialog(
                onDismissRequest = { dialog = null },
                properties = SecureDialog,
                title = { Text("Erase everything?") },
                text = { Text("Everything this app has stored on this phone is deleted and the app closes, as if it had just been installed. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData()
                    }) { Text("Erase", color = dev.pfeed.ui.theme.Bad) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        null -> Unit
    }
}

private fun formatBytes(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "${b shr 20} MB"
    else -> "${(b shr 10).coerceAtLeast(1)} KB"
}

private fun formatMb(mb: Int) = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    note: String? = null,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(title) },
        text = {
            Column {
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                }
                options.forEach { (value, label) -> RadioOption(label, value == selected) { onPick(value) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChangePinDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text("Change PIN") },
        text = {
            Column {
                PinField("Current PIN", current) { current = it }
                PinField("New PIN (4–8 digits)", new) { new = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(current, new) }, enabled = current.isNotEmpty() && new.length >= 4) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TwoPinDialog(title: String, first: String, second: String, action: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(title) },
        text = {
            Column {
                PinField(first, a) { a = it }
                PinField(second, b) { b = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(a, b) }, enabled = a.length >= 4 && b.length >= 4) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OnePinDialog(title: String, label: String, action: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(title) },
        text = { PinField(label, a) { a = it } },
        confirmButton = { TextButton(onClick = { onConfirm(a) }, enabled = a.isNotEmpty()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun PasswordDialog(title: String, confirmTwice: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val valid = pw.length >= 8 && (!confirmTwice || pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, label = { Text("Password (8+ characters)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirmTwice) {
                    OutlinedTextField(
                        value = pw2, onValueChange = { pw2 = it }, label = { Text("Repeat password") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pw) }, enabled = valid) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
