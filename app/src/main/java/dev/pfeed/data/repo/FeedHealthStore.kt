package dev.pfeed.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pfeed.source.FeedHealthMemory
import javax.inject.Inject
import javax.inject.Singleton

/** [FeedHealthMemory] persisted across launches (one small preferences file: "strikes:lastAt"). */
@Singleton
class FeedHealthStore @Inject constructor(@ApplicationContext context: Context) : FeedHealthMemory() {
    private val prefs = context.getSharedPreferences("feed_health", Context.MODE_PRIVATE)

    init {
        prefs.all.forEach { (k, v) ->
            val parts = (v as? String)?.split(':') ?: return@forEach
            val strikes = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return@forEach
            records[k] = Record(strikes, at)
        }
    }

    override fun persist(key: String, record: Record?) {
        prefs.edit().apply { if (record == null) remove(key) else putString(key, "${record.strikes}:${record.lastAt}") }.apply()
    }

    /** How many entries of [source] are currently being skipped. */
    fun deadCount(source: String): Int = records.keys.count { it.startsWith("$source|") && isDead(source, it.substringAfter('|')) }

    fun clear() {
        records.clear()
        prefs.edit().clear().apply()
    }
}
