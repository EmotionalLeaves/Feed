package dev.pfeed.ui.feed

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.pfeed.domain.model.MediaItem

/**
 * This or that: two upcoming posts, as different as can be; tap the one you'd rather watch. Ten
 * quick choices teach For You more than ten minutes of scrolling, and the feed starts over with them.
 */
@Composable
fun ThisOrThat(vm: FeedViewModel, onDone: (choices: Int) -> Unit) {
    val pairs by produceState<List<Pair<MediaItem, MediaItem>>?>(null) { value = vm.thisOrThatPairs() }
    var round by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = { onDone(chosen) },
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF0A0A0F)).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("This or that", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("Tap the one you'd rather watch", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                }
                IconButton(onClick = { onDone(chosen) }) { Icon(Icons.Filled.Close, "Done", tint = Color.White) }
            }
            val list = pairs
            val total = list?.size ?: 0
            // Progress: one dot per pair.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(total) { i ->
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (i < round) dev.pfeed.ui.theme.BrandGradient else androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.15f))),
                    )
                }
            }
            when {
                list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { dev.pfeed.ui.common.BrandSpinner() }
                list.isEmpty() || round >= list.size -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(
                            if (list.isEmpty()) "Scroll a little first, then try again" else "Thanks: For You took note",
                            style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(16.dp))
                        dev.pfeed.ui.common.BrandButton("Back to the feed", onClick = { onDone(chosen) })
                    }
                }
                else -> AnimatedContent(
                    targetState = round,
                    transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.94f)) togetherWith fadeOut(tween(160)) },
                    modifier = Modifier.weight(1f),
                    label = "pair",
                ) { r ->
                    val (a, b) = list[r]
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(a to b, b to a).forEach { (pick, other) ->
                            Box(
                                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.06f))
                                    .clickable { vm.preferred(pick, listOf(other)); chosen++; round++ },
                            ) {
                                Thumbnail(pick, Modifier.fillMaxSize())
                                dev.pfeed.ui.common.MediaMask()
                                val label = Display.tags(pick, max = 3).joinToString("  ") { "#" + it.first }
                                if (label.isNotBlank()) Text(
                                    label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (list != null && round < list.size) {
                TextButton(
                    onClick = { val (a, b) = list[round]; vm.preferred(null, listOf(a, b)); round++ },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp),
                ) { Text("Neither", color = Color.White.copy(alpha = 0.75f)) }
            }
        }
    }
}
