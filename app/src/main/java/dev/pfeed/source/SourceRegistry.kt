package dev.pfeed.source

import dev.pfeed.data.settings.AppSettings
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.data.settings.SourceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class RegisteredSource(val adapter: SourceAdapter, val enabled: Boolean)

@Singleton
class SourceRegistry @Inject constructor(
    adapters: Set<@JvmSuppressWildcards SourceAdapter>,
    private val settings: SettingsRepository,
) {
    val all: List<SourceAdapter> = adapters.sortedBy { it.id }

    fun byId(id: String): SourceAdapter? = all.firstOrNull { it.id == id }

    fun displayName(id: String): String = byId(id)?.displayName ?: id

    /** All adapters in user priority order with their enabled state. */
    fun resolve(s: AppSettings): List<RegisteredSource> {
        val configured = s.sources.mapNotNull { c -> byId(c.id)?.let { RegisteredSource(it, c.enabled && it.isConfigured) } }
        val missing = all.filter { a -> s.sources.none { it.id == a.id } }.map { RegisteredSource(it, it.isConfigured) }
        return configured + missing
    }

    fun observe(): Flow<List<RegisteredSource>> = settings.flow.map(::resolve)

    suspend fun enabledAdapters(): List<SourceAdapter> =
        resolve(settings.get()).filter { it.enabled }.map { it.adapter }

    suspend fun setEnabled(id: String, enabled: Boolean) = settings.update { s ->
        s.copy(sources = resolve(s).map { SourceConfig(it.adapter.id, if (it.adapter.id == id) enabled else it.enabled) })
    }

    /** Moves a source up (delta = -1) or down (+1) in priority. */
    suspend fun move(id: String, delta: Int) = settings.update { s ->
        val list = resolve(s).map { SourceConfig(it.adapter.id, it.enabled) }.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i >= 0 && j in list.indices) {
            val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        }
        s.copy(sources = list)
    }
}
