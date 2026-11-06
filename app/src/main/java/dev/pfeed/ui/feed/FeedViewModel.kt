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
import kotlinx.coroutines.async
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
    val items: StateFlow<List<MediaItem>> = combine(raw, blocks.blocklist) { list, bl -> list.filter(bl::allows) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val liked: StateFlow<Set<String>> = library.keys(LibraryKind.LIKED).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val saved: StateFlow<Set<String>> = library.keys(LibraryKind.SAVED).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val follows: StateFlow<Follows> = library.follows.stateIn(viewModelScope, SharingStarted.Eagerly, Follows())
    private val settings: StateFlow<AppSettings> = settingsRepo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _muted = MutableStateFlow(true)
    val muted: StateFlow<Boolean> = _muted

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    val startIndex: Int

    private var session: FeedSession? = null
    private var endReached = false
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
        session = sessions.create(tag?.let { FeedSpec.Tag(it) } ?: FeedSpec.Main(_mode.value))
        raw.value = emptyList()
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
                raw.update { it + r.items }
                endReached = r.endReached
                _hint.value = r.hint
                r.errors.forEach { _messages.tryEmit(it) }
            } finally {
                _loading.value = false
            }
        }
    }

    // ---- playback plumbing ----

    fun statsFor(key: String): PlaybackStats = stats.getOrPut(key) { PlaybackStats() }

    suspend fun resolve(item: MediaItem): StreamInfo? = streamAsync(item).await()

    private fun streamAsync(item: MediaItem): Deferred<StreamInfo?> = streams.getOrPut(item.key) {
        viewModelScope.async {
            try {
                registry.byId(item.sourceId)?.resolveStream(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("${sourceName(item.sourceId)}: ${e.message ?: "can't play this item"}")
                null
            }
        }
    }

    fun autoplayAllowed(): Boolean = settings.value.autoplayOnMobileData || !network.isMetered

    fun toggleMute() { _muted.value = !_muted.value }

    // ---- dwell tracking & signals ----

    fun onPageSettled(index: Int) {
        val list = items.value
        val item = list.getOrNull(index) ?: return
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
        val st = stats[item.key]
        viewModelScope.launch {
            if (dwell < FAST_SKIP_MS) {
                signals.record(item, Signal(SignalType.FAST_SKIP))
                return@launch
            }
            val durationMs = st?.durationMs?.takeIf { it > 0 } ?: item.durationSec?.times(1000L)
            val isVideo = item.type == MediaType.VIDEO || item.type == MediaType.EMBED
            val ratio = if (isVideo && durationMs != null) dwell.toDouble() / durationMs else dwell / STILL_NOMINAL_MS
            signals.record(item, Signal(SignalType.WATCH, ratio.coerceAtMost(3.0)))
            val replays = st?.replays ?: 0
            if (isVideo && (ratio >= 0.9 || replays > 0)) signals.record(item, Signal(SignalType.COMPLETE))
            if (replays > 0) signals.record(item, Signal(SignalType.REPLAY, replays.toDouble()))
        }
    }

    /** Next two items: thumbnails always; for the one after next, also the first seconds of video. */
    private fun preloadAround(list: List<MediaItem>, index: Int) {
        val ahead = (index + 1..index + 2).mapNotNull { list.getOrNull(it) }
        ahead.forEach { preloader.thumbnail(it.thumbnailUrl) }
        if (autoplayAllowed()) {
            list.getOrNull(index + 2)?.takeIf { it.type == MediaType.VIDEO }?.let { item ->
                viewModelScope.launch {
                    (resolve(item) as? StreamInfo.Direct)?.let { preloader.video(item.key, it.url) }
                }
            }
        }
        preloader.retainOnly(ahead.map { it.key }.toSet())
    }

    // ---- actions ----

    fun like(item: MediaItem) {
        if (item.key in liked.value) return
        viewModelScope.launch {
            library.add(LibraryKind.LIKED, item)
            signals.record(item, Signal(SignalType.LIKE))
        }
    }

    fun toggleLike(item: MediaItem) {
        if (item.key !in liked.value) return like(item)
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
                signals.record(item, Signal(SignalType.SAVE))
                _messages.tryEmit("Saved")
            }
        }
    }

    fun notInterested(item: MediaItem) {
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
            _messages.tryEmit("Blocked $creator")
        }
    }

    fun blockTag(tag: String) {
        viewModelScope.launch {
            blocks.block(BlockKind.TAG, tag)
            _messages.tryEmit("Blocked #${Blocklist.normalize(tag)}")
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
                signals.record(item, Signal(SignalType.FOLLOW))
                _messages.tryEmit("Following $creator")
            }
        }
    }

    fun toggleFollowTag(tag: String) {
        viewModelScope.launch {
            if (Blocklist.normalize(tag) in follows.value.tags) {
                library.unfollow(FollowKind.TAG, tag)
            } else {
                library.follow(FollowKind.TAG, tag)
                _messages.tryEmit("Following #${Blocklist.normalize(tag)}")
            }
        }
    }

    fun reportUrl(item: MediaItem): String? = registry.byId(item.sourceId)?.reportUrl(item)

    companion object {
        private const val LOAD_AHEAD = 5
        private const val FAST_SKIP_MS = 2_000L
        private const val STILL_NOMINAL_MS = 6_000.0
    }
}
