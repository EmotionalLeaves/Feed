package dev.pfeed.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Cuts a short clip out of a video into a file on the phone ("Save a clip"): only that stretch is
 * fetched (through the app's own cache and connection), copied as-is where the file allows and
 * re-encoded only at the edges, so a 30-second clip of a long scene is a few MB and plays offline.
 */
@OptIn(UnstableApi::class)
@Singleton
class ClipMaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaCache: MediaCache,
) {
    /** The clip as a file, or null if it couldn't be made (the cause in [lastError]). */
    suspend fun clip(url: String, mimeType: String?, startMs: Long, endMs: Long): File? = withContext(Dispatchers.Main) {
        val dir = File(context.filesDir, "clips").apply { mkdirs() }
        val out = File(dir, "clip-${System.currentTimeMillis()}.mp4")
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { mimeType?.let { setMimeType(it) } }
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
            .build()
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setAssetLoaderFactory(
                    DefaultAssetLoaderFactory(
                        context,
                        DefaultDecoderFactory.Builder(context).build(),
                        Clock.DEFAULT,
                        DefaultMediaSourceFactory(mediaCache.dataSourceFactory),
                        DataSourceBitmapLoader(context),
                    ),
                )
                .experimentalSetTrimOptimizationEnabled(true)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(out)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        lastError = exportException.errorCodeName
                        out.delete()
                        if (cont.isActive) cont.resume(null)
                    }
                })
                .build()
            cont.invokeOnCancellation { runCatching { transformer.cancel() }; out.delete() }
            runCatching { transformer.start(EditedMediaItem.Builder(item).build(), out.absolutePath) }
                .onFailure { lastError = it.message; if (cont.isActive) cont.resume(null) }
        }
    }

    /**
     * One video of [parts] (files this app made) played one after another: "Make a reel". Each is
     * scaled to the same height so they join, and a part without sound gets silence.
     */
    suspend fun reel(parts: List<File>, height: Int = REEL_HEIGHT): File? = withContext(Dispatchers.Main) {
        if (parts.isEmpty()) return@withContext null
        val dir = File(context.filesDir, "clips").apply { mkdirs() }
        val out = File(dir, "reel-${System.currentTimeMillis()}.mp4")
        val items = parts.map { f ->
            EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(f)))
                .setEffects(androidx.media3.transformer.Effects(emptyList(), listOf(androidx.media3.effect.Presentation.createForHeight(height))))
                .build()
        }
        val composition = Composition.Builder(androidx.media3.transformer.EditedMediaItemSequence(items))
            .experimentalSetForceAudioTrack(true)
            .build()
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(out)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        lastError = exportException.errorCodeName
                        out.delete()
                        if (cont.isActive) cont.resume(null)
                    }
                })
                .build()
            cont.invokeOnCancellation { runCatching { transformer.cancel() }; out.delete() }
            runCatching { transformer.start(composition, out.absolutePath) }
                .onFailure { lastError = it.message; if (cont.isActive) cont.resume(null) }
        }
    }

    @Volatile var lastError: String? = null
        private set

    private companion object { const val REEL_HEIGHT = 720 }
}
