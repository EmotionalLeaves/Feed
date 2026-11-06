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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
) {
    fun create(spec: FeedSpec) = FeedSession(spec, registry, settings, blocks, library, signals, hasher)
}

/**
 * One scroll session of one feed (mode or tag). Holds per-source cursors, the dedup state and the
 * set of keys already shown. Pipeline per page: fan-out fetch → blocklist → mode filter → dedup → rank.
 */
class FeedSession(
    private val spec: FeedSpec,
    private val registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val blocks: BlockRepository,
    private val library: LibraryRepository,
    private val signals: SignalRepository,
    private val hasher: ThumbnailHasher,
) {
    private val cursors = HashMap<String, String?>()
    private val started = HashSet<String>()
    private val exhausted = HashSet<String>()
    private val shown = HashSet<String>()
    private val dedup = Deduplicator()
    private val recentSources = ArrayList<String>()

    suspend fun loadMore(): LoadResult {
        val errors = mutableListOf<String>()
        repeat(MAX_ATTEMPTS) {
            val r = loadOnce()
            errors += r.errors
            if (r.items.isNotEmpty() || r.endReached || r.hint != null) return r.copy(errors = errors.distinct())
        }
        return LoadResult(emptyList(), errors.distinct(), endReached = false)
    }

    private suspend fun loadOnce(): LoadResult {
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

        val active = adapters.filter { it.id !in exhausted }
        val fetched: List<Pair<SourceAdapter, Result<Page<MediaItem>>>> = coroutineScope {
            active.map { a ->
                async {
                    val cursor = if (a.id in started) cursors[a.id] else null
                    a to fetch(a, cursor, prefs)
                }
            }.awaitAll()
        }

        val errors = mutableListOf<String>()
        val incoming = mutableListOf<MediaItem>()
        for ((adapter, result) in fetched) {
            result.onSuccess { page ->
                started += adapter.id
                cursors[adapter.id] = page.nextCursor
                if (page.nextCursor == null) exhausted += adapter.id
                incoming += page.items
            }.onFailure { e ->
                errors += "${adapter.displayName}: ${e.message ?: e.javaClass.simpleName}"
                // Don't hammer a failing source within this session.
                exhausted += adapter.id
            }
        }

        val blocklist = blocks.current()
        var items = incoming
            .filter { it.key !in shown }
            .filter(blocklist::allows)
        if (tag != null) items = items.filter { item -> item.tags.any { Blocklist.normalize(it) == tag } }
        if (mode == FeedMode.FOLLOWING) {
            items = items.filter { item ->
                item.creator?.let { Blocklist.normalize(it) in follows.creators } == true ||
                    item.tags.any { Blocklist.normalize(it) in follows.tags }
            }
        }

        val hashes = withTimeoutOrNull(HASH_BUDGET_MS) { hasher.hashAll(items) } ?: hasher.cached(items)
        items = dedup.filter(items, hashes)

        val now = Instant.now()
        val ordered = when {
            spec is FeedSpec.Main && mode == FeedMode.FOR_YOU -> {
                val aff = signals.affinities()
                Ranker(settings.get().weights).rank(items, aff.tags, aff.creators, now, recentSources)
            }
            else -> items.sortedByDescending { it.publishedAt ?: Instant.EPOCH }
        }
        ordered.forEach { shown += it.key; recentSources += it.sourceId }
        if (recentSources.size > 50) recentSources.subList(0, recentSources.size - 50).clear()

        val end = exhausted.containsAll(adapters.map { it.id })
        val hint = if (end && shown.isEmpty()) {
            if (errors.isNotEmpty()) "Couldn't load anything. Check Settings → Sources."
            else "No items yet. Add sources in Settings → Sources."
        } else null
        return LoadResult(ordered, errors, endReached = end, hint = hint)
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

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val HASH_BUDGET_MS = 3_000L
    }
}
