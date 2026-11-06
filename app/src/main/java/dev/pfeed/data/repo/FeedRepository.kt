package dev.pfeed.data.repo

import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.model.FeedMode
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.reco.Deduplicator
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.source.SourceAdapter
import dev.pfeed.source.SourceException
import dev.pfeed.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FeedSpec {
    data class Main(val mode: FeedMode) : FeedSpec
    data class Tag(val tag: String) : FeedSpec
}

data class LoadResult(
    val items: List<MediaItem>,
    val errors: List<String>,
    val endReached: Boolean,
    val hint: String? = null,
)

@Singleton
class FeedSessionFactory @Inject constructor(
    private val registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val blocks: BlockRepository,
    private val library: LibraryRepository,
    private val signals: SignalRepository,
    private val hasher: ThumbnailHasher,
    private val seen: SeenItems,
) {
    fun create(spec: FeedSpec, scope: CoroutineScope) =
        FeedSession(spec, scope, registry, settings, blocks, library, signals, hasher, seen)
}

/**
 * One endless scroll session of one feed (mode or tag).
 *
 * TikTok-style loop:
 *  1. Sources stream items into a **candidate pool** (filtered: blocklist, already-seen, dedup).
 *  2. Each [loadMore] hands out only a small batch ([BATCH]) — for For You, the best-scoring
 *     candidates under the user's *current* affinities, so likes/skips from a few swipes ago
 *     already reshape what comes next. The rest stay in the pool and are re-scored next time.
 *  3. The pool is topped up in the background; a slow source never blocks the screen — whatever
 *     arrives within the wait budget is used, stragglers land in the pool for the next batch.
 *  4. A failing source backs off and retries later in the session instead of being dropped.
 */
