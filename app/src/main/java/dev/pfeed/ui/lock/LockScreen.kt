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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.width
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
    // A wrong PIN shakes the dots (and buzzes), like a lock screen should.
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    var shakes by remember { mutableStateOf(0) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(shakes) {
        if (shakes == 0) return@LaunchedEffect
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) shake.animateTo(x, androidx.compose.animation.core.tween(45))
    }
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

    // Disguised as a calculator (Settings → Privacy): type the PIN and press =.
    val asCalculator by LockLook.calculator.collectAsState()
    if (asCalculator && lock.hasPin) {
        CalculatorLock(lock, onBiometric = { if (biometricAvailable) showBiometric() })
        return
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
                    message = "PINs didn't match. Start again."; shakes++
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
                    val r = lock.unlockWithPin(chars)
                    message = when (r) {
                        AppLockManager.PinResult.Ok -> null
                        is AppLockManager.PinResult.Wrong -> "Wrong PIN (${r.attemptsLeft} left)"
                        is AppLockManager.PinResult.LockedOut -> "Too many attempts. Wait ${r.seconds} s."
                    }
                    if (r != AppLockManager.PinResult.Ok) shakes++
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
        // Keys sized to the screen: roomy on a tall phone, still all on screen (with breathing room
        // above the lock) on a short one.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val key = ((maxHeight - 300.dp) / 4 - 14.dp).coerceIn(52.dp, 76.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).background(dev.pfeed.ui.theme.AccentSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Lock, contentDescription = null, tint = dev.pfeed.ui.theme.Accent, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.height(20.dp))
            Text(
                when (stage) {
                    Stage.ENTER -> "Enter PIN"
                    Stage.CREATE -> "Create a PIN"
                    Stage.CONFIRM -> "Confirm PIN"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.graphicsLayer { translationX = shake.value * density }) {
                repeat(maxOf(MIN_PIN, pin.length)) { i ->
                    // Each dot swells a little as it fills.
                    val filled = i < pin.length
                    val dot by androidx.compose.animation.core.animateFloatAsState(
                        if (filled) 1f else 0.85f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 800f), label = "pinDot",
                    )
                    Box(
                        Modifier
                            .graphicsLayer { scaleX = dot; scaleY = dot }
                            .size(14.dp)
                            .border(1.5.dp, if (i < pin.length) dev.pfeed.ui.theme.Accent else dev.pfeed.ui.theme.TextLow, CircleShape)
                            .background(if (i < pin.length) dev.pfeed.ui.theme.Accent else Color.Transparent, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(message ?: " ", color = dev.pfeed.ui.theme.Bad, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("bio", "0", "del"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(key / 3), modifier = Modifier.padding(vertical = 7.dp)) {
                    row.forEach { k ->
                        Box(Modifier.size(key), contentAlignment = Alignment.Center) {
                            when (k) {
                                "bio" -> if (stage == Stage.ENTER && biometricAvailable) {
                                    IconButton(onClick = ::showBiometric) { Icon(Icons.Filled.Fingerprint, "Use biometrics") }
                                }
                                "del" -> IconButton(onClick = { pin = pin.dropLast(1) }) {
                                    Icon(Icons.AutoMirrored.Filled.Backspace, "Delete")
                                }
                                else -> TextButton(
                                    onClick = {
                                        if (pin.length < MAX_PIN) {
                                            pin += k
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    modifier = Modifier.size(key).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                    shape = CircleShape,
                                ) { Text(k, fontSize = (key.value * 0.38f).sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onBackground) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            dev.pfeed.ui.common.BrandButton(
                if (stage == Stage.ENTER) "Unlock" else "Continue",
                onClick = ::submit,
                enabled = pin.length >= MIN_PIN && !busy,
                modifier = Modifier.width(220.dp),
            )
        }
        }
    }
}

private const val MIN_PIN = 4
private const val MAX_PIN = 8
