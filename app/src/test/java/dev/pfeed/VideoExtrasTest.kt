package dev.pfeed

import dev.pfeed.domain.reco.VideoExtras
import dev.pfeed.source.tube.TubeSite
import org.junit.Assert.assertEquals
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
}
