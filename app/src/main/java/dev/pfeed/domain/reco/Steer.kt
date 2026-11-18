package dev.pfeed.domain.reco

/**
 * Telling For You what you're in the mood for, in your own words: "more redheads, less cosplay",
 * "only amateur", "no feet", "just videos tonight". Each phrase becomes a directive on a tag (or a
 * format) that lasts for this sitting: more of it, less of it, none of it, or only it.
 */
object Steer {
    enum class Kind { MORE, LESS, NO, ONLY }

    /** One instruction: [tag] is a feature as [Features.of] writes it ("redhead", "type:video"). */
    data class Directive(val kind: Kind, val tag: String) {
        val label: String get() = when (kind) {
            Kind.MORE -> "+" + short(tag)
            Kind.LESS -> "−" + short(tag)
            Kind.NO -> "no " + short(tag)
            Kind.ONLY -> "only " + short(tag)
        }
    }

    private fun short(tag: String): String = when {
        tag.startsWith(Features.LENGTH) -> when (tag.removePrefix(Features.LENGTH)) { "short" -> "short clips"; "long" -> "long videos"; else -> "mid-length videos" }
        tag.startsWith(Features.TYPE) -> Features.label(tag).lowercase()
        else -> Features.label(tag).removePrefix("#")
    }

    // Longest first, so "not so much" wins over "not".
    private val PREFIXES: List<Pair<String, Kind>> = listOf(
        "nothing but " to Kind.ONLY, "only " to Kind.ONLY, "just " to Kind.ONLY, "exclusively " to Kind.ONLY,
        "not so much " to Kind.LESS, "not too much " to Kind.LESS, "not much " to Kind.LESS, "a bit less " to Kind.LESS,
        "less of " to Kind.LESS, "less " to Kind.LESS, "fewer " to Kind.LESS, "tone down " to Kind.LESS,
        "none of " to Kind.NO, "no more " to Kind.NO, "without " to Kind.NO, "never " to Kind.NO, "skip " to Kind.NO,
        "zero " to Kind.NO, "no " to Kind.NO, "not " to Kind.NO, "hide " to Kind.NO,
        "lots of " to Kind.MORE, "a lot of " to Kind.MORE, "more of " to Kind.MORE, "more " to Kind.MORE,
        "show me " to Kind.MORE, "i want " to Kind.MORE, "in the mood for " to Kind.MORE, "craving " to Kind.MORE,
        "some " to Kind.MORE, "give me " to Kind.MORE, "extra " to Kind.MORE,
    ).sortedByDescending { it.first.length }

    private val FILLER = Regex("\\b(please|pls|tonight|today|right now|now|for now|stuff|content|posts?|for a while|thanks|thx)\\b")
    private val SPLIT = Regex("\\s*(?:,|;|\\.|\\n|\\band\\b|\\bbut\\b|\\bplus\\b|&|\\+)\\s*")

    /** Formats people say in words. */
    private val FORMATS = mapOf(
        "video" to "video", "videos" to "video", "vids" to "video", "clips" to "video",
        "pics" to "image", "pictures" to "image", "photos" to "image", "images" to "image", "pics only" to "image",
        "gifs" to "gif", "gif" to "gif", "comics" to "comic", "comic" to "comic", "hentai comics" to "comic",
    )
    private val LENGTHS = mapOf(
        "short videos" to "short", "short clips" to "short", "short ones" to "short", "quickies" to "short",
        "long videos" to "long", "long ones" to "long", "full videos" to "long", "full length" to "long",
    )

    /**
     * Read [text] into directives. [resolve] turns a phrase into the tag it most likely means among
     * the tags you've met ("redheads" → "redhead"); unknown phrases are kept as they are (they still
     * steer the searches).
     */
    fun parse(text: String, resolve: (String) -> String = { Features.normalizeTag(it) }): List<Directive> {
        val out = ArrayList<Directive>()
        var carry: Kind? = null
        for (raw in text.lowercase().split(SPLIT)) {
            var clause = raw.replace(FILLER, " ").replace(Regex("\\s+"), " ").trim().trim('!', '?', '"', '\'')
            if (clause.isEmpty()) continue
            var kind: Kind? = null
            for ((p, k) in PREFIXES) if (clause.startsWith(p)) { kind = k; clause = clause.removePrefix(p).trim(); break }
            // "videos only", "redheads please", "cosplay less"
            if (kind == null) {
                when {
                    clause.endsWith(" only") -> { kind = Kind.ONLY; clause = clause.removeSuffix(" only") }
                    clause.endsWith(" less") -> { kind = Kind.LESS; clause = clause.removeSuffix(" less") }
                    clause.endsWith(" more") -> { kind = Kind.MORE; clause = clause.removeSuffix(" more") }
                }
            }
            // "no feet and cosplay" means no to both.
            val k = kind ?: carry ?: Kind.MORE
            carry = k
            clause = clause.removePrefix("of ").removePrefix("the ").trim()
            if (clause.length < 2) continue
            val tag = LENGTHS[clause]?.let { Features.LENGTH + it }
                ?: FORMATS[clause]?.let { Features.TYPE + it }
                ?: resolve(clause)
            if (tag.isBlank()) continue
            out.removeAll { it.tag == tag }
            out += Directive(k, tag)
        }
        return out
    }

    /**
     * The best match for [phrase] among [known] tags: exact, then singular/plural, then with the
     * spaces dropped, then the known tag containing it that you meet most.
     */
    fun resolver(known: Map<String, Int>): (String) -> String = { phrase ->
        val n = Features.normalizeTag(phrase)
        val variants = listOf(n, n.removeSuffix("s"), n.removeSuffix("es"), n + "s", n.replace("_", ""), n.removeSuffix("s").replace("_", ""))
        variants.firstOrNull { it in known }
            ?: known.keys.filter { it.replace("_", "") == n.replace("_", "") }.maxByOrNull { known[it] ?: 0 }
            ?: known.keys.filter { n.length >= 4 && (it.startsWith(n + "_") || it.endsWith("_$n")) }.maxByOrNull { known[it] ?: 0 }
            ?: n
    }

    /** True when [features] carry [tag] (formats and lengths included). */
    fun has(features: Collection<String>, tag: String): Boolean = tag in features
}
