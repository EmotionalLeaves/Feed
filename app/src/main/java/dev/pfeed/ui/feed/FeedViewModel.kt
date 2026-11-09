package dev.pfeed.ui.feed

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pfeed.data.repo.BlockKind
import dev.pfeed.data.repo.BlockRepository
import dev.pfeed.data.repo.FeedSession
import dev.pfeed.data.repo.FeedSessionFactory
import dev.pfeed.data.repo.FeedSpec
import dev.pfeed.data.repo.FollowKind
import dev.pfeed.data.repo.Follows
import dev.pfeed.data.repo.LibraryKind
import dev.pfeed.data.repo.LibraryRepository
import dev.pfeed.data.repo.NetworkMonitor
import dev.pfeed.data.repo.SeenItems
import dev.pfeed.data.repo.DwellBaseline
import dev.pfeed.domain.reco.Engagement
import dev.pfeed.data.repo.SignalRepository
import dev.pfeed.data.repo.TransientFeedHolder
import dev.pfeed.data.settings.AppSettings
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.filter.Blocklist
import dev.pfeed.domain.model.FeedMode
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.model.MediaType
import dev.pfeed.domain.model.StreamInfo
import dev.pfeed.domain.reco.Signal
import dev.pfeed.domain.reco.SignalType
import dev.pfeed.player.PlayerPool
import dev.pfeed.player.Preloader
import dev.pfeed.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Per-item playback stats written by the video page, read when the user swipes away. */
class PlaybackStats {
    @Volatile var durationMs: Long = 0
    @Volatile var replays: Int = 0
    /** Set when the user liked/saved/followed/dismissed during this view: that's the signal, not dwell. */
    @Volatile var explicit: Boolean = false
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val sessions: FeedSessionFactory,
    private val signals: SignalRepository,
    private val library: LibraryRepository,
    private val blocks: BlockRepository,
    private val settingsRepo: SettingsRepository,
    private val registry: SourceRegistry,
    private val network: NetworkMonitor,
    private val preloader: Preloader,
    private val seen: SeenItems,
    private val dwellBaseline: DwellBaseline,
    holder: TransientFeedHolder,
    val playerPool: PlayerPool,
) : ViewModel() {

    val tag: String? = savedState.get<String>("tag")
    private val viewerToken: String? = savedState.get<String>("token")
    val isViewer: Boolean = viewerToken != null

    private val _mode = MutableStateFlow(FeedMode.FOR_YOU)
    val mode: StateFlow<FeedMode> = _mode

    private val raw = MutableStateFlow<List<MediaItem>>(emptyList())

    /** Re-filtered whenever the blocklist changes, so a block takes effect immediately. */
    /** Items that failed to resolve, load or play. Removed from the feed so it simply moves on. */
    private val broken = MutableStateFlow<Set<String>>(emptySet())

    val items: StateFlow<List<MediaItem>> = combine(raw, blocks.blocklist, broken) { list, bl, bad ->
        list.filter { bl.allows(it) && it.key !in bad }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Something about this item doesn't work (dead link, unplayable format, blocked host). Drop it
     * quietly instead of showing an error: the pager slides the next item into its place.
     */
    fun markBroken(item: MediaItem) {
        if (item.key in broken.value) return
        // Only drop what's on screen or ahead. Removing an item behind you would shift the list and
        // jump the page you're looking at; it stays behind you, harmlessly.
        val index = items.value.indexOfFirst { it.key == item.key }
        if (index in 0 until lastSettled) return
        broken.update { it + item.key }
        streams.remove(item.key)
    }

    val liked: StateFlow<Set<String>> = library.keys(LibraryKind.LIKED).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val saved: StateFlow<Set<String>> = library.keys(LibraryKind.SAVED).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val follows: StateFlow<Follows> = library.follows.stateIn(viewModelScope, SharingStarted.Eagerly, Follows())
    private val settings: StateFlow<AppSettings> = settingsRepo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _muted = MutableStateFlow(true)
    val muted: StateFlow<Boolean> = _muted

    /** Landscape mode: the whole feed sideways and immersive, kept while swiping post to post. */
    private val _landscape = MutableStateFlow(false)
    val landscape: StateFlow<Boolean> = _landscape
    fun setLandscape(on: Boolean) { _landscape.value = on }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint


    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val startIndex: Int

    private var session: FeedSession? = null
    private var endReached = false
    private var lastSettled = 0
    private var retryJob: Job? = null
    private var retryDelayMs = RETRY_MIN_MS
    private val streams = HashMap<String, Deferred<StreamInfo?>>()
    private val stats = HashMap<String, PlaybackStats>()

    private var currentItem: MediaItem? = null
    private var enteredAt = 0L
    private var visible = false

    init {
        viewModelScope.launch { _muted.value = settingsRepo.get().defaultMute }
        if (viewerToken != null) {
            val (list, start) = holder.get(viewerToken) ?: (emptyList<MediaItem>() to 0)
            raw.value = list
            startIndex = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))
            endReached = true
        } else {
            startIndex = 0
            restart()
        }
    }

    fun sourceName(sourceId: String) = registry.displayName(sourceId)

    fun setMode(mode: FeedMode) {
        if (mode == _mode.value) return
        _mode.value = mode
        restart()
    }

    fun refresh() = restart()

    private fun restart() {
        if (isViewer) return
        finishCurrent()
        currentItem = null
        retryJob?.cancel()
        retryDelayMs = RETRY_MIN_MS
        session?.close()
        session = sessions.create(tag?.let { FeedSpec.Tag(it) } ?: FeedSpec.Main(_mode.value), viewModelScope)
        raw.value = emptyList()
        lastSettled = 0
        endReached = false
        _hint.value = null
        loadMore()
    }

    fun loadMore() {
        val s = session ?: return
        if (_loading.value || endReached) return
        _loading.value = true
        viewModelScope.launch {
            try {
                val r = s.loadMore()
                if (s !== session) return@launch // mode changed while loading
                raw.update { list ->
                    val have = list.mapTo(HashSet()) { it.key }
                    list + r.items.filter { it.key !in have }
                }
                endReached = r.endReached
                _hint.value = r.hint
                when {
                    r.items.isEmpty() && !r.endReached && r.hint == null -> scheduleRetry(s)
                    // A short batch (pool briefly thin) still leaves the screen near the end: keep
                    // filling right away instead of waiting for a swipe that can't happen yet.
                    r.items.isNotEmpty() && !r.endReached && items.value.size - lastSettled <= LOAD_AHEAD -> {
                        retryDelayMs = RETRY_MIN_MS
                        retryJob?.cancel()
                        retryJob = viewModelScope.launch {
                            delay(CHAIN_DELAY_MS)
                            if (s === session) loadMore()
                        }
                    }
                    r.endReached && r.hint == null -> {
                        // All sources ran dry; auto-restart after a short pause so the feed is truly endless.
                        retryJob?.cancel()
                        retryJob = viewModelScope.launch {
                            delay(RESTART_DELAY_MS)
                            if (s === session) restart()
                        }
                    }
                    else -> retryDelayMs = RETRY_MIN_MS
                }
            } finally {
                _loading.value = false
            }
        }
    }

    /** Sources were slow or briefly failing: try again shortly (backing off) if the user is near the end. */
    private fun scheduleRetry(s: FeedSession) {
        retryJob?.cancel()
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
        retryJob = viewModelScope.launch {
            delay(wait)
            if (s === session && lastSettled >= items.value.size - LOAD_AHEAD) loadMore()
        }
    }

    override fun onCleared() {
        session?.close()
        super.onCleared()
    }

    // ---- playback plumbing ----

    fun statsFor(key: String): PlaybackStats = stats.getOrPut(key) { PlaybackStats() }

    suspend fun resolve(item: MediaItem): StreamInfo? = streamAsync(item).await()

    private fun streamAsync(item: MediaItem): Deferred<StreamInfo?> = streams.getOrPut(item.key) {
        viewModelScope.async {
            val resolved: StreamInfo? = try {
                registry.byId(item.sourceId)?.resolveStream(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (resolved == null) markBroken(item)
            resolved
        }
    }

    fun autoplayAllowed(): Boolean = settings.value.autoplayOnMobileData || !network.isMetered

    fun toggleMute() { _muted.value = !_muted.value }

    // ---- dwell tracking & signals ----

    fun onPageSettled(index: Int) {
        val list = items.value
        lastSettled = index
        val item = list.getOrNull(index) ?: return
        seen.mark(item.key)
        // Free memory from items far behind — keep a window of 50 around the current position.
        if (index > GC_BEHIND) {
            val oldKeys = list.subList(0, (index - GC_BEHIND).coerceAtLeast(0)).map { it.key }.toSet()
            oldKeys.forEach { streams.remove(it)?.cancel(); stats.remove(it) }
        }
        if (item.key != currentItem?.key) {
            finishCurrent()
            currentItem = item
            if (visible) enteredAt = SystemClock.elapsedRealtime()
            viewModelScope.launch { library.add(LibraryKind.HISTORY, item) }
        }
        if (index >= list.size - LOAD_AHEAD) loadMore()
        preloadAround(list, index)
    }

    fun onVisibilityChanged(isVisible: Boolean) {
        if (isVisible == visible) return
        visible = isVisible
        if (isVisible) enteredAt = SystemClock.elapsedRealtime() else finishCurrent()
    }

    private fun finishCurrent() {
        val item = currentItem ?: return
        if (enteredAt == 0L) return
        val dwell = SystemClock.elapsedRealtime() - enteredAt
        enteredAt = 0L
        // It never worked, so the time on it says nothing about taste: don't learn from it.
        if (item.key in broken.value) return
        val st = stats[item.key]
        viewModelScope.launch {
            // A post listed as an image can turn out to be a clip (see Display.effectiveType); if the
            // player reported a duration, judge the dwell against typical video viewing, not images.
            val isVideo = item.type == MediaType.VIDEO || item.type == MediaType.EMBED || (st?.durationMs ?: 0L) > 0L
            val typical = dwellBaseline.typicalMs(isVideo)
            dwellBaseline.observe(isVideo, dwell)
            // A like/save/follow/"not interested" already told the model what you think; recording the
            // dwell afterwards would pull it back toward an average view (the model is error-driven).
            if (st?.explicit == true) return@launch
            val durationMs = st?.durationMs?.takeIf { it > 0 } ?: item.durationSec?.times(1000L)
            val target = Engagement.target(dwell, isVideo, durationMs, st?.replays ?: 0, typical)
            signals.record(item, Signal(SignalType.VIEW, target))
        }
    }

    /**
     * Next [PRELOAD_AHEAD] items: thumbnails for all; the real file resolved early for the next few
     * (board post pages and RedGIFs lookups take a round trip, so they're done before you arrive);
     * full-size images warmed, and the first ~2.5 MB of the next two videos cached.
     */
    private fun preloadAround(list: List<MediaItem>, index: Int) {
        val ahead = (index + 1..index + PRELOAD_AHEAD).mapNotNull { list.getOrNull(it) }
        ahead.forEach { preloader.thumbnail(it.thumbnailUrl) }
        val autoplay = autoplayAllowed()
        ahead.take(RESOLVE_AHEAD).forEachIndexed { i, item ->
            viewModelScope.launch {
                val s = resolve(item) as? StreamInfo.Direct ?: return@launch
                val type = Display.effectiveType(item, s)
                when {
                    type == MediaType.VIDEO -> if (autoplay && i < 2) preloader.video(item.key, s.url)
                    else -> preloader.image(s.url)
                }
            }
        }
        preloader.retainOnly(ahead.map { it.key }.toSet())
    }

    // ---- actions ----

    fun like(item: MediaItem) {
        if (item.key in liked.value) return
        statsFor(item.key).explicit = true
        viewModelScope.launch {
            library.add(LibraryKind.LIKED, item)
            signals.record(item, Signal(SignalType.LIKE))
        }
    }

    fun toggleLike(item: MediaItem) {
        if (item.key !in liked.value) return like(item)
        statsFor(item.key).explicit = true
        viewModelScope.launch {
            library.remove(LibraryKind.LIKED, item)
            signals.record(item, Signal(SignalType.UNLIKE))
        }
    }

    fun toggleSave(item: MediaItem) {
        viewModelScope.launch {
            if (item.key in saved.value) {
                library.remove(LibraryKind.SAVED, item)
            } else {
                library.add(LibraryKind.SAVED, item)
                statsFor(item.key).explicit = true
                signals.record(item, Signal(SignalType.SAVE))
                _messages.tryEmit("Saved")
            }
        }
    }

    fun notInterested(item: MediaItem) {
        statsFor(item.key).explicit = true
        viewModelScope.launch {
            signals.record(item, Signal(SignalType.NOT_INTERESTED))
            blocks.block(BlockKind.ITEM, item.key)
            _messages.tryEmit("Got it. You'll see less like this.")
        }
    }

    fun blockCreator(item: MediaItem) {
        val creator = item.creator ?: return
        viewModelScope.launch {
            blocks.block(BlockKind.CREATOR, creator)
            _messages.tryEmit("Blocked " + (Display.creator(item)?.let { "@$it" } ?: "this creator"))
        }
    }

    /** Never show posts from this item's subreddit / board site / server again. */
    fun hideOrigin(item: MediaItem, label: String) {
        statsFor(item.key).explicit = true
        viewModelScope.launch {
            signals.record(item, Signal(SignalType.NOT_INTERESTED))
            blocks.block(BlockKind.ORIGIN, dev.pfeed.domain.reco.Spread.origin(item))
            _messages.tryEmit("Hidden: $label")
        }
    }

    fun blockTag(tag: String) {
        viewModelScope.launch {
            blocks.block(BlockKind.TAG, tag)
            _messages.tryEmit("Hiding #${Blocklist.normalize(tag).replace('_', ' ')}")
        }
    }

    fun isFollowingCreator(item: MediaItem, f: Follows): Boolean =
        item.creator?.let { Blocklist.normalize(it) in f.creators } == true

    fun toggleFollowCreator(item: MediaItem) {
        val creator = item.creator ?: return
        viewModelScope.launch {
            if (Blocklist.normalize(creator) in follows.value.creators) {
                library.unfollow(FollowKind.CREATOR, creator)
            } else {
                library.follow(FollowKind.CREATOR, creator)
                statsFor(item.key).explicit = true
                signals.record(item, Signal(SignalType.FOLLOW))
                _messages.tryEmit("Following " + (Display.creator(item)?.let { "@$it" } ?: "this creator"))
            }
        }
    }

    fun toggleFollowTag(tag: String) {
        viewModelScope.launch {
            if (Blocklist.normalize(tag) in follows.value.tags) {
                library.unfollow(FollowKind.TAG, tag)
            } else {
                library.follow(FollowKind.TAG, tag)
                _messages.tryEmit("Following #${Blocklist.normalize(tag).replace('_', ' ')}")
            }
        }
    }

    fun reportUrl(item: MediaItem): String? = registry.byId(item.sourceId)?.reportUrl(item)

    companion object {
        private const val LOAD_AHEAD = 10
        private const val PRELOAD_AHEAD = 5
        private const val RESOLVE_AHEAD = 3
        private const val CHAIN_DELAY_MS = 300L
        private const val RETRY_MIN_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val RESTART_DELAY_MS = 5_000L
        private const val GC_BEHIND = 50
    }
}
