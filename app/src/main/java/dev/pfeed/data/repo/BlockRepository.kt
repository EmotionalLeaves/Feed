package dev.pfeed.data.repo

import dev.pfeed.data.db.BlockDao
import dev.pfeed.data.db.BlockEntity
import dev.pfeed.domain.filter.Blocklist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class BlockKind { TAG, CREATOR, ITEM }

@Singleton
class BlockRepository @Inject constructor(private val dao: BlockDao) {

    val entries: Flow<List<BlockEntity>> = dao.all()
    val blocklist: Flow<Blocklist> = entries.map(::toBlocklist)

    suspend fun current(): Blocklist = toBlocklist(dao.allOnce())

    suspend fun block(kind: BlockKind, value: String) {
        val v = if (kind == BlockKind.ITEM) value else Blocklist.normalize(value)
        if (v.isBlank()) return
        dao.upsert(BlockEntity(kind.name, v, System.currentTimeMillis()))
    }

    suspend fun unblock(kind: String, value: String) = dao.delete(kind, value)

    suspend fun clearNotInterested() = dao.deleteKind(BlockKind.ITEM.name)

    suspend fun allOnce(): List<BlockEntity> = dao.allOnce()

    private fun toBlocklist(rows: List<BlockEntity>) = Blocklist(
        tags = rows.filter { it.kind == BlockKind.TAG.name }.mapTo(HashSet()) { it.value },
        creators = rows.filter { it.kind == BlockKind.CREATOR.name }.mapTo(HashSet()) { it.value },
        items = rows.filter { it.kind == BlockKind.ITEM.name }.mapTo(HashSet()) { it.value },
    )
}
