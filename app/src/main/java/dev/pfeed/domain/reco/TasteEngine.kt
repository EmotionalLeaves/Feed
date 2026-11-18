package dev.pfeed.domain.reco

import dev.pfeed.domain.model.MediaItem
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The taste engine: what For You knows about you beyond tag-by-tag weights.
 *
 * Three ideas, all learned on the phone from what you do:
 *
 * 1. **A map of meaning** ([TagSpace]). Every tag gets a position learned from which tags appear
 *    together across everything the feed has seen (random indexing: cheap, incremental, no training
 *    run). Tags that keep company end up close, so liking "shibari" also lifts posts tagged "rope"
 *    or "ties" that you never touched, and a post with one vague tag still lands somewhere sensible.
 *
 * 2. **Several interests, not one average** ([Interest]). Your engaged posts are clustered on that
 *    map into a handful of interests, each with its own strength. One averaged taste collapses onto
 *    whatever you watched most; separate interests keep each of your tastes alive, and the feed
 *    takes turns between them in proportion to how much you like each.
 *
 * 3. **The mood right now** ([session]). A fast-moving vector of the last few things you lingered
 *    on, so a change of mood mid-session is followed within a few posts instead of a few batches.
 *
 * Everything is plain arrays and maps: a few hundred KB, fast enough to score a pool of a thousand
 * candidates on every load.
 */
class TasteEngine(val space: TagSpace = TagSpace()) {
    /**
     * One interest: a direction on the map, and how it's been going lately as a running tally of
     * hits ([alpha]) and misses ([beta]) for posts near it, both fading so recent reactions count
     * most. Judged by its *rate* (how often its posts land), not by volume: a taste you watched a
     * thousand times last week doesn't drown out the one you've started loving today.
     */
    class Interest(val centroid: FloatArray, var alpha: Double, var beta: Double = 1.0, var hits: Int = 1) {
        /** Faded sum of reactions (targets) to posts near it, and how many: their mean is how much you enjoy it. */
        var sum: Double = alpha - 1.0
        var count: Double = 1.0
        val rate: Double get() = alpha / (alpha + beta)
        /** How sure we are it's real: grows with evidence. */
        val confidence: Double get() = alpha / (alpha + 2.0)
        val mean: Double get() = sum / count
        /** Stride-scheduling position: the interest with the lowest pass serves the next slot. */
        var pass: Double = 0.0
    }

    val interests = ArrayList<Interest>()
    /** Directions you've shown you don't want (posts skipped hard, Not interested). */
    val dislikes = ArrayList<FloatArray>()
    /** The current mood: recent positive posts, fading fast. */
    var session: FloatArray? = null
        private set
    private var sessionStrength = 0.0
    private var events = 0

    /** Learn the map from posts passing through the feed (whether or not they're shown). */
    fun observe(items: Collection<MediaItem>) = items.forEach { space.observe(Features.of(it)) }

    fun vectorOf(item: MediaItem): FloatArray? = space.itemVector(Features.of(item))

    /**
     * One reaction to [item]: [target] as from [Engagement] / [SignalWeights] (about −1 skip … +3
     * like … −8 for Not interested).
     */
    fun learn(item: MediaItem, target: Double) {
        val x = vectorOf(item) ?: return
        events++
        val near = interests.maxByOrNull { cos(it.centroid, x) }
        val sim = near?.let { cos(it.centroid, x) } ?: -1.0
        if (near != null && sim >= ATTRIBUTE) {
            // The interest this post belongs to: its tally fades a little, then counts this one.
            near.alpha *= FADE; near.beta *= FADE
            if (target > 0) near.alpha += target * sim else near.beta += -target * sim
            near.sum = near.sum * FADE + target * sim
            near.count = near.count * FADE + sim
        }
        if (target > POSITIVE) {
            if (near != null && sim >= MERGE) {
                blend(near.centroid, x, (LEARN_RATE * target / (1 + near.hits * 0.05)).toFloat())
                near.hits++
            } else if (target >= SPAWN) {
                // Something new you clearly liked: a tentative interest, which has to prove itself
                // (one lingering view while the phone was down shouldn't take over the feed).
                if (interests.size >= MAX_INTERESTS) interests.remove(interests.minBy { it.mean * it.confidence })
                interests += Interest(x.copyOf(), alpha = NEW_ALPHA + 0.25 * target, beta = NEW_BETA).also { it.sum = target * NEW_WEIGHT; it.count = NEW_WEIGHT }
            }
            // The mood moves toward this post.
            val s = session
            if (s == null) { session = x.copyOf(); sessionStrength = target }
            else { blend(s, x, SESSION_RATE.toFloat()); sessionStrength = sessionStrength * 0.8 + target }
        } else if (target < NEGATIVE) {
            if (target <= HARD_NO) {
                dislikes += x.copyOf()
                if (dislikes.size > MAX_DISLIKES) dislikes.removeAt(0)
            }
            // The mood cools when you skip what matches it.
            val s = session
            if (s != null && cos(s, x) > MERGE) sessionStrength *= 0.6
            sessionStrength *= 0.9
        }
        // Interests that keep missing are dropped.
        // An interest that keeps missing stops being served; it stays a while as something to avoid.
        if (interests.size > MAX_INTERESTS) interests.remove(interests.minBy { it.mean * it.confidence })
    }

