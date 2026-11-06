package dev.pfeed

import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.Deduplicator
import dev.pfeed.domain.reco.PerceptualHash
import org.junit.Assert.assertEquals
import org.junit.Test

class DeduplicatorTest {

    @Test
    fun `same normalized title and duration across sources collapses, first source wins`() {
        val a = item("1", source = "api", title = "Sunset Timelapse [HD] (1080p)", duration = 120)
        val b = item("9", source = "rss", title = "sunset   timelapse!", duration = 121)
        val out = Deduplicator().filter(listOf(a, b))
        assertEquals(listOf(a.key), out.map { it.key })
    }

    @Test
    fun `same title but different duration is kept`() {
        val a = item("1", source = "api", title = "Sunset timelapse", duration = 120)
        val b = item("2", source = "rss", title = "Sunset timelapse", duration = 300)
        assertEquals(2, Deduplicator().filter(listOf(a, b)).size)
    }

    @Test
    fun `short or missing titles never match on title alone`() {
        val a = item("1", source = "a", title = "IMG", duration = null, type = MediaType.IMAGE)
        val b = item("2", source = "b", title = "IMG", duration = null, type = MediaType.IMAGE)
        assertEquals(2, Deduplicator().filter(listOf(a, b)).size)
    }

    @Test
    fun `near-identical thumbnail hashes collapse images`() {
        val a = item("1", source = "a", title = "one", type = MediaType.IMAGE)
        val b = item("2", source = "b", title = "two", type = MediaType.IMAGE)
        val hashA = 0b1011_0110_1110_0001L
        val hashB = hashA xor 0b101L // 2 bits differ
        val out = Deduplicator().filter(listOf(a, b), mapOf(a.key to hashA, b.key to hashB))
        assertEquals(1, out.size)
    }

    @Test
    fun `videos need matching duration for a hash match`() {
        val a = item("1", source = "a", title = "one", duration = 60)
        val b = item("2", source = "b", title = "two", duration = 600)
        val h = 0x0F0F_0F0F_0F0F_0F0FL
        assertEquals(2, Deduplicator().filter(listOf(a, b), mapOf(a.key to h, b.key to h)).size)
    }

    @Test
    fun `state carries across pages and same key is never repeated`() {
        val d = Deduplicator()
        val a = item("1", title = "A fairly long unique title", duration = 30)
        d.filter(listOf(a))
        assertEquals(0, d.filter(listOf(a, item("2", source = "other", title = "A fairly long unique title", duration = 31))).size)
    }

    @Test
    fun `dHash is stable and distance counts bits`() {
        val gradient = IntArray(72) { i -> (i % 9) * 20 } // increasing left→right: all bits 0
        assertEquals(0L, PerceptualHash.dHash(gradient))
        val reversed = IntArray(72) { i -> (8 - i % 9) * 20 } // decreasing: all 64 bits 1
        assertEquals(64, PerceptualHash.distance(PerceptualHash.dHash(gradient), PerceptualHash.dHash(reversed)))
    }

    @Test
    fun `different board posts are not duplicates just because they share tags`() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"id":1,"file_url":"https://c/1.jpg","tags":"1girl ass bdsm big_breasts rope"},
                {"id":2,"file_url":"https://c/2.jpg","tags":"1girl ass bdsm big_breasts gag"}]""",
        )
        val posts = dev.pfeed.source.parse.BooruParser.parse(root, "gelbooru.com")
        assertEquals(2, Deduplicator().filter(posts).size)
    }

    @Test
    fun `images from one gallery post are all kept`() {
        val a = item("a", title = "A long shared caption for a gallery", type = dev.pfeed.domain.model.MediaType.IMAGE)
        val b = item("b", title = "A long shared caption for a gallery", type = dev.pfeed.domain.model.MediaType.IMAGE)
            .copy(pageUrl = a.pageUrl)
        assertEquals(2, Deduplicator().filter(listOf(a, b)).size)
    }
}
