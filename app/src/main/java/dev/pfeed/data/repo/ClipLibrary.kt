package dev.pfeed.data.repo

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.player.ClipMaker
import javax.inject.Inject
import javax.inject.Singleton

/** Makes clips ([ClipMaker]) and keeps them in Saved → Clips as videos of their own. */
@Singleton
class ClipLibrary @Inject constructor(
    private val clips: ClipMaker,
    private val library: LibraryRepository,
) {
    val lastError: String? get() = clips.lastError

    /** A [lengthMs] clip of [item] from [atMs], saved; null if it couldn't be cut. */
    suspend fun save(item: MediaItem, stream: StreamInfo.Direct, atMs: Long, lengthMs: Long = CLIP_MS): MediaItem? {
        val end = item.durationSec?.let { minOf(atMs + lengthMs, it * 1000L) } ?: (atMs + lengthMs)
        val file = runCatching { clips.clip(stream.url, stream.mimeType, atMs, end) }.getOrNull() ?: return null
        val uri = android.net.Uri.fromFile(file).toString()
        val clip = MediaItem(
            id = "clip/" + file.nameWithoutExtension,
            sourceId = "direct",
            type = MediaType.VIDEO,
            title = (item.title?.let { "$it · " } ?: "") + "clip at " + dev.pfeed.ui.common.formatDuration((atMs / 1000).toInt()),
            creator = item.creator,
            tags = item.tags,
            durationSec = ((end - atMs) / 1000).toInt(),
            thumbnailUrl = item.thumbnailUrl,
            pageUrl = item.pageUrl,
            publishedAt = java.time.Instant.now(),
            extras = mapOf("media" to uri, "mime" to "video/mp4"),
        )
        runCatching {
            library.add(LibraryKind.SAVED, clip)
            library.addToCollection(CLIPS, clip)
        }
        return clip
    }

    companion object {
        const val CLIPS = "Clips"
        const val CLIP_MS = 30_000L
    }
}
