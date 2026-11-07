package dev.pfeed.data.repo

import dev.pfeed.data.db.CredentialDao
import dev.pfeed.data.db.CredentialEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last few searches, newest first. Search terms are as private as watch history, so they live
 * in the encrypted database (one key/value row), never in plain preferences.
 */
@Singleton
class RecentSearches @Inject constructor(
    private val dao: CredentialDao,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val _items = MutableStateFlow<List<String>>(emptyList())
    val items: StateFlow<List<String>> = _items

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        _items.value = dao.get(ROW)?.let { row ->
            runCatching { json.decodeFromString(ListSerializer(String.serializer()), row.json) }.getOrNull()
        }.orEmpty()
        loaded = true
    }

    suspend fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        load()
        save((listOf(q) + _items.value.filterNot { it.equals(q, ignoreCase = true) }).take(MAX))
    }

    suspend fun remove(query: String) {
        load()
        save(_items.value.filterNot { it == query })
    }

    suspend fun clear() {
        load()
        save(emptyList())
    }

    private suspend fun save(list: List<String>) = mutex.withLock {
        _items.value = list
        if (list.isEmpty()) dao.delete(ROW)
        else dao.upsert(CredentialEntity(ROW, json.encodeToString(ListSerializer(String.serializer()), list)))
    }

    private companion object {
        const val ROW = "recent-searches"
        const val MAX = 12
    }
}
