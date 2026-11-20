package dev.pfeed

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.SameVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class SameVideoTest {
    private fun v(id: String, title: String, sec: Int?) = MediaItem(
        id = id, sourceId = "tube", type = MediaType.VIDEO, title = title, creator = null, tags = emptyList(),
        durationSec = sec, thumbnailUrl = null, pageUrl = "", publishedAt = null, extras = emptyMap(),
    )

    @Test
    fun `finds the same upload on another site`() {
        val dead = v("pornhub.com/1", "Stepsister gets caught in the shower - Full HD", 734)
        val found = listOf(
            v("xvideos.com/9", "Stepsister Gets Caught In The Shower", 735),
            v("xnxx.com/4", "Stepsister gets caught in the shower", 412), // another cut
            v("eporner.com/2", "Caught in the kitchen", 734),
        )
        assertEquals(listOf("xvideos.com/9"), SameVideo.copies(dead, found).map { it.id })
        assertEquals("stepsister gets caught shower", SameVideo.query(dead))
    }

    @Test
    fun `finds the full scene of a short cut`() {
        val cut = v("analdin.com/830981", "Roller Dirty 2 - Pervs On Patrol", 900)
        val found = listOf(
            v("xvideos.com/1", "Pervs On Patrol - Roller Dirty 2 (Mofos)", 2310),
            v("pornhub.com/2", "Roller Dirty 2 Pervs On Patrol", 902), // the same cut
            v("eporner.com/3", "Roller derby girls", 2600), // another video
            v("xnxx.com/4", "Pervs On Patrol: Roller Dirty 2", 1500),
        )
        // Two longer ones match the title; the longest is the full scene.
        assertEquals("xvideos.com/1", SameVideo.longer(cut, found)?.id)
        // Nothing longer: nothing offered.
        assertEquals(null, SameVideo.longer(cut, found.drop(1).take(2)))
        // Too few title words to be sure of anything.
        assertEquals(null, SameVideo.longer(v("a.com/1", "Hot milf", 600), listOf(v("b.com/2", "Hot milf", 2000))))
    }
}
