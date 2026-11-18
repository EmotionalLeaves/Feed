package dev.pfeed.data.repo

import androidx.room.withTransaction
import dev.pfeed.data.db.AppDatabase
import dev.pfeed.data.db.FollowEntity
import dev.pfeed.data.db.ItemEntity
import dev.pfeed.data.db.LibraryEntity
import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class LibraryKind { SAVED, LIKED, HISTORY }
enum class FollowKind { CREATOR, TAG }

data class Follows(val creators: Set<String> = emptySet(), val tags: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = creators.isEmpty() && tags.isEmpty()
}

@Singleton
class LibraryRepository @Inject constructor(
    private val db: AppDatabase,
    private val json: Json,
) {
    private val libraryDao = db.libraryDao()
    private val itemDao = db.itemDao()
    private val followDao = db.followDao()

    fun count(kind: LibraryKind): Flow<Int> = libraryDao.count(kind.name)

    fun keys(kind: LibraryKind): Flow<Set<String>> = libraryDao.keys(kind.name).map { it.toSet() }

    fun items(kind: LibraryKind): Flow<List<MediaItem>> = libraryDao.itemsJson(kind.name, MAX_ITEMS).map { rows ->
        rows.mapNotNull { runCatching { json.decodeFromString(MediaItem.serializer(), it) }.getOrNull() }
    }

    /** When each post in [kind] was added (watched, for History), by key. */
    fun times(kind: LibraryKind): Flow<Map<String, Long>> =
        libraryDao.times(kind.name, MAX_ITEMS).map { rows -> rows.associate { it.itemKey to it.at } }

    suspend fun add(kind: LibraryKind, given: MediaItem) = addTo(kind.name, given)

    private suspend fun addTo(kind: String, given: MediaItem) {
        if (kind == LibraryKind.HISTORY.name && dev.pfeed.domain.privacy.Incognito.active) return
        val item = OfflineStore.original(given)
        val now = System.currentTimeMillis()
        db.withTransaction {
            itemDao.upsert(ItemEntity(item.key, json.encodeToString(MediaItem.serializer(), item), now))
            libraryDao.upsert(LibraryEntity(item.key, kind, now))
        }
    }

    /** Unsaving a post also takes it out of your collections (they're folders within Saved). */
    suspend fun remove(kind: LibraryKind, item: MediaItem) {
        db.withTransaction {
            libraryDao.delete(item.key, kind.name)
            if (kind == LibraryKind.SAVED) libraryDao.removeFromAllCollections(item.key)
        }
    }

    // ── backup ──

    /** Saved, liked and collections (not history), each with the post as stored. */
    suspend fun exportRows(): List<dev.pfeed.data.db.LibraryRow> = libraryDao.exportRows()

    /** Puts backed-up entries back; ones already here are kept as they are. Returns how many were new. */
    suspend fun importRows(rows: List<dev.pfeed.data.db.LibraryRow>): Int {
        val allowed = rows.filter { it.kind != LibraryKind.HISTORY.name && it.itemKey.isNotBlank() }
        var added = 0
        db.withTransaction {
            val have = HashMap<String, Set<String>>()
            allowed.forEach { r ->
                val kinds = have.getOrPut(r.kind) { libraryDao.keysOnce(r.kind).toHashSet() }
                if (r.itemKey in kinds) return@forEach
                // Only a post that still reads as one: an unreadable copy would be an empty tile.
                if (runCatching { json.decodeFromString(MediaItem.serializer(), r.json) }.isFailure) return@forEach
                itemDao.upsert(ItemEntity(r.itemKey, r.json, r.at))
                libraryDao.upsert(LibraryEntity(r.itemKey, r.kind, r.at))
                added++
            }
        }
        return added
    }

    // ── collections: named folders of saved posts ──

    /** [cover]: the thumbnail of the post most recently put in it. */
    data class Collection(val name: String, val count: Int, val cover: String? = null)

    val collections: Flow<List<Collection>> = libraryDao.collections().map { rows ->
        rows.map { row ->
            val cover = libraryDao.latestJson(row.kind)?.let { j ->
                runCatching { json.decodeFromString(MediaItem.serializer(), j) }.getOrNull()?.thumbnailUrl
            }
            Collection(row.kind.removePrefix(COLLECTION), row.count, cover)
        }
    }

    fun collectionItems(name: String): Flow<List<MediaItem>> = libraryDao.itemsJson(COLLECTION + name, MAX_ITEMS).map { rows ->
        rows.mapNotNull { runCatching { json.decodeFromString(MediaItem.serializer(), it) }.getOrNull() }
    }

    suspend fun collectionsOf(item: MediaItem): Set<String> =
        libraryDao.collectionsOf(item.key).mapTo(HashSet()) { it.removePrefix(COLLECTION) }

    /** Putting a post in a collection saves it too, so it's kept offline like any saved post. */
    suspend fun addToCollection(name: String, item: MediaItem) {
        val n = cleanName(name) ?: return
        db.withTransaction {
            addTo(LibraryKind.SAVED.name, item)
            addTo(COLLECTION + n, item)
        }
    }

    suspend fun removeFromCollection(name: String, item: MediaItem) = libraryDao.delete(item.key, COLLECTION + name)

    suspend fun renameCollection(from: String, to: String) {
        val n = cleanName(to) ?: return
        if (n != from) libraryDao.renameKind(COLLECTION + from, COLLECTION + n)
    }

    /** The collection goes; its posts stay saved. */
    suspend fun deleteCollection(name: String) = libraryDao.deleteKind(COLLECTION + name)

    suspend fun purgeHistory(retentionDays: Int) {
        libraryDao.purgeHistory(System.currentTimeMillis() - retentionDays * DAY_MS)
        itemDao.deleteOrphans()
    }

    suspend fun clearHistory() {
        libraryDao.clearHistory()
        dev.pfeed.player.ResumePoints.clear() // where you left videos is history too
        dev.pfeed.player.ComicPages.clear()
        itemDao.deleteOrphans()
    }

    val followRows: Flow<List<FollowEntity>> = followDao.all()
    val follows: Flow<Follows> = followRows.map(::toFollows)

    suspend fun followsOnce(): Follows = toFollows(followDao.allOnce())

    suspend fun follow(kind: FollowKind, value: String) {
        val v = Blocklist.normalize(value)
        if (v.isNotBlank()) followDao.upsert(FollowEntity(kind.name, v, System.currentTimeMillis()))
    }

    suspend fun unfollow(kind: FollowKind, value: String) = followDao.delete(kind.name, Blocklist.normalize(value))

    suspend fun allFollowRows(): List<FollowEntity> = followDao.allOnce()

    private fun toFollows(rows: List<FollowEntity>) = Follows(
        creators = rows.filter { it.kind == FollowKind.CREATOR.name }.mapTo(HashSet()) { it.value },
        tags = rows.filter { it.kind == FollowKind.TAG.name }.mapTo(HashSet()) { it.value },
    )

    companion object {
        private const val COLLECTION = "C:"
        const val MAX_COLLECTION_NAME = 40

        fun cleanName(name: String): String? = name.trim().replace(Regex("\\s+"), " ").take(MAX_COLLECTION_NAME).ifEmpty { null }
        private const val DAY_MS = 86_400_000L
        private const val MAX_ITEMS = 2000
    }
}