    /** How well [x] fits what you like: the best interest's match, scaled by how well it's going (0..1). */
    fun interestScore(x: FloatArray): Double {
        var best = 0.0
        for (i in interests) {
            if (i.mean <= 0) continue
            val sim = cos(i.centroid, x)
            if (sim <= 0) continue
            best = max(best, sim * i.rate * (0.5 + 0.5 * i.confidence))
        }
        return best
    }

    /**
     * How much [x] looks like something you've turned away from: a "Not interested" direction, or
     * an interest that has gone sour (you keep skipping what's near it). 0..1.
     */
    fun aversion(x: FloatArray): Double {
        var worst = dislikes.maxOfOrNull { cos(it, x) }?.coerceAtLeast(0.0) ?: 0.0
        for (i in interests) if (i.mean < 0) worst = max(worst, cos(i.centroid, x).coerceAtLeast(0.0) * (-i.mean).coerceAtMost(1.0))
        return worst
    }

    fun moodScore(x: FloatArray): Double {
        val s = session ?: return 0.0
        val strength = sessionStrength / (sessionStrength + 2.0)
        return cos(s, x).coerceAtLeast(0.0) * strength
    }

    fun dislikeScore(x: FloatArray): Double = dislikes.maxOfOrNull { cos(it, x) }?.coerceAtLeast(0.0) ?: 0.0

    /** Which interest the next slot serves (see inside). Null when nothing's been learned yet. */
    fun pickInterest(random: kotlin.random.Random): Interest? {
        val liked = interests.filter { it.mean > SERVE_MIN }
        if (liked.isEmpty()) return null
        // Each interest's share of slots: a Thompson draw of how much you enjoy it (its mean, give
        // or take how unsure we still are), sharpened so the favourite leads without starving the
        // rest. Slots are then dealt out by stride scheduling: whoever is furthest behind its share
        // goes next. Your tastes interleave (A B C A B A C…) instead of running in streaks, and a
        // taste that starts missing loses share at once.
        val draws = liked.associateWith { i -> kotlin.math.exp((i.mean + gaussian(random) * SPREAD / sqrt(i.count + 1.0)) / TEMPERATURE) }
        val total = draws.values.sum()
        val minPass = liked.minOf { it.pass }
        liked.forEach { if (it.pass < minPass) it.pass = minPass }
        val chosen = liked.minBy { it.pass }
        chosen.pass += total / draws.getValue(chosen)
        // Keep the passes from drifting apart forever (a new interest starts level with the rest).
        val floor = liked.minOf { it.pass }
        interests.forEach { it.pass -= floor }
        return chosen
    }

