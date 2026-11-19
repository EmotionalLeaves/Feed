package dev.pfeed

import dev.pfeed.domain.reco.BestPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BestPartTest {
    @Test
    fun `finds the stretch people rewatch, not the opening`() {
        // 100 stretches of a 1000 s video: everyone starts (stretch 0), most come back to 60–62.
        val counts = List(100) { i -> when (i) { 0 -> 900; in 60..62 -> 300; else -> 100 } }
        assertEquals(61 * 10_000L - 4_000L, BestPart.from(counts, 1000))
    }

    @Test
    fun `nothing stands out, or too short`() {
        assertNull(BestPart.from(List(100) { 100 }, 1000))
        assertNull(BestPart.from(List(100) { if (it == 50) 500 else 100 }, 120))
    }
}
