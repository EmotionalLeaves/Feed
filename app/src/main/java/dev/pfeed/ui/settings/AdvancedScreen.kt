package dev.pfeed.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlin.math.roundToInt

@Composable
fun AdvancedScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val w = s.weights

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackHeader("Recommendation weights", nav)
        Text(
            "For You score = tag·affinity + creator·affinity + freshness + source diversity, with a share of " +
                "random picks for exploration. Changes apply to the next page loaded.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        LabeledSlider("Tag affinity: ${"%.2f".format(w.tag)}", w.tag.toFloat(), 0f..2f) { vm.setWeights(w.copy(tag = it.toDouble())) }
        LabeledSlider("Creator affinity: ${"%.2f".format(w.creator)}", w.creator.toFloat(), 0f..2f) { vm.setWeights(w.copy(creator = it.toDouble())) }
        LabeledSlider("Freshness: ${"%.2f".format(w.freshness)}", w.freshness.toFloat(), 0f..2f) { vm.setWeights(w.copy(freshness = it.toDouble())) }
        LabeledSlider("Source diversity: ${"%.2f".format(w.diversity)}", w.diversity.toFloat(), 0f..2f) { vm.setWeights(w.copy(diversity = it.toDouble())) }
        LabeledSlider("Exploration: ${(w.exploration * 100).roundToInt()}%", w.exploration.toFloat(), 0f..0.5f) {
            vm.setWeights(w.copy(exploration = it.toDouble()))
        }
        LabeledSlider("Affinity half-life: ${s.halfLifeDays.roundToInt()} days", s.halfLifeDays.toFloat(), 1f..60f) {
            vm.setHalfLife(it.roundToInt().toDouble())
        }
        OutlinedButton(onClick = vm::resetWeights, modifier = Modifier.padding(16.dp)) { Text("Restore defaults") }
        OutlinedButton(onClick = { vm.resetRecommendations() }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Reset recommendations") }
    }
}
