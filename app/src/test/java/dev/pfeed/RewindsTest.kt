package dev.pfeed

import dev.pfeed.domain.reco.Rewinds
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RewindsTest {
    @Before fun reset() = Rewinds.clear()

    @Test
    fun `going back to the same bit twice marks it, once`() {
        assertEquals(null, Rewinds.note("v", 600_000))
        // Somewhere else: nothing yet.
        assertEquals(null, Rewinds.note("v", 120_000))
        // Back to the first bit again: that's the one, from the earlier landing.
        assertEquals(595_000L, Rewinds.note("v", 595_000))
        // A third time: already offered.
        assertEquals(null, Rewinds.note("v", 601_000))
    }

    @Test
    fun `each video on its own`() {
        assertEquals(null, Rewinds.note("a", 60_000))
        assertEquals(null, Rewinds.note("b", 61_000))
    }
}
