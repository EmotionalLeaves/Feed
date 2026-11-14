package dev.pfeed.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.data.db.BlockEntity
import dev.pfeed.data.repo.BlockKind
import dev.pfeed.ui.common.EmptyState
import dev.pfeed.ui.common.RowDivider
import dev.pfeed.ui.common.ScreenHeader
import dev.pfeed.ui.common.Section
import dev.pfeed.ui.common.SettingRow
import dev.pfeed.ui.theme.Accent
import dev.pfeed.ui.theme.TextLow

/**
 * Everything kept out of your feed, in one list: blocked tags and creators, hidden sources and
 * posts marked "Not interested". Add a tag or creator by name here; the ⋯ menu on any post does
 * the same from the feed.
 */
@Composable
fun BlocklistScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val entries by vm.blockEntries.collectAsStateWithLifecycle()
    val tags = entries.filter { it.kind == BlockKind.TAG.name }
    val creators = entries.filter { it.kind == BlockKind.CREATOR.name }
    val origins = entries.filter { it.kind == BlockKind.ORIGIN.name }
    val hiddenCount = entries.count { it.kind == BlockKind.ITEM.name }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Hidden & blocked",
            subtitle = "Kept out of every feed and search the moment you add them.",
            onBack = { nav.popBackStack() },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AddBlock { kind, value -> vm.block(kind, value) }

        if (tags.isEmpty() && creators.isEmpty() && origins.isEmpty() && hiddenCount == 0) {
            EmptyState(Icons.Filled.Block, "Nothing hidden", "Use ⋯ on any post to hide a tag, a creator or a whole source.")
        }
        if (tags.isNotEmpty()) BlockSection("Tags", tags, Icons.Filled.Tag, vm) { "#" + it.value.replace('_', ' ') }
        if (creators.isNotEmpty()) BlockSection("Creators", creators, Icons.Filled.Person, vm) { "@" + it.value }
        if (origins.isNotEmpty()) BlockSection("Sources", origins, Icons.Filled.VisibilityOff, vm) { it.value.substringAfter(':') }
        if (hiddenCount > 0) {
            Section("Posts") {
                SettingRow(
                    "$hiddenCount marked Not interested",
                    icon = Icons.Filled.ThumbDown,
                    iconTint = TextLow,
                    trailing = { TextButton(onClick = { vm.clearNotInterested() }) { Text("Unhide all") } },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun BlockSection(
    title: String,
    rows: List<BlockEntity>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    vm: SettingsViewModel,
    label: (BlockEntity) -> String,
) {
    Section(title) {
        rows.forEachIndexed { i, row ->
            if (i > 0) RowDivider()
            SettingRow(
                label(row),
                icon = icon,
                iconTint = TextLow,
                trailing = { IconButton(onClick = { vm.unblock(row) }) { Icon(Icons.Filled.Close, contentDescription = "Unblock", tint = TextLow) } },
            )
        }
    }
}

@Composable
private fun AddBlock(onAdd: (BlockKind, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(BlockKind.TAG) }
    val submit = {
        val v = text.trim().removePrefix("#").removePrefix("@")
        if (v.isNotEmpty()) { onAdd(kind, v); text = "" }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == BlockKind.TAG, onClick = { kind = BlockKind.TAG }, label = { Text("Tag") })
            FilterChip(selected = kind == BlockKind.CREATOR, onClick = { kind = BlockKind.CREATOR }, label = { Text("Creator") })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(if (kind == BlockKind.TAG) "Tag to block, e.g. feet" else "Creator name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = submit, enabled = text.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("Block") }
        }
    }
}
