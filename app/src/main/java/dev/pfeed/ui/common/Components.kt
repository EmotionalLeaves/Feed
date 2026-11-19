package dev.pfeed.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pfeed.ui.theme.Accent
import dev.pfeed.ui.theme.SectionLabel
import dev.pfeed.ui.theme.TextLow
import dev.pfeed.ui.theme.TextMid

/**
 * The one header every non-feed screen uses: a big bold title, an optional line under it, an
 * optional back arrow (only on screens you were sent to, never on tabs) and optional actions.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp),
            )
        }
    }
}

/**
 * A titled group of rows on one rounded card. Rows inside are separated by hairlines, so a screen
 * reads as a few clear blocks rather than one long list.
 */
@Composable
fun Section(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = SectionLabel,
                color = TextLow,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = TextLow,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            )
        }
    }
}

/** Hairline between rows in a [Section]; indented past the icon column. */
@Composable
fun RowDivider(indent: Boolean = true) {
    HorizontalDivider(
        Modifier.padding(start = if (indent) 64.dp else 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Icon in a soft tinted tile: the leading mark of every settings row. */
@Composable
fun IconTile(icon: ImageVector, tint: Color = Accent, modifier: Modifier = Modifier) {
    Box(
        modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * The single row shape used in settings-like screens. [trailing] defaults to a chevron when the row
 * navigates ([chevron] = true), nothing otherwise.
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Accent,
    value: String? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    /** Extra content under the title (a status line), in place of or after [subtitle]. */
    below: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(icon, if (enabled) iconTint else TextLow)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (!enabled) TextLow else titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (below != null) {
                Spacer(Modifier.height(3.dp))
                below()
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = TextMid, maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextLow)
        }
    }
}

@Composable
fun SwitchSetting(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Accent,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        enabled = enabled,
        onClick = { onChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White),
            )
        },
    )
}

@Composable
fun RadioOption(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Centered "nothing here" block: icon, one-line title, one line of how to fill it, optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // A soft brand-tinted halo around the icon: the app's colour, without shouting.
        Box(
            Modifier.size(84.dp).clip(CircleShape)
                .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(dev.pfeed.ui.theme.Accent.copy(alpha = 0.22f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = dev.pfeed.ui.theme.Accent.copy(alpha = 0.9f), modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = TextMid, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** A small status dot + text, for health lines (working / paused / failing). */
@Composable
fun StatusLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The one main action on a screen, in the brand gradient. A pill with optional leading icon;
 * dimmed (and not clickable) when disabled.
 */
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
    val press = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val squeeze by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.96f else 1f, label = "brandPress")
    Row(
        modifier
            .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
            .clip(shape)
            .then(if (enabled) Modifier.background(dev.pfeed.ui.theme.BrandGradient) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
            .clickable(enabled = enabled, interactionSource = press, indication = androidx.compose.material3.ripple(color = Color.White), onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 22.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (compact) 18.dp else 20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Confirmations ("Saved", "Hidden"): a compact dark pill with a hairline edge, instead of Material's
 * light bar, so they read as part of the app over any picture.
 */
@Composable
fun AppSnackbarHost(state: androidx.compose.material3.SnackbarHostState, modifier: Modifier = Modifier) {
    androidx.compose.material3.SnackbarHost(state, modifier) { data ->
        Row(
            Modifier
                .padding(8.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(Color(0xF01C1C21))
                .border(1.dp, Color.White.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                // Tap the note itself to put it away.
                .clickable { data.dismiss() }
                .padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dev.pfeed.ui.theme.BrandGradient))
            Spacer(Modifier.width(10.dp))
            Text(data.visuals.message, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 2)
            data.visuals.actionLabel?.let { label ->
                Spacer(Modifier.width(14.dp))
                Text(
                    label,
                    color = dev.pfeed.ui.theme.Accent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable { data.performAction() }.padding(4.dp),
                )
            }
        }
    }
}

/**
 * The app's loading spinner: a rounded arc in the brand gradient, turning, over a faint track.
 * Used wherever something is on its way (a video starting, the feed filling, a search).
 */
@Composable
fun BrandSpinner(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 36.dp, stroke: androidx.compose.ui.unit.Dp = 3.5.dp) {
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spinner")
    val angle by spin.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)),
        label = "spinnerAngle",
    )
    val sweep by spin.animateFloat(
        initialValue = 60f, targetValue = 260f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "spinnerSweep",
    )
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawArc(
            brush = androidx.compose.ui.graphics.Brush.sweepGradient(listOf(dev.pfeed.ui.theme.AccentWarm, dev.pfeed.ui.theme.Accent, dev.pfeed.ui.theme.AccentWarm)),
            startAngle = angle, sweepAngle = sweep, useCenter = false, topLeft = topLeft, size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/**
 * A creator's round initial in a colour of their own (the same colour everywhere for the same
 * name): the feed's caption and Library → Following use it, so a creator looks the same in both.
 */
@Composable
fun InitialAvatar(name: String, size: androidx.compose.ui.unit.Dp = 32.dp, modifier: Modifier = Modifier) {
    val clean = name.removePrefix("@").trim()
    val hue = androidx.compose.runtime.remember(clean) { ((clean.lowercase().hashCode() and 0x7fffffff) % 360).toFloat() }
    Box(
        modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.hsl(hue, 0.6f, 0.5f), Color.hsl((hue + 40f) % 360f, 0.6f, 0.4f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            clean.take(1).uppercase().ifEmpty { "?" },
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
    }
}

/** The last few releases' notes (assets/whats_new.md), as a dialog. */
@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    val assets = androidx.compose.ui.platform.LocalContext.current.assets
    val notes = androidx.compose.runtime.remember { runCatching { assets.open("whats_new.md").bufferedReader().readText() }.getOrDefault("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text("What's new") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                notes.lines().forEach { line ->
                    when {
                        line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        line.startsWith("- ") -> Text("• " + line.removePrefix("- ").replace("**", ""), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                        line.isBlank() -> {}
                        else -> Text(line.replace("**", ""), style = MaterialTheme.typography.bodySmall, color = TextMid)
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Asked for from outside Settings (the "Updated" note on the feed): shown over everything. */
object WhatsNew {
    var showing by androidx.compose.runtime.mutableStateOf(false)
}
