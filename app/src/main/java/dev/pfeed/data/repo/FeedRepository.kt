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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FeedSpec {
    data class Main(val mode: FeedMode) : FeedSpec
    data class Tag(val tag: String) : FeedSpec
    /**
     * More like one post: [queries] (its creator and strongest tags) are searched on every source,
     * and the results ranked by how much they share with the post ([signature]).
     */
    data class Similar(val seedKey: String, val queries: List<String>, val signature: Set<String>, val creator: String?, val look: String? = null) : FeedSpec
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

    // Fetches run on the background pool; everything that touches this session's state runs on
    // one background thread (see [loadMore]). Before, both ran on the main thread, so ranking,
    // dedup and thumbnail hashing competed with drawing the feed.
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job) + kotlinx.coroutines.Dispatchers.Default)

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

    private var nichesCheckedAt = 0L
    private var niches: Set<String> = emptySet()

    /** Niches opened by explicit likes, saves and follows (see [dev.pfeed.domain.reco.Niche.opened]); re-read once a minute. */
    private suspend fun openNiches(follows: Follows): Set<String> {
        val now = System.currentTimeMillis()
        if (now - nichesCheckedAt > 60_000) {
            nichesCheckedAt = now
            val kept = library.items(LibraryKind.LIKED).first() + library.items(LibraryKind.SAVED).first()
            niches = dev.pfeed.domain.reco.Niche.opened(kept, follows.tags)
        }
        return niches
    }

    suspend fun loadMore(): LoadResult = kotlinx.coroutines.withContext(SESSION_THREAD) { loadMoreHere() }

    /**
     * Get For You ready while the app is closed: a couple of rounds of fetching and ranking, then
     * everything gathered (the ranked batches first) is kept for the next launch's first screen.
     * Returns how many posts were kept.
     */
    suspend fun prepareForLater(rounds: Int = 2, pauseMs: Long = 8_000): Int {
        val ranked = ArrayList<MediaItem>()
        repeat(rounds) { i ->
            ranked += loadMore().items
            if (i < rounds - 1) kotlinx.coroutines.delay(pauseMs)
        }
        return kotlinx.coroutines.withContext(SESSION_THREAD) {
            collect(library.followsOnce(), null, FeedMode.FOR_YOU)
            val keep = (ranked + candidates).distinctBy { it.key }
            poolCache.save(keep)
            kept = PoolCache.cacheable(keep)
            kept.size
        }
    }
    private var kept: List<MediaItem> = emptyList()

    /** The first [n] posts [prepareForLater] kept (what the next launch opens on). */
    fun firstKept(n: Int): List<MediaItem> = kept.take(n)

    private suspend fun loadMoreHere(): LoadResult {
        loads++
        // Live is its own tab: the live source feeds it alone, and no other feed shows it.
        val liveTab = spec is FeedSpec.Main && spec.mode == FeedMode.LIVE
        val adapters = registry.enabledAdapters().filter { (it.id == dev.pfeed.source.live.LiveCamsAdapter.SOURCE_ID) == liveTab }
        if (adapters.isEmpty()) {
            return LoadResult(emptyList(), emptyList(), endReached = true, hint = if (liveTab) "Live cams are turned off. Turn them on in Sources." else "All sources are turned off. Turn some on in Sources.")
        }
        val follows = library.followsOnce()
        val mode = (spec as? FeedSpec.Main)?.mode ?: FeedMode.LATEST
        if (mode == FeedMode.FOLLOWING && follows.isEmpty) {
            return LoadResult(emptyList(), emptyList(), endReached = true, hint = "Follow a creator or a tag and their posts show up here.")
        }
        val tag = (spec as? FeedSpec.Tag)?.tag?.let(Blocklist::normalize)
        val prefs = FeedPrefs(mode, tag, follows.creators, follows.tags)

        val forYouMain = spec is FeedSpec.Main && mode == FeedMode.FOR_YOU
        // Very first load of the main feed: start from what last session fetched but never showed,
        // so there's a first screen right away while fresh fetches (launched below) catch up.
        if (loads == 1 && forYouMain) seed += poolCache.take()

        // For You: what the sites showed beside videos you just enjoyed joins the pool first.
        if (forYouMain) seed += dev.pfeed.domain.reco.Related.drain()
        // More like this: start from what the post's own site shows beside it.
        if (loads == 1 && spec is FeedSpec.Similar) seed += dev.pfeed.domain.reco.Related.of(spec.seedKey)
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
            // More like this: every one of the post's queries, not just the first.
            spec is FeedSpec.Similar -> spec.queries.drop(1)
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
            if (errors.isNotEmpty()) "Nothing loaded. Check your connection, or see which sources are working." else "You're all caught up. Try again in a bit for new posts."
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
        // Anything you asked For You for in words is searched every load, on top of the rotation.
        val asked = SteerState.current().map { it.tag.replace('_', ' ') }.filter { it in queries }
        val picks = (asked + List(minOf(INTERESTS_PER_LOAD, queries.size)) { queries[(interestRound + it) % queries.size] }).distinct()
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

    private suspend fun computeInterests(): List<String> {
        val tags = signals.affinities().tags
        // One search per interest the engine knows (so every one of your tastes stays supplied),
        // then the strongest single tags.
        val perInterest = signals.engine.use { it.searchTags(tags, MAX_INTERESTS) }
        val strongest = tags.entries
            .filter { it.value >= INTEREST_MIN_WEIGHT }
            .sortedByDescending { it.value }
            .map { it.key }
        // What you asked for in words comes first: those searches go out on the very next load.
        val asked = SteerState.current()
            .filter { (it.kind == dev.pfeed.domain.reco.Steer.Kind.MORE || it.kind == dev.pfeed.domain.reco.Steer.Kind.ONLY) && !dev.pfeed.domain.reco.Features.isMeta(it.tag) }
            .map { it.tag }
        return (asked + perInterest + strongest)
            .map { it.removePrefix("w:").replace('_', ' ').trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(MAX_INTERESTS)
    }

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
        // Live rooms are never "seen": the person is still on, doing something else by now.
        var items = incoming.filter { it.key !in shown && it.key !in candidateKeys && (isLive(it) || !seen.isSeen(it.key)) && blocklist.allows(it) }
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
        // The same video uploaded to several sites (same length, nearly the same title): once is enough.
        items = items.filter { item -> !sameVideoSeen(item).also { if (!it) indexSameVideo(item) } }
        // What each one looks like rides along with it, for the visual side of your taste.
        items = items.map { item ->
            val look = hasher.look(item)
            if (look == null || item.extras.containsKey(dev.pfeed.domain.reco.Visual.EXTRA)) item
            else item.copy(extras = item.extras + (dev.pfeed.domain.reco.Visual.EXTRA to look))
        }
        // Cap how many candidates any one source holds in the pool, so the fastest/largest source
        // (often one booru) can't crowd out everything else and make the feed feel single-source.
        // Same idea one level down: no single subreddit/site may fill the pool either, and a big
        // gallery post (30 images on Coomer) contributes a few images, not all of them.
        // Everything admitted also teaches the taste engine's map which tags go together.
        val admitted = ArrayList<MediaItem>()
        // For You: room for each source by how often its posts land for you.
        val sourceRoom: Map<String, Double> = if (spec is FeedSpec.Main && mode == FeedMode.FOR_YOU)
            signals.engine.use { e -> items.map { it.sourceId }.distinct().associateWith(e::sourceWeight) } else emptyMap()
        val perSource = candidates.groupingBy { it.sourceId }.eachCount().toMutableMap()
        val perOrigin = candidates.groupingBy { Spread.origin(it) }.eachCount().toMutableMap()
        items.shuffled().forEach { item ->
            val n = perSource[item.sourceId] ?: 0
            if (n >= ((SOURCE_POOL_CAPS[item.sourceId] ?: PER_SOURCE_POOL_CAP) * (sourceRoom[item.sourceId] ?: 1.0)).toInt()) return@forEach
            val origin = Spread.origin(item)
            val o = perOrigin[origin] ?: 0
            if (o >= PER_ORIGIN_POOL_CAP) return@forEach
            val post = Spread.postKey(item)
            if (post != null && (admittedPerPost[post] ?: 0) >= MAX_PER_POST) return@forEach
            if (candidateKeys.add(item.key)) {
                admitted += item
                candidates += item
                pooledAt[item.key] = loads
                perSource[item.sourceId] = n + 1
                perOrigin[origin] = o + 1
                if (post != null) admittedPerPost[post] = (admittedPerPost[post] ?: 0) + 1
            }
        }
        signals.engine.observe(admitted)
        return errors
    }

    /**
     * Main feeds read each source's stream. A tag feed (any tag the user taps or types) asks every
     * source to *search* for that tag, so it isn't limited to whatever the main feed happened to load.
     */
    private suspend fun fetch(a: SourceAdapter, cursor: String?, prefs: FeedPrefs): Result<Page<MediaItem>> = try {
        val tag = (spec as? FeedSpec.Tag)?.tag ?: (spec as? FeedSpec.Similar)?.queries?.firstOrNull()
        Result.success(if (tag != null) a.search(tag.removePrefix("@").replace('_', ' '), cursor) else a.fetchFeed(cursor, prefs))
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
        // The main feeds leave out kinds of post you've switched off (Settings → Feed shows).
        val hiddenTypes = if (spec is FeedSpec.Main) settings.get().hiddenFeedTypes else emptySet()
        val videoLength = if (spec is FeedSpec.Main) settings.get().videoLength else "any"
        val dropped = candidates.filter {
            (hiddenTypes.isNotEmpty() && feedType(it) in hiddenTypes) ||
            (videoLength != "any" && !lengthAllowed(it, videoLength)) ||
            !blocklist.allows(it) || (!isLive(it) && seen.isSeen(it.key)) || loads - (pooledAt[it.key] ?: loads) > STALE_LOADS ||
                // A site whose files aren't loading right now: don't hand out more of it.
                mediaHealth.isResting(it)
        }
        if (dropped.isNotEmpty()) {
            candidates.removeAll(dropped.toSet())
            dropped.forEach { candidateKeys.remove(it.key); pooledAt.remove(it.key) }
        }
        if (candidates.isEmpty()) return emptyList()

        val now = Instant.now()
        // A fandom niche (My Little Pony) only gets its normal share once the user has shown they want it.
        // A tag or creator page shows exactly what was asked for.
        Spread.openNiches = if (spec is FeedSpec.Tag) setOf(dev.pfeed.domain.reco.Niche.MLP) else openNiches(library.followsOnce())
        val batch = if (spec is FeedSpec.Main && mode == FeedMode.FOR_YOU) {
            val aff = signals.affinities()
            val ranker = Ranker(settings.get().weights)
            // With the taste engine: slots take turns between your interests, scored on the map of meaning.
            val picked = signals.engine.use { engine ->
                ranker.rank(candidates, aff.tags, aff.creators, now, recentSources, limit = BATCH, recent = recentShown, allowForcedRun = allowForcedRun, recentOrigins = recentOrigins, engine = engine, steer = SteerState.current())
            }
            // Keep the pool bounded by dropping the weakest candidates.
            if (candidates.size - picked.size > POOL_MAX) {
                val pickedKeys = picked.mapTo(HashSet()) { it.key }
                // Score each once; as a sort key it would be recomputed on every comparison.
                val keep = candidates.filter { it.key !in pickedKeys }
                    .map { it to ranker.baseScore(it, aff.tags, aff.creators, now) }
                    .sortedByDescending { it.second }
                    .take(POOL_MAX)
                    .map { it.first }
                candidates.clear(); candidateKeys.clear()
                keep.forEach { candidates += it; candidateKeys += it.key }
                pooledAt.keys.retainAll(candidateKeys)
            }
            picked
        } else if (spec is FeedSpec.Similar) {
            // Closest first: shared tags (cosine over the post's features), the same creator counts
            // extra; the post itself never comes back. Spread keeps one site from filling a screen.
            val sig = spec.signature
            val creator = spec.creator?.let(Blocklist::normalize)
            // Also by meaning (posts tagged differently that are about the same thing) and by look.
            val (seedVec, vecs) = signals.engine.use { e -> e.space.itemVector(sig.toList()) to candidates.associate { it.key to e.vectorOf(it) } }
            val seedLook = dev.pfeed.domain.reco.Visual.decode(spec.look)
            val relatedKeys = dev.pfeed.domain.reco.Related.of(spec.seedKey).mapTo(HashSet()) { it.key }
            fun closeness(c: MediaItem): Double {
                val f = Features.of(c).filterNot(Features::isMeta).toSet()
                val shared = f.count { it in sig }
                val cos = if (f.isEmpty() || sig.isEmpty()) 0.0 else shared / kotlin.math.sqrt(f.size.toDouble() * sig.size)
                val same = if (creator != null && c.creator?.let(Blocklist::normalize) == creator) 0.6 else 0.0
                val v = vecs[c.key]
                val meaning = if (seedVec != null && v != null) dev.pfeed.domain.reco.TasteEngine.cos(seedVec, v).coerceAtLeast(0.0) else 0.0
                val look = seedLook?.let { a -> dev.pfeed.domain.reco.Visual.decode(c.extras[dev.pfeed.domain.reco.Visual.EXTRA])?.let { b -> dev.pfeed.domain.reco.Visual.similarity(a, b) } } ?: 0.0
                val siteSays = if (c.key in relatedKeys) SIMILAR_RELATED else 0.0
                return cos + same + SIMILAR_MEANING * meaning + SIMILAR_LOOK * look + siteSays
            }
            candidates.removeAll { it.key == spec.seedKey }
            Spread.order(
                candidates.map { it to closeness(it) }.sortedByDescending { it.second }.map { it.first },
                recentShown,
                BATCH,
                allowForcedRun = allowForcedRun,
            )
        } else if (spec is FeedSpec.Tag) {
            val t = Features.normalizeTag(spec.tag)
            // "@name" is a creator page: their own posts first, then what searching their name found.
            val creatorName = spec.tag.takeIf { it.startsWith("@") }?.removePrefix("@")?.let(Blocklist::normalize)
            // Among the exact matches: the ones you'd most enjoy first, with newer ones a little ahead
            // (a tag page is still a place for what's new). Before, it was newest first only.
            val personal = signals.engine.use { e ->
                candidates.associate { item ->
                    val v = e.vectorOf(item)
                    val days = item.publishedAt?.let { java.time.Duration.between(it, now).toHours() / 24.0 }
                    val fresh = days?.let { kotlin.math.exp(-it.coerceAtLeast(0.0) / 14.0) } ?: 0.3
                    item.key to ((v?.let { 0.9 * e.interestScore(it) - 0.8 * e.aversion(it) } ?: 0.0) + 0.3 * e.visualScore(item) + 0.5 * fresh)
                }
            }
            Spread.order(
                candidates.sortedWith(
                    compareByDescending<MediaItem> { item ->
                        if (creatorName != null) item.creator?.let(Blocklist::normalize) == creatorName
                        else item.tags.any { Features.normalizeTag(it) == t }
                    }
                        .thenByDescending { personal[it.key] ?: 0.0 },
                ),
                recentShown,
                BATCH,
                allowForcedRun = allowForcedRun,
            )
        } else if (mode == FeedMode.LIVE) {
            // Live: the rooms you'd most enjoy first (by everything For You knows), busy ones a little ahead.
            val who = LiveFilter.who.value
            val rooms = if (who == null) candidates else candidates.filter { who in it.tags }
            val personal = signals.engine.use { e ->
                rooms.associate { item ->
                    val v = e.vectorOf(item)
                    val viewers = item.extras["viewers"]?.toDoubleOrNull() ?: 0.0
                    item.key to ((v?.let { 0.9 * e.interestScore(it) - 0.8 * e.aversion(it) } ?: 0.0) + 0.5 * e.visualScore(item) + 0.12 * kotlin.math.ln(1.0 + viewers))
                }
            }
            Spread.order(rooms.sortedByDescending { personal[it.key] ?: 0.0 }, recentShown, BATCH, allowForcedRun = true)
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
        // Long enough for the niche rule (one in 36), not just the spacing window.
        val keep = maxOf(Spread.WINDOW, dev.pfeed.domain.reco.Niche.CLOSED_WINDOW)
        if (recentShown.size > keep) recentShown.subList(0, recentShown.size - keep).clear()
        return batch
    }

    /**
     * Settings → Feed shows → video length. Clips: under 3 min, or no length given (mostly short
     * clips from feeds that don't say). Full videos: 3 min and up, by the length the site lists.
     */
    private fun lengthAllowed(item: MediaItem, length: String): Boolean {
        if (feedType(item) != "video") return true
        val sec = item.durationSec
        return when (length) {
            "short" -> sec == null || sec < LONG_VIDEO_SEC
            "long" -> sec != null && sec >= LONG_VIDEO_SEC
            else -> true
        }
    }

    private fun feedType(item: MediaItem): String = when {
        item.isGallery -> "comic"
        item.type == dev.pfeed.domain.model.MediaType.VIDEO || item.type == dev.pfeed.domain.model.MediaType.EMBED -> "video"
        item.type == dev.pfeed.domain.model.MediaType.GIF -> "gif"
        else -> "image"
    }

    companion object {
        private const val LONG_VIDEO_SEC = 180
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
        /**
         * Tube sites are eleven sites behind one source and the app's main video supply; capped like a
         * single site they made up about one post in eight. Each site is still held to
         * [PER_ORIGIN_POOL_CAP], and For You learns from there whether you want videos.
         */
        private val SOURCE_POOL_CAPS = mapOf("tube" to 40, dev.pfeed.source.live.LiveCamsAdapter.SOURCE_ID to 150)

        /** Videos taken in this feed so far, by length in seconds, to spot the same one from another site. */
    private val byLength = HashMap<Int, ArrayList<MediaItem>>()
    private var lengthIndexed = 0

    private fun sameVideoSeen(item: MediaItem): Boolean {
        val d = item.durationSec ?: return false
        if (item.type != dev.pfeed.domain.model.MediaType.VIDEO || item.title.isNullOrBlank() || d < SAME_VIDEO_MIN_SEC) return false
        val slack = maxOf(3, d / 50)
        for (len in d - slack..d + slack) {
            val near = byLength[len] ?: continue
            if (near.any { it.key != item.key && dev.pfeed.domain.reco.SameVideo.score(item, it) >= dev.pfeed.domain.reco.SameVideo.MATCH }) return true
        }
        return false
    }

    private fun indexSameVideo(item: MediaItem) {
        val d = item.durationSec ?: return
        if (item.type != dev.pfeed.domain.model.MediaType.VIDEO || item.title.isNullOrBlank() || d < SAME_VIDEO_MIN_SEC) return
        if (++lengthIndexed > SAME_VIDEO_INDEX_MAX) { byLength.clear(); lengthIndexed = 1 }
        byLength.getOrPut(d) { ArrayList() } += item
    }

    private fun isLive(item: MediaItem) = item.extras[dev.pfeed.source.live.LiveCamsAdapter.LIVE] == "1"
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
        /** Shorter clips are too often trimmed differently to call the same by title and length. */
        private const val SAME_VIDEO_MIN_SEC = 60
        private const val SAME_VIDEO_INDEX_MAX = 4000
        private const val INTEREST_PREFIX = "interest|"
        private const val INTERESTS_PER_LOAD = 2
        private const val SIMILAR_MEANING = 0.5
        private const val SIMILAR_LOOK = 0.3
        private const val SIMILAR_RELATED = 0.6
        private const val MAX_INTERESTS = 8
        /** TasteModel weight above which a feature counts as a real interest worth searching for. */
        private const val INTEREST_MIN_WEIGHT = 0.6
    }
}

/** One background thread shared by feed sessions: their state is only ever touched from here. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val SESSION_THREAD = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

