package dev.pfeed

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.Related
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelatedTest {
    private fun v(id: String) = MediaItem(id = id, sourceId = "tube", type = MediaType.VIDEO, title = id, creator = null, tags = emptyList(),
        durationSec = 600, thumbnailUrl = "https://t/$id.jpg", pageUrl = "", publishedAt = null)

    @Test
    fun `enjoying a video queues what its site shows beside it, once`() {
        Related.drain()
        val seed = v("seed")
        Related.put(seed.key, listOf(v("a"), v("b"), v("c"), v("d")))
        Related.enjoyed(seed.key, "Seed video")
        assertEquals(listOf("a", "b", "c", "d"), Related.drain().map { it.id })
        assertTrue(Related.drain().isEmpty())
        assertEquals("Seed video", Related.recentlyEnjoyed().first().first)
    }
}
