package dev.pfeed

import dev.pfeed.domain.filter.MinorsFilter
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.Spread
import dev.pfeed.source.booru.BooruAdapter
import dev.pfeed.source.kemono.KemonoAdapter
import dev.pfeed.ui.feed.Display
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a coomer post's images are one set, its video stays separate`() {
        val post = json.parseToJsonElement(
            """{"id":"99","user":"ana","service":"onlyfans","title":"Comic ch.2 #milf","published":"2026-11-01T10:00:00",
                "file":{"path":"/a/cover.jpg"},
                "attachments":[{"path":"/a/p1.jpg"},{"path":"/a/p2.png"},{"path":"/a/clip.mp4"},{"path":"/a/notes.pdf"}]}""",
        ) as JsonObject
        val items = KemonoAdapter.itemsFromPost("coomer.st", post)
        assertEquals(2, items.size)
        val set = items.first { it.isGallery }
        assertEquals("coomer.st/99/set", set.id)
        assertEquals(MediaType.IMAGE, set.type)
        assertEquals(listOf("https://coomer.st/data/a/cover.jpg", "https://coomer.st/data/a/p1.jpg", "https://coomer.st/data/a/p2.png"), set.pages)
        assertEquals(set.pages.first(), set.extras["media"])
        assertEquals("https://img.coomer.st/thumbnail/data/a/cover.jpg", set.thumbnailUrl)
        assertTrue(set.tags.contains("milf"))
        val clip = items.first { !it.isGallery }
        assertEquals(MediaType.VIDEO, clip.type)
        assertEquals(set.thumbnailUrl, clip.thumbnailUrl) // a still from the same post
        // One post → one spacing key, so the set isn't scattered across the feed any more.
        assertEquals(Spread.origin(set), Spread.origin(clip))
    }

    @Test
    fun `a single-image post stays a plain item`() {
        val post = json.parseToJsonElement("""{"id":"5","user":"u","service":"fansly","file":{"path":"/x.jpg"},"attachments":[]}""") as JsonObject
        val only = KemonoAdapter.itemsFromPost("coomer.st", post).single()
        assertFalse(only.isGallery)
        assertEquals("coomer.st/5/0", only.id)
    }

    private fun page(id: Long, rating: String, type: MediaType = MediaType.IMAGE, tags: List<String> = listOf("comic", "sex")) =
        item("e621.net/$id", source = "booru", type = type, tags = tags).copy(extras = mapOf("media" to "https://e621.net/f/$id.jpg", "rating" to rating, "score" to "$id"))

    @Test
    fun `a pool becomes one comic in the pool's order, with safe pages dropped`() {
        val posts = listOf(page(3, "e"), page(1, "s"), page(2, "q"), page(4, "e"), page(5, "e", type = MediaType.VIDEO))
        val comic = BooruAdapter.poolGallery("e621.net", 77, "Night_Shift ch1", order = listOf(1, 2, 3, 4, 5), posts = posts)!!
        assertEquals("e621.net/pool/77", comic.id)
        assertEquals("Night Shift ch1", comic.title)
        assertEquals(listOf("https://e621.net/f/2.jpg", "https://e621.net/f/3.jpg", "https://e621.net/f/4.jpg"), comic.pages)
        assertEquals("https://e621.net/f/2.jpg", comic.extras["media"])
        assertEquals("e", comic.extras["rating"])
        assertEquals("4", comic.extras["score"])
        assertEquals("https://e621.net/pools/77", comic.pageUrl)
        assertEquals("comic", comic.tags.first())
        assertEquals("booru:e621.net", Spread.origin(comic))
    }

    @Test
    fun `pools that are too short after filtering are skipped`() {
        assertNull(BooruAdapter.poolGallery("e621.net", 1, "x", listOf(1, 2, 3), listOf(page(1, "e"), page(2, "s"), page(3, "s"))))
    }

    @Test
    fun `a blocked tag on any page blocks the whole comic`() {
        val posts = listOf(page(1, "e"), page(2, "e", tags = listOf("comic", "loli")), page(3, "e"))
        val comic = BooruAdapter.poolGallery("e621.net", 2, "x", listOf(1, 2, 3), posts)!!
        assertFalse(MinorsFilter.allows(comic))
    }

    @Test
    fun `gallery pages open as plain image items`() {
        val set = item("coomer.st/9/set", source = "kemono").copy(
            extras = mapOf("media" to "u1", "full" to "big1", "resolve" to "page"),
            pages = listOf("u1", "u2", "u3"),
        )
        val p2 = Display.galleryPage(set, 1)
        assertEquals("coomer.st/9/set#1", p2.id)
        assertEquals("u2", p2.extras["media"])
        assertNull(p2.extras["full"])
        assertNull(p2.extras["resolve"])
        assertFalse(p2.isGallery)
        assertEquals(MediaType.IMAGE, p2.type)
    }
}

class NoveltyTest {
    @Test
    fun `places you haven't seen lately are preferred over equally good familiar ones`() {
        val now = java.time.Instant.now()
        fun sub(name: String, n: Int) = (1..n).map { item("reddit/t3_${name}_$it", source = "reddit", type = MediaType.IMAGE, tags = listOf(name)) }
        val familiar = sub("gonewild", 6) + sub("nsfw", 6)
        val fresh = sub("newsub", 2) + sub("othersub", 2)
        val recentOrigins = List(30) { "r/gonewild" } + List(30) { "r/nsfw" }
        val ranked = dev.pfeed.domain.reco.Ranker(dev.pfeed.domain.reco.RankWeights(exploration = 0.0), kotlin.random.Random(3))
            .rank(familiar + fresh, emptyMap(), emptyMap(), now, limit = 6, recentOrigins = recentOrigins)
        // The bonus is for an origin's first appearance: both unseen subreddits lead, one each.
        val top = ranked.take(2).map { Spread.origin(it) }.toSet()
        assertEquals(setOf("r/newsub", "r/othersub"), top)
    }
}
