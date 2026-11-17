package dev.pfeed

import dev.pfeed.domain.filter.AiArt
import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiArtTest {
    private fun item(tags: List<String> = emptyList(), title: String? = null, source: String = "booru", id: String = "gelbooru.com/1") =
        MediaItem(id = id, sourceId = source, type = MediaType.IMAGE, title = title, creator = null, tags = tags + listOf("nude", "sex", "pussy"), durationSec = null, thumbnailUrl = "https://x/t.jpg", pageUrl = "https://x/p", publishedAt = null)

    @Test fun `tags, titles and AI-only boards are recognised`() {
        assertTrue(AiArt.isAi(item(tags = listOf("ai_generated"))))
        assertTrue(AiArt.isAi(item(tags = listOf("ai-generated"))))
        assertTrue(AiArt.isAi(item(tags = listOf("Stable Diffusion"))))
        assertTrue(AiArt.isAi(item(title = "My new AI generated waifu")))
        assertTrue(AiArt.isAi(item(title = "made with midjourney")))
    }

    @Test fun `ordinary posts are left alone`() {
        assertFalse(AiArt.isAi(item(tags = listOf("hayasaka_ai", "blonde"))))
        assertFalse(AiArt.isAi(item(title = "Thai art model posing")))
        assertFalse(AiArt.isAi(item(title = "Said she'd wait")))
    }

    @Test fun `the blocklist only hides them when asked`() {
        val ai = item(tags = listOf("ai_generated"))
        assertTrue(Blocklist().allows(ai))
        assertFalse(Blocklist(hideAi = true).allows(ai))
    }
}

class GeoBlockTest {
    @Test fun `block pages are recognised and real pages are not`() {
        assertTrue(dev.pfeed.source.net.GeoBlock.looksBlocked("<title>Blocked due to legislation in the state of Virginia</title>"))
        assertTrue(dev.pfeed.source.net.GeoBlock.looksBlocked("Sorry, the site is not available in your region"))
        assertFalse(dev.pfeed.source.net.GeoBlock.looksBlocked("<title>Free Porn Videos</title><div class=\"thumbs\">"))
        org.junit.Assert.assertEquals("redgifs.com", dev.pfeed.source.net.GeoBlock.siteOf("api.redgifs.com"))
    }
}
