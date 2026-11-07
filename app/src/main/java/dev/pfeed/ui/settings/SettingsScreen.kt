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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
    data object EnableLock : PendingDialog
    data object DisableLock : PendingDialog
    data object ConfirmReset : PendingDialog
    data object PickLockTimeout : PendingDialog
    data object PickDisguise : PendingDialog
    data object PickHistory : PendingDialog
    data class ExportPassword(val uri: Uri) : PendingDialog
    data class ImportPassword(val uri: Uri) : PendingDialog
}

@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val lockOn by vm.lockEnabled.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<PendingDialog?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) dialog = PendingDialog.ExportPassword(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) dialog = PendingDialog.ImportPassword(uri)
    }

    val disguiseOptions = listOf(Disguise.NONE to "Feed (default)", Disguise.NOTES to "Notes", Disguise.CALCULATOR to "Calculator")

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )

        Header("Content")
        NavRow("Sources", "Turn sites on and off, add subreddits, tags and feeds", Icons.Filled.Dns) { nav.navigate(Routes.SOURCES) }
        NavRow("What For You has learned", "What it thinks you like and skip", Icons.Filled.AutoAwesome) { nav.navigate(Routes.INTERESTS) }
        NavRow("Blocked", "Tags, creators and posts you've hidden", Icons.Filled.Block) { nav.navigate(Routes.BLOCKLIST) }

        Header("Playback")
        SwitchRow("Start muted", s.defaultMute, Icons.Filled.VolumeOff, vm::setDefaultMute)
        SwitchRow("Autoplay on mobile data", s.autoplayOnMobileData, Icons.Filled.SignalCellularAlt, vm::setAutoplayOnMobile)
        val cacheIndex = cacheSteps.indexOf(s.cacheSizeMb).takeIf { it >= 0 } ?: 2
        LabeledSlider(
            label = "Video cache: ${formatMb(cacheSteps[cacheIndex])}",
            value = cacheIndex.toFloat(),
            range = 0f..(cacheSteps.size - 1).toFloat(),
            steps = cacheSteps.size - 2,
            onChange = { vm.setCacheSize(cacheSteps[it.roundToInt()]) },
        )
        Text(
            "Bigger cache = fewer re-downloads. Applies next time the app starts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Header("Privacy")
        SwitchRow("App lock", lockOn, Icons.Filled.Lock) { on -> dialog = if (on) PendingDialog.EnableLock else PendingDialog.DisableLock }
        if (lockOn) {
            ValueRow("Lock again after", lockSteps.firstOrNull { it.first == s.lockTimeoutSec }?.second ?: "${s.lockTimeoutSec} s", Icons.Filled.Timer) {
                dialog = PendingDialog.PickLockTimeout
            }
            NavRow("Change PIN", null, Icons.Filled.Password) { dialog = PendingDialog.ChangePin }
        }
        ValueRow("App icon", disguiseOptions.firstOrNull { it.first == s.disguise }?.second ?: "Feed", Icons.Filled.Apps) {
            dialog = PendingDialog.PickDisguise
        }
        ValueRow("Keep watch history", "${s.historyRetentionDays} days", Icons.Filled.History) { dialog = PendingDialog.PickHistory }
        Text(
            "Panic: touch the screen with three fingers to jump to your home screen" +
                if (lockOn) " and lock the app." else ".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Header("Recommendations")
        NavRow("Fine-tune ranking", "Advanced weights", Icons.Filled.Tune) { nav.navigate(Routes.ADVANCED) }
        NavRow("Reset recommendations", "Start learning your taste from scratch", Icons.Filled.RestartAlt) { dialog = PendingDialog.ConfirmReset }

        Header("Backup")
        NavRow("Export settings", "Encrypted file. Not included: PIN, sign-ins, history", Icons.Filled.Upload) { exportLauncher.launch("pfeed-settings.json") }
        NavRow("Import settings", null, Icons.Filled.Download) { importLauncher.launch(arrayOf("application/json", "*/*")) }
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
        PendingDialog.ConfirmReset -> AlertDialog(
            onDismissRequest = { dialog = null },
            properties = SecureDialog,
            title = { Text("Reset recommendations?") },
            text = { Text("Your likes, saves and history stay. Only the learned preferences are cleared.") },
            confirmButton = { TextButton(onClick = { vm.resetRecommendations(); dialog = null }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        PendingDialog.PickLockTimeout -> ChoiceDialog(
            title = "Lock again after",
            options = lockSteps,
            selected = s.lockTimeoutSec,
            onDismiss = { dialog = null },
        ) { vm.setLockTimeout(it); dialog = null }
        PendingDialog.PickDisguise -> ChoiceDialog(
            title = "App icon",
            note = "Changes the icon and name on your home screen.",
            options = disguiseOptions,
            selected = s.disguise,
            onDismiss = { dialog = null },
        ) { vm.setDisguise(it); dialog = null }
        PendingDialog.PickHistory -> ChoiceDialog(
            title = "Keep watch history for",
            note = "Older history is deleted. Posts you've seen won't come back while they're in history.",
            options = historySteps.map { it to "$it days" },
            selected = s.historyRetentionDays,
            onDismiss = { dialog = null },
        ) { vm.setHistoryRetention(it); dialog = null }
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
internal fun NavRow(title: String, subtitle: String?, icon: ImageVector? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

/** A setting with one current value; tapping opens a picker. */
@Composable
internal fun ValueRow(title: String, value: String, icon: ImageVector? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value, color = MaterialTheme.colorScheme.primary) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun <T> ChoiceDialog(
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
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                }
                options.forEach { (value, label) -> RadioRow(label, value == selected) { onPick(value) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
