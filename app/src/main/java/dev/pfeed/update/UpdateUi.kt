package dev.pfeed.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdaterEntryPoint {
    fun updater(): Updater
}

@Composable
fun rememberUpdater(): Updater {
    val context = LocalContext.current.applicationContext
    return androidx.compose.runtime.remember { EntryPointAccessors.fromApplication(context, UpdaterEntryPoint::class.java).updater() }
}

/** Starts the install, sending you to Android's "Install unknown apps" switch first if it's off. */
fun Updater.installFromUi(context: android.content.Context) {
    if (!allowedToInstall()) {
        runCatching { context.startActivity(unknownSourcesSettings()) }
        android.widget.Toast.makeText(context, "Allow Feed to install apps, then come back and tap Install", android.widget.Toast.LENGTH_LONG).show()
    } else install()
}

/**
 * A downloaded update that needs your tap: a small card above the tab bar, once per session
 * ("Later" hides it until the app is next opened). Quiet updates never show it.
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val updater = rememberUpdater()
    val state by updater.state.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = state as? Updater.State.Ready
    val context = LocalContext.current
    val show = ready != null && dismissed != ready.release.version && !updater.canInstallQuietly()
    AnimatedVisibility(
        visible = show,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val r = ready ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = 12.dp).widthIn(max = 520.dp).fillMaxWidth()
                .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(dev.pfeed.ui.theme.BrandGradient),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Version ${r.release.version} is ready", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Downloaded from GitHub", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
            TextButton(onClick = { dismissed = r.release.version }) { Text("Later", color = Color.White.copy(alpha = 0.7f)) }
            TextButton(onClick = { updater.installFromUi(context) }) { Text("Install", fontWeight = FontWeight.Bold) }
        }
    }
}
