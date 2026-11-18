package dev.pfeed

import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.ui.search.Combo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComboTest {
    private fun item(vararg tags: String, title: String? = null) = MediaItem(
        id = "x", sourceId = "s", type = MediaType.VIDEO, title = title, creator = null, tags = tags.toList(),
        durationSec = null, thumbnailUrl = null, pageUrl = "", publishedAt = null, extras = emptyMap(),
    )

    @Test
    fun parsesAndMatches() {
        val c = Combo.parse("anal + pov -toys")
        assertEquals("anal", c.ask)
        assertEquals(listOf("pov"), c.need)
        assertEquals(listOf("toys"), c.without)
        assertTrue(c.matches(item("anal", "pov")))
        assertFalse(c.matches(item("anal")))
        assertFalse(c.matches(item("anal", "pov", "toys")))
        assertTrue(Combo.parse("big tits").isPlain)
        assertTrue(Combo.parse("red head + close_up").matches(item("redhead", "close_up", title = "red head")))
    }
}
