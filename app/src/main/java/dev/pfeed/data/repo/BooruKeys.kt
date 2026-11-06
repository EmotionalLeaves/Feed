package dev.pfeed.data.repo

import dev.pfeed.data.db.CredentialDao
import dev.pfeed.data.db.CredentialEntity
import dev.pfeed.source.parse.BooruFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BooruKey(val userId: String, val apiKey: String)

/**
 * API credentials for boards that no longer allow anonymous API use (rule34.xxx, gelbooru.com).
 * Stored only in the encrypted database, never in exported settings.
 */
@Singleton
class BooruKeys @Inject constructor(
    private val dao: CredentialDao,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var cache: Map<String, BooruKey>? = null
    private val _configured = MutableStateFlow<Set<String>>(emptySet())
    /** Hosts (from [NEEDS_KEY]) that have a key saved. */
    val configured: StateFlow<Set<String>> = _configured

    suspend fun get(host: String): BooruKey? = load()[keyHost(host)]

    /** True when the board needs a key and none is saved, so its feeds should be skipped. */
    suspend fun missing(host: String): Boolean {
        val k = keyHost(host)
        return k in NEEDS_KEY && load()[k] == null
    }

    suspend fun set(host: String, key: BooruKey) = mutex.withLock {
        val k = keyHost(host)
        dao.upsert(CredentialEntity(rowId(k), json.encodeToString(BooruKey.serializer(), key)))
        cache = (cache ?: emptyMap()) + (k to key)
        _configured.value = cache!!.keys
    }

    suspend fun clear(host: String) = mutex.withLock {
        val k = keyHost(host)
        dao.delete(rowId(k))
        cache = (cache ?: emptyMap()) - k
        _configured.value = cache!!.keys
    }

    /** Load once so the flow is populated for the settings screen. */
    suspend fun refresh() { load() }

    private suspend fun load(): Map<String, BooruKey> = mutex.withLock {
        cache ?: NEEDS_KEY.keys.mapNotNull { host ->
            dao.get(rowId(host))?.let { row ->
                runCatching { json.decodeFromString(BooruKey.serializer(), row.json) }.getOrNull()?.let { host to it }
            }
        }.toMap().also {
            cache = it
            _configured.value = it.keys
        }
    }

    private fun rowId(host: String) = "booru-key:$host"

    companion object {
        /** Boards whose API refuses anonymous requests, with where to get a key. */
        val NEEDS_KEY: Map<String, String> = linkedMapOf(
            "rule34.xxx" to "https://rule34.xxx/index.php?page=account&s=options",
            "gelbooru.com" to "https://gelbooru.com/index.php?page=account&s=options",
        )

        fun keyHost(host: String): String = BooruFamily.bareHost(host).removePrefix("api.")

        private val API_KEY = Regex("api_key=([^&\\s]+)")
        private val USER_ID = Regex("user_id=([^&\\s]+)")

        /**
         * Parses what the board's account page shows, e.g. `&api_key=abc123&user_id=456`. Also accepts
         * "key id" or "id key" pasted as two words.
         */
        fun parse(text: String): BooruKey? {
            val t = text.trim()
            val key = API_KEY.find(t)?.groupValues?.get(1)
            val user = USER_ID.find(t)?.groupValues?.get(1)
            if (key != null && user != null) return BooruKey(user, key)
            val parts = t.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }
            if (parts.size == 2) {
                val (a, b) = parts
                return when {
                    a.all(Char::isDigit) && !b.all(Char::isDigit) -> BooruKey(a, b)
                    b.all(Char::isDigit) && !a.all(Char::isDigit) -> BooruKey(b, a)
                    else -> null
                }
            }
            return null
        }
    }
}
