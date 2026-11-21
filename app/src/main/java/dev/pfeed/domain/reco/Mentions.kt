package dev.pfeed.domain.reco

import dev.pfeed.domain.model.MediaItem

/**
 * Is a person you follow in this video, even when someone else uploaded it? Tube sites credit the
 * uploader or studio as the "creator", but name the performers in the title ("…, Anna Bell Peaks")
 * or among the tags. Only names of two words or more count, so a follow like "amateur" or a one-word
 * handle can't match half the site.
 */
object Mentions {
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** [text] as lowercase words with single spaces, padded so " name " only matches whole words. */
    fun padded(text: String?): String =
        " " + text.orEmpty().lowercase().split(SPLIT).filter { it.isNotEmpty() }.joinToString(" ") + " "

    /** [name] ready to look for, or null if it's too short or one word (too likely to match by chance). */
    fun personName(name: String): String? {
        val n = padded(name.substringBefore('@')).trim()
        return n.takeIf { it.count { c -> c == ' ' } >= 1 && it.length >= MIN_LENGTH }
    }

    /** Which of [names] (already passed through [personName]) are in [item]'s title or tags. */
    fun named(item: MediaItem, names: Collection<String>): List<String> {
        if (names.isEmpty()) return emptyList()
        // Title, tags, and the performers its site or page credits.
        val credited = item.extras[PERFORMERS]?.split(',').orEmpty() + PagePeople.of(item.key)
        val text = padded(item.title) + item.tags.joinToString("") { padded(it.replace('_', ' ')) } + credited.joinToString("") { padded(it) }
        return names.filter { text.contains(" $it ") }
    }

    /**
     * The performers a title names, in the two ways studio uploads put them: before the title
     * ("Auhneesh Nicole, Taysha Sativa - Two Maids…") or after it ("…monster trunk, Anna Bell Peaks").
     * A name is two or three capitalised words; anything with an everyday word in it isn't one.
     */
    fun performers(title: String?): List<String> {
        val t = title?.trim().orEmpty()
        if (t.isEmpty()) return emptyList()
        val dash = DASH.find(t)
        val before = dash?.let { t.substring(0, it.range.first) }?.let(::names).orEmpty()
        if (before.isNotEmpty()) return before
        val tail = t.substringAfterLast(", ", "").takeIf { it.isNotEmpty() && it.length < t.length }
        return tail?.let(::names)?.take(1).orEmpty()
    }

    /**
     * Names the app has learned are people (ones you follow, ones titles have named before), so a
     * title that names one anywhere ("Lena Paul gets…") is recognised, not only the "Name - Title" form.
     */
    @Volatile private var known: Map<String, String> = emptyMap()

    /** Adds [names] (as they should be shown) to the people [inTitle] looks for. */
    fun learn(names: Collection<String>) {
        val add = names.mapNotNull { n -> personName(n)?.let { it to n.substringBefore('@').trim() } }.filter { it.first !in known }
        if (add.isNotEmpty()) known = (known + add).let { if (it.size > MAX_KNOWN) it.entries.toList().takeLast(MAX_KNOWN).associate { e -> e.key to e.value } else it }
    }

    /** Extras key: the performers a site credits on the video, comma-separated. */
    const val PERFORMERS = "performers"

    /** The performers the site or [item]'s title credits, plus any known person it names anywhere in its title or tags. */
    fun inTitle(item: MediaItem): List<String> {
        val listed = (item.extras[PERFORMERS]?.split(',')?.map { it.trim() }.orEmpty() + PagePeople.of(item.key)).filter { personName(it) != null }
        val credited = (listed + performers(item.title)).distinctBy { it.lowercase() }
        val k = known
        if (k.isEmpty()) return credited
        val more = named(item, k.keys).mapNotNull { k[it] }
        return (credited + more).distinctBy { it.lowercase() }
    }

    private const val MAX_KNOWN = 2000

    /**
     * The person [query] most likely meant, when it's a name typed slightly wrong ("lena pual"):
     * the closest of [names] within two letters' difference, or null (also when it's spelled right).
     */
    fun closest(query: String, names: Collection<String>): String? {
        val q = personName(query) ?: return null
        var best: String? = null
        var bestD = MAX_TYPO + 1
        for (n in names) {
            val k = personName(n) ?: continue
            if (k == q) return null
            if (kotlin.math.abs(k.length - q.length) > MAX_TYPO) continue
            val d = editDistance(q, k, bestD)
            if (d < bestD) { bestD = d; best = n.substringBefore('@').trim() }
        }
        return best?.takeIf { bestD <= MAX_TYPO }
    }

    private const val MAX_TYPO = 2

    /** Letters to change, add or drop to turn [a] into [b] (stops counting past [limit]). */
    private fun editDistance(a: String, b: String, limit: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin >= limit) return limit
            prev = cur
        }
        return prev[b.length]
    }

    /** Everyone [inTitle] knows by name. */
    fun knownNames(): Collection<String> = known.values

    private fun names(part: String): List<String>? {
        val list = part.split(JOIN).map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty() || list.size > MAX_NAMES) return null
        return list.takeIf { it.all(::isName) }
    }

    private fun isName(s: String): Boolean {
        val words = s.split(' ').filter { it.isNotEmpty() }
        return words.size in 2..3 && words.all { w ->
            w.length >= 2 && w[0].isUpperCase() && w.drop(1).all { it.isLetter() || it == '\'' || it == '-' } &&
                w.drop(1).any { it.isLowerCase() } && w.lowercase() !in NOT_NAMES
        }
    }

    private val DASH = Regex("""\s[-–—]\s""")
    private val JOIN = Regex(""",|\s&\s|\sand\s""")
    private const val MAX_NAMES = 4
    private val NOT_NAMES = setOf(
        "the", "my", "her", "his", "hot", "big", "sexy", "busty", "horny", "petite", "teen", "milf", "step", "stepmom",
        "stepsister", "stepbro", "stepdad", "wife", "girl", "girls", "boy", "guy", "babe", "slut", "amateur", "blonde",
        "brunette", "redhead", "ebony", "asian", "latina", "black", "white", "young", "old", "mature", "cute", "beautiful",
        "naughty", "lesbian", "lesbians", "full", "video", "scene", "part", "episode", "with", "gets", "fucked", "fucks",
        "anal", "pov", "first", "time", "new", "best", "sex", "porn", "hd", "tits", "ass", "pussy", "cock", "dick",
        "massage", "public", "real", "homemade", "fake", "taxi", "agent", "casting", "family", "brother", "sister", "mom",
        "dad", "daughter", "son", "aunt", "uncle", "teacher", "nurse", "doctor", "boss", "maid", "neighbor", "neighbour",
        "on", "in", "at", "of", "for", "to", "an", "is", "and", "or", "by", "from", "dirty", "perv", "pervs", "office", "talk",
        "bitch", "dp", "vr", "4k", "xxx", "compilation", "cumshot", "creampie", "blowjob", "solo", "threesome", "orgy",
    )

    private const val MIN_LENGTH = 6
}
