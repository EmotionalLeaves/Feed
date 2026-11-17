package dev.pfeed.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pfeed.ui.theme.BrandGradient

/** Common starting points: a mix of what people come for. */
val STARTER_TOPICS = listOf(
    "amateur", "milf", "lesbian", "anal", "blowjob", "big tits", "big ass", "pov", "bdsm",
    "bondage", "femdom", "cosplay", "hentai", "3d", "furry", "asian", "latina", "ebony", "redhead",
    "creampie", "gangbang", "threesome", "solo", "feet", "lingerie", "public", "rough", "gay", "trans",
)

/**
 * A full-screen choice of topics (tap to pick, tap again to drop) with one gradient button to
 * confirm. Used on first run and from Your taste.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopicPicker(
    title: String,
    subtitle: String,
    onDone: (List<String>) -> Unit,
    onSkip: () -> Unit,
    skipLabel: String = "Skip",
    exclude: Set<String> = emptySet(),
) {
    val picked = remember { mutableStateListOf<String>() }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF09090B)).pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding().padding(horizontal = 20.dp),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 28.dp, bottom = 120.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                STARTER_TOPICS.filter { it !in exclude }.forEach { topic ->
                    val on = topic in picked
                    Text(
                        topic,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .then(if (on) Modifier.background(BrandGradient) else Modifier.background(Color.White.copy(alpha = 0.08f)))
                            .clickable { if (on) picked.remove(topic) else picked.add(topic) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0xFF09090B), 1f to Color(0xFF09090B)))
                .padding(top = 48.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandButton(
                if (picked.isEmpty()) "Pick at least one" else "Start with ${picked.size}",
                onClick = { onDone(picked.toList()) },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onSkip) { Text(skipLabel, color = Color.White.copy(alpha = 0.6f)) }
        }
    }
}
