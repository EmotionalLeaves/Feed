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
        val pony = (1..6).map { item("site$it.org/$it", listOf("pony"), source = "p$it") }
        val other = (1..11).map { item("other$it.org/$it", listOf("milf"), source = "o$it") }
        val out = Spread.order(pony + other, emptyList(), limit = 12, allowForcedRun = false)
        assertEquals(1, out.take(12).count { Niche.of(it) != null })

        // Watching doesn't open it; liking or saving three pony posts, or following a pony tag, does.
        assertTrue(Niche.opened(pony.take(2), emptySet()).isEmpty())
        assertEquals(setOf(Niche.MLP), Niche.opened(pony.take(3), emptySet()))
        assertEquals(setOf(Niche.MLP), Niche.opened(emptyList(), setOf("twilight_sparkle")))
        Spread.openNiches = Niche.opened(pony.take(3), emptySet())
        val open = Spread.order(pony + other, emptyList(), limit = 12, allowForcedRun = false)
        assertTrue(open.count { Niche.of(it) != null } > 1)
    }

    @Test
    fun `closed niche is one in 36, and board-style pony tags are recognised`() {
        val pony = (1..20).map { item("site$it.org/$it", listOf("rarity_(mlp)"), source = "p$it") }
        val other = (1..40).map { item("other$it.org/$it", listOf("milf"), source = "o${it % 20}") }
        val out = Spread.order(pony + other, emptyList(), limit = 36, allowForcedRun = false)
        assertEquals(1, out.take(36).count { Niche.of(it) != null })
        assertEquals(Niche.MLP, Niche.of(item("e621.net/9", listOf("my_little_pony_friendship_is_magic"))))
        assertEquals(Niche.MLP, Niche.of(item("e621.net/9", listOf("equestria_girls_(franchise)"))))
    }
}
