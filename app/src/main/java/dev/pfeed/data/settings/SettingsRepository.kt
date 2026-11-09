package dev.pfeed.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val key = stringPreferencesKey("app_settings")

    val flow: Flow<AppSettings> = context.settingsStore.data
        .map(::decode)
        .distinctUntilChanged()

    suspend fun get(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs ->
            prefs[key] = json.encodeToString(AppSettings.serializer(), transform(decode(prefs)))
        }
    }

    /** Last raw JSON and what it decoded to: settings hold thousands of feeds and every fetch reads them. */
    @Volatile private var lastDecoded: Pair<String?, AppSettings>? = null

    private fun decode(prefs: Preferences): AppSettings {
        val raw = prefs[key]
        lastDecoded?.let { (r, s) -> if (r == raw) return s }
        val decoded = (raw?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
            ?: AppSettings()).let(::migrate)
        lastDecoded = raw to decoded
        return decoded
    }

    /**
     * One-time cleanups for settings saved by older versions. Pure and idempotent: applied on every
     * read until the next write persists the result with the new version number.
     */
    private fun migrate(s: AppSettings): AppSettings {
        var out = s
        if (out.contentFilterVersion < 1) {
            // v1: drop sites that only host non-adult content (they were in older defaults).
            out = out.copy(
                booruFeeds = out.booruFeeds.filterNot { DefaultSources.isSfwOnlyHost(it.host) },
                kemonoHosts = out.kemonoHosts.filterNot { DefaultSources.isSfwOnlyHost(it) },
                contentFilterVersion = 1,
            )
        }
        if (out.contentFilterVersion < LIBRARY_VERSION) {
            // v2+: merge the (grown) default feed library into existing installs, so saved settings
            // from before the library grew still pick up the new sources. The user's own entries and
            // ordering come first; defaults are appended and de-duplicated; SFW-only and excluded
            // hosts stay out. Bump LIBRARY_VERSION whenever DefaultSources/SourceCatalog grows.
            out = out.copy(
                redditSubs = (out.redditSubs + DefaultSources.redditSubs)
                    .distinctBy { it.trim().lowercase() },
                mastodonFeeds = (out.mastodonFeeds + DefaultSources.mastodonFeeds)
                    .filterNot { MastodonExclusions.isExcluded(it.host) }
                    .distinctBy { it.host.trim().lowercase() + "|" + (it.tag?.trim()?.lowercase() ?: "") },
                booruFeeds = (out.booruFeeds + DefaultSources.booruFeeds)
                    .filterNot { DefaultSources.isSfwOnlyHost(it.host) }
                    .distinctBy { it.host.trim().lowercase() + "|" + (it.tags?.trim()?.lowercase() ?: "") },
                // coomer.su stopped resolving in 2025; the site lives on at coomer.st.
                kemonoHosts = (out.kemonoHosts.map { if (it.trim().lowercase().removePrefix("https://").removePrefix("www.").trim('/') in MOVED_COOMER) "coomer.st" else it } + DefaultSources.kemonoHosts)
                    .filterNot { DefaultSources.isSfwOnlyHost(it) }
                    .distinctBy { it.trim().lowercase() },
                contentFilterVersion = LIBRARY_VERSION,
            )
        }
        return out
    }

    companion object {
        /** Version of the built-in source library merged into saved settings (see [migrate]). */
        const val LIBRARY_VERSION = 3
        private val MOVED_COOMER = setOf("coomer.su", "coomer.party")
    }
}
