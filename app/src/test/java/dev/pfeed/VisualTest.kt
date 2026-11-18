package dev.pfeed

import dev.pfeed.domain.reco.Visual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VisualTest {
    private fun picture(rgb: Int, noise: Int = 0, seed: Int = 1): IntArray {
        val r = Random(seed)
        return IntArray(Visual.SIDE * Visual.SIDE) {
            val n = if (noise > 0) r.nextInt(-noise, noise + 1) else 0
            val red = ((rgb shr 16 and 0xFF) + n).coerceIn(0, 255); val g = ((rgb shr 8 and 0xFF) + n).coerceIn(0, 255); val b = ((rgb and 0xFF) + n).coerceIn(0, 255)
            (0xFF shl 24) or (red shl 16) or (g shl 8) or b
        }
    }

    @Test
    fun `describes and survives the trip through a string`() {
        val skin = Visual.describe(picture(0xE0AC8C))
        val sea = Visual.describe(picture(0x2050C0))
        assertTrue("skin tones read as skin", skin[36] > 0.9f && sea[36] < 0.1f)
        val back = Visual.decode(Visual.encode(skin))!!
        for (i in skin.indices) assertEquals(skin[i].coerceIn(-1f, 1f), back[i], 0.01f)
        val flat = Visual.describe(picture(0x808080))
        val busy = Visual.describe(picture(0x808080, noise = 60))
        assertTrue("a busy picture has more edges", busy[40] > flat[40] + 0.1f)
    }

    @Test
    fun `learns which looks you stay for`() {
        val taste = Visual.Taste()
        repeat(300) { i ->
            taste.learn(Visual.describe(picture(0xE0AC8C, 30, i)), 2.0)
            taste.learn(Visual.describe(picture(0x2050C0, 30, i)), -1.0)
        }
        val liked = taste.score(Visual.describe(picture(0xD8A080, 30, 999)))
        val other = taste.score(Visual.describe(picture(0x3060B0, 30, 999)))
        assertTrue("the look you stay for should score higher ($liked vs $other)", liked > other + 0.3)
    }
}
