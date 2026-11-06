package dev.pfeed.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Locked on every cold launch, and again whenever the app returns from the background after
 * `lockTimeoutSec` (default 30 s). [lockNow] is used by the panic gesture.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked

    @Volatile private var timeoutMs = 30_000L
    private var backgroundedAt = 0L
    private var failures = 0
    private var lockoutUntil = 0L

    val hasPin: Boolean get() = secrets.hasPin

    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch { settings.flow.collect { timeoutMs = it.lockTimeoutSec * 1000L } }
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt >= timeoutMs) _locked.value = true
        backgroundedAt = 0L
    }

    fun lockNow() {
        _locked.value = true
    }

    /** Called after a successful biometric prompt. */
    fun unlockWithBiometric() {
        failures = 0
        _locked.value = false
    }

    suspend fun createPin(pin: CharArray) {
        withContext(Dispatchers.Default) { secrets.setPin(pin) }
        pin.fill('0')
        _locked.value = false
    }

    sealed interface PinResult {
        data object Ok : PinResult
        data class Wrong(val attemptsLeft: Int) : PinResult
        data class LockedOut(val seconds: Long) : PinResult
    }

    suspend fun unlockWithPin(pin: CharArray): PinResult {
        val now = SystemClock.elapsedRealtime()
        if (now < lockoutUntil) return PinResult.LockedOut((lockoutUntil - now) / 1000 + 1)
        val ok = withContext(Dispatchers.Default) { secrets.verifyPin(pin) }
        pin.fill('0')
        if (ok) {
            failures = 0
            _locked.value = false
            return PinResult.Ok
        }
        failures++
        if (failures >= MAX_ATTEMPTS) {
            failures = 0
            lockoutUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS
            return PinResult.LockedOut(LOCKOUT_MS / 1000)
        }
        return PinResult.Wrong(MAX_ATTEMPTS - failures)
    }

    suspend fun changePin(current: CharArray, new: CharArray): Boolean {
        val ok = withContext(Dispatchers.Default) { secrets.verifyPin(current) }
        current.fill('0')
        if (ok) withContext(Dispatchers.Default) { secrets.setPin(new) }
        new.fill('0')
        return ok
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_MS = 30_000L
    }
}
