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
}
