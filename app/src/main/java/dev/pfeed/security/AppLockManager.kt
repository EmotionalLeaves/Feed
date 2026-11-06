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
 * The app lock is optional and off until the user sets a PIN in Settings. With a PIN, the app is
 * locked on every cold launch and again whenever it returns from the background after
 * `lockTimeoutSec` (default 30 s); [lockNow] is used by the panic gesture. Without one, it never locks.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private val _locked = MutableStateFlow(secrets.hasPin)
    val locked: StateFlow<Boolean> = _locked

    private val _enabled = MutableStateFlow(secrets.hasPin)
    /** True when a PIN is set (the lock is on). */
    val enabled: StateFlow<Boolean> = _enabled

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
        if (secrets.hasPin && backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt >= timeoutMs) _locked.value = true
        backgroundedAt = 0L
    }

    /** Locks if a PIN is set; with the lock off there's nothing to unlock with, so it does nothing. */
    fun lockNow() {
        if (secrets.hasPin) _locked.value = true
    }

    /** Called after a successful biometric prompt. */
    fun unlockWithBiometric() {
        failures = 0
        _locked.value = false
    }

    suspend fun createPin(pin: CharArray) {
        withContext(Dispatchers.Default) { secrets.setPin(pin) }
        pin.fill('0')
        _enabled.value = true
        _locked.value = false
    }

    /** Turns the lock off after checking the current PIN. */
    suspend fun disable(current: CharArray): Boolean {
        val ok = withContext(Dispatchers.Default) { secrets.verifyPin(current) }
        current.fill('0')
        if (!ok) return false
        withContext(Dispatchers.Default) { secrets.clearPin() }
        _enabled.value = false
        _locked.value = false
        return true
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
