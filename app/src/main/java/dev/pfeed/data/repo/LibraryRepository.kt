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

    fun keys(kind: LibraryKind): Flow<Set<String>> = libraryDao.keys(kind.name).map { it.toSet() }

    fun items(kind: LibraryKind): Flow<List<MediaItem>> = libraryDao.itemsJson(kind.name, MAX_ITEMS).map { rows ->
        rows.mapNotNull { runCatching { json.decodeFromString(MediaItem.serializer(), it) }.getOrNull() }
    }

    suspend fun add(kind: LibraryKind, item: MediaItem) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            itemDao.upsert(ItemEntity(item.key, json.encodeToString(MediaItem.serializer(), item), now))
            libraryDao.upsert(LibraryEntity(item.key, kind.name, now))
        }
    }

    suspend fun remove(kind: LibraryKind, item: MediaItem) = libraryDao.delete(item.key, kind.name)

    suspend fun purgeHistory(retentionDays: Int) {
        libraryDao.purgeHistory(System.currentTimeMillis() - retentionDays * DAY_MS)
        itemDao.deleteOrphans()
    }

    suspend fun clearHistory() {
        libraryDao.clearHistory()
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
        private const val DAY_MS = 86_400_000L
        private const val MAX_ITEMS = 2000
    }
}
