package dev.pfeed.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.data.repo.BlockKind

@Composable
fun BlocklistScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val entries by vm.blockEntries.collectAsStateWithLifecycle()
    val tags = entries.filter { it.kind == BlockKind.TAG.name }
    val creators = entries.filter { it.kind == BlockKind.CREATOR.name }
    val hiddenCount = entries.count { it.kind == BlockKind.ITEM.name }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackHeader("Blocklist", nav)
        Text(
            "Blocked tags and creators are filtered out of every feed and search the moment you add them.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )

        Header("Tags")
        AddRow("Tag to block") { vm.block(BlockKind.TAG, it) }
        tags.forEach { row ->
            ListItem(
                headlineContent = { Text("#${row.value}") },
                trailingContent = { IconButton(onClick = { vm.unblock(row) }) { Icon(Icons.Filled.Close, "Unblock") } },
            )
        }

        Header("Creators")
        AddRow("Creator to block") { vm.block(BlockKind.CREATOR, it) }
        creators.forEach { row ->
            ListItem(
                headlineContent = { Text("@${row.value}") },
                trailingContent = { IconButton(onClick = { vm.unblock(row) }) { Icon(Icons.Filled.Close, "Unblock") } },
            )
        }

        Header("Hidden items")
        Text("$hiddenCount items marked “Not interested”.", Modifier.padding(horizontal = 16.dp))
        if (hiddenCount > 0) {
            OutlinedButton(onClick = { vm.clearNotInterested() }, modifier = Modifier.padding(16.dp)) { Text("Unhide all") }
        }
    }
}

@Composable
private fun AddRow(label: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        Button(onClick = { onAdd(text); text = "" }, enabled = text.isNotBlank()) { Text("Block") }
    }
}
