package dev.pfeed.ui.common

import dev.pfeed.data.repo.NetworkMonitor
import dev.pfeed.data.repo.OfflineStore
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.player.PlayerPool
import dev.pfeed.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize

/**
 * What the grids need to play their live previews: a player from the shared pool and the post's
 * file. Files are looked up once and remembered for the session (a kept copy first, then the site).
 */
@Singleton
class LivePreviews @Inject constructor(
    val pool: PlayerPool,
    private val registry: SourceRegistry,
    private val offline: OfflineStore,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
) {
    private val found = ConcurrentHashMap<String, StreamInfo>()

    suspend fun resolve(item: MediaItem): StreamInfo? {
        found[item.key]?.let { return it }
        val s = withContext(Dispatchers.IO) { offline.local(item.key) } ?: try {
            registry.byId(item.sourceId)?.resolveStream(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (s != null) {
            if (found.size > MAX_REMEMBERED) found.clear()
            found[item.key] = s
        }
        return s
    }

    /** On in Settings, and either on Wi-Fi or allowed to autoplay on mobile data. */
    suspend fun allowed(): Boolean {
        val s = settings.flow.first()
        return s.livePreviews && (s.autoplayOnMobileData || !network.isMetered) && !dev.pfeed.player.Battery.saving
    }

    private companion object { const val MAX_REMEMBERED = 300 }
}

/**
 * The grid's live-preview player for [lp], or null while previews are off (Settings, or mobile
 * data without autoplay). Checked again each time the grid comes back on screen.
 */
@androidx.compose.runtime.Composable
fun rememberLivePreview(lp: LivePreviews, playing: Boolean = true): (@androidx.compose.runtime.Composable (MediaItem) -> Unit)? {
    val on by androidx.compose.runtime.produceState(false, lp) { value = lp.allowed() }
    if (!on) return null
    return { item ->
        dev.pfeed.ui.feed.MutedLoop(item, lp.pool, lp::resolve, playing, PREVIEW_MAX_HEIGHT, androidx.compose.ui.Modifier.fillMaxSize())
    }
}

private const val PREVIEW_MAX_HEIGHT = 360

/** Set by a screen whose grids should come alive; [MediaGrid] picks it up. */
val LocalLivePreview = androidx.compose.runtime.staticCompositionLocalOf<(@androidx.compose.runtime.Composable (MediaItem) -> Unit)?> { null }
