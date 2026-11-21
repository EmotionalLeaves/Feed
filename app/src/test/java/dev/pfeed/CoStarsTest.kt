package dev.pfeed

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.CoStars
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CoStarsTest {
    private fun v(id: String, title: String) = MediaItem(
        id = id, sourceId = "tube", type = MediaType.VIDEO, title = title, creator = null, tags = emptyList(),
        durationSec = 600, thumbnailUrl = null, pageUrl = "", publishedAt = null, extras = emptyMap(),
    )

    @Before fun clear() = CoStars.clearForTests()

    @Test
    fun `people most often together, each video counted once`() {
        val a = v("x.com/1", "Lena Paul, Abella Danger - Office Fun")
        CoStars.seen(listOf(a, a, v("x.com/2", "Lena Paul, Abella Danger - Pool Day"), v("x.com/3", "Lena Paul, Riley Reid - Gym")))
        CoStars.seen(listOf(a)) // the same video listed again
        assertEquals(listOf("Abella Danger", "Riley Reid"), CoStars.with("lena paul"))
        assertEquals(listOf("Lena Paul"), CoStars.with("Riley Reid"))
        assertEquals(emptyList<String>(), CoStars.with("nobody here"))
    }

    @Test
    fun `kept on the phone`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        CoStars.load(dir)
        CoStars.record(listOf("Lena Paul", "Abella Danger"))
        CoStars.save()
        CoStars.clearForTests()
        assertEquals(emptyList<String>(), CoStars.with("Lena Paul"))
        CoStars.load(dir)
        assertEquals(listOf("Abella Danger"), CoStars.with("Lena Paul"))
        dir.deleteRecursively()
    }
}
