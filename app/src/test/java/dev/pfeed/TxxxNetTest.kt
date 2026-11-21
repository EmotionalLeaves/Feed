package dev.pfeed

import dev.pfeed.source.tube.TubeSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TXXX network's JSON API, as served in November 2026. */
class TxxxNetTest {
    @Test
    fun `listing from the json api`() {
        val list = TubeSite.Txxx.parseList(LIST)
        assertEquals(2, list.size)
        val v = list[0]
        assertEquals("21855859", v.id)
        assertEquals("Roommate ass fetish fun", v.title)
        assertEquals("https://txxx.com/videos/21855859/roommate-ass-fetish-fun/", v.pageUrl)
        assertEquals(15 * 60 + 12, v.durationSec)
        assertEquals("davidfs21", v.creator)
        assertTrue(v.tags.toString(), "femdom" in v.tags && "big clit" in v.tags)
        // American, Belgian, Swiss on one video: nationality spam, dropped.
        assertTrue(v.tags.toString(), "belgian" !in v.tags && "swiss" !in v.tags)
        assertTrue(v.thumb!!.startsWith("https://tn.txxx.tube/"))
        assertTrue(v.preview!!.startsWith("https://") && v.preview!!.endsWith("_tr.mp4"))
        assertEquals(java.time.Instant.parse("2026-11-20T03:36:37Z"), v.published)
        // The same API on every site of the network.
        assertTrue(TubeSite.HClips.listUrl("new", 1).startsWith("https://hclips.com/api/json/videos2/86400/str/latest-updates/60/..2.all"))
        assertTrue(TubeSite.Upornia.listUrl("big tits", 0).endsWith("&s=big+tits"))
    }

    @Test
    fun `file path decoded from base164`() {
        val s = TubeSite.Txxx.stream(FILES)!!
        assertEquals("https://txxx.com/get_file/25/f92f7dff724fd011229640246756341f18fadc1b67/21855000/21855859/21855859_hq.mp4/?d=912&br=207&ti=1795181579", s.url)
        assertEquals("https://txxx.com/api/videofile.php?video_id=21855859&lifetime=8640000", TubeSite.Txxx.streamPageUrl("https://txxx.com/videos/21855859/x/", "21855859"))
        assertEquals("https://txxx.com/api/json/videos_related2/432000/50/21000000/21855000/21855859.all.1.json", TubeSite.Txxx.relatedUrl("21855859"))
    }

    private val LIST = """{"params": {"cache_lifetime": 86400, "gender": "str", "sort": "latest-updates", "count": 60, "page": 1, "section": "", "object_id": "", "type": "all", "duration": "", "date": "", "sq": null, "filters": {"categories": "", "models": "", "source": "", "content_source": "", "content_source_group": "", "type": "all", "duration": "", "days": ""}}, "total_count": "3766624", "videos": [{"video_id": "21855859", "title": "roommate ass fetish fun", "dir": "roommate-ass-fetish-fun", "duration": "15:12", "post_date": "2026-11-20 03:36:37", "is_private": "0", "categories": "American,Belgian,Big Clit,Cumshot,Femdom,Swiss,Teacher", "scr": "https://tn.txxx.tube/contents/videos_screenshots/21855000/21855859/288x162/7.jpg", "pv": "vp2.txxx.com/c14/videos/21855000/21855859/21855859_tr.mp4", "username": "davidfs21", "display_name": "davidfs21", "file_formats": "||_hq.mp4|1280x720|912|125632412|31|30|0||_tr.mp4|384x216|7|184777|0|0|0"}, {"video_id": "21855837", "title": "Auhneesh Nicole, Taysha Sativa - Two Baddies Maids Have Threeway Sex For Extra Cash f", "dir": "auhneesh-nicole-taysha-sativa-two-baddies-maids-have-threeway-sex-for-extra-cash-f2", "duration": "10:59", "post_date": "2026-11-20 03:26:09", "is_private": "0", "categories": "Bangladeshi,Behind The Scenes,Cumshot,Estonian,Greek,High Heels,Nipples,Red Head,Smoking,Squirt", "scr": "https://tn.txxx.tube/contents/videos_screenshots/21855000/21855837/288x162/7.jpg", "pv": "vp2.txxx.com/c14/videos/21855000/21855837/21855837_tr.mp4", "username": "nepalo7747", "display_name": "nepalo7747", "file_formats": "||_hq.mp4|1280x720|659|137523969|22|30|0||_tr.mp4|384x216|7|193282|0|0|0"}]}"""
    private val FILES = """[{"format":"_hq.mp4","timelines_count":"31","timelines_interval":"30","video_url":"L2dldF9maWxlLzI1L2Y5\u041cmY3ZGZmNzI0ZmQw\u041cT\u0415y\u041cjk2ND\u0410yNDY3NTYzNDFm\u041cThmYWRj\u041cWI2Ny8y\u041cTg1NT\u0410w\u041c\u04218y\u041cTg1NTg1OS8y\u041cTg1NTg1OV9ocS5tcDQvP2Q9OT\u0415yJmJyPTIwNyZ0aT0xNzk1\u041cTgxNTc5","is_default":1,"c":"memc"}]"""
}
