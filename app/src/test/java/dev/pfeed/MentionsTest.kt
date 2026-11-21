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

    @Test
    fun `a followed performer the site credits is found`() {
        val names = listOf("coco lovelock")
        assertEquals(names, Mentions.named(v("Breakthrough session").copy(extras = mapOf(Mentions.PERFORMERS to "CoCo Lovelock")), names))
    }

    @Test
    fun `a performer the site credits is a taste feature too`() {
        val f = dev.pfeed.domain.reco.Features.of(v("Breakthrough session").copy(extras = mapOf(Mentions.PERFORMERS to "CoCo Lovelock")))
        assertEquals(true, dev.pfeed.domain.reco.Features.normalizeTag("CoCo Lovelock") in f)
    }

    @Test
    fun `known people are found anywhere in a title`() {
        Mentions.learn(listOf("Zelda Quartz@onlyfans", "solo"))
        assertEquals(listOf("Zelda Quartz"), Mentions.inTitle(v("zelda quartz gets a surprise at the gym")))
        assertEquals(emptyList<String>(), Mentions.inTitle(v("A quiet afternoon")))
    }

    @Test
    fun `a search for a name finds the person`() {
        val results = listOf(v("Lena Paul & Abella Danger - Lesbian Fun"), v("Busy day with lena paul"), v("LENA PAUL at work"), v("Other video"))
        assertEquals("Lena Paul" to 3, dev.pfeed.ui.search.personFor("lena paul", results))
        assertEquals(null, dev.pfeed.ui.search.personFor("lesbian", results))
        assertEquals(null, dev.pfeed.ui.search.personFor("lena paul", results.take(2)))
    }

    @Test
    fun `credited performers count, and her most common tags narrow her search`() {
        val credited = v("Breakthrough session", tags = listOf("milf", "anal")).let { it.copy(extras = mapOf(Mentions.PERFORMERS to "Lena Paul,Someone Else")) }
        assertEquals(listOf("Lena Paul", "Someone Else"), Mentions.inTitle(credited).take(2))
        val results = listOf(
            credited,
            v("Lena Paul at work", tags = listOf("milf", "office", "HD", "lena paul")),
            v("Busy day with lena paul", tags = listOf("anal", "milf")),
            v("Other video", tags = listOf("anal", "milf", "teen")),
        )
        assertEquals("Lena Paul" to 3, dev.pfeed.ui.search.personFor("lena paul", results))
        // Her own name and "hd" aren't what she's in; tags on one video only don't make the cut.
        assertEquals(listOf("milf", "anal"), dev.pfeed.ui.search.personTags("Lena Paul", results))
        // "name + tag" is already narrowed: no person card for it.
        assertNull(dev.pfeed.ui.search.personFor("lena paul + anal", results))
    }

    @Test
    fun `a name typed slightly wrong finds the person meant`() {
        val names = listOf("Lena Paul", "Abella Danger", "Riley Reid")
        assertEquals("Lena Paul", Mentions.closest("lena pual", names))
        assertEquals("Abella Danger", Mentions.closest("abela danger", names))
        assertNull(Mentions.closest("lena paul", names)) // spelled right
        assertNull(Mentions.closest("milf anal", names)) // nothing like it
        assertNull(Mentions.closest("redhead", names)) // one word: not a name
    }
}
