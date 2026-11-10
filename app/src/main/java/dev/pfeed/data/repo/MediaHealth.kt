package dev.pfeed.data.repo

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.reco.Spread
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notices when one site's *files* stop loading even though its listings still work (e.g. Coomer's
 * file servers being down while its API answers). Without this, every post from that site sat on a
 * spinner until the dead-clip timeout before being skipped, one after another.
 *
 * After [BREAKS] posts in a row from one origin fail to show, with posts from elsewhere showing
 * fine in the meantime (so it isn't the phone's own connection), that origin is rested for
 * [REST_MS] and then tried again.
 */
@Singleton
class MediaHealth @Inject constructor() {
    internal var now: () -> Long = System::currentTimeMillis

    private class State(var breaks: Int = 0, var firstBreakAt: Long = 0, var restUntil: Long = 0)
    private val states = ConcurrentHashMap<String, State>()
    @Volatile private var lastSuccessAt = 0L
    @Volatile private var lastSuccessOrigin: String? = null

    /** How many posts have actually displayed (first video frame / image drawn) since launch. */
    val shownCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** The last few outcomes ("shown booru:x.org IMAGE", "failed redgifs VIDEO: stalled"), for diagnostics. */
    private val recent = java.util.concurrent.ConcurrentLinkedDeque<String>()
    fun recentEvents(): List<String> = recent.toList()
    private fun note(event: String) { recent.addLast(event); while (recent.size > 40) recent.pollFirst() }

    fun shown(item: MediaItem) {
        shownCount.incrementAndGet()
        note("shown ${Spread.origin(item)} ${item.type}")
        val o = Spread.origin(item)
        lastSuccessAt = now()
        lastSuccessOrigin = o
        states.remove(o)
    }

    /** @return true if this failure just put the origin to rest. */
    fun failed(item: MediaItem, reason: String = ""): Boolean {
        val o = Spread.origin(item)
        note("failed $o ${item.type}: $reason")
        val s = states.getOrPut(o) { State() }
        synchronized(s) {
            if (s.breaks == 0) s.firstBreakAt = now()
            s.breaks++
            val othersFine = lastSuccessAt >= s.firstBreakAt && lastSuccessOrigin != o
            if (s.breaks >= BREAKS && othersFine && s.restUntil < now()) {
                s.restUntil = now() + REST_MS
                return true
            }
        }
        return false
    }

    fun isResting(item: MediaItem): Boolean = isResting(Spread.origin(item))

    fun isResting(origin: String): Boolean {
        val s = states[origin] ?: return false
        if (s.restUntil == 0L) return false
        if (now() >= s.restUntil) {
            // Rest over: one more failure rests it again straight away.
            synchronized(s) { s.restUntil = 0; s.breaks = BREAKS - 1; s.firstBreakAt = 0 }
            return false
        }
        return true
    }

    /** Origins currently rested, for the Sources screen. */
    fun resting(): List<String> = states.filter { it.value.restUntil > now() }.keys.sorted()

    companion object {
        const val BREAKS = 3
        const val REST_MS = 10 * 60_000L
    }
}