class FeedSession(
    private val spec: FeedSpec,
    parentScope: CoroutineScope,
    private val registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val blocks: BlockRepository,
    private val library: LibraryRepository,
    private val signals: SignalRepository,
    private val hasher: ThumbnailHasher,
    private val seen: SeenItems,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))

    private val cursors = HashMap<String, String?>()
    private val started = HashSet<String>()
    private val exhausted = HashSet<String>()
    private val failures = HashMap<String, Int>()
    private val cooldownUntil = HashMap<String, Int>()
    private val inFlight = HashMap<String, Deferred<Result<Page<MediaItem>>>>()
    private var loads = 0

    private val shown = HashSet<String>()
    private val candidates = ArrayList<MediaItem>()
    private val candidateKeys = HashSet<String>()
    private val dedup = Deduplicator()
    private val recentSources = ArrayList<String>()

    /** Stop background fetches; call when the session is replaced. */
    fun close() = scope.cancel()

    suspend fun loadMore(): LoadResult {
        loads++
        val adapters = registry.enabledAdapters()
        if (adapters.isEmpty()) {
            return LoadResult(emptyList(), emptyList(), endReached = true, hint = "No sources enabled. Add one in Settings → Sources.")
        }
        val follows = library.followsOnce()
        val mode = (spec as? FeedSpec.Main)?.mode ?: FeedMode.LATEST
        if (mode == FeedMode.FOLLOWING && follows.isEmpty) {
            return LoadResult(emptyList(), emptyList(), endReached = true, hint = "Follow a creator or tag to fill this feed.")
        }
        val tag = (spec as? FeedSpec.Tag)?.tag?.let(Blocklist::normalize)
        val prefs = FeedPrefs(mode, tag, follows.creators, follows.tags)

        // Pick up anything that finished in the background since the last call.
        val errors = collect(follows, tag, mode).toMutableList()
        var rounds = 0
        while (candidates.size < POOL_TARGET && rounds < MAX_ROUNDS) {
            rounds++
            val launched = launchFetches(adapters, prefs)
            if (launched == 0 && inFlight.isEmpty()) break
            // First screen: wait for at least something. Later: don't hold up scrolling for stragglers.
            val budget = if (candidates.size < BATCH) FIRST_WAIT_MS else TOP_UP_WAIT_MS
            awaitSome(budget, needAny = candidates.isEmpty())
            errors += collect(follows, tag, mode)
            if (candidates.size >= BATCH && inFlight.isNotEmpty()) break
        }

        val batch = takeBatch(mode)
        val allDone = adapters.all { it.id in exhausted } && inFlight.isEmpty()
        val end = allDone && candidates.isEmpty() && batch.isEmpty()
        val hint = if (end && shown.isEmpty()) {
            if (errors.isNotEmpty()) "Couldn't load anything. Check Settings → Sources." else "You have seen everything new for now. Tap Refresh in a bit."
        } else null
        return LoadResult(batch, errors.distinct(), endReached = end, hint = hint)
    }

    // ── fetching ──

    private fun launchFetches(adapters: List<SourceAdapter>, prefs: FeedPrefs): Int {
        var n = 0
        for (a in adapters) {
            if (a.id in exhausted || a.id in inFlight) continue
            if ((cooldownUntil[a.id] ?: 0) > loads) continue
            val cursor = if (a.id in started) cursors[a.id] else null
            inFlight[a.id] = scope.async { fetch(a, cursor, prefs) }
            n++
        }
        return n
    }

    /** Wait until every in-flight fetch is done or [budgetMs] passes; with [needAny], keep waiting (up to a hard cap) for the first one. */
    private suspend fun awaitSome(budgetMs: Long, needAny: Boolean) {
        val pending = inFlight.values.toList()
        if (pending.isEmpty()) return
        withTimeoutOrNull(budgetMs) { pending.awaitAll() }
        if (needAny && pending.none { it.isCompleted }) {
            withTimeoutOrNull(HARD_WAIT_MS) { select<Unit> { pending.forEach { d -> d.onAwait { } } } }
        }
    }

    private suspend fun collect(follows: Follows, tag: String?, mode: FeedMode): List<String> {
        val errors = mutableListOf<String>()
        val incoming = mutableListOf<MediaItem>()
        val done = inFlight.filterValues { it.isCompleted }
        for ((id, deferred) in done) {
            inFlight.remove(id)
            val result: Result<Page<MediaItem>> = runCatching { deferred.await() }.getOrElse { Result.failure(it) }
            val adapter = registry.byId(id)
            result.onSuccess { page ->
                started += id
                failures.remove(id)
                cursors[id] = page.nextCursor
                if (page.nextCursor == null) exhausted += id
                incoming += page.items
            }.onFailure { e ->
                val n = (failures[id] ?: 0) + 1
                failures[id] = n
                if (n >= MAX_SOURCE_FAILURES) exhausted += id else cooldownUntil[id] = loads + (1 shl n)
                // Only surface the first failure so a flaky source doesn't spam snackbars.
                if (n == 1) errors += "${adapter?.displayName ?: id}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (incoming.isEmpty()) return errors

        val blocklist = blocks.current()
        var items = incoming.filter { it.key !in shown && it.key !in candidateKeys && !seen.isSeen(it.key) && blocklist.allows(it) }
        if (tag != null) items = items.filter { item -> item.tags.any { Blocklist.normalize(it) == tag } }
        if (mode == FeedMode.FOLLOWING) {
            items = items.filter { item ->
                item.creator?.let { Blocklist.normalize(it) in follows.creators } == true ||
                    item.tags.any { Blocklist.normalize(it) in follows.tags }
            }
        }
        val hashes = withTimeoutOrNull(HASH_BUDGET_MS) { hasher.hashAll(items) } ?: hasher.cached(items)
        items = dedup.filter(items, hashes)
        items.forEach { if (candidateKeys.add(it.key)) candidates += it }
        return errors
    }

    private suspend fun fetch(a: SourceAdapter, cursor: String?, prefs: FeedPrefs): Result<Page<MediaItem>> = try {
        Result.success(a.fetchFeed(cursor, prefs))
    } catch (e: CancellationException) {
        throw e
    } catch (e: SourceException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(Exception("couldn't load (${e.javaClass.simpleName}: ${e.message})", e))
    }

    // ── ranking ──

    private suspend fun takeBatch(mode: FeedMode): List<MediaItem> {
        // Re-apply filters: the user may have blocked something or swiped past it since it was pooled.
        val blocklist = blocks.current()
        val dropped = candidates.filter { !blocklist.allows(it) || seen.isSeen(it.key) }
        if (dropped.isNotEmpty()) {
            candidates.removeAll(dropped.toSet())
            dropped.forEach { candidateKeys.remove(it.key) }
        }
        if (candidates.isEmpty()) return emptyList()

        val now = Instant.now()
        val batch = if (spec is FeedSpec.Main && mode == FeedMode.FOR_YOU) {
            val aff = signals.affinities()
            val ranker = Ranker(settings.get().weights)
            val picked = ranker.rank(candidates, aff.tags, aff.creators, now, recentSources, limit = BATCH)
            // Keep the pool bounded by dropping the weakest candidates.
            if (candidates.size - picked.size > POOL_MAX) {
                val pickedKeys = picked.mapTo(HashSet()) { it.key }
                val keep = candidates.filter { it.key !in pickedKeys }
                    .sortedByDescending { ranker.baseScore(it, aff.tags, aff.creators, now) }
                    .take(POOL_MAX)
                candidates.clear(); candidateKeys.clear()
                keep.forEach { candidates += it; candidateKeys += it.key }
            }
            picked
        } else {
            candidates.sortedByDescending { it.publishedAt ?: Instant.EPOCH }.take(BATCH)
        }

        val batchKeys = batch.mapTo(HashSet()) { it.key }
        candidates.removeAll { it.key in batchKeys }
        candidateKeys.removeAll(batchKeys)
        batch.forEach { shown += it.key; recentSources += it.sourceId }
        if (recentSources.size > 50) recentSources.subList(0, recentSources.size - 50).clear()
        return batch
    }

    companion object {
        /** Items handed to the screen per call; small so ranking reacts quickly to new signals. */
        const val BATCH = 12
        /** Candidates to keep on hand so ranking has real choice. */
        private const val POOL_TARGET = 120
        private const val POOL_MAX = 600
        private const val MAX_ROUNDS = 3
        private const val MAX_SOURCE_FAILURES = 4
        private const val FIRST_WAIT_MS = 6_000L
        private const val TOP_UP_WAIT_MS = 1_500L
        private const val HARD_WAIT_MS = 40_000L
        private const val HASH_BUDGET_MS = 3_000L
    }
}
