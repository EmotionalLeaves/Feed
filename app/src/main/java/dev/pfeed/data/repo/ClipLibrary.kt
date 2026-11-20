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
            // Where it came from, for "Watch the full video".
            extras = mapOf("media" to uri, "mime" to "video/mp4", FROM to item.key, FROM_AT to atMs.toString()),
        )
        runCatching {
            library.add(LibraryKind.SAVED, clip)
            library.addToCollection(CLIPS, clip)
        }
        return clip
    }

    /**
     * "Make a reel": [parts] (clips in Saved → Clips) joined into one video, kept there too.
     * Null if there's nothing to join or it couldn't be made.
     */
    suspend fun reel(parts: List<MediaItem>): MediaItem? {
        val files = parts.mapNotNull { p -> p.extras["media"]?.takeIf { it.startsWith("file:") }?.let { android.net.Uri.parse(it).path }?.let { java.io.File(it) }?.takeIf { it.exists() } }
        if (files.size < 2) return null
        val file = clips.reel(files) ?: return null
        val reel = MediaItem(
            id = "clip/" + file.nameWithoutExtension,
            sourceId = "direct",
            type = MediaType.VIDEO,
            title = "Your reel · ${files.size} clips",
            creator = null,
            tags = parts.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key },
            durationSec = parts.sumOf { it.durationSec ?: 0 }.takeIf { it > 0 },
            thumbnailUrl = parts.first().thumbnailUrl,
            pageUrl = "",
            publishedAt = java.time.Instant.now(),
            extras = mapOf("media" to android.net.Uri.fromFile(file).toString(), "mime" to "video/mp4"),
        )
        runCatching {
            library.add(LibraryKind.SAVED, reel)
            library.addToCollection(CLIPS, reel)
        }
        return reel
    }

    companion object {
        const val FROM = "from"
        const val FROM_AT = "fromAt"
        const val CLIPS = "Clips"
        const val CLIP_MS = 30_000L
    }
}
