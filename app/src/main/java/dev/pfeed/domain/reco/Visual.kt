package dev.pfeed.domain.reco

import java.util.Base64
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a post looks like, in a few numbers read off its thumbnail (no AI model, nothing leaves the
 * phone): colour and brightness in a 3×3 grid, how much of it is skin-coloured, how flat or busy it
 * is, how many distinct colours. Enough to tell drawn from photographed, studio-lit from phone-lit,
 * close-up from wide, a redhead's palette from a dark room — the things tags often don't say,
 * least of all on Reddit, where a post's only tag is its subreddit.
 */
object Visual {
    /** 9 cells × (hue x, hue y, saturation, brightness) + 6 whole-picture measures. */
    const val DIM = 9 * 4 + 6
    const val SIDE = 16

    /** Describe a [SIDE]×[SIDE] ARGB thumbnail. */
    fun describe(pixels: IntArray, w: Int = SIDE, h: Int = SIDE): FloatArray {
        val out = FloatArray(DIM)
        val cells = Array(9) { DoubleArray(5) } // hx, hy, s, v, n
        var skin = 0; var sat = 0.0; var edges = 0.0; var vSum = 0.0; var vSq = 0.0
        val palette = HashSet<Int>()
        val value = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = pixels[y * w + x]
            val r = (p shr 16 and 0xFF) / 255.0; val g = (p shr 8 and 0xFF) / 255.0; val b = (p and 0xFF) / 255.0
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val v = mx; val s = if (mx > 0) (mx - mn) / mx else 0.0
            val hue = when {
                mx == mn -> 0.0
                mx == r -> ((g - b) / (mx - mn)).mod(6.0)
                mx == g -> (b - r) / (mx - mn) + 2
                else -> (r - g) / (mx - mn) + 4
            } * Math.PI / 3
            val c = cells[(y * 3 / h) * 3 + (x * 3 / w)]
            c[0] += cos(hue) * s; c[1] += sin(hue) * s; c[2] += s; c[3] += v; c[4] += 1.0
            value[y * w + x] = v
            sat += s; vSum += v; vSq += v * v
            // Skin: the classic RGB rule (works across skin tones under ordinary light).
            val R = r * 255; val G = g * 255; val B = b * 255
            if (R > 95 && G > 40 && B > 20 && R > G && R > B && abs(R - G) > 15 && (max(R, max(G, B)) - min(R, min(G, B))) > 15) skin++
            palette += ((p shr 20 and 0xF) shl 8) or ((p shr 12 and 0xF) shl 4) or (p shr 4 and 0xF)
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (x + 1 < w) edges += abs(value[y * w + x] - value[y * w + x + 1])
            if (y + 1 < h) edges += abs(value[y * w + x] - value[(y + 1) * w + x])
        }
        val n = (w * h).toDouble()
        for (i in 0 until 9) {
            val c = cells[i]; val k = c[4].coerceAtLeast(1.0)
            out[i * 4] = (c[0] / k).toFloat(); out[i * 4 + 1] = (c[1] / k).toFloat()
            out[i * 4 + 2] = (c[2] / k).toFloat(); out[i * 4 + 3] = (c[3] / k).toFloat()
        }
        val mean = vSum / n
        out[36] = (skin / n).toFloat()
        out[37] = (sat / n).toFloat()
        out[38] = mean.toFloat()
        out[39] = sqrt((vSq / n - mean * mean).coerceAtLeast(0.0)).toFloat() * 2
        out[40] = (edges / (2 * n) * 4).coerceAtMost(1.0).toFloat()
        out[41] = (palette.size / n).toFloat()
        return out
    }

    /** One byte per number, as a short string to ride along in a post's extras. */
    fun encode(d: FloatArray): String {
        val bytes = ByteArray(d.size) { i -> (((d[i].coerceIn(-1f, 1f) + 1f) / 2f) * 255f + 0.5f).toInt().toByte() }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(s: String?): FloatArray? {
        if (s.isNullOrEmpty()) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull() ?: return null
        if (bytes.size != DIM) return null
        return FloatArray(DIM) { i -> (bytes[i].toInt() and 0xFF) / 255f * 2f - 1f }
    }

    const val EXTRA = "vis"

    /** 0..1: how alike two pictures look (1 = the same colours, light and detail). */
    fun similarity(a: FloatArray, b: FloatArray): Double {
        var d = 0.0
        for (i in a.indices) { val x = (a[i] - b[i]).toDouble(); d += x * x }
        return kotlin.math.exp(-d / SIMILAR_SCALE)
    }
    private const val SIMILAR_SCALE = 1.5

    /** How the user's eye works: a small linear model from looks to how much they stay, learned as they watch. */
    class Taste {
        val w = DoubleArray(DIM + 1)
        var seen = 0
            private set

        fun score(d: FloatArray): Double {
            // Without the overall level (the intercept): only how this picture differs counts.
            var s = 0.0
            for (i in 0 until DIM) s += w[i] * d[i]
            // Until it has seen enough, it says little.
            val trust = seen / (seen + TRUST_AFTER)
            return kotlin.math.tanh(s) * trust
        }

        fun learn(d: FloatArray, target: Double) {
            val y = (target / 2.0).coerceIn(-1.0, 1.0)
            var p = w[DIM]
            for (i in 0 until DIM) p += w[i] * d[i]
            val err = y - kotlin.math.tanh(p)
            for (i in 0 until DIM) w[i] = (w[i] + RATE * err * d[i] - DECAY * w[i]).coerceIn(-3.0, 3.0)
            w[DIM] += RATE * err
            seen++
        }

        fun reset() { w.fill(0.0); seen = 0 }

        /**
         * Your eye in words: the looks you stay longer for, and the ones you don't, strongest first
         * (empty until it has seen a couple of hundred posts).
         */
        fun describe(): Pair<List<String>, List<String>> {
            if (seen < DESCRIBE_AFTER) return emptyList<String>() to emptyList()
            val named = WORDS.map { (i, words) -> Triple(words.first, words.second, w[i]) }.filter { abs(it.third) >= DESCRIBE_MIN }
            val more = named.sortedByDescending { abs(it.third) }.map { if (it.third > 0) it.first else it.second }
            val less = named.sortedByDescending { abs(it.third) }.map { if (it.third > 0) it.second else it.first }
            return more.take(3) to less.take(3)
        }

        fun writeTo(out: java.io.DataOutputStream) { out.writeInt(seen); w.forEach { out.writeDouble(it) } }
        fun readFrom(input: java.io.DataInputStream) { seen = input.readInt(); for (i in w.indices) w[i] = input.readDouble() }

        companion object {
            const val RATE = 0.03
            const val DECAY = 0.0005
            const val TRUST_AFTER = 60.0
            const val DESCRIBE_AFTER = 200
            const val DESCRIBE_MIN = 0.25
            /** The whole-picture measures, each with words for more of it and less of it. */
            private val WORDS = listOf(
                36 to ("lots of skin" to "less skin"),
                37 to ("vivid colour" to "muted colour"),
                38 to ("bright pictures" to "dark, moody ones"),
                39 to ("strong contrast" to "soft light"),
                40 to ("busy, detailed scenes" to "simple, clean ones"),
                41 to ("photos" to "drawn art"),
            )
        }
    }

    /** The picture's colours as a hue angle, for tests and debugging. */
    internal fun hueOf(d: FloatArray, cell: Int): Double = atan2(d[cell * 4 + 1].toDouble(), d[cell * 4].toDouble())
}
