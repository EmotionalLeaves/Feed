package dev.pfeed.ui.feed

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Flag
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
            if (mode != null) {
                ListItem(
                    headlineContent = { Text("Hide posts tagged…", fontWeight = FontWeight.Bold) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") },
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
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))) { Thumbnail(item) }
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
            HorizontalDivider()

            // Save, like and follow live on the post itself; this menu is for the rest.
            MenuRow("Not interested", Icons.Filled.ThumbDown, "Show fewer posts like this") { vm.notInterested(item); onDismiss() }
            if (tags.isNotEmpty()) MenuRow("Hide a tag…", Icons.Filled.Block, "Never show posts with that tag") { pickTag = "block" }
            if (creator != null) {
                MenuRow("Block @$creator", Icons.Filled.PersonOff, "Never show their posts") { vm.blockCreator(item); onDismiss() }
            }
            MenuRow("Hide $source", Icons.Filled.VisibilityOff, "Never show posts from it") { vm.hideOrigin(item, source); onDismiss() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuRow("Open on $source", Icons.AutoMirrored.Filled.OpenInNew) { openExternal(context, item.pageUrl); onDismiss() }
            val report = vm.reportUrl(item)
            if (report != null) {
                MenuRow("Report to $source", Icons.Filled.Flag) { openExternal(context, report); onDismiss() }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
