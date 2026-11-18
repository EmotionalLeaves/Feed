package dev.pfeed.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Smart volume: evens out loudness, so a quiet clip comes up and a loud one doesn't blast.
 * A slow automatic gain on 16-bit PCM: it measures the level over the last moments, eases the gain
 * toward a target (never more than ×3 up or ×0.35 down), and a soft limit stops any peak clipping.
 * Switched in Settings → Playback; when off, audio passes through untouched.
 */
@UnstableApi
class SmartVolume : BaseAudioProcessor() {
    private var gain = 1f
    private var level = TARGET_RMS
    private var channels = 2
    private var sampleRate = 44_100
    private var scratch = FloatArray(2)
    private var fast = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8_000)
        // Always in the chain (it also measures the sound for "Feel the sound"); with smart volume
        // off it leaves the samples as they are.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = remaining / (2 * channels)
        // Level smoothing: ~400 ms; gain moves at most a little per frame (no pumping).
        val levelAlpha = 1f / (sampleRate * 0.4f)
        val fastAlpha = 1f / (sampleRate * 0.05f)
        val on = enabled
        val gainStep = 1f / (sampleRate * 0.8f)
        if (scratch.size != channels) scratch = FloatArray(channels)
        val samples = scratch
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val v = input.short / 32768f
                samples[c] = v
                sum += v * v
            }
            val rms = sqrt(sum / channels)
            level += (rms - level) * levelAlpha
            fast += (rms - fast) * fastAlpha
            // Silence isn't something to boost.
            val want = if (!on) 1f else if (level < SILENCE) gain else (TARGET_RMS / level).coerceIn(MIN_GAIN, MAX_GAIN)
            gain += (want - gain).coerceIn(-gainStep * 4, gainStep)
            for (c in 0 until channels) {
                var v = samples[c] * gain
                // Soft knee above 0.8 so loud peaks round off instead of clipping.
                if (v > KNEE) v = KNEE + (1 - KNEE) * kotlin.math.tanh((v - KNEE) / (1 - KNEE))
                else if (v < -KNEE) v = -KNEE - (1 - KNEE) * kotlin.math.tanh((-v - KNEE) / (1 - KNEE))
                out.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        out.flip()
        envelope = fast
        envelopeAt = android.os.SystemClock.elapsedRealtime()
    }

    override fun onFlush() {
        // A new clip: start from where its own level says, not the last one's.
        gain = 1f
        level = TARGET_RMS
    }

    companion object {
        /** Read when each player's audio is configured (set from settings). */
        @Volatile var enabled: Boolean = false

        /** How loud the sound playing right now is (0..1, ~50 ms smoothing), and when it was measured. */
        @Volatile var envelope: Float = 0f
        @Volatile var envelopeAt: Long = 0L

        private const val TARGET_RMS = 0.12f
        private const val SILENCE = 0.004f
        private const val MIN_GAIN = 0.35f
        private const val MAX_GAIN = 3f
        private const val KNEE = 0.8f
    }
}
