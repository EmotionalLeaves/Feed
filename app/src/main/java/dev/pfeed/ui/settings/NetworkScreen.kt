package dev.pfeed.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.pfeed.ui.common.RowDivider
import dev.pfeed.ui.common.ScreenHeader
import dev.pfeed.ui.common.Section
import dev.pfeed.ui.common.SettingRow
import dev.pfeed.ui.common.SwitchSetting
import dev.pfeed.ui.theme.Good
import dev.pfeed.ui.theme.TextLow

/**
 * Sites that block where you are (US state age-verification laws, country blocks), and the proxy
 * that gets around them: Orbot's Tor on the phone, or an HTTP/SOCKS5 proxy of your own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(nav: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val blocked by dev.pfeed.source.net.GeoBlock.sites.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(
            "Blocked where you are",
            subtitle = "Some sites shut out whole states or countries. A proxy gets their posts back.",
            onBack = { nav.popBackStack() },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(
                "Blocked right now",
                footer = "Found from each site's own answer. Checked again every few days, and cleared as soon as a site answers normally.",
            ) {
                if (blocked.isEmpty()) {
                    SettingRow("Nothing is blocked where you are", icon = Icons.Filled.CheckCircle, iconTint = Good)
                } else {
                    blocked.sorted().forEachIndexed { i, site ->
                        if (i > 0) RowDivider()
                        SettingRow(
                            siteLabel(site),
                            subtitle = if (dev.pfeed.source.net.AppProxy.config.active) "Going through your proxy" else "Its posts are missing until a proxy or VPN is on",
                            icon = Icons.Filled.Block,
                            iconTint = TextLow,
                        )
                    }
                }
            }

            Section(
                "Proxy",
                footer = "Orbot is a free Tor app from the Play Store: install it, start it, then pick Orbot here. " +
                    "Or use a VPN app, which covers every app on the phone. Your proxy's password stays on this phone and isn't put in backups.",
            ) {
                val options = listOf("off" to "Off", "orbot" to "Orbot", "http" to "HTTP", "socks" to "SOCKS5")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = s.proxyType == value,
                            onClick = { vm.setProxy(type = value) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            icon = {},
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
                if (s.proxyType == "http" || s.proxyType == "socks") {
                    var host by remember(s.proxyType) { mutableStateOf(s.proxyHost) }
                    var port by remember(s.proxyType) { mutableStateOf(s.proxyPort.takeIf { it > 0 }?.toString().orEmpty()) }
                    var user by remember(s.proxyType) { mutableStateOf(s.proxyUser) }
                    var pass by remember(s.proxyType) { mutableStateOf(s.proxyPassword) }
                    val changed = host != s.proxyHost || port != (s.proxyPort.takeIf { it > 0 }?.toString().orEmpty()) || user != s.proxyUser || pass != s.proxyPassword
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(host, { host = it.trim() }, label = { Text("Address") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(0.45f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(user, { user = it }, label = { Text("Username (optional)") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (s.proxyHost.isNotBlank() && s.proxyPort > 0) "Using ${s.proxyHost}:${s.proxyPort}" else "Not set yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextLow,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = changed && host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535,
                                onClick = { vm.setProxy(host = host, port = port.toIntOrNull() ?: 0, user = user, password = pass) },
                            ) { Text("Save") }
                        }
                    }
                }
                if (s.proxyType != "off") {
                    RowDivider()
                    SwitchSetting(
                        "Everything through the proxy",
                        s.proxyEverything,
                        subtitle = if (s.proxyEverything) "Every site goes through it (slower)" else "Only sites blocked where you are; everything else stays direct",
                        icon = Icons.Filled.VpnLock,
                        onChange = { vm.setProxy(everything = it) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

fun siteLabel(site: String): String = when (site) {
    "redgifs.com" -> "RedGIFs"
    "pornhub.com" -> "Pornhub"
    "youporn.com" -> "YouPorn"
    "redtube.com" -> "RedTube"
    "tube8.com" -> "Tube8"
    "xvideos.com" -> "XVideos"
    "xnxx.com" -> "XNXX"
    "xhamster.com" -> "xHamster"
    "reddit.com" -> "Reddit"
    else -> site
}
