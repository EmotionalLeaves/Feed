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
            <div id="video_81234567" data-id="81234567" class="frame-block thumb-block "><div class="thumb-inside"><div class="thumb"><a href="/video.ukfhdck1234/hot_title_here"><img src="https://static-cdn77.xvideos-cdn.com/v3/img/skins/default/lightbox/lightbox-blk.gif" data-src="https://img-hw.xvideos-cdn.com/videos/thumbs169lll/5c/1f/5c1f/1.jpg" data-pvv="https://cdn77-pic.xvideos-cdn.com/p.mp4" /></a></div></div>
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
        assertEquals(3600 + 2 * 60, list[1].durationSec)

        val page = """html5player.setVideoUrlLow('https://cdn77-vid.xvideos-cdn.com/a/low.mp4?e=1');html5player.setVideoUrlHigh('https://cdn77-vid.xvideos-cdn.com/a/high.mp4?e=1');html5player.setVideoHLS('https://cdn77-vid.xvideos-cdn.com/a/hls.m3u8?e=1');"""
        val s = TubeSite.Xvideos.stream(page)!!
        assertEquals("https://cdn77-vid.xvideos-cdn.com/a/hls.m3u8?e=1", s.url)
        assertEquals(MediaTypes.HLS_MIME, s.mime)
        assertEquals("https://cdn77-vid.xvideos-cdn.com/a/high.mp4?e=1", TubeSite.Xvideos.stream(page.substringBefore("html5player.setVideoHLS"))!!.url)
        assertEquals("https://www.xvideos.com/embedframe/81234567", TubeSite.Xvideos.embedUrl("81234567"))
    }

    @Test
    fun `xnxx shares the xvideos engine but keeps its own host`() {
        val html = """<div id="video_1" data-id="1" class="thumb-block"><div class="thumb-inside"><div class="thumb"><a href="/video-abc12/slug"><img data-src="https://img.xnxx-cdn.com/t.jpg"/></a></div></div><div class="thumb-under"><p><a href="/video-abc12/slug" title="T">T</a></p><p class="metadata"><span class="right"> 26.4M <span class="icon-f icf-eye"></span><span class="superfluous">100%</span></span> 5min <span class="video-hd"><span class="superfluous"> - </span> 1080p </span></p></div></div>"""
        val v = TubeSite.Xnxx.parseList(html).single()
        assertEquals("https://www.xnxx.com/video-abc12/slug", v.pageUrl)
        assertEquals(300, v.durationSec)
        assertEquals("https://www.xnxx.com/embedframe/1", TubeSite.Xnxx.embedUrl("1"))
        assertEquals("https://www.xnxx.com/search/big+tits/2", TubeSite.Xnxx.listUrl("big tits", 2))
        assertEquals(TubeSite.Xnxx, TubeSite.byHost("www.xnxx.com"))
    }

    @Test
    fun `youporn and tube8 article listings`() {
        val yp = """<article class="video-box mobile js_video-box js-pop" data-video-id="308517921" data-uploader-name="miss frog" aria-label="Rainbow socks &amp; more"><a href="/watch/308517921/" class="tm_video_link" data-webm='https://ev-ph.ypncdn.com/x_fb.mp4' data-poster='https://pix-cdn77.ypncdn.com/c6531/videos/202611/10/68672575/original_68672575.mp4/plain/a?hash=1'><img src="https://pix-cdn77.ypncdn.com/other.jpg"><div class="video-duration tm_video_duration"> <span> 12:34 </span> </div></a></article>"""
        val v = TubeSite.Youporn.parseList(yp).single()
        assertEquals("308517921", v.id)
        assertEquals("Rainbow socks & more", v.title)
        assertEquals("https://www.youporn.com/watch/308517921/", v.pageUrl)
        assertTrue(v.thumb!!.startsWith("https://pix-cdn77.ypncdn.com/c6531"))
        assertEquals(754, v.durationSec)

        val t8 = """<article class="video-box" data-video-id="184522501" aria-label="nubilefilms - x"><a href="/porn-video/39030671/" data-poster='https://ci.t8cdn.com/p.jpg'></a><span class="duration">1:00</span></article>"""
        val t = TubeSite.Tube8.parseList(t8).single()
        assertEquals("https://www.tube8.com/porn-video/39030671/", t.pageUrl)
        assertEquals(60, t.durationSec)
        assertEquals("https://www.tube8.com/embed/184522501/", TubeSite.Tube8.embedUrl("184522501"))
        val page = """"mediaDefinitions":[{"format":"mp4","videoUrl":"https:\/\/ev.ypncdn.com\/720.mp4","quality":"720"}]"""
        assertEquals("https://ev.ypncdn.com/720.mp4", TubeSite.Youporn.stream(page)!!.url)
    }

    @Test
    fun `pornhub listing and media definitions`() {
        val html = """
            <li class="pcVideoListItem js-pop videoblock videoBox" data-video-vkey="ph5f0a1b2c3d4e5"><div class="phimage"><a href="/view_video.php?viewkey=ph5f0a1b2c3d4e5" title="Nice one" class="linkVideoThumb"><img src="https://pix-cdn77.phncdn.com/videos/202411/01/abc/original_1.mp4/plain/1.jpg?hash=abc" data-mediumthumb="https://ei.phncdn.com/videos/202411/01/abc/original/(m=eaf8Ggaaaa)(mh=x)1.jpg" title="Nice one" /></a><div class="marker-overlays"><var class="duration">10:23</var></div></div></li>
            <li class="pcVideoListItem" data-video-vkey="ph999"><a href="/view_video.php?viewkey=ph999" title="Two"><img data-thumb_url="https://ei.phncdn.com/two.jpg"></a><var class="duration">1:00:05</var></li>
        """
        val list = TubeSite.Pornhub.parseList(html)
        assertEquals(listOf("ph5f0a1b2c3d4e5", "ph999"), list.map { it.id })
        assertEquals("Nice one", list[0].title)
        assertEquals(623, list[0].durationSec)
        assertEquals(3605, list[1].durationSec)
        assertTrue(list[0].thumb!!.contains("phncdn.com"))
        assertTrue(list[1].thumb!!.startsWith("https://ei.phncdn.com/"))

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

        val mobile = """<li class="thumb-list-mobile-item" data-ecommerce-list-item="31565239"><a href="https://xhamster.com/videos/lets-play-xhE9Dby" class="thumb-image-container mobile-thumb-player-container" aria-label="Let&#39;s Play"><img class="thumb-image-container__no-lazy-thumb" src="https://ic-vt-nss.xhcdn.com/a/b.webp"><div class="video-duration">5:30</div></a></li>"""
        val m = TubeSite.Xhamster.parseList(mobile).single()
        assertEquals("xhE9Dby", m.id)
        assertEquals("Let's Play", m.title)
        assertEquals("https://ic-vt-nss.xhcdn.com/a/b.webp", m.thumb)
        assertEquals(330, m.durationSec)

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

        val page = """"mediaDefinitions":[{"format":"hls","videoUrl":"\/media\/hls?s=abc&v=1","remote":true,"quality":[]},{"format":"mp4","videoUrl":"\/media\/mp4?s=abc","remote":true,"quality":[]}]"""
        assertNull(TubeSite.Redtube.stream(page))
        assertEquals("https://www.redtube.com/media/hls?s=abc&v=1", TubeSite.Redtube.streamIndexUrl(page))
        val idx = Json.parseToJsonElement("""[{"format":"hls","videoUrl":"\/media\/hls\/720.m3u8?x=1","quality":"720"},{"format":"hls","videoUrl":"https://cv.rdtcdn.com/1080.m3u8","quality":"1080"}]""")
        assertEquals("https://cv.rdtcdn.com/1080.m3u8", TubeSite.Redtube.streamFromIndex(idx)!!.url)

        val ep = """{"videos":[{"id":"abCD12ef3","title":"EP one","keywords":"big tits, pov","views":1,"rate":"4.5","url":"https://www.eporner.com/video-abCD12ef3/ep-one/","added":"2026-11-01 10:00:00","length_sec":"601","default_thumb":{"src":"https://static-eu-cdn.eporner.com/thumbs/x.jpg"}}]}"""
        val e = TubeSite.Eporner.parseList(ep).single()
        assertEquals("abCD12ef3", e.id)
        assertEquals(601, e.durationSec)
        assertEquals(listOf("big tits", "pov"), e.tags)
        assertNull(TubeSite.Eporner.stream("<html>anything</html>"))
        val embed = """EP.video.player.vid = '4EeRbbhmzAh'; EP.video.player.hash = 'da3b99db1194ac5ed20deae3dca2d6e0';"""
        val idx = TubeSite.Eporner.streamIndexUrl(embed)!!
        assertTrue(idx.startsWith("https://www.eporner.com/xhr/video/4EeRbbhmzAh?hash="))
        assertEquals("https://www.eporner.com/embed/x/", TubeSite.Eporner.streamPageUrl("https://www.eporner.com/video-x/y/", "x"))
        val src = Json.parseToJsonElement("""{"sources":{"mp4":{"480p":{"src":"https://v/480.mp4"},"1080p":{"src":"https://v/1080.mp4"},"2160p(4K)":{"src":"https://v/4k.mp4"}}}}""")
        assertEquals("https://v/1080.mp4", TubeSite.Eporner.streamFromIndex(src)!!.url)
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
        assertEquals(listOf("https://i1.nhentai.net/galleries/999/1.jpg", "https://i1.nhentai.net/galleries/999/2.png", "https://i1.nhentai.net/galleries/999/3.webp"), g.pages)
        assertEquals("https://t.nhentai.net/galleries/999/thumb.jpg", g.thumbnailUrl)
        assertEquals("https://nhentai.net/g/555/", g.pageUrl)
        assertTrue(g.tags.containsAll(listOf("comic", "big_breasts", "artist:someone", "language:english")))
        assertEquals("1234", g.extras["score"])
        assertTrue(MinorsFilter.allows(g))
        assertFalse(MinorsFilter.allows(items[1]))
        assertEquals("nhentai", Spread.origin(g))
    }

    @Test
    fun `nhentai v2 listing without detail still opens as a comic`() {
        val root = Json.parseToJsonElement("""{"result":[{"id":4314731,"media_id":"3214567","english_title":"[A] B","japanese_title":"","thumbnail":"galleries/3214567/thumb.webp","thumbnail_width":250,"thumbnail_height":353,"num_pages":3,"num_favorites":7,"tag_ids":[1,2]}],"num_pages":10}""")
        val g = NhentaiAdapter.parse(root).single()
        assertEquals("4314731", g.id)
        assertEquals("[A] B", g.title)
        assertEquals("https://t1.nhentai.net/galleries/3214567/thumb.webp", g.thumbnailUrl)
        assertEquals(3, g.pages.size)
        assertEquals("https://i1.nhentai.net/galleries/3214567/2.webp", g.pages[1])
        assertEquals(listOf("comic", "doujinshi"), g.tags)
        assertEquals("https://nhentai.net/api/v2/search?query=tag%3Amilf+-tag%3Alolicon&sort=popular&page=2", NhentaiAdapter.searchUrl("tag:milf -tag:lolicon", "popular", 2))
        // A listing entry carrying a minors tag id never becomes an item.
        assertTrue(NhentaiAdapter.parse(Json.parseToJsonElement("""{"result":[{"id":1,"media_id":"2","english_title":"x","thumbnail":"galleries/2/thumb.webp","num_pages":2,"tag_ids":[5,19440]}]}""")).isEmpty())
    }

    @Test
    fun `xhamster can be read from a mirror domain`() {
        assertEquals("https://xhamster2.com/newest/1", TubeSite.Xhamster.onMirror(TubeSite.Xhamster.listUrl("new", 0), "xhamster2.com"))
        assertEquals(TubeSite.Xhamster, TubeSite.byHost("xhamster.desi"))
        assertEquals(TubeSite.Xhamster, TubeSite.byHost("ru.xhamster.com"))
        assertTrue(TubeSite.Xvideos.mirrors.isEmpty())
    }

    @Test
    fun `nhentai v2 detail gives every page's exact file`() {
        val d = Json.parseToJsonElement("""{"id":696940,"media_id":"4326714","title":{"english":"E","pretty":"P"},"cover":{"path":"galleries/4326714/cover.webp"},"thumbnail":{"path":"galleries/4326714/thumb.webp"},"tags":[{"type":"tag","name":"milf"}],"num_pages":2,"num_favorites":3,"pages":[{"number":1,"path":"galleries/4326714/1.jpg"},{"number":2,"path":"galleries/4326714/2.png"}]}""")
        val g = NhentaiAdapter.parse(Json.parseToJsonElement("{\"result\":[$d]}")).single()
        assertEquals(listOf("https://i1.nhentai.net/galleries/4326714/1.jpg", "https://i1.nhentai.net/galleries/4326714/2.png"), g.pages)
        assertEquals("https://t1.nhentai.net/galleries/4326714/thumb.webp", g.thumbnailUrl)
        assertTrue(g.tags.contains("milf"))
    }
}
