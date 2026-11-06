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
import dev.pfeed.domain.reco.Features
import dev.pfeed.domain.reco.Signal
import dev.pfeed.domain.reco.SignalWeights
import dev.pfeed.domain.reco.TasteModel
import dev.pfeed.domain.reco.RankWeights
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class Affinities(val tags: Map<String, Double>, val creators: Map<String, Double>)

/** Logs interaction signals locally and folds them into decaying tag/creator affinities. */
@Singleton
class SignalRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val signalDao = db.signalDao()
    private val affinityDao = db.affinityDao()

    @Volatile private var modelChecked = false

    /**
     * Affinities learned before v2 used a rule that over-credited co-occurring tags and inflated every
     * score; they'd mislead the new model, so start the learned part fresh once. Raw signals stay.
     */
    private suspend fun ensureModel() {
        if (modelChecked) return
        val version = settings.get().tasteModelVersion
        if (version < TASTE_MODEL_VERSION) {
            if (version < 2) {
                db.withTransaction {
                    affinityDao.clearTags()
                    affinityDao.clearCreators()
                }
            }
            // v3: new ranking weights. Saved settings hold the old defaults; move those to the new
            // ones, but keep weights the user tuned by hand.
            settings.update { s ->
                s.copy(
                    weights = if (s.weights.isPre22Default()) RankWeights() else s.weights,
                    tasteModelVersion = TASTE_MODEL_VERSION,
                )
            }
        }
        modelChecked = true
    }

    suspend fun record(item: MediaItem, signal: Signal) {
        ensureModel()
        val now = System.currentTimeMillis()
        val model = AffinityModel(settings.get().halfLifeDays)
        val delta = SignalWeights.delta(signal)
        // Learn from every tag on the item (plus title keywords where tags are thin), error-driven.
        val tags = Features.of(item)
        db.withTransaction {
            signalDao.insert(SignalEntity(itemKey = item.key, type = signal.type.name, value = signal.value, at = now))
            if (tags.isNotEmpty()) {
                val current = affinityDao.tags(tags).associate { it.tag to model.decayed(it.score, it.updatedAt, now) }
                val next = TasteModel.update(tags, current, delta)
                affinityDao.upsertTags(next.map { (t, score) -> TagAffinityEntity(t, score, now) })
            }
            item.creator?.let(Blocklist::normalize)?.takeIf { it.isNotBlank() }?.let { c ->
                val row = affinityDao.creator(c)
                val current = row?.let { model.decayed(it.score, it.updatedAt, now) } ?: 0.0
                val next = TasteModel.update(listOf(c), mapOf(c to current), delta).getValue(c)
                affinityDao.upsertCreator(CreatorAffinityEntity(c, next, now))
            }
        }
    }

    /** Current affinities, decayed to now. */
    suspend fun affinities(): Affinities {
        ensureModel()
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
        const val TASTE_MODEL_VERSION = 3
    }

    /** Strongest learned likes and dislikes, for the "what it has learned" screen. */
    suspend fun topInterests(limit: Int = 25): Pair<List<Pair<String, Double>>, List<Pair<String, Double>>> {
        val a = affinities()
        val all = a.tags.entries.map { it.key to it.value } + a.creators.entries.map { "@" + it.key to it.value }
        val likes = all.filter { it.second > 0.05 }.sortedByDescending { it.second }.take(limit)
        val dislikes = all.filter { it.second < -0.05 }.sortedBy { it.second }.take(limit)
        return likes to dislikes
    }
}
