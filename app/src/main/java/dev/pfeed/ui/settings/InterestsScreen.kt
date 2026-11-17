package dev.pfeed.ui.settings

import dev.pfeed.ui.openTag
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
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
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadInterests() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Your taste",
            subtitle = "Learned from what you finish, replay, like, save and follow, and what you skip. Interests fade as you move on.",
            onBack = { nav.popBackStack() },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val (likes, dislikes) = interests ?: (emptyList<Pair<String, Double>>() to emptyList())
        if (interests != null && likes.isEmpty() && dislikes.isEmpty()) {
            EmptyState(Icons.Filled.AutoAwesome, "Still learning", "Watch, like and skip a few dozen posts and this fills in.")
        }
        // What it learned, grouped the way people think about it: what kind of post, which sites,
        // then the subjects themselves.
        val all = likes.map { Triple(it.first, it.second, true) } + dislikes.map { Triple(it.first, it.second, false) }
        val formats = all.filter { it.first.startsWith(Features.TYPE) || it.first.startsWith(Features.LENGTH) }
        val sites = all.filter { it.first.startsWith(Features.ORIGIN) }
        val moreOf = likes.filterNot { Features.isMeta(it.first) }
        val lessOf = dislikes.filterNot { Features.isMeta(it.first) }
        // Tell it directly, rather than waiting for it to notice.
        Section {
            SettingRow(
                "Add topics you like",
                subtitle = "For You starts showing more of them right away",
                icon = Icons.Filled.Add,
                chevron = true,
                onClick = { picking = true },
            )
        }
        if (formats.isNotEmpty()) InterestSection("Formats", formats, nav, vm::forgetInterest)
        if (sites.isNotEmpty()) InterestSection("Sites", sites.sortedByDescending { it.second }, nav, vm::forgetInterest)
        if (moreOf.isNotEmpty()) InterestSection("More of", moreOf.map { Triple(it.first, it.second, true) }, nav, vm::forgetInterest)
        if (lessOf.isNotEmpty()) InterestSection("Less of", lessOf.map { Triple(it.first, it.second, false) }, nav, vm::forgetInterest)

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

    if (picking) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { picking = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        ) {
            dev.pfeed.ui.common.TopicPicker(
                title = "Add topics you like",
                subtitle = "Tap the ones you want more of.",
                onDone = { vm.addInterests(it); picking = false },
                onSkip = { picking = false },
                skipLabel = "Cancel",
                // Topics it already likes aren't offered again.
                exclude = (interests?.first.orEmpty()).map { it.first }.toSet().let { liked ->
                    dev.pfeed.ui.common.STARTER_TOPICS.filter { Features.normalizeTag(it) in liked }.toSet()
                },
            )
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

@Composable
private fun InterestSection(title: String, rows: List<Triple<String, Double, Boolean>>, nav: NavController, onForget: (String) -> Unit) {
    Section(title, footer = if (title == "Formats") "Tap × to make For You forget something it learned." else null) {
        rows.forEachIndexed { i, (f, v, positive) ->
            if (i > 0) RowDivider(indent = false)
            InterestRow(f, v, positive, onForget = { onForget(f) }) { openTag(nav, f) }
        }
    }
}

private fun openTag(nav: NavController, feature: String) {
    if (feature.startsWith("@") || dev.pfeed.domain.reco.Features.isMeta(feature)) return
    nav.openTag(feature.removePrefix("w:"))
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
private fun InterestRow(feature: String, score: Double, positive: Boolean, onForget: () -> Unit, onClick: () -> Unit) {
    val label = if (feature.startsWith("@")) "by ${feature.removePrefix("@")}" else Features.label(feature)
    // Same squash the ranker uses, so the bar shows how much this actually moves the ranking.
    val strength = abs(Ranker.squash(score)).toFloat().coerceIn(0.04f, 1f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label gets the full width (it was cut off beside the bar); the strength sits under it.
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                // The bar in a word, for a glance down the list.
                Text(
                    when {
                        !positive -> if (strength > 0.5f) "Avoiding" else "Less"
                        strength > 0.66f -> "Strong"
                        strength > 0.33f -> "Steady"
                        else -> "Light"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (positive && strength > 0.66f) Accent else TextLow,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth(0.85f).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(strength).clip(RoundedCornerShape(2.dp))
                        .then(if (positive) Modifier.background(dev.pfeed.ui.theme.BrandGradient) else Modifier.background(Bad)),
                )
            }
        }
        androidx.compose.material3.IconButton(onClick = onForget) {
            Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Forget", tint = TextLow, modifier = Modifier.size(18.dp))
        }
    }
}
