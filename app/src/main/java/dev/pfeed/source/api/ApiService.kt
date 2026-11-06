package dev.pfeed.source.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * PLACEHOLDER CONTRACT. These endpoints and DTOs are a neutral template, not any real service's API.
 * When the owner picks the source (see SOURCES.md), replace the paths and DTO fields with that
 * source's documented API and keep [ApiSourceAdapter.toMediaItem] as the single mapping point.
 */
interface ApiService {
    @GET("feed")
    suspend fun feed(
        @Query("cursor") cursor: String?,
        @Query("tag") tag: String?,
        @Query("limit") limit: Int,
    ): ApiPage

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): ApiPage

    @GET("items/{id}/stream")
    suspend fun stream(@Path("id") id: String): ApiStream
}

@Serializable
data class ApiPage(
    val items: List<ApiItem> = emptyList(),
    val next: String? = null,
)

@Serializable
data class ApiItem(
    val id: String,
    /** "video" | "image" | "gif" | "embed" */
    val kind: String = "video",
    val title: String? = null,
    val creator: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("duration") val durationSec: Int? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("page_url") val pageUrl: String,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("embed_url") val embedUrl: String? = null,
    @SerialName("report_url") val reportUrl: String? = null,
)

@Serializable
data class ApiStream(
    val url: String,
    val embed: Boolean = false,
    @SerialName("mime_type") val mimeType: String? = null,
)
