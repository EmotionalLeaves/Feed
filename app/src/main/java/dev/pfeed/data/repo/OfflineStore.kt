package dev.pfeed.data.repo

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saved posts are kept on the phone, so they open instantly and without a connection. Saving is
 * the one way in: there's no separate "download" button. Files go to the app's private storage
 * (nothing appears in the gallery, and "Erase everything" removes them); unsaving deletes the copy.
 *
 * Downloads run on Wi-Fi only, two at a time, and are retried every so often until they finish.
 * A post that only plays in the site's embed player, or as an HLS stream, can't be kept.
 */
@Singleton
class OfflineStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    private val registry: SourceRegistry,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
) {
    private val dir by lazy { File(context.filesDir, "offline").apply { mkdirs() } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** name hash → finished file. */
    private val index: ConcurrentHashMap<String, File> by lazy {
        ConcurrentHashMap<String, File>().also { map ->
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(PART)) f.delete() else map[f.name.substringBefore('.')] = f
            }
        }
    }

    private val _bytes = MutableStateFlow(0L)
    /** Space the offline copies take. */
    val bytes: StateFlow<Long> = _bytes

    /** The kept copy of [key] (a post, or one page of a comic: "key#3"), if it's finished. */
    fun local(key: String): StreamInfo.Direct? {
        val f = index[hash(key)] ?: return null
        if (!f.exists()) { index.remove(hash(key)); return null }
        return StreamInfo.Direct(Uri.fromFile(f).toString(), mimeOf(f.extension))
    }

    /** Follows the Saved list for the life of the app: keeps what's saved, drops what isn't. */
    fun start() {
        scope.launch {
            val tick = flow { while (true) { emit(Unit); delay(RETRY_MS) } }
            combine(
                library.items(LibraryKind.SAVED),
                settings.flow.map { it.keepSavedOffline }.distinctUntilChanged(),
                tick,
            ) { saved, on, _ -> saved to on }.collect { (saved, on) -> sync(saved, on) }
        }
    }

    private suspend fun sync(saved: List<MediaItem>, on: Boolean) {
        val wanted = if (on) saved.flatMap { item -> parts(item).map { it.first } }.toSet() else emptySet()
        val wantedHashes = wanted.mapTo(HashSet(), ::hash)
        index.entries.removeIf { (h, f) -> (h !in wantedHashes).also { drop -> if (drop) f.delete() } }
        updateSize()
        if (!on || network.isMetered) return
        for (item in saved) {
            if (item.key in running) continue
            if (parts(item).all { (k, _) -> index.containsKey(hash(k)) }) continue
            running += item.key
            scope.launch {
                try { gate.withPermit { keep(item) } } finally { running -= item.key }
            }
        }
    }

    /** What to fetch for a post: its one file, or every page of a comic (key "#i", as the reader asks for them). */
    private fun parts(item: MediaItem): List<Pair<String, String?>> =
        if (item.isGallery) item.pages.mapIndexed { i, url -> "${item.key}#$i" to url } else listOf(item.key to null)

    private suspend fun keep(item: MediaItem) {
        for ((key, pageUrl) in parts(item)) {
            if (index.containsKey(hash(key))) continue
            if (network.isMetered || dir.usableSpace < MIN_FREE_BYTES || _bytes.value > MAX_TOTAL_BYTES) return
            val stream = if (pageUrl != null) StreamInfo.Direct(pageUrl) else resolve(item) ?: return
            val direct = stream as? StreamInfo.Direct ?: return // embed-only: nothing to keep
            if (!direct.url.startsWith("http")) return // already a local file
            if (isHls(direct)) return
            try {
                fetch(key, direct)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return // try again on the next pass
            }
        }
    }

    private suspend fun resolve(item: MediaItem): StreamInfo? = try {
        registry.byId(item.sourceId)?.resolveStream(item)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun fetch(key: String, direct: StreamInfo.Direct) = withContext(Dispatchers.IO) {
        okHttp.newCall(Request.Builder().url(direct.url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("empty")
            val length = body.contentLength()
            if (length > MAX_FILE_BYTES) return@withContext
            val mime = direct.mimeType ?: body.contentType()?.let { "${it.type}/${it.subtype}" }
            val ext = extFor(direct.url, mime)
            val h = hash(key)
            val part = File(dir, "$h$PART")
            part.outputStream().use { out -> body.byteStream().use { it.copyTo(out, 64 * 1024) } }
            if (length > 0 && part.length() != length) { part.delete(); throw java.io.IOException("cut off") }
            val done = File(dir, "$h.$ext")
            if (!part.renameTo(done)) { part.delete(); throw java.io.IOException("rename") }
            index[h] = done
            updateSize()
        }
    }

    private fun updateSize() {
        _bytes.value = index.values.sumOf { it.length() }
    }

    companion object {
        private const val PART = ".part"
        private const val RETRY_MS = 15 * 60_000L
        private const val MAX_FILE_BYTES = 800L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        private const val MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024

        internal fun hash(key: String): String =
            MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun isHls(s: StreamInfo.Direct): Boolean =
            s.mimeType?.contains("mpegurl", ignoreCase = true) == true || s.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

        /** File extension for a download: from the URL if it has a known one, else from the type. */
        internal fun extFor(url: String, mime: String?): String {
            val fromUrl = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            if (fromUrl in KNOWN) return fromUrl
            return when {
                mime == null -> "bin"
                mime.contains("mp4") -> "mp4"
                mime.contains("webm") -> "webm"
                mime.contains("gif") -> "gif"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.startsWith("video/") -> "mp4"
                else -> "bin"
            }
        }

        internal fun mimeOf(ext: String): String? = when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "gif" -> "image/gif"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> null
        }

        private val KNOWN = setOf("mp4", "m4v", "webm", "mov", "gif", "png", "webp", "jpg", "jpeg")
    }
}
