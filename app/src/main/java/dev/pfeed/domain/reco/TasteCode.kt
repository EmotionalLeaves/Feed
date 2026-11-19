package dev.pfeed.domain.reco

import java.util.Base64

/**
 * Your taste as a short code to give someone (a partner, say): the tags you like most, each with
 * how much (1–9). Pasted into their app, it blends your taste into their For You. Nothing else is
 * in it: no history, no posts, nothing about the phone.
 */
object TasteCode {
    private const val PREFIX = "PF1."
    const val MAX_TAGS = 16

    fun encode(affinity: Map<String, Double>, max: Int = MAX_TAGS): String? {
        val top = affinity.entries
            .filter { it.value > 0.3 && !Features.isMeta(it.key) && !it.key.startsWith("w:") && ',' !in it.key && ':' !in it.key }
            .sortedByDescending { it.value }
            .take(max)
        if (top.isEmpty()) return null
        val hi = top.first().value
        val body = top.joinToString(",") { e -> e.key + ":" + (1 + (8 * e.value / hi).toInt().coerceIn(0, 8)) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray(Charsets.UTF_8))
    }

    /** The tags in a code (strongest first), or null if it isn't one. Finds the code inside a longer message too. */
    fun decode(text: String): List<Pair<String, Int>>? {
        val code = Regex("PF1\\.[A-Za-z0-9_-]{4,2000}").find(text)?.value ?: return null
        val body = runCatching { String(Base64.getUrlDecoder().decode(code.removePrefix(PREFIX)), Charsets.UTF_8) }.getOrNull() ?: return null
        val out = body.split(',').mapNotNull { part ->
            val tag = Features.normalizeTag(part.substringBeforeLast(':'))
            val w = part.substringAfterLast(':').toIntOrNull()?.coerceIn(1, 9) ?: return@mapNotNull null
            if (tag.isBlank() || tag.length > 60) null else tag to w
        }.distinctBy { it.first }.take(MAX_TAGS)
        return out.ifEmpty { null }
    }

    /** How two tastes line up: a match from 0 to 100, what you share, and what theirs would add. */
    data class Match(val percent: Int, val shared: List<String>, val theirs: List<String>)

    /**
     * Your affinities against their code: tags you both like count for the match, tags that sit
     * close on your map count half (they like "ginger", you like "redhead").
     */
    fun match(mine: Map<String, Double>, theirs: List<Pair<String, Int>>, near: (String, String) -> Double = { _, _ -> 0.0 }): Match {
        val liked = mine.filter { it.value > 0.3 && !Features.isMeta(it.key) && !it.key.startsWith("w:") }
        if (liked.isEmpty() || theirs.isEmpty()) return Match(0, emptyList(), theirs.map { it.first })
        val shared = ArrayList<String>(); val add = ArrayList<String>()
        var got = 0.0; var total = 0.0
        for ((tag, w) in theirs) {
            total += w
            when {
                tag in liked -> { got += w; shared += tag }
                liked.keys.any { near(it, tag) >= NEAR } -> { got += w * 0.5; shared += tag }
                else -> add += tag
            }
        }
        return Match((100 * got / total).toInt().coerceIn(0, 100), shared, add)
    }
    private const val NEAR = 0.6

    /** Steering that blends [tags] into For You: their strongest ones, as "more of". */
    fun blend(tags: List<Pair<String, Int>>, max: Int = 8): List<Steer.Directive> =
        tags.sortedByDescending { it.second }.take(max).map { Steer.Directive(Steer.Kind.MORE, it.first) }
}
