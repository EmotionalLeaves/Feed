package dev.pfeed

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.reco.Mentions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionsTest {
    private fun v(title: String, tags: List<String> = emptyList()) = MediaItem(
        id = "x.com/1", sourceId = "tube", type = MediaType.VIDEO, title = title, creator = "SomeUploader", tags = tags,
        durationSec = 600, thumbnailUrl = null, pageUrl = "", publishedAt = null, extras = emptyMap(),
    )

    @Test
    fun `a followed performer is found in titles and tags, whole words only`() {
        val names = listOf("anna bell peaks", "lena paul").mapNotNull(Mentions::personName)
        assertEquals(listOf("anna bell peaks"), Mentions.named(v("Tatted ginger gets plumbed by a monster trunk, Anna Bell Peaks"), names))
        assertEquals(listOf("lena paul"), Mentions.named(v("Busy afternoon", tags = listOf("Lena_Paul", "milf")), names))
        // Part of a longer word isn't her.
        assertEquals(emptyList<String>(), Mentions.named(v("Helena Paulson at the gym"), names))
    }

    @Test
    fun `one-word and short follows never match by title`() {
        assertNull(Mentions.personName("amateur"))
        assertNull(Mentions.personName("a b"))
        assertEquals("lena paul", Mentions.personName("Lena  Paul@onlyfans"))
    }

    @Test
    fun `performers named before or after a studio title`() {
        assertEquals(listOf("Auhneesh Nicole", "Taysha Sativa"), Mentions.performers("Auhneesh Nicole, Taysha Sativa - Two Baddies Maids Have Threeway Sex"))
        assertEquals(listOf("Lena Paul", "Abella Danger"), Mentions.performers("Lena Paul & Abella Danger - Lesbian Fun"))
        assertEquals(listOf("Anna Bell Peaks"), Mentions.performers("Tatted ginger gets plumbed by a monster trunk, Anna Bell Peaks"))
        // Series names and everyday words aren't people.
        assertEquals(emptyList<String>(), Mentions.performers("Pervs On Patrol - Roller Dirty 2"))
        assertEquals(emptyList<String>(), Mentions.performers("Big Tits At Work - Office Fun"))
        assertEquals(emptyList<String>(), Mentions.performers("Hot Milf - fucked hard"))
    }

    @Test
    fun `a named performer is a taste feature, like a board's tag for her`() {
        val f = dev.pfeed.domain.reco.Features.of(v("Lena Paul & Abella Danger - Lesbian Fun"))
        assertEquals(true, dev.pfeed.domain.reco.Features.normalizeTag("Lena Paul") in f)
        assertEquals(true, dev.pfeed.domain.reco.Features.normalizeTag("abella_danger") in f)
    }
}
