package dev.pfeed.data.repo

import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.model.FeedMode
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.reco.Deduplicator
import dev.pfeed.domain.reco.Features
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.domain.reco.Spread
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
    private val mediaHealth: MediaHealth,
    private val poolCache: PoolCache,
) {
    fun create(spec: FeedSpec, scope: CoroutineScope) =
        FeedSession(spec, scope, registry, settings, blocks, library, signals, hasher, seen, mediaHealth, poolCache)
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
    private val mediaHealth: MediaHealth,
    private val poolCache: PoolCache,
) {
    /** Unshown posts saved by the previous session, fed into the pool on the first load. */
    private val seed = ArrayList<MediaItem>()

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
    private val recentOrigins = ArrayDeque<String>()
    private val recentShown = ArrayList<MediaItem>()
    /** Load number each candidate entered the pool, so items passed over again and again age out. */
    private val pooledAt = HashMap<String, Int>()
    /** Items admitted per post this session, so a huge gallery can't trickle in for an hour. */
    private val admittedPerPost = HashMap<String, Int>()

    // Interest fetching (For You): searches for what the user has shown they like.
    private val interestCursors = HashMap<String, String?>()
    private val interestDone = HashSet<String>()
    private var interestRound = 0

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

        val forYouMain = spec is FeedSpec.Main && mode == FeedMode.FOR_YOU
        // Very first load of the main feed: start from what last session fetched but never showed,
        // so there's a first screen right away while fresh fetches (launched below) catch up.
        if (loads == 1 && forYouMain) seed += poolCache.take()

        // Pick up anything that finished in the background since the last call.
        val errors = collect(follows, tag, mode).toMutableList()
        val instantStart = loads == 1 && candidates.size >= BATCH
        val forYou = spec is FeedSpec.Main && mode == FeedMode.FOR_YOU
        // Following: the general feeds rarely happen to carry the exact creators/tags you follow
        // among thousands of feeds, so search every source for each of them directly.
        val following = spec is FeedSpec.Main && mode == FeedMode.FOLLOWING
        val searches: List<String>? = when {
            forYou -> interestQueries()
            // Coomer creators are stored as "name@service"; search by the name.
            following -> (follows.creators.map { it.substringBefore('@') } + follows.tags)
                .map { it.replace('_', ' ').trim() }.filter { it.length >= 2 }.distinct()
            else -> null
        }
        // Keep fresh candidates flowing every time, not only when the pool runs low: otherwise the
        // ranker ends up choosing among leftovers it already passed over.
        if (candidates.size < POOL_MAX) launchFetches(adapters, prefs)
        if (searches != null) launchInterestFetches(adapters, searches)
        var rounds = 0
        while (!instantStart && candidates.size < POOL_TARGET && rounds < MAX_ROUNDS) {
            rounds++
            val launched = launchFetches(adapters, prefs) + if (searches != null) launchInterestFetches(adapters, searches) else 0
            if (launched == 0 && inFlight.isEmpty()) break
            // First screen: wait for at least something. Later: don't hold up scrolling for stragglers.
            val budget = if (candidates.size < BATCH) FIRST_WAIT_MS else TOP_UP_WAIT_MS
            awaitSome(budget, needAny = candidates.isEmpty())
            errors += collect(follows, tag, mode)
            if (candidates.size >= BATCH && inFlight.isNotEmpty()) break
        }

        // A batch may fall back to running one origin only when variety genuinely isn't available:
        // either every source is exhausted, or nothing is still loading and the whole pool is one
        // origin anyway (e.g. the user runs a single image board), so waiting wouldn't help. While
        // any fetch is still in flight, the batch stops short of a run and fills in on the next load.
        val allDone = adapters.all { it.id in exhausted } && inFlight.isEmpty()
        val poolOrigins = candidates.mapTo(HashSet()) { Spread.origin(it) }.size
        val allowForcedRun = allDone || (inFlight.isEmpty() && poolOrigins <= 1)
        // The saved posts may be all there is for a moment; show them rather than hold out for variety.
        val batch = takeBatch(mode, allowForcedRun = allowForcedRun || instantStart)
        if (forYouMain) poolCache.save(candidates)
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

    /**
     * For You: search every source for the user's strongest learned interests (a few per load,
     * rotating), so candidates aren't limited to whatever the general feeds happen to return.
     * Each (source, interest) pair is paged deeper on later loads. Failures stay silent.
     */
    private fun launchInterestFetches(adapters: List<SourceAdapter>, queries: List<String>): Int {
        if (queries.isEmpty()) return 0
        val picks = List(minOf(INTERESTS_PER_LOAD, queries.size)) { queries[(interestRound + it) % queries.size] }.distinct()
        interestRound += picks.size
        var n = 0
        for (q in picks) for (a in adapters) {
            val key = "$INTEREST_PREFIX${a.id}|$q"
            if (key in interestDone || key in inFlight) continue
            if ((cooldownUntil[key] ?: 0) > loads) continue
            val cursor = interestCursors[key]
            inFlight[key] = scope.async { searchFetch(a, q, cursor) }
            n++
        }
        return n
    }

    /** Top positive learned features, as search queries ("rough_sex" → "rough sex", "w:tied" → "tied"). */
    private var interestCache: Pair<Int, List<String>>? = null

    private suspend fun interestQueries(): List<String> {
        interestCache?.let { (at, list) -> if (at == loads) return list }
        return computeInterests().also { interestCache = loads to it }
    }

    private suspend fun computeInterests(): List<String> =
        signals.affinities().tags.entries
            .filter { it.value >= INTEREST_MIN_WEIGHT }
            .sortedByDescending { it.value }
            .take(MAX_INTERESTS)
            .map { it.key.removePrefix("w:").replace('_', ' ').trim() }
            .filter { it.length >= 2 }
            .distinct()

    private suspend fun searchFetch(a: SourceAdapter, query: String, cursor: String?): Result<Page<MediaItem>> = try {
        Result.success(a.search(query, cursor))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
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
        if (seed.isNotEmpty()) { incoming += seed; seed.clear() }
        val done = inFlight.filterValues { it.isCompleted }
        for ((id, deferred) in done) {
            inFlight.remove(id)
            val result: Result<Page<MediaItem>> = runCatching { deferred.await() }.getOrElse { Result.failure(it) }
            if (id.startsWith(INTEREST_PREFIX)) {
                result.onSuccess { page ->
                    interestCursors[id] = page.nextCursor
                    if (page.nextCursor == null) interestDone += id
                    incoming += page.items
                }.onFailure {
                    val n = (failures[id] ?: 0) + 1
                    failures[id] = n
                    if (n >= MAX_SOURCE_FAILURES) interestDone += id else cooldownUntil[id] = loads + (1 shl n.coerceAtMost(6))
                }
                continue
            }
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
                if (n >= MAX_SOURCE_FAILURES) exhausted += id else cooldownUntil[id] = loads + (1 shl n.coerceAtMost(6))  // cap back-off at ~64 rounds
                // Only surface the first failure so a flaky source doesn't spam snackbars.
                if (n == 1) errors += "${adapter?.displayName ?: id}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (incoming.isEmpty()) return errors

        val blocklist = blocks.current()
        var items = incoming.filter { it.key !in shown && it.key !in candidateKeys && !seen.isSeen(it.key) && blocklist.allows(it) }
        // Tag feeds come from per-source searches, which match on titles and related tags too; keep
        // those, but put exact tag matches first when ranking (see takeBatch).
        if (mode == FeedMode.FOLLOWING) {
            items = items.filter { item ->
                item.creator?.let { Blocklist.normalize(it) in follows.creators } == true ||
                    item.tags.any { Blocklist.normalize(it) in follows.tags }
            }
        }
        // Near-duplicate detection hashes thumbnails; on the very first screen don't hold it up for long.
        val hashBudget = if (shown.isEmpty()) FIRST_HASH_BUDGET_MS else HASH_BUDGET_MS
        val hashes = withTimeoutOrNull(hashBudget) { hasher.hashAll(items) } ?: hasher.cached(items)
        items = dedup.filter(items, hashes)
        // Cap how many candidates any one source holds in the pool, so the fastest/largest source
        // (often one booru) can't crowd out everything else and make the feed feel single-source.
        // Same idea one level down: no single subreddit/site may fill the pool either, and a big
        // gallery post (30 images on Coomer) contributes a few images, not all of them.
        val perSource = candidates.groupingBy { it.sourceId }.eachCount().toMutableMap()
        val perOrigin = candidates.groupingBy { Spread.origin(it) }.eachCount().toMutableMap()
        items.shuffled().forEach { item ->
            val n = perSource[item.sourceId] ?: 0
            if (n >= PER_SOURCE_POOL_CAP) return@forEach
            val origin = Spread.origin(item)
            val o = perOrigin[origin] ?: 0
            if (o >= PER_ORIGIN_POOL_CAP) return@forEach
            val post = Spread.postKey(item)
            if (post != null && (admittedPerPost[post] ?: 0) >= MAX_PER_POST) return@forEach
            if (candidateKeys.add(item.key)) {
                candidates += item
                pooledAt[item.key] = loads
                perSource[item.sourceId] = n + 1
                perOrigin[origin] = o + 1
                if (post != null) admittedPerPost[post] = (admittedPerPost[post] ?: 0) + 1
            }
        }
        return errors
    }

    /**
     * Main feeds read each source's stream. A tag feed (any tag the user taps or types) asks every
     * source to *search* for that tag, so it isn't limited to whatever the main feed happened to load.
     */
    private suspend fun fetch(a: SourceAdapter, cursor: String?, prefs: FeedPrefs): Result<Page<MediaItem>> = try {
        val tag = (spec as? FeedSpec.Tag)?.tag
        Result.success(if (tag != null) a.search(tag.replace('_', ' '), cursor) else a.fetchFeed(cursor, prefs))
    } catch (e: CancellationException) {
        throw e
    } catch (e: SourceException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(Exception("couldn't load (${e.javaClass.simpleName}: ${e.message})", e))
    }

    // ── ranking ──

    private suspend fun takeBatch(mode: FeedMode, allowForcedRun: Boolean): List<MediaItem> {
        // Re-apply filters: the user may have blocked something or swiped past it since it was pooled.
        val blocklist = blocks.current()
        // Also age out candidates the ranker has passed over for many loads: they're the leftovers.
        val dropped = candidates.filter {
            !blocklist.allows(it) || seen.isSeen(it.key) || loads - (pooledAt[it.key] ?: loads) > STALE_LOADS ||
                // A site whose files aren't loading right now: don't hand out more of it.
                mediaHealth.isResting(it)
        }
        if (dropped.isNotEmpty()) {
            candidates.removeAll(dropped.toSet())
            dropped.forEach { candidateKeys.remove(it.key); pooledAt.remove(it.key) }
        }
        if (candidates.isEmpty()) return emptyList()

        val now = Instant.now()
        val batch = if (spec is FeedSpec.Main && mode == FeedMode.FOR_YOU) {
            val aff = signals.affinities()
            val ranker = Ranker(settings.get().weights)
            val picked = ranker.rank(candidates, aff.tags, aff.creators, now, recentSources, limit = BATCH, recent = recentShown, allowForcedRun = allowForcedRun, recentOrigins = recentOrigins)
            // Keep the pool bounded by dropping the weakest candidates.
            if (candidates.size - picked.size > POOL_MAX) {
                val pickedKeys = picked.mapTo(HashSet()) { it.key }
                val keep = candidates.filter { it.key !in pickedKeys }
                    .sortedByDescending { ranker.baseScore(it, aff.tags, aff.creators, now) }
                    .take(POOL_MAX)
                candidates.clear(); candidateKeys.clear()
                keep.forEach { candidates += it; candidateKeys += it.key }
                pooledAt.keys.retainAll(candidateKeys)
            }
            picked
        } else if (spec is FeedSpec.Tag) {
            val t = Features.normalizeTag(spec.tag)
            Spread.order(
                candidates.sortedWith(
                    compareByDescending<MediaItem> { item -> item.tags.any { Features.normalizeTag(it) == t } }
                        .thenByDescending { it.publishedAt ?: Instant.EPOCH },
                ),
                recentShown,
                BATCH,
                allowForcedRun = allowForcedRun,
            )
        } else {
            // Latest / Following: newest first, but spaced so one busy source can't fill the screen.
            Spread.order(candidates.sortedByDescending { it.publishedAt ?: Instant.EPOCH }, recentShown, BATCH, allowForcedRun = allowForcedRun)
        }

        val batchKeys = batch.mapTo(HashSet()) { it.key }
        candidates.removeAll { it.key in batchKeys }
        candidateKeys.removeAll(batchKeys)
        batchKeys.forEach { pooledAt.remove(it) }
        batch.forEach { shown += it.key; recentSources += it.sourceId; recentShown += it; recentOrigins += Spread.origin(it) }
        while (recentOrigins.size > Ranker.NOVELTY_WINDOW) recentOrigins.removeFirst()
        if (recentSources.size > 50) recentSources.subList(0, recentSources.size - 50).clear()
        if (recentShown.size > Spread.WINDOW) recentShown.subList(0, recentShown.size - Spread.WINDOW).clear()
        return batch
    }

    companion object {
        /** Items handed to the screen per call; small so ranking reacts quickly to new signals. */
        const val BATCH = 12
        /**
         * Candidates to keep on hand. Enough that ranking has real choice (~4 batches), but small so
         * the newest signals reshape the next batch quickly — a big backlog means the ranker keeps
         * serving posts it scored before your recent likes/skips, which feels like it isn't learning.
         */
        private const val POOL_TARGET = 50
        private const val POOL_MAX = 120
        /** Most candidates one source may hold at once (~40% of POOL_TARGET), to keep the feed mixed. */
        private const val PER_SOURCE_POOL_CAP = 20
        /** Most candidates one subreddit / board site / server may hold at once. */
        private const val PER_ORIGIN_POOL_CAP = 10
        /** Most images taken from one multi-image post per session. */
        private const val MAX_PER_POST = 3
        private const val MAX_ROUNDS = 3
        private const val MAX_SOURCE_FAILURES = 4
        /** First screen: show something as soon as a couple of fast sources answer. */
        private const val FIRST_WAIT_MS = 3_500L
        private const val TOP_UP_WAIT_MS = 1_200L
        private const val HARD_WAIT_MS = 40_000L
        private const val HASH_BUDGET_MS = 1_500L
        private const val FIRST_HASH_BUDGET_MS = 400L
        /** A candidate passed over for this many loads (~12 items each) is dropped. */
        private const val STALE_LOADS = 8
        private const val INTEREST_PREFIX = "interest|"
        private const val INTERESTS_PER_LOAD = 2
        private const val MAX_INTERESTS = 8
        /** TasteModel weight above which a feature counts as a real interest worth searching for. */
        private const val INTEREST_MIN_WEIGHT = 0.6
    }
}
