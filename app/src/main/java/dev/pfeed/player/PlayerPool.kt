package dev.pfeed.player

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fixed pool of [SIZE] ExoPlayers shared by the pager's live pages (current ± 1). A page acquires a
 * player when composed and releases it when disposed, so the number of decoders never grows no matter
 * how many items are swiped. If the pool is momentarily full (fast fling), the page shows its thumbnail
 * and retries when [changes] ticks.
 */
@OptIn(UnstableApi::class)
@Singleton
class PlayerPool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaCache: MediaCache,
) {
    private val players = arrayOfNulls<ExoPlayer>(SIZE)
    private val owners = arrayOfNulls<String>(SIZE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    @MainThread
    fun acquire(owner: String): ExoPlayer? {
        val existing = owners.indexOf(owner)
        if (existing >= 0) return playerAt(existing).also(::applyQuality)
        val free = owners.indexOfFirst { it == null }
        if (free < 0) return null
        owners[free] = owner
        return playerAt(free).also(::applyQuality)
    }

    /** Adaptive (HLS) streams pick their own quality; cap it to [maxHeight]. */
    private fun applyQuality(player: ExoPlayer) {
        val p = player.trackSelectionParameters
        if (p.maxVideoHeight == maxHeight) return
        player.trackSelectionParameters = p.buildUpon().setMaxVideoSize(Int.MAX_VALUE, maxHeight).build()
    }

    @MainThread
    fun release(owner: String) {
        val i = owners.indexOf(owner)
        if (i < 0) return
        owners[i] = null
        players[i]?.apply {
            playWhenReady = false
            stop()
            clearMediaItems()
        }
        _changes.value = _changes.value + 1
    }

    /** The video size of a player that's playing right now (for the picture-in-picture shape), or null. */
    @MainThread
    fun anyPlaying(): Pair<Int, Int>? = players.firstOrNull { it?.isPlaying == true }?.videoSize?.let { it.width to it.height }

    /** Frees decoders entirely (activity finishing). Players are recreated lazily. */
    @MainThread
    fun releaseAll() {
        players.forEachIndexed { i, p -> p?.release(); players[i] = null; owners[i] = null }
        _changes.value = _changes.value + 1
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun playerAt(i: Int): ExoPlayer = players[i] ?: ExoPlayer.Builder(context, smartVolumeRenderers())
        .setMediaSourceFactory(DefaultMediaSourceFactory(mediaCache.dataSourceFactory))
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Short buffers: start fast, keep memory flat across hundreds of swipes.
                .setBufferDurationsMs(2_500, 15_000, 500, 1_000)
                .build(),
        )
        // Headphones unplugged / Bluetooth dropped → pause, so sound never jumps to the speaker.
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(MOVIE, /* handleAudioFocus = */ false)
        }
        .also { players[i] = it }

    /** Audio goes through [SmartVolume] (a pass-through when it's off). */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun smartVolumeRenderers() = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(SmartVolume()))
                .build()
    }

    companion object {
        const val SIZE = 4

        /** 1080, or 480 with Data saver on (set from settings at startup and on change). */
        @Volatile var maxHeight: Int = 1080

        val MOVIE: androidx.media3.common.AudioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        /**
         * Muted: don't take audio focus, so music or a podcast keeps playing under a silent feed.
         * With sound: take focus (other audio pauses; a call pauses the video).
         */
        fun applyMute(player: ExoPlayer, muted: Boolean) {
            player.volume = if (muted) 0f else 1f
            player.setAudioAttributes(MOVIE, !muted)
        }
    }
}
