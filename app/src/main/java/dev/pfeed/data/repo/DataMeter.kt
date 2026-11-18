package dev.pfeed.data.repo

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import java.time.YearMonth

/**
 * How much the app has downloaded: this session and this calendar month. Read from Android's own
 * per-app traffic counter (everything: videos, pictures, pages), so it matches the system's data
 * usage screen. The month's total is kept on the phone and starts over on the 1st.
 */
object DataMeter {
    private const val PREFS = "data_meter"
    private var baseline = -1L
    private var counted = 0L
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        baseline = rx()
        counted = baseline
    }

    private fun rx(): Long = TrafficStats.getUidRxBytes(Process.myUid()).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: -1L

    /** Bytes since the app started (0 if Android doesn't report it). */
    fun session(): Long {
        val now = rx()
        return if (now < 0 || baseline < 0) 0L else (now - baseline).coerceAtLeast(0)
    }

    /** Adds what's new since the last time to this month's total, and returns that total. */
    @Synchronized
    fun month(): Long {
        val p = prefs ?: return session()
        val key = YearMonth.now().toString()
        if (p.getString("month", null) != key) p.edit().putString("month", key).putLong("bytes", 0L).apply()
        val now = rx()
        var total = p.getLong("bytes", 0L)
        if (now >= 0 && counted >= 0 && now >= counted) {
            total += now - counted
            p.edit().putLong("bytes", total).apply()
        }
        counted = now
        return total
    }

    fun format(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "${bytes shr 20} MB"
        else -> "${bytes shr 10} KB"
    }
}
