package dev.pfeed.data.repo

import dev.pfeed.domain.reco.Related
import dev.pfeed.security.SecretStore
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps [Related] (what the sites showed beside videos you enjoyed) between launches, encrypted
 * with the app's keystore key like its other secrets, so co-watching and "Watched with your
 * favourites" don't start from nothing each time.
 */
@Singleton
class RelatedStore @Inject constructor(
    private val secrets: SecretStore,
    private val json: Json,
) {
    fun load() {
        val text = runCatching { secrets.secret(NAME) }.getOrNull() ?: return
        runCatching { Related.restore(json.decodeFromString(Related.Snapshot.serializer(), text)) }
    }

    fun save() {
        if (dev.pfeed.domain.privacy.Incognito.active) return
        runCatching { secrets.putSecret(NAME, json.encodeToString(Related.Snapshot.serializer(), Related.export())) }
    }

    fun clear() {
        Related.clear()
        runCatching { secrets.putSecret(NAME, null) }
    }

    private companion object { const val NAME = "related_v1" }
}
