package dev.pfeed.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pfeed.domain.reco.Steer
import dev.pfeed.ui.common.SecureDialog

private val EXAMPLES = listOf("more redheads, less cosplay", "only amateur", "no feet", "just videos", "short clips and pov")

/** Tell For You what you're in the mood for, in your own words. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SteerDialog(current: List<Steer.Directive>, onSteer: (String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun go() { if (text.isNotBlank()) { onSteer(text); onDismiss() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
        title = { Text("What are you in the mood for?") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(200) },
                    placeholder = { Text("more redheads, less cosplay") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    singleLine = true,
                    modifier = Modifier.focusRequester(focus),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    "Say more of, less of, no or only. For You follows it for the next few hours, then goes back to normal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
                Spacer(Modifier.size(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EXAMPLES.forEach { e ->
                        Text(
                            e,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                .clickable { text = if (text.isBlank()) e else "$text, $e" }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { go() }) { Text("Steer") } },
        dismissButton = {
            if (current.isNotEmpty()) TextButton(onClick = { onClear(); onDismiss() }) { Text("Back to normal") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Under the feed tabs while For You is being steered: what it was told, tap to change, × to stop. */
@Composable
fun SteerChip(list: List<Steer.Directive>, onEdit: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onEdit)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = dev.pfeed.ui.theme.Accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            list.joinToString("  ") { it.label },
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 7.dp).weight(1f, fill = false),
        )
        Icon(
            Icons.Filled.Close, contentDescription = "Stop steering", tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClear).padding(6.dp).size(14.dp),
        )
    }
}
