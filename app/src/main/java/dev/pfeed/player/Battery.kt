package dev.pfeed.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Battery saver for the app: when the battery is low (15% or under, not charging) and the setting
 * is on, videos drop to 480p and grids stop playing previews, until it's charging or back up.
 */
object Battery {
    private val _low = MutableStateFlow(false)
    /** Low and not charging. */
    val low: StateFlow<Boolean> = _low
    @Volatile var saverOn = true

    /** Whether the app is saving battery right now. */
    val saving: Boolean get() = saverOn && _low.value

    fun init(context: Context, onChange: () -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                val low = level in 0..(scale * LOW_PERCENT / 100) && !charging
                if (low != _low.value) { _low.value = low; onChange() }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { receiver.onReceive(context, it) }
    }

    private const val LOW_PERCENT = 15
}
