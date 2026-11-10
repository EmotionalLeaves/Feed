package dev.pfeed

import dev.pfeed.domain.filter.MinorsFilter
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.domain.reco.Spread
import dev.pfeed.source.nhentai.NhentaiAdapter
import dev.pfeed.source.parse.MediaTypes
import dev.pfeed.source.tube.TubeSite
import dev.pfeed.source.tube.items
import dev.pfeed.ui.feed.Display
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TubeSitesTest {

    @Test
    fun `xvideos listing and player`() {
        val html = """
            <div id="video_81234567" class="thumb-block  " data-id="81234567"><div class="thumb-inside"><div class="thumb"><a href="/video.ukfhdck1234/hot_title_here"><img src="data:," data-src="https://img-hw.xvideos-cdn.com/videos/thumbs169lll/5c/1f/5c1f/1.jpg" /></a></div></div>
            <div class="thumb-under"><p class="title"><a href="/video.ukfhdck1234/hot_title_here" title="Hot title &amp; more">Hot title &amp; more</a> <span class="duration">12 min</span></p></div></div>
            <div id="video_81234568" class="thumb-block" data-id="81234568"><div class="thumb-inside"><div class="thumb"><a href="/video.abcd/x"><img data-src="https://img.xvideos-cdn.com/t.jpg"/></a></div></div><div class="thumb-under"><p class="title"><a href="/video.abcd/x" title="Second">Second</a> <span class="duration">1 h 2 min</span></p></div></div>
        """
        val list = TubeSite.Xvideos.parseList(html)
        assertEquals(2, list.size)
        assertEquals("81234567", list[0].id)
        assertEquals("Hot title & more", list[0].title)
        assertEquals("https://www.xvideos.com/video.ukfhdck1234/hot_title_here", list[0].pageUrl)
        assertEquals("https://img-hw.xvideos-cdn.com/videos/thumbs169lll/5c/1f/5c1f/1.jpg", list[0].thumb)
        assertEquals(12 * 60, list[0].durationSec)

        val page = """html5player.setVideoUrlLow('https://cdn77-vid.xvideos-cdn.com/a/low.mp4?e=1');html5player.setVideoUrlHigh('https://cdn77-vid.xvideos-cdn.com/a/high.mp4?e=1');html5player.setVideoHLS('https://cdn77-vid.xvideos-cdn.com/a/hls.m3u8?e=1');"""
        val s = TubeSite.Xvideos.stream(page)!!
        assertEquals("https://cdn77-vid.xvideos-cdn.com/a/hls.m3u8?e=1", s.url)
        assertEquals(MediaTypes.HLS_MIME, s.mime)
        assertEquals("https://cdn77-vid.xvideos-cdn.com/a/high.mp4?e=1", TubeSite.Xvideos.stream(page.substringBefore("html5player.setVideoHLS"))!!.url)
        assertEquals("https://www.xvideos.com/embedframe/81234567", TubeSite.Xvideos.embedUrl("81234567"))
    }

    @Test
    fun `pornhub listing and media definitions`() {
        val html = """
            <li class="pcVideoListItem js-pop videoblock videoBox" data-video-vkey="ph5f0a1b2c3d4e5"><div class="phimage"><a href="/view_video.php?viewkey=ph5f0a1b2c3d4e5" title="Nice one" class="linkVideoThumb"><img src="data:," data-src="https://ei.phncdn.com/videos/202411/01/abc/original/(m=eaf8Ggaaaa)(mh=x)1.jpg" title="Nice one" /></a><div class="marker-overlays"><var class="duration">10:23</var></div></div></li>
            <li class="pcVideoListItem" data-video-vkey="ph999"><a href="/view_video.php?viewkey=ph999" title="Two"><img data-thumb_url="https://ei.phncdn.com/two.jpg"></a><var class="duration">1:00:05</var></li>
        """
        val list = TubeSite.Pornhub.parseList(html)
        assertEquals(listOf("ph5f0a1b2c3d4e5", "ph999"), list.map { it.id })
        assertEquals("Nice one", list[0].title)
        assertEquals(623, list[0].durationSec)
        assertEquals(3605, list[1].durationSec)
        assertTrue(list[0].thumb!!.startsWith("https://ei.phncdn.com/"))

        val page = """var flashvars_1 = {"experimentId":0,"mediaDefinitions":[{"defaultQuality":false,"format":"hls","videoUrl":"https:\/\/www.pornhub.com\/video\/get_media?s=abc&f=hls","quality":[240,480,720,1080]},{"format":"mp4","videoUrl":"https:\/\/ev.phncdn.com\/v\/480.mp4?x=1","quality":"480"},{"format":"mp4","videoUrl":"https:\/\/ev.phncdn.com\/v\/720.mp4?x=1","quality":"720"}],"isVertical":"false"};"""
        assertEquals("https://ev.phncdn.com/v/720.mp4?x=1", TubeSite.Pornhub.stream(page)!!.url)
        assertEquals("https://www.pornhub.com/video/get_media?s=abc&f=hls", TubeSite.Pornhub.streamIndexUrl(page))
        val index = Json.parseToJsonElement("""[{"format":"hls","videoUrl":"https://a/480.m3u8","quality":"480"},{"format":"hls","videoUrl":"https://a/1080.m3u8","quality":"1080"}]""")
        val fromIndex = TubeSite.Pornhub.streamFromIndex(index)!!
        assertEquals("https://a/1080.m3u8", fromIndex.url)
        assertEquals(MediaTypes.HLS_MIME, fromIndex.mime)
        // Only the index entry: no direct pick, the index is used instead.
        assertNull(TubeSite.Pornhub.stream(page.replace(""",{"format":"mp4","videoUrl":"https:\/\/ev.phncdn.com\/v\/480.mp4?x=1","quality":"480"},{"format":"mp4","videoUrl":"https:\/\/ev.phncdn.com\/v\/720.mp4?x=1","quality":"720"}""", "")))
    }

    @Test
    fun `xhamster listing from initials and player sources`() {
        val list = """<script>window.initials={"videoListProps":{"videoThumbProps":[{"id":1,"pageURL":"https://xhamster.com/videos/some-slug-12345678","title":"A title","duration":754,"thumbURL":"https://ic-vt-nss.xhcdn.com/a/x/1.jpg","views":10}]},"other":{"x":"}"}};</script>"""
        val v = TubeSite.Xhamster.parseList(list).single()
        assertEquals("12345678", v.id)
        assertEquals("A title", v.title)
        assertEquals(754, v.durationSec)
        assertEquals("https://ic-vt-nss.xhcdn.com/a/x/1.jpg", v.thumb)

        val page = """<script>window.initials={"xplayerSettings":{"sources":{"standard":{"mp4":[{"url":"https://v.xhcdn.com/480.mp4","quality":"480p"},{"url":"https://v.xhcdn.com/720.mp4","quality":"720p"}]},"hls":{"h264":{"url":"https://v.xhcdn.com/m.m3u8","quality":"auto"}}}}};</script>"""
        val s = TubeSite.Xhamster.stream(page)!!
        assertEquals("https://v.xhcdn.com/m.m3u8", s.url)
        val mp4Only = page.replace(""","hls":{"h264":{"url":"https://v.xhcdn.com/m.m3u8","quality":"auto"}}""", "")
        assertEquals("https://v.xhcdn.com/720.mp4", TubeSite.Xhamster.stream(mp4Only)!!.url)
    }

    @Test
    fun `redtube and eporner api listings`() {
        val rt = """{"count":2,"videos":[{"video":{"video_id":"123","title":"RT one","url":"https://www.redtube.com/123","duration":"10:00","default_thumb":"https://ci.rdtcdn.com/a.jpg","tags":[{"tag_name":"milf"},{"tag_name":"anal"}],"publish_date":"2026-11-01 10:00:00"}}]}"""
        val r = TubeSite.Redtube.parseList(rt).single()
        assertEquals("123", r.id)
        assertEquals(600, r.durationSec)
        assertEquals(listOf("milf", "anal"), r.tags)
        assertTrue(r.published != null)

        val ep = """{"videos":[{"id":"abCD12ef3","title":"EP one","keywords":"big tits, pov","views":1,"rate":"4.5","url":"https://www.eporner.com/video-abCD12ef3/ep-one/","added":"2026-11-01 10:00:00","length_sec":"601","default_thumb":{"src":"https://static-eu-cdn.eporner.com/thumbs/x.jpg"}}]}"""
        val e = TubeSite.Eporner.parseList(ep).single()
        assertEquals("abCD12ef3", e.id)
        assertEquals(601, e.durationSec)
        assertEquals(listOf("big tits", "pov"), e.tags)
        assertNull(TubeSite.Eporner.stream("<html>anything</html>"))
        assertEquals("https://www.eporner.com/embed/abCD12ef3/", TubeSite.Eporner.embedUrl("abCD12ef3"))
    }

    @Test
    fun `tube items are one origin per site and play in the embed when no stream`() {
        val items = TubeSite.Xvideos.items(listOf(dev.pfeed.source.tube.TubeVideo("1", "t", "https://www.xvideos.com/video.1/t", "https://x/t.jpg", 60)), "milf")
        val i = items.single()
        assertEquals("tube:xvideos.com", Spread.origin(i))
        assertEquals(MediaType.VIDEO, i.type)
        assertEquals(listOf("milf"), i.tags)
        assertEquals("XVideos", Display.source(i, "x"))
        assertEquals(MediaType.EMBED, Display.effectiveType(i, StreamInfo.Embed("https://www.xvideos.com/embedframe/1")))
        assertEquals(MediaType.VIDEO, Display.effectiveType(i, StreamInfo.Direct("https://a/b.m3u8", MediaTypes.HLS_MIME)))
    }

    @Test
    fun `nhentai galleries become comics and the minors filter sees their tags`() {
        val root = Json.parseToJsonElement(
            """{"result":[
              {"id":555,"media_id":"999","title":{"english":"[Artist] Title (Series) [English]","pretty":"Title"},
               "images":{"pages":[{"t":"j","w":1,"h":1},{"t":"p","w":1,"h":1},{"t":"w","w":1,"h":1}],"cover":{"t":"j","w":1,"h":1}},
               "tags":[{"type":"tag","name":"big breasts"},{"type":"artist","name":"someone"},{"type":"language","name":"english"},{"type":"category","name":"doujinshi"}],
               "num_pages":3,"num_favorites":1234,"upload_date":1700000000},
              {"id":556,"media_id":"1000","title":{"pretty":"Bad"},"images":{"pages":[{"t":"j"},{"t":"j"}],"cover":{"t":"j"}},"tags":[{"type":"tag","name":"lolicon"}],"num_pages":2}
            ],"num_pages":5,"per_page":25}""",
        )
        val items = NhentaiAdapter.parse(root)
        assertEquals(2, items.size)
        val g = items[0]
        assertEquals("555", g.id)
        assertEquals("Title", g.title)
        assertEquals("someone", g.creator)
        assertEquals(listOf("https://i.nhentai.net/galleries/999/1.jpg", "https://i.nhentai.net/galleries/999/2.png", "https://i.nhentai.net/galleries/999/3.webp"), g.pages)
        assertEquals("https://t.nhentai.net/galleries/999/thumb.jpg", g.thumbnailUrl)
        assertEquals("https://nhentai.net/g/555/", g.pageUrl)
        assertTrue(g.tags.containsAll(listOf("comic", "big_breasts", "artist:someone", "language:english")))
        assertEquals("1234", g.extras["score"])
        assertTrue(MinorsFilter.allows(g))
        assertFalse(MinorsFilter.allows(items[1]))
        assertEquals("nhentai", Spread.origin(g))
    }
}
