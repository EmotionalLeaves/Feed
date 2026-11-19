package dev.pfeed

import dev.pfeed.domain.reco.Scenes
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ScenesTest {
    /** A frame of one scene: its own colours, plus a little movement. */
    private fun frame(scene: Int, rnd: Random) = FloatArray(27) { i -> ((scene * 53 + i * 31) % 200 + 20).toFloat() + rnd.nextFloat() * 6f }

    @Test
    fun `finds the cuts between scenes`() {
        val rnd = Random(3)
        // Four scenes of 20 frames, a frame every 5 s: cuts at 100 s, 200 s, 300 s.
        val frames = (0 until 80).map { frame(it / 20, rnd) }
        assertEquals(listOf(100_000L, 200_000L, 300_000L), Scenes.cuts(frames, 5_000))
    }

    @Test
    fun `a flicker inside a scene is not two cuts`() {
        val rnd = Random(4)
        val frames = (0 until 40).map { frame(if (it == 20) 9 else 0, rnd) }
        // One odd frame: two jumps 5 s apart, kept as one.
        assertEquals(1, Scenes.cuts(frames, 5_000).size)
    }

    @Test
    fun `a steady video has no cuts`() {
        val rnd = Random(5)
        assertEquals(emptyList<Long>(), Scenes.cuts((0 until 60).map { frame(1, rnd) }, 5_000))
    }

    @Test
    fun `next scene moves on past where you are`() {
        val cuts = listOf(100_000L, 200_000L)
        assertEquals(100_000L, Scenes.next(cuts, 30_000))
        assertEquals(200_000L, Scenes.next(cuts, 99_000))
        assertEquals(null, Scenes.next(cuts, 250_000))
    }
}
