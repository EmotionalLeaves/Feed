package dev.pfeed.data.repo

import dev.pfeed.data.db.BlockDao
import dev.pfeed.data.db.BlockEntity
import dev.pfeed.domain.filter.Blocklist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** ORIGIN = one subreddit / board site / server / service (see Spread.origin): "never show posts from it". */
enum class BlockKind { TAG, CREATOR, ITEM, ORIGIN }

@Singleton
class BlockRepository @Inject constructor(
    private val dao: BlockDao,
    private val settings: dev.pfeed.data.settings.SettingsRepository,
) {

    val entries: Flow<List<BlockEntity>> = dao.all()
    val blocklist: Flow<Blocklist> = kotlinx.coroutines.flow.combine(
        entries,
        settings.flow.map { it.hideAiArt }.distinctUntilChanged(),
    ) { rows, hideAi -> toBlocklist(rows, hideAi) }

    suspend fun current(): Blocklist = toBlocklist(dao.allOnce(), settings.get().hideAiArt)

    suspend fun block(kind: BlockKind, value: String) {
        val v = if (kind == BlockKind.ITEM) value else Blocklist.normalize(value)
        if (v.isBlank()) return
        dao.upsert(BlockEntity(kind.name, v, System.currentTimeMillis()))
    }

    suspend fun unblock(kind: String, value: String) = dao.delete(kind, value)

    suspend fun clearNotInterested() = dao.deleteKind(BlockKind.ITEM.name)

    suspend fun allOnce(): List<BlockEntity> = dao.allOnce()

    private fun toBlocklist(rows: List<BlockEntity>, hideAi: Boolean) = Blocklist(
        hideAi = hideAi,
        tags = rows.filter { it.kind == BlockKind.TAG.name }.mapTo(HashSet()) { it.value },
        creators = rows.filter { it.kind == BlockKind.CREATOR.name }.mapTo(HashSet()) { it.value },
        items = rows.filter { it.kind == BlockKind.ITEM.name }.mapTo(HashSet()) { it.value },
        origins = rows.filter { it.kind == BlockKind.ORIGIN.name }.mapTo(HashSet()) { it.value },
    )
}
