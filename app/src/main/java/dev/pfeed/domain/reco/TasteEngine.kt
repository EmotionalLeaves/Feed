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
        /** The last few reactions only (fast fading): a sudden change shows here long before in [mean]. */
        var lately: Double = Double.NaN
        /**
         * What to go by now: the long view, unless lately has turned worse; then mostly lately (a
         * taste you've gone off shouldn't keep its slots for a dozen more posts while the mean catches up).
         */
        val current: Double get() = if (lately.isNaN() || lately >= mean) mean else mean + LATELY_PULL * (lately - mean)
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

    /**
     * Your own weighting of what makes a post good (learning to rank): one multiplier per part of the
     * score (taste by tags, creator, votes, freshness, interest fit, mood, aversion), starting at 1
     * and nudged after every reaction toward whatever predicts what you actually stay for. Gently
     * pulled back toward 1 so a few odd reactions can't run away with it.
     */
    val tune = DoubleArray(PARTS) { 1.0 }
    private val shownParts = LinkedHashMap<String, DoubleArray>()
    private var tuneBias = 0.0

    /** Ranked and shown: remember the parts of its score until you react to it. */
    fun shown(key: String, parts: DoubleArray, explored: Boolean = false, vec: FloatArray? = null) {
        if (vec != null) {
            exposure.addLast(vec)
            if (exposure.size > MAX_EXPOSURE) exposure.removeFirst()
        }
        shownParts[key] = parts
        if (shownParts.size > MAX_REMEMBERED) shownParts.remove(shownParts.keys.first())
        if (explored) {
            exploredKeys += key
            if (exploredKeys.size > MAX_REMEMBERED) exploredKeys.remove(exploredKeys.first())
        }
    }

    /** Where on the map you've been shown things lately (liked or not). */
    private val exposure = ArrayDeque<FloatArray>()

    /**
     * How far [x] is from everything you've been shown lately (0 = right where you've been, 1 =
     * nowhere near). Exploring by this tries each region of the map in turn instead of drawing at
     * random, so a second or third taste turns up in a fraction of the tries.
     */
    fun unexplored(x: FloatArray): Double {
        var best = 0.0
        for (e in exposure) { val c = cos(e, x); if (c > best) best = c }
        return 1.0 - best
    }

    /** Posts put in a slot to explore (so "why am I seeing this" can say so). */
    private val exploredKeys = LinkedHashSet<String>()

    /** A reason in plain words, and the tag page it leads to (if any). */
    data class Reason(val text: String, val tag: String?, val strong: Boolean)

    /**
     * Why For You picked [item], as the engine sees it: which of your tastes it serves (and that
     * taste's share of the feed), that it's like what you've been watching just now, or that it was
     * a look past what you know.
     */
    fun explain(item: MediaItem, affinity: Map<String, Double>): List<Reason> {
        val out = ArrayList<Reason>()
        if (item.key in exploredKeys) out += Reason("Something new, near what you like", null, false)
        val x = vectorOf(item) ?: return out
        val near = interests.filter { it.current > SERVE_MIN }.maxByOrNull { cos(it.centroid, x) }
        if (near != null && cos(near.centroid, x) >= ATTRIBUTE) {
            val view = describe(affinity).firstOrNull { interests.getOrNull(it.index) === near }
            if (view != null) {
                val name = view.tags.take(2).joinToString(" · ") { Features.label(it) }
                out += Reason("Your taste for $name · ${(view.share * 100).toInt().coerceAtLeast(1)}% of For You", view.tags.first(), true)
            }
        }
        if (moodScore(x) >= MOOD_REASON) out += Reason("Like what you've watched just now", null, false)
        return out
    }

    private fun tuneFrom(key: String, target: Double) {
        val x = shownParts.remove(key) ?: return
        val y = (target / 2.0).coerceIn(-1.0, 1.25)
        // An intercept soaks up the overall level (reactions are judged against your own pace, so
        // they average near zero whatever is shown); only the differences between posts tune weights.
        var pred = tuneBias
        for (i in x.indices) pred += DEFAULT_PART[i] * tune[i] * x[i]
        val err = y - pred
        tuneBias += TUNE_RATE * err
        for (i in x.indices) {
            val grad = err * DEFAULT_PART[i] * x[i]
            tune[i] = (tune[i] + TUNE_RATE * grad - TUNE_PULL * (tune[i] - 1.0)).coerceIn(TUNE_MIN, TUNE_MAX)
        }
    }

    /** Learn the map from posts passing through the feed (whether or not they're shown). */
    fun observe(items: Collection<MediaItem>) = items.forEach { space.observe(Features.of(it)) }

    fun vectorOf(item: MediaItem): FloatArray? = space.itemVector(Features.of(item))

    /**
     * One reaction to [item]: [target] as from [Engagement] / [SignalWeights] (about −1 skip … +3
     * like … −8 for Not interested).
     */
    fun learn(item: MediaItem, target: Double) {
        tuneFrom(item.key, target)
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
            near.lately = if (near.lately.isNaN()) near.mean else near.lately + LATELY_RATE * sim * (target - near.lately)
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

    /**
     * How often to explore. Exploring on the map (just past what you know, good posts, nothing like
     * what you turned down) finds a new taste in far fewer tries than random posts did, so it needs
     * fewer slots: in the simulated world 40% of the old rate gave the best feeds, and shifts of
     * taste were still followed as fast. "Random discoveries" still sets the level.
     */
    fun explorationRate(base: Double): Double = base * EXPLORE_SHARE

    /** How well [x] fits what you like: the best interest's match, scaled by how well it's going (0..1). */
    fun interestScore(x: FloatArray): Double {
        var best = 0.0
        for (i in interests) {
            if (i.current <= 0) continue
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
        val liked = interests.filter { it.current > SERVE_MIN }
        if (liked.isEmpty()) return null
        // Each interest's share of slots: a Thompson draw of how much you enjoy it (its mean, give
        // or take how unsure we still are), sharpened so the favourite leads without starving the
        // rest. Slots are then dealt out by stride scheduling: whoever is furthest behind its share
        // goes next. Your tastes interleave (A B C A B A C…) instead of running in streaks, and a
        // taste that starts missing loses share at once.
        val draws = liked.associateWith { i -> kotlin.math.exp((i.current + gaussian(random) * SPREAD / sqrt(i.count + 1.0)) / TEMPERATURE) }
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
     * What to search the sites for, one query per interest you're enjoying now (sour ones get none):
     * the tag that sits closest to the interest's centre on the map. It needn't be a tag you've
     * liked yet; a taste you found two posts ago gets its own searches straight away, which is most
     * of how a change of taste is followed (the general feeds carry only a trickle of any one theme).
     */
    fun searchTags(affinity: Map<String, Double>, limit: Int = 3): List<String> {
        val serving = interests.filter { it.current > SERVE_MIN && it.current > 0 }
            .sortedByDescending { it.current * (0.5 + 0.5 * it.confidence) }
        if (serving.isEmpty()) return emptyList()
        val candidates = space.tags(QUERY_MIN_COUNT)
            .filter { !Features.isMeta(it) && !it.startsWith("w:") && (affinity[it] ?: 0.0) > QUERY_MIN_AFFINITY }
            .mapNotNull { t -> space.tagVector(t)?.let { t to it } }
        val out = ArrayList<String>()
        val covered = ArrayList<FloatArray>()
        for (i in serving) {
            // Two interests that are nearly the same thing share one search.
            if (covered.any { cos(it, i.centroid) >= QUERY_SAME }) continue
            covered += i.centroid
            val best = candidates.filter { it.first !in out }.maxByOrNull { (t, v) ->
                cos(i.centroid, v) + 0.05 * (affinity[t] ?: 0.0).coerceIn(-1.0, 1.0) - 0.5 * aversion(v)
            } ?: continue
            out += best.first
            if (out.size >= limit) break
        }
        // Room left: your best-liked tags, nearest your interests first.
        if (out.size < limit) {
            val liked = affinity.filter { it.value > 0.3 && it.key !in out && !Features.isMeta(it.key) && !it.key.startsWith("w:") }
            liked.keys.sortedByDescending { t ->
                val v = space.tagVector(t) ?: return@sortedByDescending -1.0
                (serving.maxOfOrNull { cos(it.centroid, v) } ?: 0.0) + 0.05 * (liked[t] ?: 0.0)
            }.take(limit - out.size).forEach { out += it }
        }
        return out
    }

    /** A tag on the taste map: where it sits (-1..1), how much you like it (-1..1), whose taste it is (-1: none). */
    data class Star(val tag: String, val x: Float, val y: Float, val liking: Double, val group: Int)
    /** An interest's centre on the map. */
    data class Sun(val group: Int, val x: Float, val y: Float, val share: Double)
    data class TasteMap(val stars: List<Star>, val suns: List<Sun>)

    /**
     * Your taste as a picture: the tags you like and the ones at the heart of each interest, laid
     * out flat so tags that keep company sit together (the two main directions of the map, found by
     * principal components), each coloured by the interest it belongs to.
     */
    fun tasteMap(affinity: Map<String, Double>, maxStars: Int = 44): TasteMap {
        val views = describe(affinity)
        val serving = views.mapNotNull { v -> interests.getOrNull(v.index)?.let { v to it } }
        val pool = space.tags(QUERY_MIN_COUNT).filter { !Features.isMeta(it) && !it.startsWith("w:") }
            .mapNotNull { t -> space.tagVector(t)?.let { t to it } }.toMap()
        val chosen = LinkedHashMap<String, FloatArray>()
        // The heart of each interest first, then what you like most, then a few you don't.
        for ((_, i) in serving) pool.entries.sortedByDescending { cos(i.centroid, it.value) }.take(5).forEach { chosen[it.key] = it.value }
        affinity.entries.filter { it.value > 0.2 && !Features.isMeta(it.key) && !it.key.startsWith("w:") }
            .sortedByDescending { it.value }.forEach { e -> if (chosen.size < maxStars - 5) (pool[e.key] ?: space.tagVector(e.key))?.let { chosen[e.key] = it } }
        affinity.entries.filter { it.value < -0.3 && !Features.isMeta(it.key) && !it.key.startsWith("w:") }
            .sortedBy { it.value }.take(5).forEach { e -> (pool[e.key] ?: space.tagVector(e.key))?.let { chosen[e.key] = it } }
        if (chosen.size < 3) return TasteMap(emptyList(), emptyList())
        val vecs = chosen.values.toList()
        val dim = vecs.first().size
        val mean = DoubleArray(dim).also { m -> vecs.forEach { v -> for (k in 0 until dim) m[k] += v[k] / vecs.size } }
        val centred = vecs.map { v -> DoubleArray(dim) { k -> v[k] - mean[k] } }
        fun component(orth: DoubleArray?): DoubleArray {
            var c = DoubleArray(dim) { k -> kotlin.math.sin(k * 1.3 + 0.7) }
            repeat(40) {
                val n = DoubleArray(dim)
                for (x in centred) { var d = 0.0; for (k in 0 until dim) d += x[k] * c[k]; for (k in 0 until dim) n[k] += d * x[k] }
                if (orth != null) { var d = 0.0; for (k in 0 until dim) d += n[k] * orth[k]; for (k in 0 until dim) n[k] -= d * orth[k] }
                val len = sqrt(n.sumOf { it * it }).coerceAtLeast(1e-12)
                c = DoubleArray(dim) { k -> n[k] / len }
            }
            return c
        }
        val a = component(null); val b = component(a)
        fun project(v: FloatArray): Pair<Double, Double> {
            var x = 0.0; var y = 0.0
            for (k in 0 until dim) { val d = v[k] - mean[k]; x += d * a[k]; y += d * b[k] }
            return x to y
        }
        val pts = chosen.mapValues { project(it.value) }
        val sunPts = serving.map { (v, i) -> v to project(i.centroid) }
        val all = pts.values + sunPts.map { it.second }
        val sx = all.maxOf { kotlin.math.abs(it.first) }.coerceAtLeast(1e-9)
        val sy = all.maxOf { kotlin.math.abs(it.second) }.coerceAtLeast(1e-9)
        val stars = chosen.keys.map { t ->
            val (x, y) = pts.getValue(t)
            val v = chosen.getValue(t)
            val g = serving.maxByOrNull { cos(it.second.centroid, v) }?.takeIf { cos(it.second.centroid, v) >= NAME_MIN }?.first?.index ?: -1
            Star(t, (x / sx).toFloat(), (y / sy).toFloat(), (affinity[t] ?: 0.0).coerceIn(-1.0, 1.0), g)
        }
        val suns = sunPts.map { (v, p) -> Sun(v.index, (p.first / sx).toFloat(), (p.second / sy).toFloat(), v.share) }
        return TasteMap(stars, suns)
    }

    /** An interest as people see it: the tags that best name it, its share of For You, and how it's going. */
    data class View(val index: Int, val tags: List<String>, val share: Double, val enjoyment: Double, val sour: Boolean)

    /**
     * Your interests in plain terms (strongest first): each named by the liked tags closest to its
     * centre, with the share of For You's slots it gets right now.
     */
    fun describe(affinity: Map<String, Double>): List<View> {
        val liked = affinity.filter { it.value > 0.2 && !Features.isMeta(it.key) && !it.key.startsWith("w:") }
        val vecs = liked.keys.associateWith { space.tagVector(it) }
        val served = interests.filter { it.current > SERVE_MIN }
        val weights = served.associateWith { kotlin.math.exp(it.current / TEMPERATURE) }
        val total = weights.values.sum().coerceAtLeast(1e-9)
        return interests.mapIndexedNotNull { idx, i ->
            val names = vecs.entries
                .map { (t, v) -> t to (v?.let { cos(i.centroid, it) } ?: -1.0) }
                .filter { it.second > NAME_MIN }
                // Among close tags, prefer telling ones: a tag on nearly everything names nothing.
                .sortedByDescending { it.second + 0.02 * (liked[it.first] ?: 0.0) + NAME_IDF * space.idf(it.first) / space.maxIdf }
                .take(3).map { it.first }
            if (names.isEmpty()) return@mapIndexedNotNull null
            View(idx, names, (weights[i] ?: 0.0) / total, i.current, i.current <= SERVE_MIN)
        }.sortedByDescending { it.share }
    }

    /** "Less of this": the interest goes sour, so it's avoided rather than served. */
    fun quieten(index: Int) {
        val i = interests.getOrNull(index) ?: return
        i.sum = -i.count * 0.8
    }

    /** Forget one interest entirely. */
    fun forget(index: Int) {
        if (index in interests.indices) interests.removeAt(index)
    }

    /** Forget everything learned (Settings → reset what For You learned). The map of meaning stays. */
    fun resetTaste() {
        interests.clear(); dislikes.clear(); exploredKeys.clear(); exposure.clear(); session = null; sessionStrength = 0.0
        tune.fill(1.0); tuneBias = 0.0; shownParts.clear()
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
        out.writeInt(tune.size); tune.forEach { out.writeDouble(it) }
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
        repeat(input.readInt()) { i -> val v = input.readDouble(); if (i < tune.size) tune[i] = v }
    }

    companion object {
        private const val FORMAT = 2

        /** Parts of a score, in order: tags, creator, votes, freshness, interest fit, mood, aversion. */
        const val PARTS = 7
        /** Their default weights (aversion counts against). */
        val DEFAULT_PART = doubleArrayOf(1.0, 0.6, 0.3, 0.15, 0.9, 0.35, -0.8)
        const val TUNE_RATE = 0.05
        const val EXPLORE_SHARE = 0.4
        const val NAME_IDF = 0.25
        const val MAX_EXPOSURE = 240
        const val NOVELTY = 1.2
        const val MOOD_REASON = 0.45
        const val QUERY_MIN_COUNT = 3
        const val QUERY_SAME = 0.7
        const val QUERY_MIN_AFFINITY = -0.3
        const val LATELY_RATE = 0.3
        const val LATELY_PULL = 0.6
        const val TUNE_PULL = 0.002
        const val TUNE_MIN = 0.25
        const val TUNE_MAX = 3.0
        private const val MAX_REMEMBERED = 400

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
        /** How close a tag must be to an interest's centre to be one of its names. */
        const val NAME_MIN = 0.35
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

    /** Tags seen at least [minCount] times. */
    fun tags(minCount: Int): List<String> = counts.entries.filter { it.value >= minCount }.map { it.key }

    val maxIdf: Double get() = ln(posts + 1.0) + 1.0

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
