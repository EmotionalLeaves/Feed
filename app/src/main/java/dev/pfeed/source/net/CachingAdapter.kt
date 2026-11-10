package dev.pfeed.source.net

import dev.pfeed.data.repo.SeenItems
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.FeedHealth
import dev.pfeed.source.SeenFilter
import dev.pfeed.source.SourceAdapter
import dev.pfeed.source.SourceException
import dev.pfeed.source.pageAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Base for adapters that read a user-configured list of entries (subreddits, board+tag pairs,
 * Mastodon tags…) and turn them into one endless stream.
 *
 * How it stays endless:
 *  - Each entry is paged *remotely* (Reddit `after`, booru page number, Mastodon `max_id`) via
 *    [fetchEntryPage], which returns the token for that entry's next page.
 *  - Entries are visited in a shuffled round-robin, a batch at a time ([entriesPerExtend]), so a
 *    huge list (130 subreddits) is never hit all at once, and every entry gets its turn.
 *  - When the consumer reads past what's loaded, the next batch goes one page deeper.
 *  - When every entry has run dry, the adapter starts them over (subclasses pick a different sort
 *    or start page on a fresh start), keeping only items that are new.
 *
 * How it stays new:
 *  - Items the user has already swiped past (any session, see [SeenItems]) are skipped.
 *  - After [cacheMs], a brand-new feed session starts a fresh generation from the top of every entry.
 *
 * Cursor format is `"<generation>:<offset>"`; a cursor from an old generation restarts at 0.
 */
abstract class CachingAdapter : SourceAdapter {

    protected data class EntryPage(val items: List<MediaItem>, val next: String?)

    private val mutex = Mutex()

    // ── generation state (guarded by mutex) ──
    private var generation = 0
    private var generationAt = 0L
    private var signature: List<String> = emptyList()
    private val pool = ArrayList<MediaItem>()
    private val poolKeys = HashSet<String>()
    private var order: List<String> = emptyList()
    private var rr = 0
    private val tokens = HashMap<String, String>()
    private val done = HashSet<String>()
    private val failures = HashMap<String, Int>()
    private var recycles = 0
    private var lastError: Throwable? = null
    /** Read-only copy of [pool] for search, so a search never waits behind a slow refill. */
    @Volatile private var poolSnapshot: List<MediaItem> = emptyList()

    /** Wired by Hilt; tests can set it directly. */
    var seen: SeenFilter = SeenFilter.NONE

    @Inject
    fun injectSeen(items: SeenItems) {
        seen = items
    }

    /** Which entries keep failing or coming back empty (persisted); wired by Hilt, tests set it. */
    var health: FeedHealth = FeedHealth.NONE

    @Inject
    fun injectHealth(store: dev.pfeed.data.repo.FeedHealthStore) {
        health = store
    }

    /** How long a generation lives before a new feed session starts again from the top. */
    protected open val cacheMs = 20 * 60_000L
    protected open val pageSize = 20
    /** Max concurrent fetches per extend. Override to tighten for rate-limited APIs. */
    protected open val maxConcurrency = 8
    /** How many entries go one page deeper each time the stream needs more. */
    protected open val entriesPerExtend = 16
    /** Budget for one entry's request (including OkHttp's 429 back-off). */
    protected open val entryTimeoutMs = 12_000L

    /** The configured entries; also used as the cache key. */
    protected abstract suspend fun entries(): List<String>

    /** Fetch one page of one entry. Default: a single, unpaged fetch via [fetchEntry]. */
    protected open suspend fun fetchEntryPage(entry: String, token: String?): EntryPage =
        EntryPage(fetchEntry(entry), next = null)

    /** Simple adapters with no remote paging implement this instead of [fetchEntryPage]. */
    protected open suspend fun fetchEntry(entry: String): List<MediaItem> = emptyList()

    override suspend fun fetchFeed(cursor: String?, prefs: FeedPrefs): Page<MediaItem> = mutex.withLock {
        val sig = entries()
        val now = System.currentTimeMillis()
        val stale = now - generationAt > cacheMs
        if (sig != signature || (cursor == null && stale)) resetGeneration(sig, now)
        if (sig.isEmpty()) return Page(emptyList(), null)

        var offset = parseCursor(cursor)
        val out = ArrayList<MediaItem>(pageSize)
        var rounds = 0
        while (true) {
            while (offset < pool.size && out.size < pageSize) {
                val item = pool[offset++]
                if (seen.isSeen(item.key)) continue
                if (prefs.tag != null && item.tags.none { it.equals(prefs.tag, ignoreCase = true) }) continue
                out += item
            }
            if (out.size >= pageSize || rounds >= MAX_EXTEND_ROUNDS) break
            val added = extend()
            rounds++
            if (added == 0 && exhausted()) break
        }
        val next = if (offset >= pool.size && exhausted()) null else "$generation:$offset"
        Page(out, next)
    }

