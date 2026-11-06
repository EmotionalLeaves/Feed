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
}
