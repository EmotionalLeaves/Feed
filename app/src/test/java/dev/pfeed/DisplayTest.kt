package dev.pfeed

import dev.pfeed.ui.feed.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTest {
    @Test
    fun `sources get short recognisable names`() {
        assertEquals("Rule34", Display.source(item("rule34.xxx/1", source = "booru"), "Booru"))
        assertEquals("Danbooru", Display.source(item("danbooru.donmai.us/1", source = "booru"), "Booru"))
        assertEquals("r/BDSM", Display.source(item("reddit/abc", source = "reddit", tags = listOf("BDSM")), "Reddit"))
        assertEquals("Coomer", Display.source(item("coomer.su/9/0", source = "kemono"), "Coomer / Kemono"))
        assertEquals("RedGIFs", Display.source(item("x", source = "redgifs"), "RedGIFs"))
        // Unknown board: first label, capitalised.
        assertEquals("Someboard", Display.source(item("someboard.net/5", source = "booru"), "Booru"))
    }

    @Test
    fun `creator handles are cleaned up`() {
        assertEquals("alice", Display.creator(item("1", creator = "/u/alice")))
        assertEquals("belle", Display.creator(item("1", source = "kemono", creator = "belle@onlyfans")))
        // A long numeric account id says nothing to a reader.
        assertNull(Display.creator(item("1", source = "kemono", creator = "84512397@patreon")))
        assertNull(Display.creator(item("1", creator = "  ")))
    }

    @Test
    fun `only meaningful tags are shown`() {
        val tags = Display.tags(
            item(
                "1",
                tags = listOf("1girl", "highres", "rating:explicit", "creator:123", "rope_bondage", "absurdres", "solo", "blindfold", "4321"),
            ),
        )
        assertEquals(listOf("rope bondage" to "rope_bondage", "blindfold" to "blindfold"), tags)
    }

    @Test
    fun `the subreddit is not repeated as a tag`() {
        val tags = Display.tags(item("1", source = "reddit", tags = listOf("BDSM")))
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `at most five tags`() {
        assertEquals(5, Display.tags(item("1", tags = (1..20).map { "topic_$it" })).size)
    }

    @Test
    fun `age shows only real dates`() {
        val now = java.time.Instant.parse("2026-11-16T12:00:00Z")
        val base = dev.pfeed.domain.model.MediaItem(id = "x/1", sourceId = "booru", type = dev.pfeed.domain.model.MediaType.IMAGE, title = null, creator = null, tags = emptyList(), durationSec = null, thumbnailUrl = null, pageUrl = "https://x", publishedAt = now.minusSeconds(3 * 3600))
        org.junit.Assert.assertEquals("3h", Display.age(base, now))
        org.junit.Assert.assertEquals(null, Display.age(base.copy(extras = mapOf(dev.pfeed.domain.model.MediaItem.APPROX_DATE to "1")), now))
        org.junit.Assert.assertEquals("2d", Display.age(base.copy(publishedAt = now.minusSeconds(2 * 86_400 + 5)), now))
    }
}