    override suspend fun resolveStream(item: MediaItem): StreamInfo = when (item.type) {
        MediaType.EMBED -> StreamInfo.Embed(item.extras["player"] ?: item.pageUrl)
        else -> StreamInfo.Direct(
            url = item.extras["media"] ?: throw SourceException(id, "Item has no media URL"),
            mimeType = item.extras["mime"],
        )
    }

    override fun reportUrl(item: MediaItem): String? = item.pageUrl.takeIf { it.startsWith("http") }

    /** Filters what's loaded so far (loading a first batch if needed); adapters with a real search API override this. */
    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val q = query.trim().lowercase()
        val snapshot = mutex.withLock {
            val sig = entries()
            if (sig != signature) resetGeneration(sig, System.currentTimeMillis())
            if (pool.isEmpty() && sig.isNotEmpty()) extend()
            pool.toList()
        }
        return snapshot.filter { item ->
            item.title?.lowercase()?.contains(q) == true ||
                item.creator?.lowercase()?.contains(q) == true ||
                item.tags.any { it.contains(q) }
        }.pageAt(cursor, pageSize)
    }

    private val searchMutex = Mutex()
    private var lastSearch: Triple<String, Long, List<MediaItem>>? = null

    /**
     * Runs [block] once per query and pages the result in memory, so asking for page 2, 3… of the
     * same search doesn't hit every server again. Cached for [SEARCH_CACHE_MS].
     */
    protected suspend fun cachedSearch(query: String, cursor: String?, block: suspend () -> List<MediaItem>): Page<MediaItem> {
        val q = query.trim().lowercase()
        val now = System.currentTimeMillis()
        val hit = searchMutex.withLock { lastSearch?.takeIf { it.first == q && now - it.second < SEARCH_CACHE_MS }?.third }
        val all = if (hit != null && cursor != null) hit else block().also { r -> searchMutex.withLock { lastSearch = Triple(q, now, r) } }
        return all.pageAt(cursor, pageSize)
    }

    /** Items already loaded into the stream that match [query] (title, creator or tag). No network. */
    protected fun poolMatches(query: String): List<MediaItem> {
        val q = query.trim().lowercase().removePrefix("#")
        if (q.isEmpty()) return emptyList()
        val qTag = q.replace(Regex("\\s+"), "_")
        return poolSnapshot.filter { item ->
            item.title?.lowercase()?.contains(q) == true ||
                item.creator?.lowercase()?.contains(q) == true ||
                item.tags.any { val t = it.lowercase(); t.contains(q) || t.contains(qTag) }
        }.take(POOL_SEARCH_LIMIT)
    }

    // ── internals (all called with mutex held) ──

    private fun resetGeneration(sig: List<String>, now: Long) {
        generation++
        generationAt = now
        signature = sig
        pool.clear(); poolKeys.clear()
        poolSnapshot = emptyList()
        // Skip entries that keep failing or returning nothing (they get another try after two days).
        // If somehow everything is marked dead, try them all rather than show nothing.
        val distinct = sig.distinct()
        order = distinct.filterNot { health.isDead(id, it) }.ifEmpty { distinct }.shuffled()
        rr = 0
        tokens.clear(); done.clear(); failures.clear()
        recycles = 0
        lastError = null
    }

    private fun parseCursor(cursor: String?): Int {
        if (cursor == null) return 0
        val gen = cursor.substringBefore(':', "").toIntOrNull()
        val off = cursor.substringAfter(':', "").toIntOrNull()
        return if (gen == generation && off != null) off.coerceIn(0, pool.size) else 0
    }

    private fun exhausted(): Boolean = order.all { it in done } && recycles >= MAX_RECYCLES

    /** Fetch the next page for the next batch of entries. Returns how many new items were added. */
    private suspend fun extend(): Int {
        if (order.isEmpty()) return 0
        if (order.all { it in done }) {
            if (recycles >= MAX_RECYCLES) return 0
            // Everything ran dry: start every entry over. Subclasses vary sort/start page on a null
            // token, and only unseen, not-yet-pooled items are kept, so this surfaces new material.
            recycles++
            tokens.clear(); done.clear(); failures.clear()
        }
        val batch = nextBatch()
        val sem = Semaphore(maxConcurrency)
        val results = coroutineScope {
            batch.map { entry ->
                async(Dispatchers.IO) {
                    entry to sem.withPermit {
                        try {
                            Result.success<EntryPage>(withTimeout(entryTimeoutMs) { fetchEntryPage(entry, tokens[entry]) })
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            Result.failure<EntryPage>(SourceException(id, "Timed out", cause = e))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure<EntryPage>(e)
                        }
                    }
                }
            }.awaitAll()
        }

        val fresh = ArrayList<List<MediaItem>>(results.size)
        var anyOk = false
        var anyContent = false
        val strikes = ArrayList<String>()
        for ((entry, result) in results) {
            val firstPage = entry !in tokens
            result.onSuccess { page ->
                anyOk = true
                failures.remove(entry)
                groupFailures.remove(groupOf(entry))
                // A first page with nothing we can show (dead tag, text-only subreddit…) counts
                // against the entry; anything usable clears its record.
                if (page.items.isNotEmpty()) { health.ok(id, entry); anyContent = true } else if (firstPage) strikes += entry
                if (page.next == null) done += entry else tokens[entry] = page.next
                fresh += page.items.filter { poolKeys.add(it.key) && !seen.isSeen(it.key) }
            }.onFailure { e ->
                lastError = e
                // Only "this doesn't exist" answers count; network trouble and rate limits don't.
                if (isPermanentFailure(e)) strikes += entry
                // A whole site that's down, rate-limiting or unreachable: after a few failures in a
                // row with no success, stop asking it for a while instead of spending part of every
                // round on it (Danbooru in maintenance, Reddit answering 429…).
                if (!isPermanentFailure(e)) {
                    val g = groupOf(entry)
                    val gf = (groupFailures[g] ?: 0) + 1
                    groupFailures[g] = gf
                    if (gf >= GROUP_FAILURES_TO_REST) {
                        groupRestUntil[g] = clock() + GROUP_REST_MS
                        groupFailures.remove(g)
                    }
                }
                val n = (failures[entry] ?: 0) + 1
                failures[entry] = n
                // Entries of a resting site keep their place; they're just not asked meanwhile.
                if (n >= MAX_ENTRY_FAILURES && !groupRestUntil.containsKey(groupOf(entry))) done += entry
            }
        }
        // Strike only when other entries in this batch did return content: if everything came back
        // empty or failed, it's the site or the connection, not these entries.
        if (anyContent) strikes.forEach { health.strike(id, it) }

        // Interleave entries so one subreddit/tag doesn't fill a whole page on its own.
        val merged = interleave(fresh)
        pool += merged
        poolSnapshot = pool.toList()

        if (!anyOk && pool.isEmpty()) {
            val e = lastError
            throw e as? SourceException ?: SourceException(id, e?.message ?: "Couldn't load", cause = e)
        }
        return merged.size
    }

    /**
     * Entries that hit the same server share a group; at most [maxPerGroupPerExtend] of one group
     * are fetched per round. With hundreds of feeds on one site, a random pick would send several
     * requests to it at once (which sites answer with 403/429) and fill the round with one origin.
     */
    protected open fun groupOf(entry: String): String = entry
    protected open val maxPerGroupPerExtend: Int = Int.MAX_VALUE

    private val groupFailures = HashMap<String, Int>()
    private val groupRestUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal var clock: () -> Long = System::currentTimeMillis

    private fun resting(group: String): Boolean {
        val until = groupRestUntil[group] ?: return false
        if (clock() >= until) { groupRestUntil.remove(group); return false }
        return true
    }

    /** Sites (groups) currently being left alone after repeated failures. */
    fun restingGroups(): List<String> = groupRestUntil.keys.filter { resting(it) }.sorted()

    private fun nextBatch(): List<String> {
        val notDone = order.filter { it !in done }
        // Leave resting sites alone, unless that would leave nothing to ask at all.
        val live = notDone.filterNot { resting(groupOf(it)) }.ifEmpty { notDone }
        if (live.isEmpty()) return emptyList()
        val n = entriesPerExtend.coerceAtMost(live.size)
        val out = ArrayList<String>(n)
        val perGroup = HashMap<String, Int>()
        var i = rr % live.size
        var scanned = 0
        while (out.size < n && scanned < live.size) {
            val entry = live[i]
            val g = groupOf(entry)
            val used = perGroup[g] ?: 0
            if (used < maxPerGroupPerExtend) { out += entry; perGroup[g] = used + 1 }
            i = (i + 1) % live.size
            scanned++
        }
        rr = i
        return out
    }

    companion object {
        /** How many times one fetchFeed call may go deeper looking for unseen items. */
        private const val MAX_EXTEND_ROUNDS = 3
        /** How many times the whole entry list may start over within one generation. */
        private const val MAX_RECYCLES = 2
        private const val MAX_ENTRY_FAILURES = 3
        private const val GROUP_FAILURES_TO_REST = 4
        private const val GROUP_REST_MS = 10 * 60_000L
        private const val POOL_SEARCH_LIMIT = 100
        private const val SEARCH_CACHE_MS = 120_000L

        // Only "gone" answers. 403 is not one: rule34.xxx and others answer 403 at random when busy
        // (seen live), and counting those switched off hundreds of working feeds.
        private val PERMANENT_HTTP = Regex("""HTTP (404|410)\b""")

        /** A "doesn't exist / not allowed" answer, as opposed to a timeout, network error or 429/5xx. */
        internal fun isPermanentFailure(e: Throwable): Boolean =
            e is SourceException && e.cause !is java.io.IOException && PERMANENT_HTTP.containsMatchIn(e.message.orEmpty())

        internal fun <T> interleave(lists: List<List<T>>): List<T> {
            val out = ArrayList<T>(lists.sumOf { it.size })
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) for (l in lists) if (i < l.size) out += l[i]
            return out
        }
    }
}
