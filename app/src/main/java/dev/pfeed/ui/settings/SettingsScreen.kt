package dev.pfeed.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
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
    data class ExportPassword(val uri: Uri) : PendingDialog
    data class ImportPassword(val uri: Uri) : PendingDialog
}

/**
 * Settings, in one screen. Three places to go (Sources, Your taste, Hidden & blocked) and every
 * other setting right here, so nothing is more than one tap deep.
 */
@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val lockOn by vm.lockEnabled.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val blocked by vm.blockEntries.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<PendingDialog?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) dialog = PendingDialog.ExportPassword(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) dialog = PendingDialog.ImportPassword(uri)
    }

    val disguiseOptions = listOf(Disguise.NONE to "PrivateFeed", Disguise.NOTES to "Notes", Disguise.CALCULATOR to "Calculator")
    val on = sources.filter { it.enabled }
    val feeds = on.sumOf { feedCount(it.adapter.id, s) ?: 1 }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader("Settings")

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
                SettingRow(
                    "Video cache",
                    subtitle = "More space, fewer re-downloads",
                    value = formatMb(s.cacheSizeMb),
                    icon = Icons.Filled.SdStorage,
                    onClick = { dialog = PendingDialog.PickCache },
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
                    value = disguiseOptions.firstOrNull { it.first == s.disguise }?.second ?: "PrivateFeed",
                    icon = Icons.Filled.Apps,
                    iconTint = Good,
                    onClick = { dialog = PendingDialog.PickDisguise },
                )
                RowDivider()
                SettingRow(
                    "Keep watch history",
                    value = "${s.historyRetentionDays} days",
                    icon = Icons.Filled.History,
                    iconTint = Good,
                    onClick = { dialog = PendingDialog.PickHistory },
                )
            }

            Section("Backup", footer = "Saved as a file encrypted with a password you choose. Your PIN, sign-ins and history aren't included.") {
                SettingRow("Export settings", icon = Icons.Filled.Upload, iconTint = TextLow, onClick = { exportLauncher.launch("privatefeed-settings.json") })
                RowDivider()
                SettingRow("Import settings", icon = Icons.Filled.Download, iconTint = TextLow, onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) })
            }

            Text(
                "PrivateFeed ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = TextLow,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
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
        null -> Unit
    }
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
