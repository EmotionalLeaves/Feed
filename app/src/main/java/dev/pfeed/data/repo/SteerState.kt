package dev.pfeed.data.repo

import dev.pfeed.domain.reco.Steer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What For You is being told in words right now (see [Steer]): this sitting only, never saved. */
object SteerState {
    private val _active = MutableStateFlow<List<Steer.Directive>>(emptyList())
    val active: StateFlow<List<Steer.Directive>> = _active
    private var setAt = 0L

    fun set(list: List<Steer.Directive>, now: Long = System.currentTimeMillis()) { _active.value = list; setAt = now }
    fun clear() { _active.value = emptyList() }

    /** The directives in force: they lapse after a few hours (a sitting, not a setting). */
    fun current(now: Long = System.currentTimeMillis()): List<Steer.Directive> {
        if (_active.value.isNotEmpty() && now - setAt > LIFETIME_MS) _active.value = emptyList()
        return _active.value
    }

    const val LIFETIME_MS = 4L * 60 * 60 * 1000
}
