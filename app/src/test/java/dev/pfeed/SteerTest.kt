package dev.pfeed

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.RankWeights
import dev.pfeed.domain.reco.Ranker
import dev.pfeed.domain.reco.Steer
import dev.pfeed.domain.reco.Steer.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class SteerTest {
    private val known = mapOf("redhead" to 40, "cosplay" to 30, "feet" to 12, "amateur" to 50, "big_tits" to 60, "pov" to 20)
    private fun parse(s: String) = Steer.parse(s, Steer.resolver(known)).map { it.kind to it.tag }

    @Test
    fun `reads everyday phrasing`() {
        assertEquals(listOf(Kind.MORE to "redhead", Kind.LESS to "cosplay"), parse("more redheads, less cosplay please"))
        assertEquals(listOf(Kind.ONLY to "amateur"), parse("only amateur tonight"))
        assertEquals(listOf(Kind.NO to "feet", Kind.NO to "cosplay"), parse("no feet and cosplay"))
        assertEquals(listOf(Kind.ONLY to "type:video"), parse("just videos"))
        assertEquals(listOf(Kind.ONLY to "type:video"), parse("videos only"))
        assertEquals(listOf(Kind.MORE to "len:short", Kind.MORE to "pov"), parse("short clips and pov"))
        assertEquals(listOf(Kind.MORE to "big_tits"), parse("big boobs"))
        assertEquals(listOf(Kind.MORE to "redhead"), parse("Redheads!"))
    }

    @Test
    fun `steers the ranking`() {
        fun item(id: Int, tags: List<String>, type: MediaType = MediaType.IMAGE) = MediaItem(
            id = "i$id", sourceId = "s${id % 5}", type = type, title = null, creator = "c$id", tags = tags,
            durationSec = null, thumbnailUrl = null, pageUrl = "", publishedAt = null, extras = mapOf("media" to "m"),
        )
        val items = (0 until 40).map { i -> item(i, listOf(if (i % 2 == 0) "redhead" else "cosplay", "x$i"), if (i % 4 == 0) MediaType.VIDEO else MediaType.IMAGE) }
        val ranker = Ranker(RankWeights(exploration = 0.0), Random(1))
        val more = ranker.rank(items, emptyMap(), emptyMap(), Instant.now(), limit = 10, steer = listOf(Steer.Directive(Kind.MORE, "redhead")))
        assertTrue("more redheads: ${more.map { it.tags }}", more.count { "redhead" in it.tags } >= 8)
        val no = ranker.rank(items, emptyMap(), emptyMap(), Instant.now(), limit = 10, steer = listOf(Steer.Directive(Kind.NO, "cosplay")))
        assertTrue("no cosplay at all", no.none { "cosplay" in it.tags } && no.isNotEmpty())
        val only = ranker.rank(items, emptyMap(), emptyMap(), Instant.now(), limit = 10, steer = listOf(Steer.Directive(Kind.ONLY, "type:video")))
        assertTrue("only videos", only.isNotEmpty() && only.all { it.type == MediaType.VIDEO })
    }
}
