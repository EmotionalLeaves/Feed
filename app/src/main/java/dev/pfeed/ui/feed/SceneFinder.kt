package dev.pfeed.ui.feed

import android.content.Context
import android.graphics.Bitmap
import coil.imageLoader
import dev.pfeed.domain.reco.Scenes
import dev.pfeed.domain.reco.VideoExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds a video's scene cuts ([Scenes]) from its site's preview sheets: loads each sheet once (they're
 * small, and often already cached from scrubbing), reduces every frame to a few average colours, and
 * hands those to [Scenes.cuts].
 */
object SceneFinder {
    /** Longer videos only: in a short clip there's nothing to skip to. */
    const val MIN_DURATION_MS = 4 * 60_000L
    private const val MAX_FRAMES = 400
    private const val MAX_SINGLE_FRAMES = 120

    suspend fun find(context: Context, key: String, timeline: VideoExtras.Timeline, durationMs: Long): List<Long> {
        Scenes.of(key)?.let { return it }
        if (durationMs < MIN_DURATION_MS) return emptyList()
        // One picture per request (KVS): fewer of them, spread over the whole video.
        val cap = if (timeline.cols * timeline.rows == 1) MAX_SINGLE_FRAMES else MAX_FRAMES
        val step = maxOf(timeline.everyMs, durationMs / cap, 1L)
        val count = (durationMs / step).toInt().coerceIn(0, cap)
        // Frames come in order, sheet after sheet: hold only the sheet in use (they're a few MB each).
        var sheetUrl: String? = null
        var sheet: Bitmap? = null
        val frames = ArrayList<FloatArray>(count)
        for (i in 0 until count) {
            val (url, col, row) = timeline.at(i * step)
            if (url != sheetUrl) { sheetUrl = url; sheet = load(context, url) }
            frames += sheet?.let { withContext(Dispatchers.Default) { colours(it, col, row, timeline.cols, timeline.rows) } } ?: break
        }
        // Frames from only part of the video would put the "cuts" in the wrong places: all or nothing.
        val cuts = if (frames.size == count) withContext(Dispatchers.Default) { Scenes.cuts(frames, step) } else emptyList()
        Scenes.put(key, cuts)
        return cuts
    }

    private suspend fun load(context: Context, url: String): Bitmap? = runCatching {
        val result = context.imageLoader.execute(coil.request.ImageRequest.Builder(context).data(url).allowHardware(false).build())
        ((result as? coil.request.SuccessResult)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    }.getOrNull()

    /** The cell's average colour in each part of a [Scenes.GRID]×[Scenes.GRID] grid. */
    private fun colours(sheet: Bitmap, col: Int, row: Int, cols: Int, rows: Int): FloatArray {
        val g = Scenes.GRID
        val cw = sheet.width / cols; val ch = sheet.height / rows
        val x0 = col * cw; val y0 = row * ch
        val out = FloatArray(g * g * 3)
        if (cw < g || ch < g || x0 + cw > sheet.width || y0 + ch > sheet.height) return out
        val pixels = IntArray(cw * ch)
        sheet.getPixels(pixels, 0, cw, x0, y0, cw, ch)
        val n = IntArray(g * g)
        // Every other pixel is plenty for an average.
        var y = 0
        while (y < ch) {
            var x = 0
            while (x < cw) {
                val p = pixels[y * cw + x]
                val cell = (y * g / ch) * g + (x * g / cw)
                out[cell * 3] += ((p shr 16) and 0xFF).toFloat()
                out[cell * 3 + 1] += ((p shr 8) and 0xFF).toFloat()
                out[cell * 3 + 2] += (p and 0xFF).toFloat()
                n[cell]++
                x += 2
            }
            y += 2
        }
        for (c in 0 until g * g) if (n[c] > 0) for (k in 0..2) out[c * 3 + k] /= n[c]
        return out
    }
}
