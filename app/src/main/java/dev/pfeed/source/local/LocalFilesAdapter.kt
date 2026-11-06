package dev.pfeed.source.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.model.FeedPrefs
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.Page
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.SourceAdapter
import dev.pfeed.source.SourceException
import dev.pfeed.source.pageAt
import dev.pfeed.source.sha1Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads images/GIFs/videos from folders the user picks via the Storage Access Framework.
 * Sub-folder names become tags; the immediate parent folder is treated as the "creator".
 * No network access.
 */
@Singleton
class LocalFilesAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : SourceAdapter {
    override val id = "local"
    override val displayName = "Local files"
    override val requiresAuth = false

    private val mutex = Mutex()
    private var cache: List<MediaItem> = emptyList()
    private var cachedFolders: List<String> = emptyList()
    private var cachedAt = 0L

    override suspend fun authenticate(ctx: AuthContext) = AuthResult.NotRequired

    override suspend fun fetchFeed(cursor: String?, prefs: FeedPrefs): Page<MediaItem> {
        var items = scan(force = cursor == null)
        prefs.tag?.let { tag -> items = items.filter { item -> item.tags.any { it.equals(tag, ignoreCase = true) } } }
        return items.pageAt(cursor, PAGE_SIZE)
    }

    override suspend fun search(query: String, cursor: String?): Page<MediaItem> {
        val q = query.trim().lowercase()
        return scan(force = false).filter { item ->
            item.title?.lowercase()?.contains(q) == true || item.tags.any { it.contains(q) }
        }.pageAt(cursor, PAGE_SIZE)
    }

    override suspend fun resolveStream(item: MediaItem): StreamInfo =
        StreamInfo.Direct(item.extras["media"] ?: item.pageUrl, item.extras["mime"])

    override fun reportUrl(item: MediaItem): String? = null

    private suspend fun scan(force: Boolean): List<MediaItem> = mutex.withLock {
        val folders = settings.get().localFolders
        val now = System.currentTimeMillis()
        if (folders == cachedFolders && now - cachedAt < if (force) MIN_RESCAN_MS else CACHE_MS) return cache
        val result = withContext(Dispatchers.IO) {
            folders.flatMap { tree ->
                try {
                    walk(Uri.parse(tree))
                } catch (e: SecurityException) {
                    throw SourceException(id, "Lost access to a folder. Remove and re-add it in Settings → Sources.", cause = e)
                }
            }
        }
        cache = result.sortedByDescending { it.publishedAt }
        cachedFolders = folders
        cachedAt = now
        cache
    }

    private fun walk(tree: Uri): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
        )

        fun visit(docId: String, path: List<String>, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_FILES) {
                    val childId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    val modified = c.getLong(3)
                    if (name.startsWith(".")) continue
                    if (mime == Document.MIME_TYPE_DIR) {
                        visit(childId, path + name, depth + 1)
                        continue
                    }
                    val type = when {
                        mime == "image/gif" -> MediaType.GIF
                        mime.startsWith("video/") -> MediaType.VIDEO
                        mime.startsWith("image/") -> MediaType.IMAGE
                        else -> null
                    } ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId).toString()
                    out += MediaItem(
                        id = sha1Hex(uri),
                        sourceId = id,
                        type = type,
                        title = name.substringBeforeLast('.'),
                        creator = path.lastOrNull(),
                        tags = path.map { it.lowercase() },
                        durationSec = null,
                        thumbnailUrl = uri,
                        pageUrl = uri,
                        publishedAt = if (modified > 0) Instant.ofEpochMilli(modified) else null,
                        extras = mapOf("media" to uri, "mime" to mime),
                    )
                }
            }
        }

        visit(DocumentsContract.getTreeDocumentId(tree), emptyList(), 0)
        return out
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val MAX_DEPTH = 6
        private const val MAX_FILES = 5000
        private const val CACHE_MS = 5 * 60_000L
        private const val MIN_RESCAN_MS = 30_000L
    }
}
