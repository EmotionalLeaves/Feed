package dev.pfeed

import dev.pfeed.domain.reco.ActWatch
import org.junit.Assert.assertEquals
import org.junit.Test

class ActWatchTest {
    private val acts = listOf("Blowjob" to 0L, "Missionary" to 300_000L, "Cowgirl" to 600_000L, "Doggystyle" to 900_000L)

    @Test
    fun `sat through is liked, jumped over is not`() {
        // Watched the blowjob, jumped over missionary, watched cowgirl, left during doggystyle.
        val v = ActWatch.judge(acts, 1_200_000L, longArrayOf(280_000, 4_000, 250_000, 20_000), startedAtMs = 0, furthestMs = 950_000)
        assertEquals(setOf("blowjob", "cowgirl"), v.liked.toSet())
        assertEquals(listOf("missionary"), v.skipped)
    }

    @Test
    fun `acts before where it started were never skipped`() {
        val v = ActWatch.judge(acts, 1_200_000L, longArrayOf(0, 0, 250_000, 0), startedAtMs = 600_000, furthestMs = 880_000)
        assertEquals(listOf("cowgirl"), v.liked)
        assertEquals(emptyList<String>(), v.skipped)
    }

    @Test
    fun `an act both watched and skipped gets no verdict`() {
        val twice = listOf("Missionary" to 0L, "Cowgirl" to 300_000L, "Missionary" to 600_000L)
        val v = ActWatch.judge(twice, 900_000L, longArrayOf(250_000, 200_000, 2_000), startedAtMs = 0, furthestMs = 900_000)
        assertEquals(listOf("cowgirl"), v.liked)
        assertEquals(emptyList<String>(), v.skipped)
    }
}
