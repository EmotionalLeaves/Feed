package dev.pfeed.ui.feed

import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemMenuSheet(
    item: MediaItem,
    saved: Boolean,
    followingCreator: Boolean,
    vm: FeedViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var pickTag by remember { mutableStateOf<String?>(null) } // "block" | "follow"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val mode = pickTag
            if (mode != null) {
                Text(
                    if (mode == "block") "Block which tag?" else "Follow which tag?",
                    modifier = Modifier.clickable { pickTag = null },
                )
                item.tags.forEach { tag ->
                    MenuRow("#$tag", Icons.Filled.Tag) {
                        if (mode == "block") vm.blockTag(tag) else vm.toggleFollowTag(tag)
                        onDismiss()
                    }
                }
                return@Column
            }
            MenuRow(if (saved) "Remove from saved" else "Save", Icons.Filled.Bookmark) { vm.toggleSave(item); onDismiss() }
            MenuRow("Not interested", Icons.Filled.ThumbDown) { vm.notInterested(item); onDismiss() }
            if (item.creator != null) {
                MenuRow(if (followingCreator) "Unfollow ${item.creator}" else "Follow ${item.creator}", Icons.Filled.PersonAdd) {
                    vm.toggleFollowCreator(item); onDismiss()
                }
            }
            if (item.tags.isNotEmpty()) MenuRow("Follow a tag…", Icons.Filled.Tag) { pickTag = "follow" }
            HorizontalDivider()
            if (item.creator != null) {
                MenuRow("Block creator ${item.creator}", Icons.Filled.PersonOff) { vm.blockCreator(item); onDismiss() }
            }
            if (item.tags.isNotEmpty()) MenuRow("Block a tag…", Icons.Filled.Block) { pickTag = "block" }
            HorizontalDivider()
            MenuRow("Open source page", Icons.AutoMirrored.Filled.OpenInNew) { openExternal(context, item.pageUrl); onDismiss() }
            val report = vm.reportUrl(item)
            if (report != null) {
                MenuRow("Report to source", Icons.Filled.Flag) { openExternal(context, report); onDismiss() }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
