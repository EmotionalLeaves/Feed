package dev.pfeed.data.repo

import dev.pfeed.data.db.CredentialDao
import dev.pfeed.data.db.CredentialEntity
import dev.pfeed.domain.model.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts that were fetched and ranked last session but never shown, kept (in the encrypted database)
 * so the next launch has a first screen immediately instead of waiting on the network. Fresh
 * fetches start at the same moment and take over from the second batch on.
 */
@Singleton
class PoolCache @Inject constructor(
    private val dao: CredentialDao,
    private val json: Json,
) {
    @Serializable
    private data class Saved(val at: Long, val items: List<MediaItem>)

    /** Returns the saved posts once (then forgets them), if they're recent enough to still load. */
    suspend fun take(now: Long = System.currentTimeMillis()): List<MediaItem> {
        val row = runCatching { dao.get(ROW) }.getOrNull() ?: return emptyList()
        runCatching { dao.delete(ROW) }
        val saved = runCatching { json.decodeFromString(Saved.serializer(), row.json) }.getOrNull() ?: return emptyList()
        return if (now - saved.at in 0..MAX_AGE_MS) saved.items else emptyList()
    }

    suspend fun save(candidates: List<MediaItem>, now: Long = System.currentTimeMillis()) {
        val keep = cacheable(candidates)
        runCatching {
            if (keep.isEmpty()) dao.delete(ROW)
            else dao.upsert(CredentialEntity(ROW, json.encodeToString(Saved.serializer(), Saved(now, keep))))
        }
    }

    companion object {
        private const val ROW = "__pool_cache"
        /** Older than this and links may have expired; start fresh instead. */
        const val MAX_AGE_MS = 6 * 60 * 60_000L
        const val MAX_ITEMS = 36

        /** Not RedGIFs (its file links are signed and expire quickly) and not on-device files. */
        internal fun cacheable(candidates: List<MediaItem>): List<MediaItem> =
            candidates.filter { it.sourceId != "redgifs" && it.extras["media"]?.startsWith("http") != false }.take(MAX_ITEMS)
    }
}
