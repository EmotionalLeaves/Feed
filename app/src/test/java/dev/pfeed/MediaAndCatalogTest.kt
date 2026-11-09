package dev.pfeed

import dev.pfeed.data.settings.DefaultSources
import dev.pfeed.data.settings.MastodonExclusions
import dev.pfeed.data.settings.SourceCatalog
import dev.pfeed.domain.filter.MinorsFilter
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.source.kemono.KemonoAdapter
import dev.pfeed.source.reddit.RedditRss
import dev.pfeed.ui.feed.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAndCatalogTest {

    // ── Reddit feed parsing ──

    private fun entry(id: String, link: String, img: String = "https://b.thumbs.redditmedia.com/tiny.jpg", sub: String = "testsub") = """
        <entry><author><name>/u/alice</name><uri>https://www.reddit.com/user/alice</uri></author>
        <category term="$sub" label="r/$sub"/>
        <content type="html">&lt;table&gt; &lt;tr&gt;&lt;td&gt; &lt;a href=&quot;https://www.reddit.com/r/$sub/comments/$id/x/&quot;&gt; &lt;img src=&quot;$img&quot; alt=&quot;x&quot; title=&quot;x&quot; /&gt; &lt;/a&gt; &lt;/td&gt;&lt;td&gt; &amp;#32; submitted by &amp;#32; &lt;a href=&quot;https://www.reddit.com/user/alice&quot;&gt; /u/alice &lt;/a&gt; &lt;br/&gt; &lt;span&gt;&lt;a href=&quot;$link&quot;&gt;[link]&lt;/a&gt;&lt;/span&gt; &amp;#32; &lt;span&gt;&lt;a href=&quot;https://www.reddit.com/r/$sub/comments/$id/x/&quot;&gt;[comments]&lt;/a&gt;&lt;/span&gt; &lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;</content>
        <id>t3_$id</id><media:thumbnail url="https://b.thumbs.redditmedia.com/tiny.jpg" />
        <link href="https://www.reddit.com/r/$sub/comments/$id/x/" />
        <updated>2026-11-01T10:00:00+00:00</updated><published>2026-11-01T10:00:00+00:00</published>
        <title>Post $id</title></entry>
    """.trimIndent()

    private val feed = """<?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
        <category term="testsub" label="r/testsub"/><title>testsub</title>
        ${entry("aaa", "https://i.redd.it/abc.jpg", img = "https://preview.redd.it/abc.jpg?width=640&amp;amp;crop=smart&amp;amp;s=1")}
        ${entry("bbb", "https://v.redd.it/vid123")}
        ${entry("ccc", "https://www.redgifs.com/watch/HappyBigCat", sub = "othersub")}
        ${entry("ddd", "https://i.imgur.com/zzz.gifv")}
        ${entry("eee", "https://www.reddit.com/gallery/eee")}
        </feed>"""

    @Test
    fun `reddit posts use the real linked media, not the tiny thumbnail`() {
        val items = RedditRss.parse(feed, "testsub")
        assertEquals(listOf("reddit/t3_aaa", "reddit/t3_bbb", "reddit/t3_ccc", "reddit/t3_ddd"), items.map { it.id })

        val image = items[0]
        assertEquals(MediaType.IMAGE, image.type)
        assertEquals("https://i.redd.it/abc.jpg", image.extras["media"])
        assertEquals("https://preview.redd.it/abc.jpg?width=640&crop=smart&s=1", image.thumbnailUrl)
        assertEquals("alice", image.creator)
        assertEquals("Post aaa", image.title)
        assertEquals(listOf("testsub"), image.tags)
        assertTrue(image.publishedAt != null)

        val video = items[1]
        assertEquals(MediaType.VIDEO, video.type)
        assertEquals("https://v.redd.it/vid123/HLSPlaylist.m3u8", video.extras["media"])
        assertEquals(RedditRss.HLS_MIME, video.extras["mime"])

        val clip = items[2]
        assertEquals(MediaType.VIDEO, clip.type)
        assertEquals("redgifs", clip.extras["resolve"])
        assertEquals("happybigcat", clip.extras["gid"])
        assertEquals(listOf("othersub"), clip.tags) // from the entry's own <category>

        assertEquals("https://i.imgur.com/zzz.mp4", items[3].extras["media"])
        assertEquals(MediaType.VIDEO, items[3].type)
    }

    @Test
    fun `reddit paging token is the last post fullname`() {
        assertEquals("t3_eee", RedditRss.lastId(feed))
        assertNull(RedditRss.lastId("<feed></feed>"))
    }

    @Test
    fun `reddit link classification`() {
        assertEquals(MediaType.GIF, RedditRss.mediaFor("https://i.redd.it/x.gif")?.type)
        assertEquals("happycat", RedditRss.mediaFor("https://redgifs.com/ifr/HappyCat")?.redgifsId)
        assertEquals("happycat", RedditRss.mediaFor("https://i.redgifs.com/i/happycat.jpg")?.redgifsId)
        assertEquals("https://i.imgur.com/abc.jpg", RedditRss.mediaFor("https://imgur.com/abc")?.url)
        assertNull(RedditRss.mediaFor("https://imgur.com/a/album"))
        assertNull(RedditRss.mediaFor("https://www.reddit.com/r/x/comments/1/y/"))
        assertNull(RedditRss.mediaFor("https://www.pornhub.com/view_video.php?viewkey=1"))
    }

    // ── Rendering by real file type ──

    @Test
    fun `an image post whose file is a video plays as a video`() {
        val post = item("b/1", type = MediaType.IMAGE)
        assertEquals(MediaType.VIDEO, Display.effectiveType(post, StreamInfo.Direct("https://x.org/a.webm", null)))
        assertEquals(MediaType.IMAGE, Display.effectiveType(post, StreamInfo.Direct("https://x.org/a.jpg", null)))
        assertEquals(MediaType.IMAGE, Display.effectiveType(post, null))
    }

    @Test
    fun `a video post that resolved to a still is detected`() {
        val clip = item("b/2", type = MediaType.VIDEO)
        assertTrue(Display.isStillForVideo(clip, StreamInfo.Direct("https://x.org/thumb.jpg", null)))
        assertFalse(Display.isStillForVideo(clip, StreamInfo.Direct("https://x.org/clip.mp4", null)))
        assertFalse(Display.isStillForVideo(clip, StreamInfo.Direct("https://v.redd.it/a/HLSPlaylist.m3u8", RedditRss.HLS_MIME)))
    }

    // ── Minors filter ──

    @Test
    fun `minors filter blocks school and stated ages under 18`() {
        assertFalse(MinorsFilter.allows(item("m1", tags = listOf("high_school"))))
        assertFalse(MinorsFilter.allows(item("m2", tags = listOf("jailbait"))))
        assertFalse(MinorsFilter.allows(item("m3", title = "my 16yo sister")))
        assertFalse(MinorsFilter.allows(item("m4", title = "she is 15 years old")))
        assertFalse(MinorsFilter.allows(item("m5", title = "14 y.o. girl")))
        assertTrue(MinorsFilter.allows(item("a1", title = "21yo first time")))
        assertTrue(MinorsFilter.allows(item("a2", title = "18 years old today")))
        assertTrue(MinorsFilter.allows(item("a3", title = "my 1080p video", tags = listOf("adult_fiction"))))
        assertTrue(MinorsFilter.allows(item("a4", tags = listOf("milf", "huge_breasts"))))
    }

    // ── Built-in library ──

    @Test
    fun `built-in library has thousands of feeds and nothing excluded`() {
        val total = DefaultSources.redditSubs.size + DefaultSources.booruFeeds.size +
            DefaultSources.mastodonFeeds.size + SourceCatalog.redgifsTerms.size
        assertTrue("expected thousands of feeds, got $total", total >= 3000)
        assertTrue(DefaultSources.mastodonFeeds.none { MastodonExclusions.isExcluded(it.host) })
        assertTrue(DefaultSources.booruFeeds.none { DefaultSources.isSfwOnlyHost(it.host) })
        assertTrue(DefaultSources.kemonoHosts.none { DefaultSources.isSfwOnlyHost(it) })
        // Unique after merging hand-picked and generated feeds.
        assertEquals(DefaultSources.redditSubs.size, DefaultSources.redditSubs.map { it.lowercase() }.distinct().size)
        assertEquals(DefaultSources.booruFeeds.size, DefaultSources.booruFeeds.map { it.host + "|" + it.tags }.distinct().size)
    }

    @Test
    fun `no built-in feed is age-adjacent`() {
        val bad = listOf("teen", "school", "petite", "flat_chest", "small_breasts", "loli", "shota", "young", "child")
        val terms = SourceCatalog.booruFeeds.mapNotNull { it.tags } + SourceCatalog.redgifsTerms +
            SourceCatalog.mastodonFeeds.mapNotNull { it.tag }
        val hits = terms.filter { t -> bad.any { t.lowercase().contains(it) } }
        assertTrue("age-adjacent built-in feeds: $hits", hits.isEmpty())
    }

    @Test
    fun `coomer moved domains`() {
        assertEquals("coomer.st", KemonoAdapter.canonicalHost("coomer.su"))
        assertEquals("coomer.st", KemonoAdapter.canonicalHost("https://www.coomer.party/"))
        assertEquals("coomer.st", KemonoAdapter.canonicalHost("coomer.st"))
        assertEquals(listOf("coomer.st"), DefaultSources.kemonoHosts)
    }
}
