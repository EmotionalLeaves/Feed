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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
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
        withContext(Dispatchers.Default) { secrets.clearPin(); secrets.setErasePin(null) }
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
        // The emergency PIN: everything this app stored is erased and it closes, as if never used.
        // Checked first, and it looks like any other PIN to whoever is watching.
        if (secrets.hasErasePin && withContext(Dispatchers.Default) { secrets.verifyErasePin(pin) }) {
            pin.fill('0')
            (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData()
            return PinResult.Wrong(MAX_ATTEMPTS)
        }
        val ok = withContext(Dispatchers.Default) { secrets.verifyPin(pin) }
        pin.fill('0')
        if (ok) {
            // Wrong PINs since the last time you got in: tell you now (when, how many).
            val tries = attemptsPrefs.getString(KEY_TRIES, null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
            if (tries.isNotEmpty()) {
                _intrusions.value = tries
                attemptsPrefs.edit().remove(KEY_TRIES).apply()
            }
            failures = 0
            _locked.value = false
            return PinResult.Ok
        }
        failures++
        val before = attemptsPrefs.getString(KEY_TRIES, null).orEmpty()
        attemptsPrefs.edit().putString(KEY_TRIES, (before.split(',').filter { it.isNotBlank() }.takeLast(MAX_LOGGED - 1) + System.currentTimeMillis().toString()).joinToString(",")).apply()
        if (failures >= MAX_ATTEMPTS) {
            failures = 0
            lockoutUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS
            return PinResult.LockedOut(LOCKOUT_MS / 1000)
        }
        return PinResult.Wrong(MAX_ATTEMPTS - failures)
    }

    private val attemptsPrefs = context.getSharedPreferences("lock_attempts", android.content.Context.MODE_PRIVATE)
    private val _intrusions = MutableStateFlow<List<Long>>(emptyList())
    /** Times of wrong PINs entered since you last unlocked, shown once after you get in. */
    val intrusions: StateFlow<List<Long>> = _intrusions
    fun intrusionsSeen() { _intrusions.value = emptyList() }

    val hasErasePin: Boolean get() = secrets.hasErasePin

    /** Sets (or with null, removes) the emergency erase PIN. It can't be the same as the PIN. */
    suspend fun setErasePin(pin: CharArray?): Boolean = withContext(Dispatchers.Default) {
        if (pin != null && secrets.verifyPin(pin)) { pin.fill('0'); return@withContext false }
        secrets.setErasePin(pin)
        pin?.fill('0')
        true
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
        private const val MAX_LOGGED = 50
        private const val KEY_TRIES = "tries"
        private const val LOCKOUT_MS = 30_000L
    }
}
