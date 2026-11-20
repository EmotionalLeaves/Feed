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
        val text = padded(item.title) + item.tags.joinToString("") { padded(it.replace('_', ' ')) }
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
