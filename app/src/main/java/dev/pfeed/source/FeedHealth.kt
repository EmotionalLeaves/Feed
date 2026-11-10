package dev.pfeed.source

/**
 * Remembers which feed entries (a subreddit, a board tag, a RedGIFs term…) keep failing or coming
 * back empty, so a library of thousands doesn't spend requests on dead ones every session. A dead
 * entry is skipped for [RETRY_AFTER_MS] (two days), then gets one more try.
 */
interface FeedHealth {
    fun isDead(source: String, entry: String): Boolean
    /** Failed, or its first page had nothing usable. */
    fun strike(source: String, entry: String)
    /** Returned content: forget any strikes. */
    fun ok(source: String, entry: String)

    companion object {
        const val STRIKES_TO_DEAD = 3
        const val RETRY_AFTER_MS = 2L * 24 * 60 * 60 * 1000

        val NONE = object : FeedHealth {
            override fun isDead(source: String, entry: String) = false
            override fun strike(source: String, entry: String) = Unit
            override fun ok(source: String, entry: String) = Unit
        }
    }
}

/** In-memory rules shared by the persisted store and tests. [now] is injectable for tests. */
open class FeedHealthMemory(private val now: () -> Long = System::currentTimeMillis) : FeedHealth {
    protected data class Record(val strikes: Int, val lastAt: Long)
    protected val records = java.util.concurrent.ConcurrentHashMap<String, Record>()

    protected fun key(source: String, entry: String) = "$source|$entry"

    override fun isDead(source: String, entry: String): Boolean {
        val r = records[key(source, entry)] ?: return false
        return r.strikes >= FeedHealth.STRIKES_TO_DEAD && now() - r.lastAt < FeedHealth.RETRY_AFTER_MS
    }

    override fun strike(source: String, entry: String) {
        val k = key(source, entry)
        val prev = records[k]
        // After the retry window a dead entry gets exactly one more chance: one strike re-kills it.
        val base = if (prev != null && prev.strikes >= FeedHealth.STRIKES_TO_DEAD) FeedHealth.STRIKES_TO_DEAD - 1 else prev?.strikes ?: 0
        val next = Record(base + 1, now())
        records[k] = next
        persist(k, next)
    }

    override fun ok(source: String, entry: String) {
        val k = key(source, entry)
        if (records.remove(k) != null) persist(k, null)
    }

    protected open fun persist(key: String, record: Record?) = Unit
}
