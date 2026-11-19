package dev.pfeed.ui.settings

import androidx.compose.material.icons.filled.ThumbDown
import dev.pfeed.ui.openTag
import androidx.compose.material.icons.filled.Add
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Share
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
        // The engine's interests: what For You is built from, and how much of it each one gets.
        val groups by vm.engineInterests.collectAsStateWithLifecycle()
        val landing by vm.landing.collectAsStateWithLifecycle()
        if (landing.count { it != null } >= 2) {
            Section("How well For You knows you", footer = "The share of the posts For You picked that you stayed on, liked or saved, day by day for two weeks.") {
                LandingCard(landing)
            }
        }
        val over by vm.tasteHistory.collectAsStateWithLifecycle()
        if (over.days.size >= 3) {
            Section("Your taste over time", footer = "Each day's share of For You for each of your interests, the last ${over.days.size} days you watched.") {
                TasteOverTime(over)
            }
        }
        val sky by vm.tasteMap.collectAsStateWithLifecycle()
        sky?.takeIf { it.stars.size >= 5 }?.let { m ->
            Section("Your taste map", footer = "Tags that keep company sit together. Bigger means you like it more; each colour is one of your interests below, glowing by its share of For You. Tap a star to open it, or the glow at a colour's centre to play that taste.") {
                TasteMapView(m, onOpenTag = { openTag(nav, it) }, onSun = { idx ->
                    groups.firstOrNull { it.index == idx }?.let { g ->
                        vm.playMix(g)
                        nav.navigate(Routes.FEED) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                })
            }
        }
        val eye by vm.eye.collectAsStateWithLifecycle()
        if (eye.first.isNotEmpty() || eye.second.isNotEmpty()) {
            Section("Your eye", footer = "Read from the thumbnails of what you stay on and what you flick past, on this phone. It counts most where tags say little.") {
                SettingRow(
                    if (eye.first.isNotEmpty()) "You stay longer for " + eye.first.joinToString(", ") else "Still watching what you like the look of",
                    subtitle = if (eye.second.isNotEmpty()) "and pass quicker on " + eye.second.joinToString(", ") else null,
                    icon = Icons.Filled.Visibility,
                )
            }
        }
        if (groups.isNotEmpty()) {
            Section("Your interests", footer = "For You takes turns between these, more often for the ones you enjoy most. Each is learned from what you linger on; a new one appears when you start loving something new. ▶ plays one on its own for a while.") {
                groups.forEachIndexed { n, g ->
                    if (n > 0) RowDivider()
                    InterestGroupRow(
                        g,
                        onOpen = { openTag(nav, g.tags.first()) },
                        onPlay = {
                            vm.playMix(g)
                            nav.navigate(Routes.FEED) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onLess = { vm.lessOfInterest(g.index) },
                        onForget = { vm.forgetEngineInterest(g.index) },
                    )
                }
            }
        }
        // Share your taste with someone, or blend theirs into yours.
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var blending by remember { mutableStateOf(false) }
        Section("Share & blend", footer = "A taste code holds only the tags you like most and how much. Blending lasts a few hours; stop it from the chip on For You.") {
            SettingRow(
                "Share your taste",
                subtitle = "A short code for someone else's app, so they can blend your taste into theirs",
                icon = Icons.Filled.Share,
                onClick = {
                    scope.launch {
                        val code = vm.tasteCode()
                        if (code == null) {
                            android.widget.Toast.makeText(context, "Nothing learned yet to share", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, "My taste code (paste it in Your taste → Blend): $code")
                            runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share your taste")) }
                        }
                    }
                },
            )
            RowDivider()
            SettingRow(
                "Blend with someone's taste",
                subtitle = "Paste their code: For You mixes their favourites in with yours",
                icon = Icons.Filled.AutoAwesome,
                onClick = { blending = true },
            )
        }
        if (blending) {
            var text by remember { mutableStateOf("") }
            val preview = remember(text) { dev.pfeed.domain.reco.TasteCode.decode(text) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { blending = false },
                properties = SecureDialog,
                title = { Text("Blend with someone's taste") },
                text = {
                    Column {
                        androidx.compose.material3.OutlinedTextField(
                            value = text, onValueChange = { text = it.take(3000) },
                            placeholder = { Text("PF1.…") }, maxLines = 3,
                        )
                        if (preview != null) {
                            Text(
                                "Their favourites: " + preview.take(8).joinToString(", ") { Features.label(it.first).removePrefix("#") },
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        } else if (text.isNotBlank()) {
                            Text("That isn't a taste code.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Bad, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = preview != null, onClick = {
                        if (vm.blendWith(text)) {
                            android.widget.Toast.makeText(context, "For You is blended for the next few hours", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        blending = false
                    }) { Text("Blend") }
                },
                dismissButton = { TextButton(onClick = { blending = false }) { Text("Cancel") } },
            )
        }

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

/** One interest: its names, a bar for its share of For You, and "less" / "forget". */
@Composable
private fun InterestGroupRow(g: dev.pfeed.domain.reco.TasteEngine.View, onOpen: () -> Unit, onLess: () -> Unit, onForget: () -> Unit, onPlay: () -> Unit = {}) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(
                g.tags.joinToString("  ·  ") { "#" + it.replace('_', ' ') },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 2,
                color = if (g.sour) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            if (g.sour) {
                Text("Being avoided", style = MaterialTheme.typography.labelMedium, color = dev.pfeed.ui.theme.TextLow)
            } else {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.weight(1f).height(6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                    ) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.fillMaxWidth(g.share.toFloat().coerceIn(0.02f, 1f)).height(6.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                                .background(dev.pfeed.ui.theme.BrandGradient),
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    Text("${(g.share * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = dev.pfeed.ui.theme.TextMid)
                }
                g.time?.let { t ->
                    androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                    Text("Mostly $t", style = MaterialTheme.typography.labelMedium, color = dev.pfeed.ui.theme.TextLow)
                }
            }
        }
        if (!g.sour) androidx.compose.material3.IconButton(onClick = onPlay) {
            androidx.compose.material3.Icon(Icons.Filled.PlayArrow, contentDescription = "Play this as a mix", tint = Accent)
        }
        if (!g.sour) androidx.compose.material3.IconButton(onClick = onLess) {
            androidx.compose.material3.Icon(Icons.Filled.ThumbDown, contentDescription = "Less of this", tint = dev.pfeed.ui.theme.TextLow)
        }
        androidx.compose.material3.IconButton(onClick = onForget) {
            androidx.compose.material3.Icon(Icons.Filled.Close, contentDescription = "Forget this interest", tint = dev.pfeed.ui.theme.TextLow)
        }
    }
}

/** This week's landing rate, the change from the week before, and a bar per day. */
@Composable
private fun LandingCard(days: List<Double?>) {
    val week = days.takeLast(7).filterNotNull()
    val before = days.dropLast(7).filterNotNull()
    val now = week.takeIf { it.isNotEmpty() }?.average()
    val then = before.takeIf { it.isNotEmpty() }?.average()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Bottom) {
            Text(
                now?.let { "${(it * 100).roundToInt()}%" } ?: "–",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            val change = if (now != null && then != null) ((now - then) * 100).roundToInt() else null
            Text(
                when {
                    change == null -> "landed this week"
                    change > 0 -> "landed this week · up $change from the week before"
                    change < 0 -> "landed this week · down ${-change} from the week before"
                    else -> "landed this week · same as the week before"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEachIndexed { i, d ->
                Box(
                    Modifier.weight(1f).fillMaxHeight(((d ?: 0.0).toFloat()).coerceIn(0.04f, 1f))
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(
                            if (d == null) androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            else if (i >= days.size - 7) dev.pfeed.ui.theme.BrandGradient
                            else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
                        ),
                )
            }
        }
    }
}

/** One stacked bar per day: how For You's slots were shared between your interests. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TasteOverTime(h: SettingsViewModel.TasteHistory) {
    val colors = TasteColors
    val ids = h.days.flatMap { d -> d.second.map { it.first } }.distinct()
    fun color(id: Int) = colors[ids.indexOf(id).coerceAtLeast(0) % colors.size]
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            h.days.forEach { (_, shares) ->
                Column(Modifier.weight(1f).fillMaxHeight().clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))) {
                    val total = shares.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1e-6f)
                    shares.sortedBy { ids.indexOf(it.first) }.forEach { (id, share) ->
                        Box(Modifier.fillMaxWidth().weight((share / total).coerceAtLeast(0.001f)).background(color(id)))
                    }
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        // The ones in the latest day, named.
        val latest = h.days.lastOrNull()?.second.orEmpty().sortedByDescending { it.second }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            latest.forEach { (id, _) ->
                val name = h.names[id]?.joinToString(" · ") { "#" + it.replace('_', ' ') } ?: return@forEach
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color(id)))
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    Text(name, style = MaterialTheme.typography.labelMedium, color = TextMid)
                }
            }
        }
    }
}

private val TasteColors = listOf(
    androidx.compose.ui.graphics.Color(0xFFFF5C8A), androidx.compose.ui.graphics.Color(0xFF8C7CFF), androidx.compose.ui.graphics.Color(0xFF3FC6FF),
    androidx.compose.ui.graphics.Color(0xFF39D98A), androidx.compose.ui.graphics.Color(0xFFFFB547), androidx.compose.ui.graphics.Color(0xFFFF7A45),
    androidx.compose.ui.graphics.Color(0xFFE36BFF), androidx.compose.ui.graphics.Color(0xFF7FE0D4),
)
