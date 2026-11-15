package dev.pfeed.player

import android.content.Context
import android.content.SharedPreferences

/**
 * Where you left long videos, so a full scene opened again from History or Saved carries on from
 * there instead of restarting. Only videos of 3+ minutes, only past the first 30 s and not in the
 * last 30 s; the oldest are dropped past [MAX].
 */
object ResumePoints {
    private const val MAX = 300
    private const val MIN_DURATION_MS = 3 * 60_000L
    private const val MARGIN_MS = 30_000L
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("resume_points", Context.MODE_PRIVATE)
    }

    fun get(key: String): Long? = prefs?.getLong(key, -1L)?.takeIf { it > 0 }

    fun save(key: String, positionMs: Long, durationMs: Long) {
        val p = prefs ?: return
        if (durationMs < MIN_DURATION_MS || positionMs < MARGIN_MS || positionMs > durationMs - MARGIN_MS) {
            if (p.contains(key)) p.edit().remove(key).apply()
            return
        }
        val edit = p.edit().putLong(key, positionMs)
        if (p.all.size >= MAX) p.all.keys.take(p.all.size - MAX + 1).forEach { edit.remove(it) }
        edit.apply()
    }

    fun clear() { prefs?.edit()?.clear()?.apply() }
}
