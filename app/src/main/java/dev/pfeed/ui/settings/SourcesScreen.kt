package dev.pfeed.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.pfeed.ui.common.findActivity

@Composable
fun SourcesScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val auth by vm.authStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var feedUrl by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackHeader("Sources", nav)
        Text(
            "Order is priority: when the same item appears in two sources, the higher one wins.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
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
                    IconButton(onClick = { vm.moveSource(a.id, -1) }, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { vm.moveSource(a.id, +1) }, enabled = index < sources.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
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

        Header("RSS / Atom feeds")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = feedUrl,
                onValueChange = { feedUrl = it },
                label = { Text("Feed URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { if (vm.addFeed(feedUrl)) feedUrl = "" }, enabled = feedUrl.isNotBlank()) { Text("Add") }
        }
        if (s.rssFeeds.isEmpty()) Text("No feeds yet.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        s.rssFeeds.forEach { url ->
            ListItem(
                headlineContent = { Text(url, maxLines = 2) },
                trailingContent = { IconButton(onClick = { vm.removeFeed(url) }) { Icon(Icons.Filled.Delete, "Remove feed") } },
            )
        }

        Header("Local folders")
        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Add folder…") }
        if (s.localFolders.isEmpty()) Text("No folders yet.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        s.localFolders.forEach { uri ->
            ListItem(
                headlineContent = { Text(Uri.parse(uri).lastPathSegment?.substringAfter(':') ?: uri, maxLines = 2) },
                trailingContent = { IconButton(onClick = { vm.removeFolder(uri) }) { Icon(Icons.Filled.Delete, "Remove folder") } },
            )
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
