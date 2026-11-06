package dev.pfeed.data.repo

import androidx.room.withTransaction
import dev.pfeed.data.db.AppDatabase
import dev.pfeed.data.db.CreatorAffinityEntity
import dev.pfeed.data.db.SignalEntity
import dev.pfeed.data.db.TagAffinityEntity
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.reco.AffinityModel
import dev.pfeed.domain.reco.Signal
import dev.pfeed.domain.reco.SignalWeights
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

data class Affinities(val tags: Map<String, Double>, val creators: Map<String, Double>)

/** Logs interaction signals locally and folds them into decaying tag/creator affinities. */
@Singleton
class SignalRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val signalDao = db.signalDao()
    private val affinityDao = db.affinityDao()

    suspend fun record(item: MediaItem, signal: Signal) {
        val now = System.currentTimeMillis()
        val model = AffinityModel(settings.get().halfLifeDays)
        val delta = SignalWeights.delta(signal)
        val tags = item.tags.map(Blocklist::normalize).filter { it.isNotBlank() }.distinct().take(MAX_TAGS)
        // Spread the signal over the item's tags so heavily-tagged items don't dominate.
        val perTag = if (tags.isEmpty()) 0.0 else delta / sqrt(tags.size.toDouble())
        db.withTransaction {
            signalDao.insert(SignalEntity(itemKey = item.key, type = signal.type.name, value = signal.value, at = now))
            if (tags.isNotEmpty()) {
                val existing = affinityDao.tags(tags).associateBy { it.tag }
                affinityDao.upsertTags(
                    tags.map { t ->
                        val row = existing[t]
                        TagAffinityEntity(t, model.update(row?.score ?: 0.0, row?.updatedAt ?: now, perTag, now), now)
                    },
                )
            }
            item.creator?.let(Blocklist::normalize)?.takeIf { it.isNotBlank() }?.let { c ->
                val row = affinityDao.creator(c)
                affinityDao.upsertCreator(CreatorAffinityEntity(c, model.update(row?.score ?: 0.0, row?.updatedAt ?: now, delta, now), now))
            }
        }
    }

    /** Current affinities, decayed to now. */
    suspend fun affinities(): Affinities {
        val now = System.currentTimeMillis()
        val model = AffinityModel(settings.get().halfLifeDays)
        return Affinities(
            tags = affinityDao.allTags().associate { it.tag to model.decayed(it.score, it.updatedAt, now) },
            creators = affinityDao.allCreators().associate { it.creator to model.decayed(it.score, it.updatedAt, now) },
        )
    }

    suspend fun reset() {
        db.withTransaction {
            signalDao.clear()
            affinityDao.clearTags()
            affinityDao.clearCreators()
        }
    }

    /** Drops raw signals older than 90 days and affinities that have decayed to ~0. */
    suspend fun compact() {
        val now = System.currentTimeMillis()
        signalDao.purge(now - 90L * 86_400_000L)
        val a = affinities()
        affinityDao.deleteTags(a.tags.filterValues { abs(it) < 0.01 }.keys.toList().take(900))
        affinityDao.deleteCreators(a.creators.filterValues { abs(it) < 0.01 }.keys.toList().take(900))
    }

    companion object {
        private const val MAX_TAGS = 15
    }
}
