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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextOverflow
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
        contentPadding = PaddingValues(2.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
            Box(
                Modifier
                    .padding(2.dp)
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpen(index) },
            ) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                val badge = when (item.type) {
                    MediaType.VIDEO -> item.durationSec?.let(::formatDuration) ?: "▶"
                    MediaType.GIF -> "GIF"
                    MediaType.EMBED -> "▶"
                    MediaType.IMAGE -> null
                }
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(Color(0x99000000))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                item.title?.let {
                    Text(
                        it,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Color(0x88000000))
                            .padding(4.dp),
                    )
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
