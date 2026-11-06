package dev.pfeed.data.repo

import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.reco.Deduplicator
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class SearchResult(val items: List<MediaItem>, val errors: List<String>, val elapsedMs: Long)

/**
 * Fans a query out to every enabled adapter in parallel. Each adapter itself searches all of its
 * servers in parallel with per-server deadlines; here each source gets up to [MAX_PER_SOURCE]
 * results (several pages) within [PER_SOURCE_TIMEOUT_MS], keeping whatever arrived if time runs out.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val registry: SourceRegistry,
    private val blocks: BlockRepository,
    private val signals: SignalRepository,
    private val settings: SettingsRepository,
    private val hasher: ThumbnailHasher,
) {
    suspend fun search(query: String): SearchResult {
        val started = System.currentTimeMillis()
        val adapters = registry.enabledAdapters()
        val results = coroutineScope {
            adapters.map { a ->
                async {
                    a to try {
                        val got = ArrayList<MediaItem>()
                        var failure: Exception? = null
                        val finished = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
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
                            true
                        }
                        when {
                            got.isNotEmpty() -> Result.success(got.toList())
                            failure != null -> Result.failure(failure!!)
                            finished == null -> Result.failure(Exception("timed out"))
                            else -> Result.success(emptyList())
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        val errors = results.mapNotNull { (a, r) -> r.exceptionOrNull()?.let { "${a.displayName}: ${it.message}" } }
        val blocklist = blocks.current()
        val merged = results.flatMap { it.second.getOrDefault(emptyList()) }.filter(blocklist::allows)
        // Search is latency-bound: dedup on titles/durations plus any hashes already memoised.
        val unique = Deduplicator().filter(merged, hasher.cached(merged))

        val aff = signals.affinities()
        val ranker = Ranker(settings.get().weights.copy(exploration = 0.0))
        val now = Instant.now()
        val q = query.trim().lowercase()
        val ranked = unique.sortedByDescending { item ->
            val textMatch = when {
                item.title?.lowercase() == q -> 2.0
                item.title?.lowercase()?.contains(q) == true -> 1.0
                item.tags.any { it.equals(q, ignoreCase = true) } -> 1.0
                else -> 0.0
            }
            textMatch + ranker.baseScore(item, aff.tags, aff.creators, now)
        }
        return SearchResult(ranked, errors, System.currentTimeMillis() - started)
    }

    companion object {
        private const val PER_SOURCE_TIMEOUT_MS = 9_000L
        private const val MAX_PER_SOURCE = 150
    }
}
