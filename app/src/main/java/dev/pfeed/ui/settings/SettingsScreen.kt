package dev.pfeed.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.data.settings.Disguise
import dev.pfeed.ui.Routes
import dev.pfeed.ui.common.SecureDialog
import kotlin.math.roundToInt

private val cacheSteps = listOf(256, 512, 1024, 2048, 4096, 8192)
private val lockSteps = listOf(0 to "Immediately", 30 to "30 seconds", 60 to "1 minute", 300 to "5 minutes")
private val historySteps = listOf(7, 30, 90, 365)

private sealed interface PendingDialog {
    data object ChangePin : PendingDialog
    data object ConfirmReset : PendingDialog
    data class ExportPassword(val uri: Uri) : PendingDialog
    data class ImportPassword(val uri: Uri) : PendingDialog
}

@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<PendingDialog?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) dialog = PendingDialog.ExportPassword(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) dialog = PendingDialog.ImportPassword(uri)
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Sources")
        NavRow("Manage sources", "Enable, sign in, reorder, add feeds and folders") { nav.navigate(Routes.SOURCES) }

        Header("Playback")
        val cacheIndex = cacheSteps.indexOf(s.cacheSizeMb).takeIf { it >= 0 } ?: 2
        LabeledSlider(
            label = "Video cache: ${formatMb(cacheSteps[cacheIndex])} (applies on next launch)",
            value = cacheIndex.toFloat(),
            range = 0f..(cacheSteps.size - 1).toFloat(),
            steps = cacheSteps.size - 2,
            onChange = { vm.setCacheSize(cacheSteps[it.roundToInt()]) },
        )
        SwitchRow("Autoplay on mobile data", s.autoplayOnMobileData, vm::setAutoplayOnMobile)
        SwitchRow("Start muted", s.defaultMute, vm::setDefaultMute)

        Header("Privacy")
        Text("Re-lock after leaving the app", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
        lockSteps.forEach { (sec, label) -> RadioRow(label, s.lockTimeoutSec == sec) { vm.setLockTimeout(sec) } }
        NavRow("Change PIN", null) { dialog = PendingDialog.ChangePin }
        Text("Launcher icon", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        listOf(Disguise.NONE to "Feed (default)", Disguise.NOTES to "Notes", Disguise.CALCULATOR to "Calculator").forEach { (d, label) ->
            RadioRow(label, s.disguise == d) { vm.setDisguise(d) }
        }
        Text(
            "Panic: tap with three fingers anywhere to lock and jump to the home screen.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
        )

        Header("History")
        Text("Keep watch history for", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
        historySteps.forEach { d -> RadioRow("$d days", s.historyRetentionDays == d) { vm.setHistoryRetention(d) } }

        Header("Filtering & recommendations")
        NavRow("Blocklist", "Blocked tags, creators and hidden items") { nav.navigate(Routes.BLOCKLIST) }
        NavRow("What For You has learned", "The tags, words and creators it thinks you like or skip") { nav.navigate(Routes.INTERESTS) }
        NavRow("Recommendation weights (advanced)", null) { nav.navigate(Routes.ADVANCED) }
        NavRow("Reset recommendations", "Forget all likes/skips used for For You") { dialog = PendingDialog.ConfirmReset }

        Header("Backup")
        NavRow("Export settings", "Encrypted JSON (no PIN, tokens or history)") { exportLauncher.launch("pfeed-settings.json") }
        NavRow("Import settings", null) { importLauncher.launch(arrayOf("application/json", "*/*")) }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    when (val d = dialog) {
        PendingDialog.ChangePin -> ChangePinDialog(onDismiss = { dialog = null }) { cur, new -> vm.changePin(cur, new); dialog = null }
        PendingDialog.ConfirmReset -> AlertDialog(
            onDismissRequest = { dialog = null },
            properties = SecureDialog,
            title = { Text("Reset recommendations?") },
            text = { Text("Your likes, saves and history stay. Only the learned preferences are cleared.") },
            confirmButton = { TextButton(onClick = { vm.resetRecommendations(); dialog = null }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
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
internal fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
internal fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
internal fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(title)
    }
}

@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
internal fun BackHeader(title: String, nav: NavController) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
    HorizontalDivider()
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
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pw) }, enabled = valid) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
