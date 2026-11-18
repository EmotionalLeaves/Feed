package dev.pfeed.data.repo

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.SourceRegistry
import dev.pfeed.source.parse.MediaTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * Videos download on Wi-Fi only (pictures and comics on any connection), two at a time, retried every so often until they finish.
 * Streamed (HLS) videos are kept too, in the player's offline cache. A post that only plays in the
 * site's embed player can't be kept.
 */
@Singleton
class OfflineStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    private val registry: SourceRegistry,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val mediaCache: dev.pfeed.player.MediaCache,
) {
    /** The app's client (same headers and DNS), without the overall call deadline: a video can take minutes. */
    private val downloads: OkHttpClient by lazy {
        okHttp.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    private val dir by lazy { File(context.filesDir, "offline").apply { mkdirs() } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    /** Downloads under way, by post key: cancelled when the post is unsaved. */
    private val running = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Name hashes of everything currently saved: a download that finishes after its post was unsaved is thrown away. */
    @Volatile private var wantedHashes: Set<String> = emptySet()

    @Volatile private var indexReady = false

    /** name hash → finished file. */
    private val index: ConcurrentHashMap<String, File> by lazy {
        ConcurrentHashMap<String, File>().also { indexReady = true }.also { map ->
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(PART)) f.delete() else map[f.name.substringBefore('.')] = f
            }
        }
    }

    /** Why a post isn't kept yet (for the health check), by post key. */
    private val problems = ConcurrentHashMap<String, String>()
    fun problem(key: String): String? = problems[key]

    private val _bytes = MutableStateFlow(0L)
    /** Space the offline copies take. */
    val bytes: StateFlow<Long> = _bytes

    /** The kept copy of [key] (a post, or one page of a comic: "key#3"), if it's finished. */
    fun local(key: String): StreamInfo.Direct? {
        val f = index[hash(key)] ?: return null
        if (!f.exists()) { index.remove(hash(key)); return null }
        // A kept stream: the marker holds the playlist URL, whose playlist and segments are all in
        // the offline cache, so playing it never touches the network (expired tokens don't matter).
        if (f.extension == HLS_EXT) return hlsUrls.getOrPut(f.name) { f.readText().trim() }.let { StreamInfo.Direct(it, MediaTypes.HLS_MIME) }
        return StreamInfo.Direct(Uri.fromFile(f).toString(), mimeOf(f.extension))
    }

    private val hlsUrls = ConcurrentHashMap<String, String>()

    /** Whether [key] is kept as a plain file, from the in-memory index only (no disk access: safe on the main thread). */
    fun localFileCached(key: String): Boolean = indexReady && index[hash(key)]?.let { it.extension != HLS_EXT && it.extension != "bin" } == true

    /** The kept file itself (not a stream), for exporting; null if not kept or kept as a stream. */
    fun localFile(key: String): File? = index[hash(key)]?.takeIf { it.exists() && it.extension != HLS_EXT && it.extension != "bin" }

    /**
     * Copies the kept file of [key] into the phone's Downloads folder (Android 10+), where other apps
     * and the gallery can see it. Only when the person asks: nothing leaves the app's storage otherwise.
     */
    suspend fun exportToDownloads(key: String, title: String?): Boolean = withContext(Dispatchers.IO) {
        if (android.os.Build.VERSION.SDK_INT < 29) return@withContext false
        val f = localFile(key) ?: return@withContext false
        val safe = (title ?: "post").replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(60).ifBlank { "post" }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "$safe.${f.extension}")
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeOf(f.extension) ?: "application/octet-stream")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
        val ok = runCatching { resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
        if (!ok) { resolver.delete(uri, null, null); return@withContext false }
        values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }

    /** [item] with its thumbnail pointing at the kept copy, when there is one (Saved works offline). */
    fun withLocalThumbnail(item: MediaItem): MediaItem {
        val kept = if (isKept(item)) item.copy(extras = item.extras + (KEPT to "1")) else item
        return withThumb(kept)
    }

    private fun withThumb(item: MediaItem): MediaItem {
        val f = index[hash(thumbKey(item.key))]?.takeIf { it.exists() } ?: return item
        // The original is kept alongside, so a post saved again from here stores its real thumbnail.
        return item.copy(thumbnailUrl = Uri.fromFile(f).toString(), extras = item.extras + (REMOTE_THUMB to item.thumbnailUrl.orEmpty()))
    }

    /** Whether [item] is fully kept (every page, for a comic). */
    fun isKept(item: MediaItem): Boolean = parts(item).all { (k, _) -> index.containsKey(hash(k)) }

    private fun thumbKey(key: String) = "$key@thumb"

    /** Follows the Saved list for the life of the app: keeps what's saved, drops what isn't. */
    fun start() {
        scope.launch { index.size } // build the index off the main thread, before anything asks
        scope.launch {
            val tick = flow { while (true) { emit(Unit); delay(RETRY_MS) } }
            combine(
                library.items(LibraryKind.SAVED),
                library.collectionItems(FOR_THE_ROAD),
                settings.flow.map { it.keepSavedOffline }.distinctUntilChanged(),
                tick,
            ) { saved, road, on, _ ->
                // "For the road" is always kept on the phone, whatever the Saved setting says.
                if (on) (saved + road).distinctBy { it.key } to true else road to road.isNotEmpty()
            }.collect { (keep, on) -> sync(keep, on) }
        }
    }

    private suspend fun sync(saved: List<MediaItem>, on: Boolean) {
        val wanted = if (on) saved.flatMap { item -> parts(item).map { it.first } + thumbKey(item.key) }.toSet() else emptySet()
        val wantedHashes = wanted.mapTo(HashSet(), ::hash)
        this.wantedHashes = wantedHashes
        val savedKeys = if (on) saved.mapTo(HashSet()) { it.key } else emptySet()
        running.entries.removeIf { (k, job) -> (k !in savedKeys).also { stop -> if (stop) job.cancel() } }
        index.entries.removeIf { (h, f) -> (h !in wantedHashes).also { drop -> if (drop) forget(f) } }
        updateSize()
        if (!on) return
        // On mobile data only pictures and comics are kept (small); videos wait for Wi-Fi.
        val metered = network.isMetered
        for (item in saved) {
            if (metered && isVideoish(item)) { problem(item, "waiting for Wi-Fi"); continue }
            if (running.containsKey(item.key)) continue
            if (parts(item).all { (k, _) -> index.containsKey(hash(k)) }) continue
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try { gate.withPermit { keep(item) } } finally { running.remove(item.key, coroutineContext[kotlinx.coroutines.Job]!!) }
            }
            running[item.key] = job
            job.start()
        }
    }

    /** What to fetch for a post: its one file, or every page of a comic (key "#i", as the reader asks for them). */
    private fun parts(item: MediaItem): List<Pair<String, String?>> =
        if (item.isGallery) item.pages.mapIndexed { i, url -> "${item.key}#$i" to url } else listOf(item.key to null)

    private suspend fun keep(item: MediaItem) {
        // Already on the phone (a saved frame): nothing to download.
        if (item.extras["media"]?.startsWith("file:") == true) return
        // The thumbnail first, so Saved looks right offline; it's best-effort and not retried on its own.
        val thumb = thumbKey(item.key)
        if (!index.containsKey(hash(thumb)) && item.thumbnailUrl?.startsWith("http") == true) {
            try { fetch(thumb, StreamInfo.Direct(item.thumbnailUrl)) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
        for ((key, pageUrl) in parts(item)) {
            if (index.containsKey(hash(key))) continue
            when {
                network.isMetered && isVideoish(item) -> return problem(item, "waiting for Wi-Fi")
                dir.usableSpace < MIN_FREE_BYTES -> return problem(item, "phone storage nearly full (${dir.usableSpace shr 20} MB free)")
                _bytes.value > MAX_TOTAL_BYTES -> return problem(item, "offline copies at their ${MAX_TOTAL_BYTES shr 30} GB limit")
            }
            val stream = if (pageUrl != null) StreamInfo.Direct(pageUrl) else resolve(item) ?: return problem(item, "no file found")
            val direct = stream as? StreamInfo.Direct ?: return problem(item, "plays in embed only")
            if (!direct.url.startsWith("http")) return // already a local file
            try {
                if (isHls(direct)) fetchHls(key, direct.url) else fetch(key, direct)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return problem(item, "${e.javaClass.simpleName}: ${e.message}") // try again on the next pass
            }
        }
    }

    private fun problem(item: MediaItem, why: String) { problems[item.key] = why }

    private fun isVideoish(item: MediaItem) =
        item.type == dev.pfeed.domain.model.MediaType.VIDEO || item.type == dev.pfeed.domain.model.MediaType.EMBED

    private suspend fun resolve(item: MediaItem): StreamInfo? = try {
        registry.byId(item.sourceId)?.resolveStream(item)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun fetch(key: String, direct: StreamInfo.Direct) = withContext(Dispatchers.IO) {
        downloads.newCall(Request.Builder().url(direct.url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw java.io.IOException("empty")
            val length = body.contentLength()
            if (length > MAX_FILE_BYTES) throw java.io.IOException("too big: ${length shr 20} MB")
            val mime = direct.mimeType ?: body.contentType()?.let { "${it.type}/${it.subtype}" }
            val ext = extFor(direct.url, mime)
            val h = hash(key)
            val part = File(dir, "$h$PART")
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive() // unsaved meanwhile: stop
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            if (length > 0 && part.length() != length) { part.delete(); throw java.io.IOException("cut off") }
            val done = File(dir, "$h.$ext")
            if (!part.renameTo(done)) { part.delete(); throw java.io.IOException("rename") }
            if (h !in wantedHashes) { done.delete(); return@withContext }
            index[h] = done
            updateSize()
        }
    }

    /**
     * A streamed video: picks one rendition (the best up to the player's height cap), and downloads its
     * playlist and every segment into the offline cache. The marker file records which playlist.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun fetchHls(key: String, url: String) = withContext(Dispatchers.IO) {
        val playlistUrl = okHttp.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("playlist HTTP ${resp.code}")
            val parsed = androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser()
                .parse(Uri.parse(resp.request.url.toString()), resp.body!!.byteStream())
            val multi = parsed as? androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
            if (multi == null || multi.variants.isEmpty()) url
            else {
                val cap = dev.pfeed.player.PlayerPool.maxHeight
                val byHeight = multi.variants.sortedBy { it.format.height.takeIf { h -> h > 0 } ?: Int.MAX_VALUE }
                (byHeight.lastOrNull { it.format.height in 1..cap } ?: byHeight.first()).url.toString()
            }
        }
        val downloader = androidx.media3.exoplayer.hls.offline.HlsDownloader(
            androidx.media3.common.MediaItem.Builder().setUri(playlistUrl).setMimeType(MediaTypes.HLS_MIME).build(),
            mediaCache.offlineWriteFactory,
        )
        try {
            kotlinx.coroutines.runInterruptible { downloader.download(null) }
        } catch (e: Exception) {
            runCatching { downloader.remove() }
            throw e
        }
        val h = hash(key)
        val marker = File(dir, "$h.$HLS_EXT")
        marker.writeText(playlistUrl)
        if (h !in wantedHashes) { forget(marker); return@withContext }
        index[h] = marker
        updateSize()
    }

    /** Deletes a kept copy; for a stream, its playlist and segments in the offline cache too. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun forget(f: File) {
        if (f.extension == HLS_EXT) runCatching {
            val url = f.readText().trim()
            androidx.media3.exoplayer.hls.offline.HlsDownloader(
                androidx.media3.common.MediaItem.Builder().setUri(url).setMimeType(MediaTypes.HLS_MIME).build(),
                mediaCache.offlineWriteFactory,
            ).remove()
            hlsUrls.remove(f.name)
        }
        f.delete()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updateSize() {
        _bytes.value = index.values.sumOf { it.length() } + runCatching { mediaCache.offline.cacheSpace }.getOrDefault(0L)
    }

    companion object {
        private const val PART = ".part"
        private const val HLS_EXT = "hls"
        const val REMOTE_THUMB = "remoteThumb"

        /** Undoes [withLocalThumbnail]: the post as it should be stored. */
        /** Marks a post in Saved whose copy is complete (for the tile's badge). */
        const val KEPT = "kept"

        /** The collection "Take some for the road" fills: always downloaded, to watch offline. */
        const val FOR_THE_ROAD = "For the road"

        fun original(given: MediaItem): MediaItem {
            val item = if (KEPT in given.extras) given.copy(extras = given.extras - KEPT) else given
            val remote = item.extras[REMOTE_THUMB] ?: return item
            return item.copy(thumbnailUrl = remote.ifBlank { null }, extras = item.extras - REMOTE_THUMB)
        }
        private const val RETRY_MS = 15 * 60_000L
        private const val MAX_FILE_BYTES = 800L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        /** Always leave this much of the phone free. */
        private const val MIN_FREE_BYTES = 400L * 1024 * 1024

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
