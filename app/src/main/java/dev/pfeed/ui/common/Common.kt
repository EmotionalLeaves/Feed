package dev.pfeed.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import coil.compose.AsyncImage
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType

/** Dialogs get their own window; force FLAG_SECURE on them too. */
val SecureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun openExternal(context: Context, url: String) {
    try {
        val uri = Uri.parse(url)
        if (uri.scheme == "http" || uri.scheme == "https") {
            CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF).build().launchUrl(context, uri)
        } else {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MediaGrid(
    items: List<MediaItem>,
    onOpen: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpen(index) },
            ) {
                // Placeholder glyph shows while the thumbnail loads (or if it never does).
                Icon(
                    if (item.type == MediaType.IMAGE) Icons.Filled.Image else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                val badge = when (item.type) {
                    MediaType.VIDEO, MediaType.EMBED -> item.durationSec?.let(::formatDuration) ?: ""
                    MediaType.GIF -> "GIF"
                    MediaType.IMAGE -> null
                }
                if (badge != null) {
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.type != MediaType.GIF) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                        if (badge.isNotEmpty()) {
                            Text(badge, style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

fun formatDuration(sec: Int): String = if (sec >= 3600) {
    "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
} else {
    "%d:%02d".format(sec / 60, sec % 60)
}
