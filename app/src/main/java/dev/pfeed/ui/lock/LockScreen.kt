package dev.pfeed.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.pfeed.security.AppLockManager
import dev.pfeed.ui.common.findActivity
import kotlinx.coroutines.launch

private enum class Stage { ENTER, CREATE, CONFIRM }

@Composable
fun LockScreen(lock: AppLockManager) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? FragmentActivity }
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(if (lock.hasPin) Stage.ENTER else Stage.CREATE) }
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val biometricAvailable = remember(activity) {
        activity != null && BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometric() {
        val a = activity ?: return
        val prompt = BiometricPrompt(
            a,
            ContextCompat.getMainExecutor(a),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lock.unlockWithBiometric()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock")
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .build(),
        )
    }

    LaunchedEffect(Unit) {
        if (stage == Stage.ENTER && biometricAvailable) showBiometric()
    }

    fun submit() {
        if (busy) return
        when (stage) {
            Stage.CREATE -> {
                if (pin.length < MIN_PIN) { message = "Use at least $MIN_PIN digits"; return }
                firstPin = pin; pin = ""; stage = Stage.CONFIRM; message = null
            }
            Stage.CONFIRM -> {
                if (pin != firstPin) {
                    message = "PINs didn't match. Start again."
                    pin = ""; firstPin = ""; stage = Stage.CREATE
                    return
                }
                busy = true
                val chars = pin.toCharArray()
                pin = ""; firstPin = ""
                scope.launch { lock.createPin(chars); busy = false }
            }
            Stage.ENTER -> {
                busy = true
                val chars = pin.toCharArray()
                pin = ""
                scope.launch {
                    message = when (val r = lock.unlockWithPin(chars)) {
                        AppLockManager.PinResult.Ok -> null
                        is AppLockManager.PinResult.Wrong -> "Wrong PIN (${r.attemptsLeft} left)"
                        is AppLockManager.PinResult.LockedOut -> "Too many attempts. Wait ${r.seconds} s."
                    }
                    busy = false
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow every touch so nothing underneath reacts.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                when (stage) {
                    Stage.ENTER -> "Enter PIN"
                    Stage.CREATE -> "Create a PIN"
                    Stage.CONFIRM -> "Confirm PIN"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(maxOf(MIN_PIN, pin.length)) { i ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .border(1.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                            .background(if (i < pin.length) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.background, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(message ?: " ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("bio", "0", "del"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    row.forEach { key ->
                        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                            when (key) {
                                "bio" -> if (stage == Stage.ENTER && biometricAvailable) {
                                    IconButton(onClick = ::showBiometric) { Icon(Icons.Filled.Fingerprint, "Use biometrics") }
                                }
                                "del" -> IconButton(onClick = { pin = pin.dropLast(1) }) {
                                    Icon(Icons.AutoMirrored.Filled.Backspace, "Delete")
                                }
                                else -> TextButton(
                                    onClick = { if (pin.length < MAX_PIN) pin += key },
                                    modifier = Modifier.size(72.dp),
                                    shape = CircleShape,
                                ) { Text(key, fontSize = 28.sp, fontWeight = FontWeight.Light) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = ::submit, enabled = pin.isNotEmpty() && !busy) {
                Text(if (stage == Stage.ENTER) "Unlock" else "OK", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private const val MIN_PIN = 4
private const val MAX_PIN = 8
