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
        if (dev.pfeed.domain.privacy.Incognito.active) return
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

/**
 * The page you were on in each comic, so one opened again (from Saved, History or the feed) opens
 * there. Only from page 2 on and before the last page (a finished comic starts over); oldest dropped past [MAX].
 */
object ComicPages {
    private const val MAX = 300
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("comic_pages", Context.MODE_PRIVATE)
    }

    /** The saved page index (0-based), or null. */
    fun get(key: String): Int? = prefs?.getInt(key, -1)?.takeIf { it > 0 }

    fun save(key: String, page: Int, pageCount: Int) {
        if (dev.pfeed.domain.privacy.Incognito.active) return
        val p = prefs ?: return
        if (page <= 0 || page >= pageCount - 1) {
            if (p.contains(key)) p.edit().remove(key).apply()
            return
        }
        val edit = p.edit().putInt(key, page)
        if (p.all.size >= MAX) p.all.keys.take(p.all.size - MAX + 1).forEach { edit.remove(it) }
        edit.apply()
    }

    fun clear() { prefs?.edit()?.clear()?.apply() }
}

/**
 * Moments you marked in videos ("Save this moment"): where they are, per video, kept on the phone.
 * They show on the scrub bar, the video offers to jump to them, and Saved → Moments plays each video
 * from its first one. A few per video; oldest videos dropped past [MAX].
 */
object Moments {
    private const val MAX = 400
    private const val PER_VIDEO = 8
    /** Marks this close together are one moment. */
    private const val SAME_MS = 5_000L
    @Volatile private var prefs: SharedPreferences? = null
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Bumps whenever a moment is added or removed, for screens showing them. */
    val changes: kotlinx.coroutines.flow.StateFlow<Int> = _changes

    fun init(context: Context) {
        prefs = context.getSharedPreferences("moments", Context.MODE_PRIVATE)
    }

    fun of(key: String): List<Long> =
        prefs?.getString(key, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.sorted().orEmpty()

    /** Every video that has a moment. */
    fun keys(): Set<String> = prefs?.all?.keys.orEmpty()

    fun add(key: String, atMs: Long) {
        val p = prefs ?: return
        val now = of(key)
        if (now.any { kotlin.math.abs(it - atMs) < SAME_MS }) return
        val next = (now + atMs.coerceAtLeast(0L)).sorted().takeLast(PER_VIDEO)
        val edit = p.edit().putString(key, next.joinToString(","))
        if (!p.contains(key) && p.all.size >= MAX) p.all.keys.take(p.all.size - MAX + 1).forEach { edit.remove(it) }
        edit.apply()
        _changes.value++
    }

    fun remove(key: String, atMs: Long) {
        val p = prefs ?: return
        val next = of(key).filter { it != atMs }
        if (next.isEmpty()) p.edit().remove(key).apply() else p.edit().putString(key, next.joinToString(",")).apply()
        _changes.value++
    }

    fun clear() { prefs?.edit()?.clear()?.apply(); _changes.value++ }
}

/**
 * How each named act has gone for you ("missionary", "cowgirl"…): +1 each time you sat through it,
 * -1 each time you jumped over it, fading a little with every new verdict. Kept on the phone; feeds
 * "your favourite" act alongside what For You makes of the act's name.
 */
object ActPrefs {
    private const val MAX = 200
    private const val FADE = 0.97f
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("act_prefs", Context.MODE_PRIVATE)
    }

    private fun keyOf(act: String) = act.trim().lowercase()

    fun score(act: String): Double = prefs?.getFloat(keyOf(act), 0f)?.toDouble() ?: 0.0

    /** The acts that have gone best for you, best first (only ones clearly liked). */
    fun top(n: Int): List<String> =
        prefs?.all?.entries?.mapNotNull { (k, v) -> (v as? Float)?.takeIf { it >= TOP_MIN }?.let { k to it } }
            ?.sortedByDescending { it.second }?.take(n)?.map { it.first }.orEmpty()
    private const val TOP_MIN = 1.5f

    fun record(liked: List<String>, skipped: List<String>) {
        if (dev.pfeed.domain.privacy.Incognito.active) return
        val p = prefs ?: return
        val edit = p.edit()
        // Older verdicts count a little less each time something new is learned.
        p.all.forEach { (k, v) -> (v as? Float)?.let { edit.putFloat(k, it * FADE) } }
        liked.forEach { a -> edit.putFloat(keyOf(a), (p.getFloat(keyOf(a), 0f) * FADE) + 1f) }
        skipped.forEach { a -> edit.putFloat(keyOf(a), (p.getFloat(keyOf(a), 0f) * FADE) - 1f) }
        if (p.all.size > MAX) p.all.entries.sortedBy { kotlin.math.abs((it.value as? Float) ?: 0f) }.take(p.all.size - MAX).forEach { edit.remove(it.key) }
        edit.apply()
    }

    fun clear() { prefs?.edit()?.clear()?.apply() }
}
