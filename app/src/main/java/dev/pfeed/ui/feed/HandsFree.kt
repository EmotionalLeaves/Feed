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

/** The app is showing as a small floating window (picture-in-picture): just the video, still playing. */
object Pip {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    fun set(value: Boolean) { _active.value = value }
}