    /**
     * Tags to search for, one per interest (strongest first): the tag that best names each interest
     * (closest to its centre, among tags you've actually engaged with), so searches keep every one of
     * your tastes supplied, not just the top one.
     */
    fun searchTags(affinity: Map<String, Double>, limit: Int = 3): List<String> {
        val liked = affinity.filter { it.value > 0.3 && !Features.isMeta(it.key) && !it.key.startsWith("w:") }
        if (liked.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (i in interests.sortedByDescending { it.rate * it.confidence }) {
            val tag = liked.keys.filter { it !in out }.maxByOrNull { t ->
                val v = space.tagVector(t) ?: return@maxByOrNull -1.0
                cos(i.centroid, v) + 0.05 * (liked[t] ?: 0.0)
            } ?: continue
            out += tag
            if (out.size >= limit) break
        }
        return out
    }

    /** Forget everything learned (Settings → reset what For You learned). The map of meaning stays. */
    fun resetTaste() {
        interests.clear(); dislikes.clear(); session = null; sessionStrength = 0.0
    }

    fun writeTo(out: java.io.DataOutputStream) {
        out.writeInt(FORMAT)
        space.writeTo(out)
        out.writeInt(interests.size)
        for (i in interests) {
            writeVec(out, i.centroid)
            out.writeDouble(i.alpha); out.writeDouble(i.beta); out.writeDouble(i.sum); out.writeDouble(i.count); out.writeInt(i.hits)
        }
        out.writeInt(dislikes.size); dislikes.forEach { writeVec(out, it) }
        val s = session
        out.writeBoolean(s != null); if (s != null) writeVec(out, s)
        out.writeDouble(sessionStrength)
    }

    fun readFrom(input: java.io.DataInputStream) {
        if (input.readInt() != FORMAT) return
        space.readFrom(input)
        interests.clear()
        repeat(input.readInt()) {
            val c = readVec(input)
            interests += Interest(c, input.readDouble(), input.readDouble()).also { i ->
                i.sum = input.readDouble(); i.count = input.readDouble(); i.hits = input.readInt()
            }
        }
        dislikes.clear(); repeat(input.readInt()) { dislikes += readVec(input) }
        session = if (input.readBoolean()) readVec(input) else null
        sessionStrength = input.readDouble()
    }

    companion object {
        private const val FORMAT = 1

        internal fun writeVec(out: java.io.DataOutputStream, v: FloatArray) { out.writeShort(v.size); v.forEach { out.writeFloat(it) } }
        internal fun readVec(input: java.io.DataInputStream): FloatArray = FloatArray(input.readShort().toInt()) { input.readFloat() }

        const val MAX_INTERESTS = 6
        const val MAX_DISLIKES = 8
        /** Two posts this close (cosine) are the same interest. */
        const val MERGE = 0.6
        const val POSITIVE = 0.25
        const val NEGATIVE = -0.4
        const val HARD_NO = -4.0
        const val LEARN_RATE = 0.25
        const val SESSION_RATE = 0.45
        /** A post this close to an interest counts toward its tally. */
        const val ATTRIBUTE = 0.55
        /** How much an interest's tally keeps with each new reaction to it. */
        const val FADE = 0.93
        /** How unsure a draw is about an interest with little record (shrinks as views add up). */
        const val SPREAD = 1.0
        /** Lower: the favourite interest takes more of the slots; higher: shares even out. */
        const val TEMPERATURE = 1.5
        /** Interests with a record below this aren't served (they're kept to steer away from). */
        const val SERVE_MIN = -0.2
        /** How strong a reaction must be to start a new interest, and its starting tally. */
        const val SPAWN = 0.8
        const val NEW_ALPHA = 0.6
        const val NEW_BETA = 1.2
        /** A new interest's record starts as this many views at the reaction that started it. */
        const val NEW_WEIGHT = 0.5

        /** A draw from Beta(a, b) (via two gamma draws; Marsaglia–Tsang). */
        fun betaSample(a: Double, b: Double, random: kotlin.random.Random): Double {
            val x = gamma(a.coerceAtLeast(0.05), random); val y = gamma(b.coerceAtLeast(0.05), random)
            return if (x + y == 0.0) 0.5 else x / (x + y)
        }

        private fun gamma(shape: Double, random: kotlin.random.Random): Double {
            if (shape < 1.0) return gamma(shape + 1.0, random) * Math.pow(random.nextDouble().coerceAtLeast(1e-12), 1.0 / shape)
            val d = shape - 1.0 / 3.0
            val c = 1.0 / sqrt(9.0 * d)
            while (true) {
                var x: Double; var v: Double
                do {
                    x = gaussian(random); v = 1.0 + c * x
                } while (v <= 0)
                v = v * v * v
                val u = random.nextDouble()
                if (u < 1 - 0.0331 * x * x * x * x) return d * v
                if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
            }
        }

        fun gaussian(random: kotlin.random.Random): Double {
            val u1 = random.nextDouble().coerceAtLeast(1e-12); val u2 = random.nextDouble()
            return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
        }

        fun cos(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
        }

        /** a ← normalise(a + rate·b). */
        fun blend(a: FloatArray, b: FloatArray, rate: Float) {
            for (i in a.indices) a[i] += rate * b[i]
            normalize(a)
        }

        fun normalize(a: FloatArray) {
            var n = 0.0
            for (v in a) n += v * v
            n = sqrt(n)
            if (n > 0) for (i in a.indices) a[i] = (a[i] / n).toFloat()
        }
    }
}

/**
 * A map of tag meaning learned by random indexing: each tag has a fixed sparse random "index"
 * vector (from its hash), and its learned vector adds up the index vectors of the tags it appears
 * with. Tags that keep the same company point the same way. Rare tags weigh more than common ones
 * (an idf), and a tag nobody has seen yet still has its own index vector to stand on.
 */
class TagSpace(val dim: Int = DIM, private val maxTags: Int = MAX_TAGS) {
    private val context = HashMap<String, FloatArray>()
    private val counts = HashMap<String, Int>()
    private var posts = 0

