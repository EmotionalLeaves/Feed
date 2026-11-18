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

    /** Steering that blends [tags] into For You: their strongest ones, as "more of". */
    fun blend(tags: List<Pair<String, Int>>, max: Int = 8): List<Steer.Directive> =
        tags.sortedByDescending { it.second }.take(max).map { Steer.Directive(Steer.Kind.MORE, it.first) }
}
