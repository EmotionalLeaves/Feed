package dev.pfeed

import dev.pfeed.domain.filter.Blocklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistTest {

    @Test
    fun `blocked tag hides item regardless of case, hash or spacing`() {
        val bl = Blocklist(tags = setOf(Blocklist.normalize("  #Some  Tag ")))
        assertFalse(bl.allows(item("1", tags = listOf("some tag"))))
        assertFalse(bl.allows(item("2", tags = listOf("other", "SOME TAG"))))
        assertTrue(bl.allows(item("3", tags = listOf("something else"))))
    }

    @Test
    fun `a hidden performer stays hidden when the site credits her`() {
        val credited = item("1", creator = "Some Studio").let { it.copy(extras = mapOf(dev.pfeed.domain.reco.Mentions.PERFORMERS to "Lena Paul,Other Person")) }
        assertFalse(Blocklist(creators = setOf(Blocklist.normalize("Lena Paul"))).allows(credited))
        assertFalse(Blocklist(tags = setOf(Blocklist.normalize("lena paul"))).allows(credited))
        assertTrue(Blocklist(creators = setOf(Blocklist.normalize("Riley Reid"))).allows(credited))
    }

    @Test
    fun `blocked creator hides item`() {
        val bl = Blocklist(creators = setOf("alice"))
        assertFalse(bl.allows(item("1", creator = "Alice")))
        assertTrue(bl.allows(item("2", creator = "bob")))
        assertTrue(bl.allows(item("3", creator = null)))
    }

    @Test
    fun `not-interested item is hidden by key only`() {
        val hidden = item("1", source = "rss")
        val bl = Blocklist(items = setOf(hidden.key))
        assertFalse(bl.allows(hidden))
        assertTrue(bl.allows(item("1", source = "local")))
    }

    @Test
    fun `empty blocklist allows everything`() {
        assertTrue(Blocklist.EMPTY.allows(item("1", tags = listOf("x"), creator = "y")))
    }

    @Test
    fun `filter keeps order of allowed items`() {
        val bl = Blocklist(tags = setOf("bad"))
        val items = listOf(item("a"), item("b", tags = listOf("bad")), item("c"))
        assertEquals(listOf("a", "c"), bl.filter(items).map { it.id })
    }

    @Test
    fun `built-in minors filter applies even with an empty blocklist`() {
        assertFalse(Blocklist.EMPTY.allows(item("1", tags = listOf("loli"))))
        assertFalse(Blocklist.EMPTY.allows(item("2", tags = listOf("Child"))))
        assertFalse(Blocklist.EMPTY.allows(item("3", title = "underage something")))
        assertTrue(Blocklist.EMPTY.allows(item("4", tags = listOf("bdsm", "young_woman"))))
    }

    @org.junit.Test
    fun `hiding a tag hides its other spellings`() {
        val b = dev.pfeed.domain.filter.Blocklist(tags = setOf("big tits"))
        val post = item("x/1", tags = listOf("large_breasts", "solo"))
        org.junit.Assert.assertFalse(b.allows(post))
        org.junit.Assert.assertTrue(b.allows(item("x/2", tags = listOf("solo"))))
    }

    @Test
    fun `a hidden tag also hides titles that say it, as whole words`() {
        val bl = Blocklist(tags = setOf(Blocklist.normalize("big tits"), "scat"))
        assertFalse(bl.allows(item("1", title = "Busty wife with BIG TITS rides")))
        assertFalse(bl.allows(item("2", title = "Scat queen")))
        // Part of another word isn't the word.
        assertTrue(bl.allows(item("3", title = "Scatter the pillows")))
        assertTrue(bl.allows(item("4", title = "Big titles everywhere")))
    }
}