    fun observe(features: List<String>) {
        val tags = features.filterNot(Features::isMeta).take(MAX_PER_POST)
        if (tags.size < 2) { tags.forEach { counts[it] = (counts[it] ?: 0) + 1 }; posts++; return }
        posts++
        for (t in tags) counts[t] = (counts[t] ?: 0) + 1
        for (t in tags) {
            val ctx = context[t] ?: run {
                if (context.size >= maxTags) prune()
                FloatArray(dim).also { context[t] = it }
            }
            for (o in tags) if (o != t) addIndex(ctx, o, idf(o).toFloat())
        }
    }

    /** How informative a tag is: rare tags say more than ones on every post. */
    fun idf(tag: String): Double = ln((posts + 1.0) / ((counts[tag] ?: 0) + 1.0)) + 1.0

    /** A tag's learned direction (its company), or its plain index vector if it's never been seen with others. */
    fun tagVector(tag: String): FloatArray? {
        val v = FloatArray(dim)
        addIndex(v, tag, SELF)
        context[tag]?.let { ctx ->
            var n = 0.0
            for (x in ctx) n += x * x
            n = sqrt(n)
            if (n > 0) for (i in v.indices) v[i] += (ctx[i] / n).toFloat()
        }
        TasteEngine.normalize(v)
        return v
    }

    /** A post's direction: its tags' directions, rarer tags weighing more. Null if it has no tags. */
    fun itemVector(features: List<String>): FloatArray? {
        val tags = features.filterNot(Features::isMeta)
        if (tags.isEmpty()) return null
        val v = FloatArray(dim)
        for (t in tags) {
            val tv = tagVector(t) ?: continue
            val w = idf(t).toFloat()
            for (i in v.indices) v[i] += w * tv[i]
        }
        TasteEngine.normalize(v)
        return v
    }

    val size: Int get() = context.size

    fun writeTo(out: java.io.DataOutputStream) {
        out.writeInt(posts)
        out.writeInt(counts.size); counts.forEach { (k, v) -> out.writeUTF(k); out.writeInt(v) }
        out.writeInt(context.size); context.forEach { (k, v) -> out.writeUTF(k); TasteEngine.writeVec(out, v) }
    }

    fun readFrom(input: java.io.DataInputStream) {
        posts = input.readInt()
        counts.clear(); repeat(input.readInt()) { counts[input.readUTF()] = input.readInt() }
        context.clear(); repeat(input.readInt()) { val k = input.readUTF(); val v = TasteEngine.readVec(input); if (v.size == dim) context[k] = v }
    }

    private fun addIndex(into: FloatArray, tag: String, weight: Float) {
        var h = tag.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong()
        repeat(NONZERO) {
            h = h xor (h ushr 31); h *= -0x40a7b892e31b1a47L; h = h xor (h ushr 29)
            val idx = ((h ushr 1) % dim).toInt()
            into[abs(idx)] += if ((h and 1L) == 0L) weight else -weight
        }
    }

    /** Full: forget the rarest half (they say the least and come back if they matter). */
    private fun prune() {
        val keep = context.keys.sortedByDescending { counts[it] ?: 0 }.take(maxTags / 2).toHashSet()
        context.keys.retainAll(keep)
        if (counts.size > maxTags * 4) {
            val keepCounts = counts.entries.sortedByDescending { it.value }.take(maxTags * 2).map { it.key }.toHashSet()
            counts.keys.retainAll(keepCounts)
        }
    }

    companion object {
        const val DIM = 128
        const val MAX_TAGS = 4_000
        private const val NONZERO = 6
        private const val MAX_PER_POST = 24
        /** How much a tag's own identity counts next to its learned company. */
        private const val SELF = 0.35f
    }
}
