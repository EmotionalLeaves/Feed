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

    @Test
    fun `a post beside several videos you enjoyed counts more, each video once`() {
        val one = v("cw-one"); val two = v("cw-two")
        Related.put(one.key, listOf(v("cw-x"), v("cw-y")))
        Related.put(two.key, listOf(v("cw-x"), v("cw-z")))
        Related.enjoyed(one.key); Related.enjoyed(one.key); Related.enjoyed(two.key)
        Related.drain()
        assertEquals(2, Related.support(v("cw-x").key))
        assertEquals(1, Related.support(v("cw-y").key))
        assertEquals(0, Related.support(v("cw-nothing").key))
    }

    @Test
    fun `kept between launches, co-watching included`() {
        val a = v("sv-a"); val b = v("sv-b")
        Related.put(a.key, listOf(v("sv-x"), v("sv-y")))
        Related.put(b.key, listOf(v("sv-x")))
        Related.enjoyed(a.key, "A"); Related.enjoyed(b.key, "B")
        Related.drain()
        val snap = Related.export()
        // Round trip through JSON, as it's stored.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(Related.Snapshot.serializer(), json.encodeToString(Related.Snapshot.serializer(), snap))
        Related.clear()
        assertEquals(0, Related.support(v("sv-x").key))
        Related.restore(back)
        assertEquals(2, Related.support(v("sv-x").key))
        assertEquals(listOf("sv-x"), Related.cowatched().map { it.id })
        // Restoring doesn't queue them for For You again.
        assertEquals(emptyList<MediaItem>(), Related.drain())
    }
}
