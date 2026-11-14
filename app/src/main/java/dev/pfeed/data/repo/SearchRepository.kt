package dev.pfeed.data.repo

import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.reco.Deduplicator
import dev.pfeed.domain.reco.Features
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.source.SourceAdapter
import dev.pfeed.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progress of one search. [items] grows as sources answer; [pending] is how many are still working.
 */
data class SearchResult(
    val items: List<MediaItem>,
    val errors: List<String>,
    val elapsedMs: Long,
    val pending: Int = 0,
    val sourcesWithResults: Int = 0,
    /** How long each source took to answer (or give up), for diagnostics. */
    val timings: Map<String, Long> = emptyMap(),
) {
    val done: Boolean get() = pending == 0
}

/**
 * Fans a query out to every enabled source in parallel and streams results back as each one
 * answers, so the fast sources show up immediately instead of everything waiting on the slowest.
 * Each source itself searches all of its servers in parallel with per-server deadlines; here each
 * source gets up to [MAX_PER_SOURCE] results (several pages) within [PER_SOURCE_TIMEOUT_MS].
 */
@Singleton
class SearchRepository @Inject constructor(
    private val registry: SourceRegistry,
    private val blocks: BlockRepository,
    private val signals: SignalRepository,
    private val settings: SettingsRepository,
    private val hasher: ThumbnailHasher,
) {
    fun search(query: String): Flow<SearchResult> = channelFlow {
        val started = System.currentTimeMillis()
        val adapters = registry.enabledAdapters()
        if (adapters.isEmpty()) {
            send(SearchResult(emptyList(), listOf("No sources enabled"), 0))
            return@channelFlow
        }
        val blocklist = blocks.current()
        val aff = signals.affinities()
        val ranker = Ranker(settings.get().weights.copy(exploration = 0.0))
        val now = Instant.now()
        val q = query.trim().lowercase()
        val qTag = Features.normalizeTag(q)
        val words = q.split(Regex("\\s+")).filter { it.length >= 2 }

        fun relevance(item: MediaItem): Double {
            val title = item.title?.lowercase().orEmpty()
            val tags = item.tags.map(Features::normalizeTag)
            val exact = when {
                title == q -> 2.0
                qTag in tags -> 1.6
                title.contains(q) -> 1.2
                else -> 0.0
            }
            // Partial credit for multi-word queries: share of words found in title or tags.
            val partial = if (words.size > 1) {
                words.count { w -> title.contains(w) || tags.any { it.contains(w) } } / words.size.toDouble()
            } else 0.0
            return exact + partial + ranker.baseScore(item, aff.tags, aff.creators, now)
        }

        val lock = Mutex()
        val collected = ArrayList<MediaItem>()
        val errors = ArrayList<String>()
        var pending = adapters.size
        var withResults = 0
        val timings = LinkedHashMap<String, Long>()

        send(SearchResult(emptyList(), emptyList(), 0, pending = pending))
        adapters.forEach { a ->
            launch {
                val t0 = System.currentTimeMillis()
                val r = searchOne(a, query)
                val snapshot = lock.withLock {
                    timings[a.id] = System.currentTimeMillis() - t0
                    pending--
                    r.onSuccess { list ->
                        val allowed = list.filter(blocklist::allows)
                        if (allowed.isNotEmpty()) withResults++
                        collected += allowed
                    }.onFailure { e -> errors += "${a.displayName}: ${e.message ?: "failed"}" }
                    // Search is latency-bound: dedup on titles/durations plus any hashes already memoised.
                    val unique = Deduplicator().filter(collected, hasher.cached(collected))
                    SearchResult(
                        items = unique.sortedByDescending(::relevance),
                        errors = errors.toList(),
                        elapsedMs = System.currentTimeMillis() - started,
                        pending = pending,
                        sourcesWithResults = withResults,
                        timings = LinkedHashMap(timings),
                    )
                }
                send(snapshot)
            }
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // dedup and ranking stay off the caller's thread (the UI), and timers fire on time

    /**
     * One source's results within [PER_SOURCE_TIMEOUT_MS], hard. The work runs detached and is only
     * awaited for that long: a source busy parsing (or ignoring cancellation) can't hold the search
     * past its budget, and whatever pages it already returned are kept.
     */
    private suspend fun searchOne(a: SourceAdapter, query: String): Result<List<MediaItem>> {
        val got = java.util.Collections.synchronizedList(ArrayList<MediaItem>())
        var failure: Exception? = null
        val work = detached.async {
            var cursor: String? = null
            do {
                val page = try {
                    a.search(query, cursor)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (got.isEmpty()) failure = e
                    break
                }
                got += page.items
                cursor = page.nextCursor
            } while (cursor != null && got.size < MAX_PER_SOURCE)
        }
        val finished = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) { work.await(); true }
        if (finished == null) work.cancel()
        val items = synchronized(got) { got.toList() }
        return when {
            items.isNotEmpty() -> Result.success(items)
            failure != null -> Result.failure(failure!!)
            finished == null -> Result.failure(Exception("didn't answer in time"))
            else -> Result.success(emptyList())
        }
    }

    /** Where per-source search work runs, outside the search's own scope (see [searchOne]). */
    private val detached = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    companion object {
        private const val PER_SOURCE_TIMEOUT_MS = 14_000L
        private const val MAX_PER_SOURCE = 150
    }
}
