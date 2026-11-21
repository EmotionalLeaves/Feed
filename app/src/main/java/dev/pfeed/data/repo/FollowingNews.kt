package dev.pfeed.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How many new posts wait in Following: counted in the background (on Wi-Fi, while the app is
 * closed) as the posts there newer than your last visit, shown as a number on the Following tab,
 * cleared when you open it. Kept in the same place as the time of that last visit.
 */
object FollowingNews {
    const val PREFS = "following"
    const val SEEN_AT = "seen_at"
    private const val NEW = "new_count"
    private const val MAX = 99

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Read when the app starts and comes back to the front. */
    fun load(context: Context) { _count.value = prefs(context).getInt(NEW, 0) }

    /** The time of your last visit to Following (0: never). */
    fun seenAt(context: Context): Long = prefs(context).getLong(SEEN_AT, 0L)

    /** [publishedMs] of what Following lists right now: those after your last visit are new. */
    fun found(context: Context, publishedMs: List<Long>) {
        val since = seenAt(context).takeIf { it > 0 } ?: return
        val n = publishedMs.count { it > since }.coerceAtMost(MAX)
        prefs(context).edit().putInt(NEW, n).apply()
        _count.value = n
    }

    /** Following opened: nothing waiting any more. */
    fun opened(context: Context) {
        if (_count.value == 0 && prefs(context).getInt(NEW, 0) == 0) return
        prefs(context).edit().putInt(NEW, 0).apply()
        _count.value = 0
    }
}
