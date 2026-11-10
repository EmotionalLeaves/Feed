package dev.pfeed

import dev.pfeed.data.repo.MediaHealth
import dev.pfeed.domain.model.MediaType
import dev.pfeed.source.parse.BooruHtml
import dev.pfeed.source.parse.BooruParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are trimmed copies of what the sites served on 2026-11-09. */
class BoardPagesTest {

    @Test
    fun `gelbooru listing`() {
        val html = """<div class="thumbnail-container"> <article class="thumbnail-preview"> <a id="p15225054" href="https://gelbooru.com/index.php?page=post&amp;s=view&amp;id=15225054&tags=sex"> <img src="https://img4.gelbooru.com/thumbnails/d5/e8/thumbnail_d5e8.jpg" title=" 1boy 1girl animated sex score:12 rating:explicit" alt="Rule 34 | 1boy"/></a></article>
            <article class="thumbnail-preview"> <a id="p15225055" href="https://gelbooru.com/index.php?page=post&amp;s=view&amp;id=15225055"> <img src="https://img4.gelbooru.com/thumbnails/aa/bb/thumbnail_aabb.jpg" title="1girl solo rating:questionable"/></a></article>"""
        val items = BooruHtml.parseListing(html, "gelbooru.com")
        assertEquals(listOf("gelbooru.com/15225054", "gelbooru.com/15225055"), items.map { it.id })
        assertEquals(MediaType.VIDEO, items[0].type)
        assertEquals("e", items[0].extras["rating"])
        assertEquals(listOf("1boy", "1girl", "animated", "sex", "rating:explicit"), items[0].tags)
        assertEquals("https://gelbooru.com/index.php?page=post&s=view&id=15225054", items[0].pageUrl)
        assertEquals(MediaType.IMAGE, items[1].type)
    }

    @Test
    fun `rule34 and tbib listing`() {
        val html = """<span id="s28901772" class="thumb"><a id="p28901772" href="index.php?page=post&amp;s=view&amp;id=28901772" ><img src="//tbib.org/thumbnails/3883/thumbnail_f832.jpg?28901772" alt="anal anthro gif score:3 rating:explicit" class="preview"/></a></span>"""
        val item = BooruHtml.parseListing(html, "tbib.org").single()
        assertEquals("https://tbib.org/thumbnails/3883/thumbnail_f832.jpg?28901772", item.thumbnailUrl)
        assertEquals(MediaType.GIF, item.type)
        assertEquals("page", item.extras["resolve"])
    }

    @Test
    fun `realbooru listing has comma separated tags`() {
        val html = """<div class="col thumb" id="s1015468"><a id="p1015468" href="https://realbooru.com/index.php?page=post&amp;s=view&amp;id=1015468"><img src="https://realbooru.com/thumbnails/6d/b3/thumbnail_6db3.jpg" title="1boy, ball sucking, cowgirl position, mp4, oral sex" alt="Image: 1015468" style="border: 3px solid #0000ff;"/></a></div>"""
        val item = BooruHtml.parseListing(html, "realbooru.com").single()
        assertEquals(listOf("1boy", "ball_sucking", "cowgirl_position", "mp4", "oral_sex"), item.tags)
        assertEquals(MediaType.VIDEO, item.type)
    }

    @Test
    fun `post pages give the video, or the sample with the original kept for zoom`() {
        val still = """<img alt="tags" height="2495" src="//tbib.org//samples/3883/sample_8935.jpg?28901773" id="image" onclick="Note.toggle();" width="850"/>
            <a href="//tbib.org//images/3883/8935.png" onclick="x" style="font-weight: bold;">Original image</a>"""
        val s = BooruHtml.mediaFromPostPage(still, "https://tbib.org/index.php?page=post&s=view&id=1")!!
        assertEquals("https://tbib.org/samples/3883/sample_8935.jpg?28901773", s.url)
        assertEquals("https://tbib.org/images/3883/8935.png", s.full)

        val video = """<video style="width: 100%;" controls loop id="gelcomVideoPlayer"><source src="https://realbooru.com//images/cc/b8/ccb8.mp4" type="video/mp4"></video>
            <a style="color: #fff;" href="https://realbooru.com//images/cc/b8/ccb8.webm">Original</a>"""
        assertEquals("https://realbooru.com/images/cc/b8/ccb8.mp4", BooruHtml.mediaFromPostPage(video, "https://realbooru.com/x")!!.url)

        val gif = """<img src="https://x.org/samples/1/sample_a.jpg" id="image"/><a href="https://x.org/images/1/a.gif">Original image</a>"""
        assertEquals("https://x.org/images/1/a.gif", BooruHtml.mediaFromPostPage(gif, "https://x.org/p")!!.url)

        assertNull(BooruHtml.mediaFromPostPage("<html>nothing here</html>", "https://x.org/p"))
    }

    @Test
    fun `tbib api posts get their file urls built from directory and image`() {
        val root = Json.parseToJsonElement(
            """[{"directory":3883,"hash":"b57d","height":4320,"id":28901773,"image":"8935.png","rating":"explicit","sample":true,"score":null,"tags":"after_sex anthro","width":1472},
               {"directory":12,"id":5,"image":"clip.mp4","rating":"explicit","sample":false,"tags":"animated"}]""",
        )
        val (a, b) = BooruParser.parse(root, "tbib.org")
        assertEquals("https://tbib.org/samples/3883/sample_8935.jpg", a.extras["media"])
        assertEquals("https://tbib.org/images/3883/8935.png", a.extras["full"])
        assertEquals("https://tbib.org/thumbnails/3883/thumbnail_8935.jpg", a.thumbnailUrl)
        assertEquals(MediaType.VIDEO, b.type)
        assertEquals("https://tbib.org/images/12/clip.mp4", b.extras["media"])
    }

    // ── A site whose files stop loading is rested ──

    @Test
    fun `an origin rests after three failures in a row while others load`() {
        var t = 1_000L
        val h = MediaHealth().also { it.now = { t } }
        val coomer = (1..4).map { item("coomer.st/$it/0", source = "kemono") }
        val other = item("reddit/t3_1", source = "reddit", tags = listOf("sub"))

        assertFalse(h.failed(coomer[0])); t += 10
        h.shown(other); t += 10
        assertFalse(h.failed(coomer[1])); t += 10
        assertTrue(h.failed(coomer[2]))
        assertTrue(h.isResting(coomer[3]))
        assertFalse(h.isResting(other))
        t += MediaHealth.REST_MS + 1
        assertFalse(h.isResting(coomer[3]))
        assertTrue(h.failed(coomer[3])) // one more failure after the rest → rested again
    }

    @Test
    fun `nothing rests when everything is failing (the phone is offline)`() {
        val h = MediaHealth()
        repeat(6) { assertFalse(h.failed(item("coomer.st/$it/0", source = "kemono"))) }
        repeat(6) { assertFalse(h.failed(item("reddit/t3_$it", source = "reddit", tags = listOf("s")))) }
    }

    @Test
    fun `a success clears the count`() {
        val h = MediaHealth()
        val other = item("reddit/t3_1", source = "reddit", tags = listOf("sub"))
        val c = (1..5).map { item("coomer.st/$it/0", source = "kemono") }
        h.failed(c[0]); h.failed(c[1]); h.shown(c[2]); h.shown(other)
        assertFalse(h.failed(c[3]))
        assertFalse(h.isResting(c[4]))
    }
}
