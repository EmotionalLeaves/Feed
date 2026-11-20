package dev.pfeed

import dev.pfeed.domain.reco.VideoExtras
import dev.pfeed.source.tube.TubeSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExtrasTest {
    /** From RedTube's player setup, November 2026. */
    private val page = """"thumbs":{"samplingFrequency":9,"type":"normal","cdnType":"regular","urlPattern":"https:\/\/ei-ph.rdtcdn.com\/videos\/202408\/04\/456069811\/timeline\/160x90\/(m=eGCaiCObaaaa)(mh=NHdPg_VNbCFlR67Q)S{5}.jpg","thumbHeight":"90","thumbWidth":"160","hotspots":[]},"actionTags":"Handjob:255,Missionary:679,Handjob:1173","""

    @Test
    fun `reads preview sheets and chapters`() {
        val t = TubeSite.Redtube.timeline(page)!!
        assertEquals(9, t.everySec)
        // 4:30 = frame 30 = sheet 1, cell 5 (column 0, row 1).
        val (url, col, row) = t.at(270_000)
        assertEquals("https://ei-ph.rdtcdn.com/videos/202408/04/456069811/timeline/160x90/(m=eGCaiCObaaaa)(mh=NHdPg_VNbCFlR67Q)S1.jpg", url)
        assertEquals(0, col); assertEquals(1, row)
        val ch = TubeSite.Redtube.chapters(page)
        assertEquals(listOf("Handjob" to 255_000L, "Missionary" to 679_000L, "Handjob" to 1_173_000L), ch)
        assertEquals("Missionary", VideoExtras.chapterAt(ch, 700_000))
    }

    @Test
    fun `reads tags off pornhub and xvideos pages`() {
        val ph = """'categories_in_video' : 'Amateur,Babe,Big Dick,Big Tits,Blonde,MILF,Exclusive,Verified Amateurs,Step Fantasy,HD',"""
        assertEquals(listOf("amateur", "babe", "big dick", "big tits", "blonde", "milf", "step fantasy"), TubeSite.Pornhub.pageTags(ph))
        val xv = """"video_tags":["blonde","babe","milf","big-butt"],"""
        assertEquals(listOf("blonde", "babe", "milf", "big butt"), TubeSite.Xvideos.pageTags(xv))
    }

    @Test
    fun `reads pornhub comments`() {
        val c = TubeSite.Pornhub.comments(PH_COMMENT)
        assertEquals("bigdickaussie69696", c.first().first)
        assertTrue(c.first().second.startsWith("Hands down"))
    }

    @Test
    fun `picks the act you like most, only when it stands out`() {
        val acts = listOf(("Handjob" to 255_000L) to 0.1, ("Missionary" to 679_000L) to 0.5, ("Handjob" to 1_173_000L) to 0.1, ("Missionary" to 1_400_000L) to 0.5)
        assertEquals("Missionary" to 679_000L, VideoExtras.favourite(acts))
        // Close call: no favourite.
        assertEquals(null, VideoExtras.favourite(listOf(("A" to 1L) to 0.40, ("B" to 2L) to 0.36)))
        // Nothing you like: no favourite.
        assertEquals(null, VideoExtras.favourite(listOf(("A" to 1L) to 0.03, ("B" to 2L) to -0.4)))
        // One act only: nothing to choose between.
        assertEquals(null, VideoExtras.favourite(listOf(("A" to 1L) to 0.9, ("a" to 2L) to 0.9)))
    }

    @Test
    fun `xvideos scrub frames span the whole video`() {
        val html = """html5player.setThumbSlideBig('https://thumb-cdn77.xvideos-cdn.com/03160cda/6/mozaique_full.jpg');
<meta property="og:duration" content="614" />"""
        val tl = TubeSite.Xvideos.timeline(html)!!
        assertEquals(Triple("https://thumb-cdn77.xvideos-cdn.com/03160cda/6/mozaique_full.jpg", 0, 0), tl.at(0))
        // Halfway: frame 50 of 100, row 5 column 0.
        assertEquals(0 to 5, tl.at(307_000).let { it.second to it.third })
        // The very end stays on the last frame.
        assertEquals(9 to 9, tl.at(614_000).let { it.second to it.third })
    }

    @Test
    fun `kvs sites' timeline pictures`() {
        val html = """timeline_screens_url: '//ptx.cdntrex.com/contents/videos_screenshots/3410000/3410304/timelines/timeline_mp4/200x116/{time}.jpg',
            timeline_screens_interval: '10',
            timeline_screens_count: '108',"""
        val tl = TubeSite.Porntrex.timeline(html)!!
        val base = "https://ptx.cdntrex.com/contents/videos_screenshots/3410000/3410304/timelines/timeline_mp4/200x116/"
        assertEquals(Triple(base + "1.jpg", 0, 0), tl.at(0))
        assertEquals(Triple(base + "3.jpg", 0, 0), tl.at(25_000))
        // Past the last picture: the last one.
        assertEquals(base + "108.jpg", tl.at(5_000_000).first)
    }

    @Test
    fun `xvideos comments come from their own call`() {
        val page = """"vmdata":{"pub_comments":2,"has_comments":true}, "id_video":92570314,"encoded_id_video":"olulicae7ff""""
        assertEquals("https://www.xvideos.com/threads/video-comments/get-posts/top/92570314/0/0", TubeSite.Xvideos.commentsUrl(page))
        assertEquals(null, TubeSite.Xvideos.commentsUrl(page.replace("true", "false")))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"posts":{"nb_posts_total":2,"posts":{"1":{"id":"1","name":"Brittany2421","message":"best part 12:31 &amp; after"},"2":{"id":"2","name":"x","message":"<b>wow</b>"}}}}"""
        )
        assertEquals(listOf("Brittany2421" to "best part 12:31 & after", "x" to "wow"), TubeSite.Xvideos.parseComments(json))
    }

    private val PH_COMMENT = """<a rel="nofollow"
 data-event="watch_page" data-label="profile_view"
 href="/users/bigdickaussie69696"
 title="bigdickaussie69696"
 class="gtm-event-watch-page usernameLink">bigdickaussie69696</a>
 </span>

 </a>
 <span class="js-moreAction moreAction"></span>
 <section id="profileBlockReport" class="displayNone">
 <div class="sectionWrapper blockAndReportSubscribers spriteProfileIcons">
 <div class="lblSubtitle ie7ReportWidth">
 <a href="#" class="flagUserProfile" data-user-id="2130842841" data-mpp-cpp="" data-user="https://www.pornhub.com/api/v1/user/2130842841/flag?token=MTc5NTA1ODg3OWTDekJJkl0hd3xalW6qg4NdEnzpHe-fWGni2iTbQ5FfeE377mBF5ssKJV_tVzIYuK2oC9sUJDOnEWt2jzeMXxM.">
 <span class="profileReportIcon"></span>
 <p class="blockReportUserPara">Report User</p>
 </a>
 </div>
 <div class="cancelBlockModal">
 <a class="js-cancelBlockReportModal" href="#" onclick="blockReportModal.closeModal(); return false;">
 <span class="cancelIcon"></span>
 <p class="cancelPara blockReportUserPara">Cancel</p>
 </a>
 </div>
 </div>
 </section>
</div>


 
 <div class="date floatRight">1 month ago</div>
 </div>
 <div class="commentMessage">
 <span>Hands down the hottest porn star I’ve seen in my life</span>
"""
}
