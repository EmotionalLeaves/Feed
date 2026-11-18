package dev.pfeed

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.pfeed.player.SmartVolume
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SmartVolumeTest {
    private fun rmsAfter(amplitude: Float, seconds: Int = 4): Float {
        SmartVolume.enabled = true
        val p = SmartVolume()
        p.configure(AudioProcessor.AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        p.flush()
        var lastRms = 0f
        repeat(seconds * 10) {
            val n = 4_410
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buf.putShort((sin(2 * PI * 440 * i / 44_100) * amplitude * 32767).toInt().toShort())
            buf.flip()
            p.queueInput(buf)
            val out = p.output
            var sum = 0.0
            var count = 0
            while (out.remaining() >= 2) { val v = out.short / 32768.0; sum += v * v; count++ }
            if (count > 0) lastRms = sqrt(sum / count).toFloat()
        }
        return lastRms
    }

    @Test
    fun quietComesUpLoudComesDown() {
        val quiet = rmsAfter(0.03f)   // rms ≈ 0.021 in
        val loud = rmsAfter(0.9f)     // rms ≈ 0.64 in
        assertTrue("quiet clip raised: $quiet", quiet > 0.04f)
        assertTrue("loud clip lowered: $loud", loud < 0.4f)
    }
}
