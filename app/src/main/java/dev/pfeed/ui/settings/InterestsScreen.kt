package dev.pfeed.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.domain.reco.Features
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.ui.Routes
import kotlin.math.abs

/**
 * Shows what For You has learned from watch time, replays, likes, saves, follows and skips, so the
 * user can see the algorithm working (and spot anything it got wrong). Tap a tag to open its feed.
 */
@Composable
fun InterestsScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val interests by vm.interests.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadInterests() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackHeader("What For You has learned", nav)
        Text(
            "Built from what you watch to the end, replay, scrub back through, like, save and follow, and " +
                "from what you skip in under 2 seconds. Every tag on a post counts, plus title words where a " +
                "source has few tags. Interests fade if you stop engaging with them.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val (likes, dislikes) = interests ?: (emptyList<Pair<String, Double>>() to emptyList())
        if (interests != null && likes.isEmpty() && dislikes.isEmpty()) {
            Text(
                "Nothing yet. Watch, like and skip a few dozen posts and check back.",
                modifier = Modifier.padding(16.dp),
            )
        }
        if (likes.isNotEmpty()) {
            Header("More of")
            likes.forEach { (f, v) -> InterestRow(f, v, positive = true) { openTag(nav, f) } }
        }
        if (dislikes.isNotEmpty()) {
            Header("Less of")
            dislikes.forEach { (f, v) -> InterestRow(f, v, positive = false) { openTag(nav, f) } }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.loadInterests() }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Refresh") }
        OutlinedButton(onClick = { vm.resetRecommendations() }, modifier = Modifier.padding(16.dp)) { Text("Forget all of this") }
    }
}

private fun openTag(nav: NavController, feature: String) {
    if (feature.startsWith("@")) return
    nav.navigate(Routes.tag(feature.removePrefix("w:")))
}

@Composable
private fun InterestRow(feature: String, score: Double, positive: Boolean, onClick: () -> Unit) {
    val label = if (feature.startsWith("@")) "by ${feature.removePrefix("@")}" else Features.label(feature)
    // Same squash the ranker uses, so the bar shows how much this actually moves the ranking.
    val strength = abs(Ranker.squash(score)).toFloat().coerceIn(0.03f, 1f)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(120.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(strength).clip(RoundedCornerShape(4.dp))
                    .background(if (positive) MaterialTheme.colorScheme.primary else Color(0xFFE5484D)),
            )
        }
    }
}
