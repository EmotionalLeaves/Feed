@file:UseSerializers(InstantSerializer::class)

package dev.pfeed.domain.model

import android.app.Activity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

@Serializable
enum class MediaType { VIDEO, IMAGE, GIF, EMBED }

@Serializable
data class MediaItem(
    val id: String,
    val sourceId: String,
    val type: MediaType,
    val title: String?,
    val creator: String?,
    val tags: List<String>,
    val durationSec: Int?,
    val thumbnailUrl: String?,
    val pageUrl: String,
    val publishedAt: Instant?,
    /** Adapter-private hints (for example a direct media URL found while parsing a feed). */
    val extras: Map<String, String> = emptyMap(),
) {
    /** Globally unique key across sources. */
    val key: String get() = "$sourceId:$id"
}

data class Page<T>(val items: List<T>, val nextCursor: String?)

sealed interface StreamInfo {
    /** A URL Media3/Coil can open directly (http(s), content://, file://). */
    data class Direct(val url: String, val mimeType: String? = null) : StreamInfo

    /** An official embed-player URL, rendered in the sandboxed WebView. */
    data class Embed(val url: String) : StreamInfo
}

enum class FeedMode { FOR_YOU, FOLLOWING, LATEST }

data class FeedPrefs(
    val mode: FeedMode,
    val tag: String? = null,
    val followedCreators: Set<String> = emptySet(),
    val followedTags: Set<String> = emptySet(),
)

/** Handed to [dev.pfeed.source.SourceAdapter.authenticate]; the activity is only used to launch a Custom Tab. */
class AuthContext(val activity: Activity)

sealed interface AuthResult {
    data object Success : AuthResult
    data object NotRequired : AuthResult
    data class Failure(val message: String) : AuthResult
}

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())
    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}
