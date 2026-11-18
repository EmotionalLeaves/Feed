package dev.pfeed.ui.feed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hands-free: the feed moves on by itself. Pictures stay up for [IMAGE_MS], galleries flip a page
 * every [GALLERY_PAGE_MS], short clips loop until they've played about [SHORT_VIDEO_TOTAL_MS], and
 * longer videos play once to the end. For the whole app, not one feed; off again when the app restarts.
 * Tapping a post pauses it as usual (and pauses the countdown).
 */
object HandsFree {
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    fun set(value: Boolean) { _on.value = value }

    const val IMAGE_MS = 6_000L
    const val GALLERY_PAGE_MS = 4_000L
    /** Clips shorter than this loop (and count loops); longer ones play once, then the feed moves on. */
    const val PLAY_ONCE_MIN_MS = 20_000L
    const val SHORT_VIDEO_TOTAL_MS = 12_000L
}

/** Playback speed for videos, picked in the hold menu; for the whole app until it restarts. */
object PlaybackSpeed {
    private val _value = MutableStateFlow(1f)
    val value: StateFlow<Float> = _value
    val steps = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
    fun next() { _value.value = steps[(steps.indexOf(_value.value) + 1) % steps.size] }
    fun label(v: Float) = (if (v % 1f == 0f) "${v.toInt()}" else "$v") + "×"
}

/** Clean screen: only the post, nothing drawn over it (no caption, buttons, top bar or tab bar). */
object CleanScreen {
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    fun set(value: Boolean) { _on.value = value }
}

/**
 * Sleep timer: playback stops when it runs out, and a dim "time's up" screen waits for a tap.
 * Lasts for this session only.
 */
object SleepTimer {
    private val _endsAt = MutableStateFlow<Long?>(null)
    /** When it runs out (wall clock), or null when there's no timer. */
    val endsAt: StateFlow<Long?> = _endsAt
    private val _upNow = MutableStateFlow(false)
    /** It ran out: everything is paused until you choose to keep watching. */
    val upNow: StateFlow<Boolean> = _upNow

    val choices = listOf(15, 30, 45, 60, 90)

    fun start(minutes: Int) { _upNow.value = false; _endsAt.value = System.currentTimeMillis() + minutes * 60_000L }
    fun cancel() { _endsAt.value = null }
    fun runOut() { _endsAt.value = null; _upNow.value = true }
    fun keepWatching() { _upNow.value = false }

    fun minutesLeft(now: Long = System.currentTimeMillis()): Int? = _endsAt.value?.let { ((it - now + 59_999) / 60_000).toInt().coerceAtLeast(0) }
}

/**
 * Feel the sound: the phone vibrates with what's playing, stronger as it gets louder. Muted or not.
 * For this session; started from the hold menu.
 */
object FeelTheSound {
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    fun set(value: Boolean) { _on.value = value }

    /** Vibration strength (1..255) for a loudness [envelope], or 0 for nothing. */
    fun amplitude(envelope: Float): Int {
        val x = ((envelope - FLOOR) / (CEIL - FLOOR)).coerceIn(0f, 1f)
        return if (x <= 0f) 0 else (40 + 215 * x * x).toInt().coerceIn(1, 255)
    }

    private const val FLOOR = 0.02f
    private const val CEIL = 0.3f
}

/** "Loop a part" from the hold menu: the post (by key) that should start marking a loop. */
object LoopPart {
    val request = MutableStateFlow<String?>(null)
}

/** "Save this frame" from the hold menu: the post (by key) whose current frame should be kept. */
object FrameGrab {
    val request = MutableStateFlow<String?>(null)
}

/**
 * Dim: a dark veil over the whole app, darker than the phone's lowest brightness, for watching in
 * the dark. Steps through off, 30%, 50%, 70%; for this session.
 */
object Dimmer {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val steps = listOf(0f, 0.3f, 0.5f, 0.7f)
    fun next() { _level.value = steps[(steps.indexOf(_level.value) + 1).mod(steps.size)] }
    fun off() { _level.value = 0f }
}

/** Minutes watched in one stretch (any feed), for the break reminder; a pause of 10 min resets it. */
object WatchStretch {
    @Volatile var minutes = 0
    @Volatile var lastTick = 0L
    @Volatile var reminded = false
}

/** The app is showing as a small floating window (picture-in-picture): just the video, still playing. */
object Pip {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    fun set(value: Boolean) { _active.value = value }
}
