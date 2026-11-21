package dev.pfeed.domain.reco

import dev.pfeed.domain.model.MediaItem
import java.io.File

/**
 * Who is often in videos with whom: every listing the app reads that names two to four
 * performers (in its title, the site's credits or the video page) counts once per video for each
 * pair. A performer's card then offers the people she's most often with, to go on to.
 * Kept on the phone in a small file; learned the same in private mode, as it says nothing about you.
 */
object CoStars {
    private const val MAX_PEOPLE = 4000
    private const val MAX_PARTNERS = 30
    private const val MAX_KEYS = 20_000

    private val pairs = LinkedHashMap<String, HashMap<String, Int>>(256, 0.75f, true)
    private val shown = HashMap<String, String>()
    private val counted = object : LinkedHashMap<String, Unit>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > MAX_KEYS
    }
    @Volatile private var file: File? = null
    @Volatile private var dirty = false

    /** Each of [items] once (by key): the people it names, in pairs. */
    fun seen(items: List<MediaItem>) {
        for (item in items) {
            val names = Mentions.inTitle(item)
            if (names.size < 2) continue
            val fresh = synchronized(this) { counted.put(item.key, Unit) == null }
            if (fresh) record(names)
        }
    }

    /** [names] seen together in one video. */
    @Synchronized fun record(names: List<String>) {
        val people = names.mapNotNull { n -> Mentions.personName(n)?.let { it to n.trim() } }.distinctBy { it.first }
        if (people.size !in 2..4) return
        for ((k, n) in people) shown.putIfAbsent(k, n)
        for ((a, _) in people) for ((b, _) in people) if (a != b) {
            val m = pairs.getOrPut(a) { HashMap() }
            m[b] = (m[b] ?: 0) + 1
            if (m.size > MAX_PARTNERS) m.entries.minByOrNull { it.value }?.let { m.remove(it.key) }
        }
        while (pairs.size > MAX_PEOPLE) pairs.remove(pairs.keys.first())
        dirty = true
    }

    /** The people most often in videos with [name], most first (as titles write them). */
    @Synchronized fun with(name: String, max: Int = 6): List<String> {
        val k = Mentions.personName(name) ?: return emptyList()
        return pairs[k].orEmpty().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(max).map { shown[it.key] ?: it.key.split(' ').joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } } }
    }

    /** Where it's kept; read once at start (off the main thread). */
    fun load(dir: File) {
        val f = File(dir, "costars.tsv").also { file = it }
        if (!f.exists()) return
        runCatching {
            f.forEachLine { line ->
                val p = line.split('\t')
                synchronized(this) {
                    when {
                        p.size == 2 && p[0].startsWith("=") -> shown[p[0].drop(1)] = p[1]
                        p.size == 3 -> pairs.getOrPut(p[0]) { HashMap() }[p[1]] = p[2].toIntOrNull() ?: 1
                    }
                }
            }
        }
    }

    /** Written when the app goes to the background, if anything was learned. */
    fun save() {
        val f = file ?: return
        if (!dirty) return
        val text = synchronized(this) {
            dirty = false
            buildString {
                for ((k, n) in shown) if (k in pairs) append('=').append(k).append('\t').append(n.replace('\t', ' ')).append('\n')
                for ((a, m) in pairs) for ((b, c) in m) append(a).append('\t').append(b).append('\t').append(c).append('\n')
            }
        }
        runCatching { File(f.parentFile, f.name + ".tmp").also { it.writeText(text) }.renameTo(f) }
    }

    @Synchronized internal fun clearForTests() { pairs.clear(); shown.clear(); counted.clear() }
}
