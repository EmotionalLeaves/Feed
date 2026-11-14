package dev.pfeed

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.Niche
import dev.pfeed.domain.reco.Spread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NicheTest {
    private fun item(id: String, tags: List<String> = emptyList(), source: String = "booru") = MediaItem(
        id = id, sourceId = source, type = MediaType.IMAGE, title = null, creator = null, tags = tags,
        durationSec = null, thumbnailUrl = null, pageUrl = "https://x/$id", publishedAt = null, extras = mapOf("media" to "https://x/$id.jpg"),
    )

    @After fun reset() { Spread.openNiches = emptySet() }

    @Test
    fun `derpibooru and pony-tagged posts are the mlp niche`() {
        assertEquals(Niche.MLP, Niche.of(item("derpibooru.org/1", source = "philomena")))
        assertEquals(Niche.MLP, Niche.of(item("e621.net/2", listOf("explicit", "twilight_sparkle"))))
        assertEquals(null, Niche.of(item("gelbooru.com/3", listOf("milf", "ponytail"))))
    }

    @Test
    fun `the mlp niche gets one slot per window unless the user wants it`() {
        // Pony posts from many different origins, so only the niche rule can hold them back.
        val pony = (1..6).map { item("site$it.org/$it", listOf("pony")) }
        val other = (1..11).map { item("other$it.org/$it", listOf("milf")) }
        val out = Spread.order(pony + other, emptyList(), limit = 12, allowForcedRun = false)
        assertEquals(1, out.take(12).count { Niche.of(it) != null })

        Spread.openNiches = Niche.opened(mapOf("pony" to 50.0))
        val open = Spread.order(pony + other, emptyList(), limit = 12, allowForcedRun = false)
        assertTrue(open.count { Niche.of(it) != null } > 1)
    }
}
