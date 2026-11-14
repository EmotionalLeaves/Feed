package dev.pfeed.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.domain.reco.Features
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.ui.Routes
import dev.pfeed.ui.common.EmptyState
import dev.pfeed.ui.common.RowDivider
import dev.pfeed.ui.common.ScreenHeader
import dev.pfeed.ui.common.SecureDialog
import dev.pfeed.ui.common.Section
import dev.pfeed.ui.common.SettingRow
import dev.pfeed.ui.theme.Accent
import dev.pfeed.ui.theme.Bad
import dev.pfeed.ui.theme.TextLow
import dev.pfeed.ui.theme.TextMid
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything about For You in one place: what it has learned (tap a tag to open it), the ranking
 * weights for people who want to tune them, and starting over.
 */
@Composable
fun TasteScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val interests by vm.interests.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    var tuning by rememberSaveable { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadInterests() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Your taste",
            subtitle = "Learned from what you watch to the end, replay, like, save and follow, and from what you skip. Interests fade when you stop engaging.",
            onBack = { nav.popBackStack() },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val (likes, dislikes) = interests ?: (emptyList<Pair<String, Double>>() to emptyList())
        if (interests != null && likes.isEmpty() && dislikes.isEmpty()) {
            EmptyState(Icons.Filled.AutoAwesome, "Still learning", "Watch, like and skip a few dozen posts and this fills in.")
        }
        if (likes.isNotEmpty()) {
            Section("More of") {
                likes.forEachIndexed { i, (f, v) ->
                    if (i > 0) RowDivider(indent = false)
                    InterestRow(f, v, positive = true) { openTag(nav, f) }
                }
            }
        }
        if (dislikes.isNotEmpty()) {
            Section("Less of") {
                dislikes.forEachIndexed { i, (f, v) ->
                    if (i > 0) RowDivider(indent = false)
                    InterestRow(f, v, positive = false) { openTag(nav, f) }
                }
            }
        }

        Section("Ranking", footer = if (tuning) "Changes apply to the next posts loaded." else null) {
            SettingRow(
                "Fine-tune ranking",
                subtitle = "How much each signal counts",
                icon = Icons.Filled.Tune,
                onClick = { tuning = !tuning },
                trailing = { Icon(if (tuning) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextLow) },
            )
            AnimatedVisibility(tuning) {
                val w = s.weights
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    WeightSlider("Tags you like", w.tag, 0f..2f) { vm.setWeights(w.copy(tag = it)) }
                    WeightSlider("Creators you like", w.creator, 0f..2f) { vm.setWeights(w.copy(creator = it)) }
                    WeightSlider("New posts first", w.freshness, 0f..2f) { vm.setWeights(w.copy(freshness = it)) }
                    WeightSlider("Mix of sources", w.diversity, 0f..2f) { vm.setWeights(w.copy(diversity = it)) }
                    WeightSlider("Avoid similar posts in a row", w.similarity, 0f..2f) { vm.setWeights(w.copy(similarity = it)) }
                    WeightSlider("Community score", w.quality, 0f..2f) { vm.setWeights(w.copy(quality = it)) }
                    WeightSlider("Random discoveries", w.exploration, 0f..0.5f, percent = true) { vm.setWeights(w.copy(exploration = it)) }
                    WeightSlider("Memory (days)", s.halfLifeDays, 1f..60f, whole = true) { vm.setHalfLife(it.roundToInt().toDouble()) }
                    TextButton(onClick = vm::resetWeights) { Text("Restore defaults") }
                }
            }
            RowDivider()
            SettingRow(
                "Start over",
                subtitle = "Forget everything above. Likes, saves and history stay.",
                icon = Icons.Filled.RestartAlt,
                iconTint = Bad,
                titleColor = Bad,
                onClick = { confirmReset = true },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            properties = SecureDialog,
            title = { Text("Start over?") },
            text = { Text("For You forgets what it has learned and starts again. Your likes, saves and history stay.") },
            confirmButton = { TextButton(onClick = { vm.resetRecommendations(); vm.loadInterests(); confirmReset = false }) { Text("Start over") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

private fun openTag(nav: NavController, feature: String) {
    if (feature.startsWith("@")) return
    nav.navigate(Routes.tag(feature.removePrefix("w:")))
}

@Composable
private fun WeightSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    percent: Boolean = false,
    whole: Boolean = false,
    onChange: (Double) -> Unit,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                when {
                    percent -> "${(value * 100).roundToInt()}%"
                    whole -> "${value.roundToInt()}"
                    else -> "%.2f".format(value)
                },
                style = MaterialTheme.typography.labelLarge,
                color = TextMid,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
        )
    }
}

@Composable
private fun InterestRow(feature: String, score: Double, positive: Boolean, onClick: () -> Unit) {
    val label = if (feature.startsWith("@")) "by ${feature.removePrefix("@")}" else Features.label(feature)
    // Same squash the ranker uses, so the bar shows how much this actually moves the ranking.
    val strength = abs(Ranker.squash(score)).toFloat().coerceIn(0.04f, 1f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.width(110.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(strength).clip(RoundedCornerShape(3.dp))
                    .background(if (positive) Accent else Bad),
            )
        }
    }
}
