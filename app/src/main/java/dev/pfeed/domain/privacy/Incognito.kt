package dev.pfeed.domain.privacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Incognito: while on, nothing you watch is written down. No History, no "seen" marks, no resume
 * points, no recent searches, and the algorithm learns nothing. What you choose to keep (Save,
 * Follow) still works. Lasts until you turn it off or the app is closed, like a private tab.
 */
object Incognito {
    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on
    /** Settings → Private mode: the same, always (set from settings at startup and on change). */
    @Volatile var always = false

    val active: Boolean get() = _on.value || always

    fun set(value: Boolean) { _on.value = value }
}
