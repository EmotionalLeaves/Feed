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
        /** Whether you've been told about it (see [confirmations]). */
        var announced = false
        /** Stays the same for the interest's life, so its history can be drawn. */
        var id = 0
        /** Stride-scheduling position: the interest with the lowest pass serves the next slot. */
        var pass: Double = 0.0
        /** Reactions by time of day (4-hour stretches, faded): some tastes are for late at night. */
        val hourSum = DoubleArray(TIME_BINS)
        val hourCount = DoubleArray(TIME_BINS)

        /** How much you enjoy it at this time of day: that stretch's own reactions, leaning on the overall mean until there are enough. */
        fun meanAt(bin: Int): Double = (hourSum[bin] + TIME_SHRINK * mean) / (hourCount[bin] + TIME_SHRINK)

        /** [current], for this time of day. */
        fun currentAt(bin: Int): Double {
            val m = meanAt(bin)
            // Lately only speaks for the stretch of the day it was measured in.
            return if (lately.isNaN() || latelyBin != bin || lately >= m) m else m + LATELY_PULL * (lately - m)
        }
        var latelyBin = -1
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
            if (oneTaste) lookedAround++
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
    fun exposureSize(): Int = exposure.size

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
        val near = interests.filter { it.currentAt(bin()) > SERVE_MIN }.maxByOrNull { cos(it.centroid, x) }
        if (near != null && cos(near.centroid, x) >= ATTRIBUTE) {
            val view = describe(affinity).firstOrNull { interests.getOrNull(it.index) === near }
            if (view != null) {
                val name = view.tags.take(2).joinToString(" · ") { Features.label(it) }
                out += Reason("Your taste for $name · ${(view.share * 100).toInt().coerceAtLeast(1)}% of For You", view.tags.first(), true)
                if (view.time != null && view.time == TIME_NAMES.getOrNull(bin())) out += Reason("You like it most ${view.time}", null, false)
            }
        }
        if (moodScore(x) >= MOOD_REASON) out += Reason("Like what you've watched just now", null, false)
        if (visualScore(item) >= LOOK_REASON) out += Reason("Looks like what you stay for", null, false)
        return out
    }

    private fun tuneFrom(key: String, target: Double) {
        val x = shownParts.remove(key) ?: return
        val y = (target / 2.0).coerceIn(-1.0, 1.25)
        // An intercept soaks up the overall level (reactions are judged against your own pace, so
        // they average near zero whatever is shown); only the differences between posts tune weights.
        // Each part measured from its own running average, so a part that's high on everything
        // shown (votes on a board) isn't mistaken for one that predicts what you stay for.
        for (i in x.indices) partMean[i] += TUNE_CENTER * (x[i] - partMean[i])
        var pred = tuneBias
        for (i in x.indices) pred += DEFAULT_PART[i] * tune[i] * (x[i] - partMean[i])
        val err = y - pred
        tuneBias += TUNE_RATE * err
        for (i in x.indices) {
            val grad = err * (x[i] - partMean[i]) * (if (TUNE_NORMALIZED) 1.0 else DEFAULT_PART[i])
            tune[i] = (tune[i] + TUNE_RATE * grad - TUNE_PULL * (tune[i] - 1.0)).coerceIn(TUNE_MIN, TUNE_MAX)
        }
    }
    private val partMean = DoubleArray(PARTS)

    /** Learn the map from posts passing through the feed (whether or not they're shown). */
    fun observe(items: Collection<MediaItem>) = items.forEach { item ->
        space.observe(Features.of(item))
        val c = item.creator?.takeIf { it.isNotBlank() } ?: return@forEach
        val v = space.itemVector(Features.of(item)) ?: return@forEach
        val key = dev.pfeed.domain.filter.Blocklist.normalize(c)
        val at = creators.getOrPut(key) { Creator(c, FloatArray(v.size)) }
        for (k in v.indices) at.sum[k] += v[k]
        at.posts++
        if (creators.size > MAX_CREATORS) creators.remove(creators.keys.first())
    }

    /**
     * Tags that mean nearly the same as [tag] on the map (they keep the same company), leaning
     * toward what you like: "redhead" → ginger, freckles, pale…
     */
    fun neighbours(tag: String, limit: Int = 8): List<String> {
        val self = Features.normalizeTag(tag)
        val v = space.tagVector(self) ?: return emptyList()
        return space.tags(NEIGHBOUR_MIN_COUNT).asSequence()
            .filter { it != self && !Features.isMeta(it) && !it.startsWith("w:") }
            .mapNotNull { t -> space.tagVector(t)?.let { tv -> t to (cos(v, tv) + 0.15 * interestScore(tv) - 0.5 * aversion(tv)) } }
            .filter { it.second >= NEIGHBOUR_MIN }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }.toList()
    }

    /** A creator on the map: the sum of their posts' directions (so, what they make). */
    class Creator(val name: String, val sum: FloatArray, var posts: Int = 0)
    private val creators = LinkedHashMap<String, Creator>(256, 0.75f, true)

    /**
     * Creators who make the same kind of thing as [name] (by where their posts sit on the map),
     * leaning toward what you like. [items] are posts of theirs at hand, for someone not met yet.
     */
    fun similarCreators(name: String, items: List<MediaItem> = emptyList(), limit: Int = 10): List<String> {
        val key = dev.pfeed.domain.filter.Blocklist.normalize(name)
        val target = FloatArray(space.dim)
        creators[key]?.sum?.let { for (k in it.indices) target[k] += it[k] }
        items.forEach { vectorOf(it)?.let { v -> for (k in v.indices) target[k] += v[k] } }
        if (target.all { it == 0f }) return emptyList()
        normalize(target)
        return creators.entries.asSequence()
            .filter { it.key != key && it.value.posts >= SIMILAR_MIN_POSTS }
            .map { (_, c) ->
                val v = c.sum.copyOf().also(::normalize)
                c.name to (cos(target, v) + SIMILAR_TASTE * interestScore(v) - aversion(v))
            }
            .filter { it.second >= SIMILAR_MIN }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }.toList()
    }

    fun vectorOf(item: MediaItem): FloatArray? {
        val own = space.itemVector(Features.of(item))
        // A post that says little about itself (a Reddit post's one tag) is read partly by who
        // posted it: what they usually make.
        if (CREATOR_BLEND <= 0.0) return own
        val c = item.creator?.let { creators[dev.pfeed.domain.filter.Blocklist.normalize(it)] }?.takeIf { it.posts >= CREATOR_MIN_POSTS } ?: return own
        val tags = item.tags.size
        // Only for someone who sticks to one kind of thing: their posts' directions agree (a
        // creator who posts everything says nothing about this one).
        var len = 0.0
        for (x in c.sum) len += x * x
        val coherence = sqrt(len) / c.posts
        if (coherence < CREATOR_COHERENT) return own
        val weight = (CREATOR_BLEND * (coherence - CREATOR_COHERENT) / (1 - CREATOR_COHERENT) * (if (tags <= 2) 1.0 else 2.0 / tags)).toFloat()
        val cv = c.sum.copyOf().also(::normalize)
        val v = own?.copyOf() ?: FloatArray(cv.size)
        for (k in v.indices) v[k] += weight * cv[k]
        normalize(v)
        return v
    }

    /** How you react to what posts look like (see [Visual]). */
    val visual = Visual.Taste()

    /** How much the look of [item] suits you (-1..1; 0 when its thumbnail wasn't read, or early on). */
    fun visualScore(item: MediaItem): Double = Visual.decode(item.extras[Visual.EXTRA])?.let(visual::score) ?: 0.0

    /**
     * One reaction to [item]: [target] as from [Engagement] / [SignalWeights] (about −1 skip … +3
     * like … −8 for Not interested).
     */
    /** Day number (days since 1970, local) → (reactions, ones that landed): how well For You is doing. */
    val days = java.util.TreeMap<Int, IntArray>()
    var today: () -> Int = { java.time.LocalDate.now().toEpochDay().toInt() }

    private var nextId = 1
    /** Day → each interest's share of For You that day (by [Interest.id]). */
    val history = java.util.TreeMap<Int, List<Pair<Int, Float>>>()
    /** What each interest (by id) was last called. */
    val historyNames = HashMap<Int, List<String>>()

    /** Note today's shares, once a day (for "your taste over time"). */
    fun snapshot(affinity: Map<String, Double>) {
        val d = today()
        if (history.containsKey(d)) return
        val views = describe(affinity).filter { !it.sour && it.share >= 0.02 }
        if (views.isEmpty()) return
        history[d] = views.mapNotNull { v -> interests.getOrNull(v.index)?.let { it.id to v.share.toFloat() } }
        views.forEach { v -> interests.getOrNull(v.index)?.let { historyNames[it.id] = v.tags.take(2) } }
        while (history.size > KEEP_DAYS) history.remove(history.firstKey())
        historyNames.keys.retainAll(history.values.flatMap { l -> l.map { it.first } }.toSet())
    }

    /** How many of For You's picks you've reacted to, over the days kept. */
    fun reactions(): Int = days.values.sumOf { it[0] }

    /** The share of posts that landed, per day, for the last [n] days (null where nothing was watched). */
    fun landing(n: Int = 14): List<Double?> {
        val t = today()
        return (t - n + 1..t).map { d -> days[d]?.takeIf { it[0] >= LANDING_MIN }?.let { it[1].toDouble() / it[0] } }
    }

    fun learn(item: MediaItem, target: Double) {
        // Only posts For You picked count toward how well it's doing.
        if (shownParts.containsKey(item.key)) {
            val day = days.getOrPut(today()) { IntArray(2) }
            day[0]++; if (target > POSITIVE) day[1]++
            while (days.size > KEEP_DAYS) days.remove(days.firstKey())
        }
        tuneFrom(item.key, target)
        Visual.decode(item.extras[Visual.EXTRA])?.let { visual.learn(it, target) }
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
            val b = bin()
            if (near.latelyBin != b) { near.lately = near.meanAt(b); near.latelyBin = b }
            near.lately = if (near.lately.isNaN()) near.mean else near.lately + LATELY_RATE * sim * (target - near.lately)
            near.hourSum[b] = near.hourSum[b] * TIME_FADE + target * sim
            near.hourCount[b] = near.hourCount[b] * TIME_FADE + sim
        }
        if (target > POSITIVE) {
            if (near != null && sim >= MERGE) {
                blend(near.centroid, x, (LEARN_RATE * target / (1 + near.hits * 0.05)).toFloat())
                near.hits++
            } else if (target >= SPAWN) {
                // Something new you clearly liked: a tentative interest, which has to prove itself
                // (one lingering view while the phone was down shouldn't take over the feed).
                if (interests.size >= MAX_INTERESTS) interests.remove(interests.minBy { it.mean * it.confidence })
                interests += Interest(x.copyOf(), alpha = NEW_ALPHA + 0.25 * target, beta = NEW_BETA).also { it.sum = target * NEW_WEIGHT; it.count = NEW_WEIGHT; it.id = nextId++ }
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
    fun explorationRate(base: Double): Double {
        // With only one taste known, the most valuable thing is finding a second: most people have
        // more than one. Once two or more are being served, back to the usual rate.
        // A budget, though: after a few dozen looks around with nothing new found, you may simply
        // have one taste, and every further look is a wasted slot.
        val serving = interests.count { it.currentAt(bin()) > 0.2 && it.confidence >= 0.5 }
        oneTaste = serving <= 1 && interests.isNotEmpty()
        val left = (1.0 - lookedAround / EXPLORE_BUDGET).coerceIn(0.0, 1.0)
        return base * EXPLORE_SHARE * (if (oneTaste) 1.0 + (EXPLORE_ONE_TASTE - 1.0) * left else 1.0)
    }
    private var oneTaste = false
    /** Looks around (exploration slots) while only one taste was known. */
    private var lookedAround = 0.0

    /** A tag's direction on the map (null if never seen). */
    fun tagVector(tag: String): FloatArray? = space.tagVector(Features.normalizeTag(tag))

    /** How well [x] fits what you like: the best interest's match, scaled by how well it's going (0..1). */
    fun interestScore(x: FloatArray): Double {
        var best = 0.0
        val now = bin()
        for (i in interests) {
            if (i.currentAt(now) <= 0) continue
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

    /** The hour now (0–23); the simulation sets its own. */
    var clock: () -> Int = { java.time.LocalTime.now().hour }
    fun bin(): Int = (clock().coerceIn(0, 23) / (24 / TIME_BINS)).coerceIn(0, TIME_BINS - 1)


    /** Which interest the next slot serves (see inside). Null when nothing's been learned yet. */
    fun pickInterest(random: kotlin.random.Random): Interest? {
        val now = bin()
        val liked = interests.filter { it.currentAt(now) > SERVE_MIN }
        if (liked.isEmpty()) return null
        // Each interest's share of slots: a Thompson draw of how much you enjoy it (its mean, give
        // or take how unsure we still are), sharpened so the favourite leads without starving the
        // rest. Slots are then dealt out by stride scheduling: whoever is furthest behind its share
        // goes next. Your tastes interleave (A B C A B A C…) instead of running in streaks, and a
        // taste that starts missing loses share at once.
        val draws = liked.associateWith { i -> kotlin.math.exp((i.currentAt(now) + gaussian(random) * SPREAD / sqrt(i.count + 1.0)) / TEMPERATURE) }
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
        val now = bin()
        val serving = interests.filter { it.currentAt(now) > SERVE_MIN && it.currentAt(now) > 0 }
            .sortedByDescending { it.currentAt(now) * (0.5 + 0.5 * it.confidence) }
        if (serving.isEmpty()) return emptyList()
        val candidates = space.tags(QUERY_MIN_COUNT)
            .filter { !Features.isMeta(it) && !it.startsWith("w:") && (affinity[it] ?: 0.0) > QUERY_MIN_AFFINITY }
            .mapNotNull { t -> space.tagVector(t)?.let { v -> Triple(t, v, aversion(v)) } }
        val out = ArrayList<String>()
        val covered = ArrayList<FloatArray>()
        for (i in serving) {
            // Two interests that are nearly the same thing share one search.
            if (covered.any { cos(it, i.centroid) >= QUERY_SAME }) continue
            covered += i.centroid
            val best = candidates.filter { it.first !in out }.maxByOrNull { (t, v, av) ->
                cos(i.centroid, v) + 0.05 * (affinity[t] ?: 0.0).coerceIn(-1.0, 1.0) - 0.5 * av
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
    data class View(val index: Int, val tags: List<String>, val share: Double, val enjoyment: Double, val sour: Boolean, val time: String? = null)

    /** When in the day [i] is clearly at its best ("late at night"), if it is. */
    fun bestTime(i: Interest): String? {
        val best = i.hourSum.indices.maxByOrNull { i.meanAt(it) } ?: return null
        if (i.hourCount[best] < TIME_EVIDENCE || i.meanAt(best) < i.mean + TIME_STANDOUT) return null
        return TIME_NAMES.getOrNull(best)
    }

    /**
     * Your interests in plain terms (strongest first): each named by the liked tags closest to its
     * centre, with the share of For You's slots it gets right now.
     */
    fun describe(affinity: Map<String, Double>): List<View> {
        val liked = affinity.filter { it.value > 0.2 && !Features.isMeta(it.key) && !it.key.startsWith("w:") }
        val vecs = liked.keys.associateWith { space.tagVector(it) }
        val now = bin()
        val served = interests.filter { it.currentAt(now) > SERVE_MIN }
        val weights = served.associateWith { kotlin.math.exp(it.currentAt(now) / TEMPERATURE) }
        val total = weights.values.sum().coerceAtLeast(1e-9)
        return interests.mapIndexedNotNull { idx, i ->
            val names = vecs.entries
                .map { (t, v) -> t to (v?.let { cos(i.centroid, it) } ?: -1.0) }
                .filter { it.second > NAME_MIN }
                // Among close tags, prefer telling ones: a tag on nearly everything names nothing.
                .sortedByDescending { it.second + 0.02 * (liked[it.first] ?: 0.0) + NAME_IDF * space.idf(it.first) / space.maxIdf }
                .take(3).map { it.first }
            if (names.isEmpty()) return@mapIndexedNotNull null
            View(idx, names, (weights[i] ?: 0.0) / total, i.currentAt(now), i.currentAt(now) <= SERVE_MIN, bestTime(i))
        }.sortedByDescending { it.share }
    }

    /** "Less of this": the interest goes sour, so it's avoided rather than served. */
    fun quieten(index: Int) {
        val i = interests.getOrNull(index) ?: return
        i.sum = -i.count * 0.8
    }

    /**
     * Interests that have just proved themselves (you keep enjoying what's near them), each named,
     * once: "For You noticed a new taste". Ones already known when the app started don't count.
     */
    fun confirmations(affinity: Map<String, Double>): List<View> {
        val fresh = interests.withIndex().filter { (_, i) ->
            !i.announced && i.mean > ANNOUNCE_MEAN && i.confidence >= ANNOUNCE_CONFIDENCE && i.hits >= ANNOUNCE_HITS
        }
        if (fresh.isEmpty()) return emptyList()
        fresh.forEach { it.value.announced = true }
        val views = describe(affinity)
        return fresh.mapNotNull { (idx, _) -> views.firstOrNull { it.index == idx } }
    }

    /** Forget one interest entirely. */
    fun forget(index: Int) {
        if (index in interests.indices) interests.removeAt(index)
    }

    /** Forget everything learned (Settings → reset what For You learned). The map of meaning stays. */
    fun resetTaste() {
        interests.clear(); dislikes.clear(); exploredKeys.clear(); exposure.clear(); session = null; sessionStrength = 0.0
        tune.fill(1.0); tuneBias = 0.0; partMean.fill(0.0); lookedAround = 0.0; visual.reset(); shownParts.clear()
    }

    fun writeTo(out: java.io.DataOutputStream) {
        out.writeInt(FORMAT)
        space.writeTo(out)
        out.writeInt(interests.size)
        for (i in interests) {
            writeVec(out, i.centroid)
            out.writeDouble(i.alpha); out.writeDouble(i.beta); out.writeDouble(i.sum); out.writeDouble(i.count); out.writeInt(i.hits)
            i.hourSum.forEach { out.writeDouble(it) }; i.hourCount.forEach { out.writeDouble(it) }
        }
        out.writeInt(dislikes.size); dislikes.forEach { writeVec(out, it) }
        val s = session
        out.writeBoolean(s != null); if (s != null) writeVec(out, s)
        out.writeDouble(sessionStrength)
        out.writeInt(tune.size); tune.forEach { out.writeDouble(it) }
        visual.writeTo(out)
        out.writeInt(days.size); days.forEach { (d, c) -> out.writeInt(d); out.writeInt(c[0]); out.writeInt(c[1]) }
        val keep = creators.entries.toList().takeLast(SAVED_CREATORS)
        out.writeInt(keep.size)
        for ((k, c) in keep) { out.writeUTF(k); out.writeUTF(c.name.take(200)); out.writeInt(c.posts); writeVec(out, c.sum) }
        out.writeInt(nextId)
        interests.forEach { out.writeInt(it.id) }
        out.writeInt(history.size)
        history.forEach { (d, l) -> out.writeInt(d); out.writeInt(l.size); l.forEach { (id, sh) -> out.writeInt(id); out.writeFloat(sh) } }
        out.writeInt(historyNames.size)
        historyNames.forEach { (id, names) -> out.writeInt(id); out.writeInt(names.size); names.forEach { out.writeUTF(it.take(80)) } }
    }

    fun readFrom(input: java.io.DataInputStream) {
        val format = input.readInt()
        // Older formats read fine: 2 (4.89–4.92) had no time of day, 3 (4.93) no visual taste, 4 (4.94) no creators, 5 (4.95–4.98) a full-size map, 6 (4.99–5.1) no history.
        if (format !in 2..FORMAT) return
        space.readFrom(input, quantized = format >= 6)
        interests.clear()
        repeat(input.readInt()) {
            val c = readVec(input)
            interests += Interest(c, input.readDouble(), input.readDouble()).also { i ->
                i.announced = true
                i.sum = input.readDouble(); i.count = input.readDouble(); i.hits = input.readInt()
                if (format >= 3) { for (b in i.hourSum.indices) i.hourSum[b] = input.readDouble(); for (b in i.hourCount.indices) i.hourCount[b] = input.readDouble() }
            }
        }
        dislikes.clear(); repeat(input.readInt()) { dislikes += readVec(input) }
        session = if (input.readBoolean()) readVec(input) else null
        sessionStrength = input.readDouble()
        repeat(input.readInt()) { i -> val v = input.readDouble(); if (i < tune.size) tune[i] = v }
        if (format >= 4) visual.readFrom(input)
        days.clear()
        if (format >= 5) repeat(input.readInt()) { val d = input.readInt(); days[d] = intArrayOf(input.readInt(), input.readInt()) }
        creators.clear()
        if (format >= 5) repeat(input.readInt()) {
            val k = input.readUTF(); val n = input.readUTF(); val posts = input.readInt(); val v = readVec(input)
            creators[k] = Creator(n, v, posts)
        }
        history.clear(); historyNames.clear()
        if (format >= 7) {
            nextId = input.readInt()
            interests.forEach { it.id = input.readInt() }
            repeat(input.readInt()) { val d = input.readInt(); history[d] = List(input.readInt()) { input.readInt() to input.readFloat() } }
            repeat(input.readInt()) { val id = input.readInt(); historyNames[id] = List(input.readInt()) { input.readUTF() } }
        } else {
            interests.forEach { it.id = nextId++ }
        }
    }

    companion object {
        private const val FORMAT = 7

        /** Parts of a score, in order: tags, creator, votes, freshness, interest fit, mood, aversion. */
        const val PARTS = 8
        /** Their default weights (aversion counts against). */
        val DEFAULT_PART = doubleArrayOf(1.0, 0.6, 0.3, 0.15, 0.9, 0.35, -0.8, Ranker.VISUAL)
        const val TUNE_RATE = 0.1
        const val TUNE_CENTER = 0.02
        const val TUNE_NORMALIZED = true
        const val EXPLORE_SHARE = 0.4
        const val EXPLORE_ONE_TASTE = 5.0
        const val EXPLORE_BUDGET = 20.0
        const val LANDING_MIN = 5
        const val KEEP_DAYS = 60
        const val MAX_CREATORS = 1500
        const val ANNOUNCE_MEAN = 0.4
        const val ANNOUNCE_CONFIDENCE = 0.7
        const val ANNOUNCE_HITS = 5
        const val NEIGHBOUR_MIN_COUNT = 4
        const val NEIGHBOUR_MIN = 0.45
        const val CREATOR_BLEND = 1.0
        const val CREATOR_MIN_POSTS = 3
        const val CREATOR_COHERENT = 0.5
        const val SAVED_CREATORS = 600
        const val SIMILAR_MIN_POSTS = 2
        const val SIMILAR_MIN = 0.55
        const val SIMILAR_TASTE = 0.2
        const val TIME_BINS = 6
        const val TIME_FADE = 0.97
        /** How many reactions' worth the overall mean counts for in each stretch of the day. */
        const val TIME_SHRINK = 3.0
        const val TIME_EVIDENCE = 4.0
        const val TIME_STANDOUT = 0.4
        val TIME_NAMES = listOf("late at night", "early in the morning", "in the mornings", "in the afternoons", "in the evenings", "at night")
        const val NAME_IDF = 0.25
        const val MAX_EXPOSURE = 240
        const val NOVELTY = 1.2
        const val MOOD_REASON = 0.45
        const val LOOK_REASON = 0.5
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
        internal fun writeVecQ(out: java.io.DataOutputStream, v: FloatArray) {
            val m = v.maxOfOrNull { abs(it) }?.takeIf { it > 0f } ?: 1f
            out.writeShort(v.size); out.writeFloat(m)
            v.forEach { out.writeShort((it / m * 32767f).toInt()) }
        }
        internal fun readVecQ(input: java.io.DataInputStream): FloatArray {
            val n = input.readShort().toInt(); val m = input.readFloat()
            return FloatArray(n) { input.readShort() * m / 32767f }
        }

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

    /** Every tag seen at least [minCount] times, with how often. */
    fun counted(minCount: Int): Map<String, Int> = counts.filter { it.value >= minCount }

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
        // Half the size of plain floats (the map is the bulk of the file): each direction as 16-bit
        // steps of its own largest value.
        out.writeInt(context.size); context.forEach { (k, v) -> out.writeUTF(k); TasteEngine.writeVecQ(out, v) }
    }

    fun readFrom(input: java.io.DataInputStream, quantized: Boolean = true) {
        posts = input.readInt()
        counts.clear(); repeat(input.readInt()) { counts[input.readUTF()] = input.readInt() }
        context.clear(); repeat(input.readInt()) {
            val k = input.readUTF()
            val v = if (quantized) TasteEngine.readVecQ(input) else TasteEngine.readVec(input)
            if (v.size == dim) context[k] = v
        }
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
