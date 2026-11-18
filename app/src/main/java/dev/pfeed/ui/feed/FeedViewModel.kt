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
import kotlinx.coroutines.flow.map
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
    /** Furthest page reached in a comic or photo set (0-based). */
    @Volatile var maxPage: Int = 0
    /** Hands-free moved on from this post by itself: the time on it wasn't your choice. */
    @Volatile var autoAdvanced: Boolean = false
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
    private val mediaHealth: dev.pfeed.data.repo.MediaHealth,
    private val offline: dev.pfeed.data.repo.OfflineStore,
    private val holder: TransientFeedHolder,
    val playerPool: PlayerPool,
    val livePreviews: dev.pfeed.ui.common.LivePreviews,
) : ViewModel() {

    private val rawTag: String? = savedState.get<String>("tag")
    /** "More like this" pages come in as a tag of the form "~like:<token>", the post kept in the holder. */
    private val similarSeed: MediaItem? = rawTag?.takeIf { it.startsWith(SIMILAR_PREFIX) }
        ?.let { holder.get(it.removePrefix(SIMILAR_PREFIX))?.first?.firstOrNull() }
    val isSimilar: Boolean = similarSeed != null
    val tag: String? = if (rawTag?.startsWith(SIMILAR_PREFIX) == true) null else rawTag
    private val similarSpec: FeedSpec.Similar? = similarSeed?.let(::similarTo)
    /** The app's own feed (For You / Following / Latest), not a tag, creator, list or "more like" page. */
    val isMainFeed: Boolean get() = tag == null && !isViewer && !isSimilar
    private val viewerToken: String? = savedState.get<String>("token")
    val isViewer: Boolean = viewerToken != null
    /** Heading for a viewer opened from Library or Search. */
    val viewerTitle: String? = viewerToken?.let { holder.title(it) } ?: similarSeed?.let { "More like this" }

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
    fun markBroken(item: MediaItem, reason: String = "") {
        if (item.key in broken.value) return
        // Only drop what's on screen or ahead. Removing an item behind you would shift the list and
        // jump the page you're looking at; it stays behind you, harmlessly.
        val index = items.value.indexOfFirst { it.key == item.key }
        if (index in 0 until lastSettled) return
        streams.remove(item.key)
        if (mediaHealth.failed(item, reason)) {
            // Third post in a row from this site that wouldn't load while others do: its files are
            // down. Drop the rest of it that's queued ahead instead of stalling on each one.
            val origin = dev.pfeed.domain.reco.Spread.origin(item)
            val ahead = items.value.drop(lastSettled + 1).filter { dev.pfeed.domain.reco.Spread.origin(it) == origin }.map { it.key }
            broken.update { it + item.key + ahead }
            _messages.tryEmit("${Display.source(item, item.sourceId)} isn't loading right now, skipping it for a while")
        } else {
            broken.update { it + item.key }
        }
    }

    /** Warms the image cache for a gallery page about to be shown. */
    fun warmImage(url: String) = preloader.image(url)

    /** The post's media actually appeared (first video frame / image drawn). */
    fun markShown(item: MediaItem) {
        // A comic's pages each draw (and the next ones preload); the post counts once.
        val post = item.key.substringBefore('#')
        if (shownPosts.add(post)) mediaHealth.shown(item)
    }

    private val shownPosts = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    val liked: StateFlow<Set<String>> = library.keys(LibraryKind.LIKED).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val saved: StateFlow<Set<String>> = library.keys(LibraryKind.SAVED).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val follows: StateFlow<Follows> = library.follows.stateIn(viewModelScope, SharingStarted.Eagerly, Follows())
    private val settings: StateFlow<AppSettings> = settingsRepo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    /** Private mode is on (shown as a small marker on the feed so it's never forgotten on). */
    val privateMode: StateFlow<Boolean> = settingsRepo.flow.map { it.privateMode }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
            // Search results continue as an endless feed of the same search after the last result.
            val more = holder.continuation(viewerToken)
            if (more != null) {
                session = sessions.create(FeedSpec.Tag(more), viewModelScope)
                endReached = false
            } else {
                endReached = true
            }
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
        session = sessions.create(similarSpec ?: tag?.let { FeedSpec.Tag(it) } ?: FeedSpec.Main(_mode.value), viewModelScope)
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

    /** A comic's reader turned to [page]. */
    fun onComicPage(item: MediaItem, page: Int) {
        val st = statsFor(item.key)
        if (page > st.maxPage) st.maxPage = page
    }

    fun statsFor(key: String): PlaybackStats = stats.getOrPut(key) { PlaybackStats() }

    suspend fun resolve(item: MediaItem): StreamInfo? = streamAsync(item).await()

    private fun streamAsync(item: MediaItem): Deferred<StreamInfo?> = streams.getOrPut(item.key) {
        viewModelScope.async {
            // A saved post kept on the phone plays from there: instant, and works offline.
            // (Looked up on the IO pool: the first lookup lists the folder of kept files.)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { offline.local(item.key) }?.let { return@async it }
            val resolved: StreamInfo? = try {
                registry.byId(item.sourceId)?.resolveStream(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (resolved == null) markBroken(item, "couldn't find its file")
            resolved
        }
    }

    /**
     * Where a long video starts when you've not watched it before: past the studio intro, if
     * "Skip intros" is on (videos of 5 minutes and more; 0 otherwise).
     */
    fun introSkipMs(item: MediaItem): Long {
        val d = item.durationSec ?: return 0L
        if (!settings.value.skipIntros || d < INTRO_MIN_SEC) return 0L
        return minOf(INTRO_SKIP_MS, d * 1000L / 20)
    }

    fun autoplayAllowed(): Boolean = settings.value.autoplayOnMobileData || !network.isMetered

    fun toggleMute() { _muted.value = !_muted.value }

    init {
        viewModelScope.launch { VolumeKeys.up.collect { if (_muted.value) _muted.value = false } }
    }

    /** Audio route went away (headphones unplugged): silence the feed rather than use the speaker. */
    fun muteNow() {
        if (!_muted.value) {
            _muted.value = true
            _messages.tryEmit("Headphones disconnected: muted")
        }
    }

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
            stats[item.key]?.autoAdvanced = false // back on it: this visit is judged afresh
            if (visible) enteredAt = SystemClock.elapsedRealtime()
            if (!settings.value.privateMode) viewModelScope.launch { library.add(LibraryKind.HISTORY, item) }
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
            // Hands-free decided how long this stayed up, so it says nothing about your pace, and a
            // picture or clip it timed out on teaches nothing either (every one would look "watched").
            // A longer video it let play to the end still counts, modestly.
            val auto = st?.autoAdvanced == true
            if (auto && (st?.explicit == true || settings.value.privateMode)) return@launch
            if (auto) {
                val longVideo = isVideo && (st?.durationMs ?: 0L) >= HandsFree.PLAY_ONCE_MIN_MS && st?.replays == 0
                if (longVideo) signals.record(item, Signal(SignalType.VIEW, HANDS_FREE_FULL_VIEW))
                return@launch
            }
            // Reading a comic takes minutes: that would teach the image baseline that people stare at images.
            if (!item.isGallery) dwellBaseline.observe(isVideo, dwell)
            // A like/save/follow/"not interested" already told the model what you think; recording the
            // dwell afterwards would pull it back toward an average view (the model is error-driven).
            if (st?.explicit == true) return@launch
            // Private mode: watching teaches nothing (likes and saves still count; they're deliberate).
            if (settings.value.privateMode) return@launch
            val durationMs = st?.durationMs?.takeIf { it > 0 } ?: item.durationSec?.times(1000L)
            val target = if (item.isGallery && !isVideo) {
                Engagement.comicTarget(dwell, (st?.maxPage ?: 0) + 1, item.pages.size, typical)
            } else {
                Engagement.target(dwell, isVideo, durationMs, st?.replays ?: 0, typical)
            }
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
        noteKeeper(item)
    }

    /** Creators whose posts you liked or saved this session, and the ones already suggested. */
    private val keptByCreator = HashMap<String, Int>()
    private val suggested = HashSet<String>()
    private val _followHints = MutableSharedFlow<MediaItem>(extraBufferCapacity = 2)
    /** "You keep liking @name: follow them?" — offered once per creator, after the second like or save. */
    val followHints: SharedFlow<MediaItem> = _followHints

    private fun noteKeeper(item: MediaItem) {
        val creator = Display.creator(item)?.let(Blocklist::normalize) ?: return
        if (isFollowingCreator(item, follows.value) || creator in suggested) return
        val n = (keptByCreator[creator] ?: 0) + 1
        keptByCreator[creator] = n
        if (n >= 2) {
            suggested += creator
            _followHints.tryEmit(item)
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
                noteKeeper(item)
                _messages.tryEmit("Saved")
            }
        }
    }

    /** Your collections (named folders of saved posts), most recently added-to first. */
    val collections: StateFlow<List<dev.pfeed.data.repo.LibraryRepository.Collection>> =
        library.collections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun collectionsOf(item: MediaItem): Set<String> = library.collectionsOf(item)

    /** Put a post in a collection (saving it, if it wasn't) or take it out (it stays saved). */
    fun setInCollection(item: MediaItem, name: String, add: Boolean) {
        viewModelScope.launch {
            if (add) {
                val wasSaved = item.key in saved.value
                library.addToCollection(name, item)
                if (!wasSaved) {
                    statsFor(item.key).explicit = true
                    signals.record(item, Signal(SignalType.SAVE))
                    noteKeeper(item)
                }
                _messages.tryEmit("Added to ${dev.pfeed.data.repo.LibraryRepository.cleanName(name) ?: name}")
            } else {
                library.removeFromCollection(name, item)
                _messages.tryEmit("Removed from $name")
            }
        }
    }

    fun notInterested(item: MediaItem) {
        statsFor(item.key).explicit = true
        viewModelScope.launch {
            signals.record(item, Signal(SignalType.NOT_INTERESTED))
            blocks.block(BlockKind.ITEM, item.key)
            undoable("Got it. You'll see less like this.", BlockKind.ITEM, item.key)
        }
    }

    /** True when nothing has been learned yet (a new install): only then is the topic picker offered. */
    val isNewUser: StateFlow<Boolean?> = kotlinx.coroutines.flow.flow {
        emit(runCatching { signals.affinities().tags.size < 3 }.getOrDefault(false))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Whether [item] is kept on the phone as a file that can be exported (not a stream). */
    fun canExport(item: MediaItem): Boolean = android.os.Build.VERSION.SDK_INT >= 29 && !item.isGallery && offline.localFileCached(item.key)

    fun exportToDownloads(item: MediaItem) {
        viewModelScope.launch {
            val ok = offline.exportToDownloads(item.key, item.title)
            _messages.tryEmit(if (ok) "Saved to your phone's Downloads" else "Couldn't export it")
        }
    }

    /** Why For You showed [item]: the learned interests it matches most, as labels ("#milf"). */
    /** The page for "More like this": its token goes in the tag route. */
    fun similarRoute(item: MediaItem): String = SIMILAR_PREFIX + holder.put(listOf(item), 0, title = "More like this")

    private fun similarTo(item: MediaItem): FeedSpec.Similar {
        val features = dev.pfeed.domain.reco.Features.of(item).filterNot(dev.pfeed.domain.reco.Features::isMeta)
        val creator = Display.creator(item)
        // Searches: the creator, then the post's first few real tags (sites list the important ones first).
        // Real words only: boards carry tags like ":o" or "2 horns" that make useless searches.
        fun searchable(t: String) = t.length >= 3 && t.count { it.isLetter() } >= 3 && !t.first().isDigit()
        val tags = features.filterNot { it.startsWith("w:") }.map { it.replace('_', ' ') }.filter(::searchable).take(3)
        val words = features.filter { it.startsWith("w:") }.take(2).map { it.removePrefix("w:") }
        val queries = (listOfNotNull(creator) + tags + words).distinct().ifEmpty { listOfNotNull(item.title?.take(40)) }
        return FeedSpec.Similar(item.key, queries, features.toSet(), item.creator)
    }

    /** Tags For You has learned you like (normalised), refreshed now and then: they're lit on posts. */
    private val _likedTags = MutableStateFlow<Set<String>>(emptySet())
    val likedTags: StateFlow<Set<String>> = _likedTags
    private var likedTagsAt = 0L
    fun refreshLikedTags() {
        val now = System.currentTimeMillis()
        if (now - likedTagsAt < LIKED_TAGS_REFRESH_MS) return
        likedTagsAt = now
        viewModelScope.launch {
            val aff = runCatching { signals.affinities().tags }.getOrDefault(emptyMap())
            _likedTags.value = aff.entries.asSequence()
                .filter { it.value >= 0.4 && !dev.pfeed.domain.reco.Features.isMeta(it.key) && !it.key.startsWith("@") }
                .sortedByDescending { it.value }.take(60).map { it.key }.toSet()
        }
    }

    suspend fun reasons(item: MediaItem): List<String> {
        val aff = runCatching { signals.affinities().tags }.getOrDefault(emptyMap())
        return dev.pfeed.domain.reco.Features.of(item)
            .filterNot(dev.pfeed.domain.reco.Features::isMeta)
            .mapNotNull { f -> aff[f]?.takeIf { it >= 0.3 }?.let { f to it } }
            .sortedByDescending { it.second }
            .take(3)
            .map { dev.pfeed.domain.reco.Features.label(it.first) }
    }

    /** One reason a post is in For You: what to say, and the page it leads to (a tag or "@creator"). */
    data class Why(val text: String, val page: String?, val strong: Boolean)

    /**
     * Why this post: who and what you follow comes first, then the learned interests it matches
     * most, each one tappable.
     */
    suspend fun whyThis(item: MediaItem): List<Why> {
        val out = mutableListOf<Why>()
        val f = follows.value
        val creator = Display.creator(item)
        if (creator != null && f.creators.any { it.equals(creator, ignoreCase = true) }) out += Why("You follow @$creator", "@$creator", true)
        val tags = Display.tags(item, max = 30)
        tags.firstOrNull { (_, raw) -> f.tags.any { it.equals(raw, ignoreCase = true) } }?.let { (label, raw) -> out += Why("You follow #$label", raw, true) }
        val aff = runCatching { signals.affinities() }.getOrNull()
        if (aff != null) {
            creator?.let { c -> aff.creators[dev.pfeed.domain.filter.Blocklist.normalize(c)]?.takeIf { it >= 0.3 } }
                ?.let { if (out.none { it.page == "@$creator" }) out += Why("You watch a lot of @$creator", "@$creator", false) }
            val byFeature = tags.associateBy { dev.pfeed.domain.reco.Features.normalizeTag(it.second) }
            dev.pfeed.domain.reco.Features.of(item)
                .filterNot(dev.pfeed.domain.reco.Features::isMeta)
                .mapNotNull { feat -> aff.tags[feat]?.takeIf { it >= 0.3 }?.let { feat to it } }
                .sortedByDescending { it.second }
                .take(3)
                .forEach { (feat, _) ->
                    val tag = byFeature[feat]
                    out += Why(if (tag != null) "You like #${tag.first}" else "You like " + dev.pfeed.domain.reco.Features.label(feat), tag?.second, false)
                }
        }
        return out.distinctBy { it.text }.take(4)
    }

    /** First run: the topics picked teach For You straight away, then the feed starts over with them. */
    fun pickInterests(topics: List<String>) {
        viewModelScope.launch {
            topics.forEach { runCatching { signals.recordInterest(it) } }
            if (topics.isNotEmpty()) refresh()
        }
    }

    /** A note with one action ("Carrying on from 12:30" · Start over). */
    private val _offers = MutableSharedFlow<Triple<String, String, () -> Unit>>(extraBufferCapacity = 2)
    val offers: SharedFlow<Triple<String, String, () -> Unit>> = _offers
    fun offer(text: String, action: String, run: () -> Unit) { _offers.tryEmit(Triple(text, action, run)) }

    /** Confirmations of things that hide posts, each with a way to take it back. */
    private val _undoables = MutableSharedFlow<Pair<String, () -> Unit>>(extraBufferCapacity = 4)
    val undoables: SharedFlow<Pair<String, () -> Unit>> = _undoables

    private fun undoable(text: String, kind: BlockKind, value: String) {
        val stored = if (kind == BlockKind.ITEM) value else Blocklist.normalize(value) // as BlockRepository.block stores it
        _undoables.tryEmit(text to { viewModelScope.launch { blocks.unblock(kind.name, stored) }; Unit })
    }

    fun blockCreator(item: MediaItem) {
        val creator = item.creator ?: return
        viewModelScope.launch {
            blocks.block(BlockKind.CREATOR, creator)
            undoable("Blocked " + (Display.creator(item)?.let { "@$it" } ?: "this creator"), BlockKind.CREATOR, creator)
        }
    }

    /** Never show posts from this item's subreddit / board site / server again. */
    fun hideOrigin(item: MediaItem, label: String) {
        statsFor(item.key).explicit = true
        viewModelScope.launch {
            signals.record(item, Signal(SignalType.NOT_INTERESTED))
            val origin = dev.pfeed.domain.reco.Spread.origin(item)
            blocks.block(BlockKind.ORIGIN, origin)
            undoable("Hidden: $label", BlockKind.ORIGIN, origin)
        }
    }

    fun blockTag(tag: String) {
        viewModelScope.launch {
            blocks.block(BlockKind.TAG, tag)
            undoable("Hiding #${Blocklist.normalize(tag).replace('_', ' ')}", BlockKind.TAG, tag)
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

    /** Whether the open tag page (or creator page, "@name") is followed. */
    fun isFollowingPage(page: String, f: Follows): Boolean =
        if (page.startsWith("@")) Blocklist.normalize(page.removePrefix("@")) in f.creators
        else Blocklist.normalize(page) in f.tags

    fun toggleFollowTag(tag: String) {
        if (tag.startsWith("@")) {
            val name = tag.removePrefix("@")
            viewModelScope.launch {
                if (Blocklist.normalize(name) in follows.value.creators) library.unfollow(FollowKind.CREATOR, name)
                else { library.follow(FollowKind.CREATOR, name); _messages.tryEmit("Following @$name") }
            }
            return
        }
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
        private const val INTRO_MIN_SEC = 300
        private const val INTRO_SKIP_MS = 25_000L
        private const val LOAD_AHEAD = 10
        const val SIMILAR_PREFIX = "~like:"
        private const val LIKED_TAGS_REFRESH_MS = 60_000L
        /** A longer video hands-free let play to the end: watched, but not chosen like a manual full watch. */
        private const val HANDS_FREE_FULL_VIEW = 0.5
        private const val PRELOAD_AHEAD = 6
        private const val RESOLVE_AHEAD = 5
        private const val CHAIN_DELAY_MS = 300L
        private const val RETRY_MIN_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val RESTART_DELAY_MS = 5_000L
        private const val GC_BEHIND = 50
    }
}

/** Volume-key presses from the activity, for the feed's mute state. */
object VolumeKeys {
    val up = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** "Volume keys turn pages" (Settings → Playback): set from settings at startup. */
    @Volatile var turnPages = false

    /**
     * The feed on screen right now, when it takes volume keys: called with +1 (down = next post)
     * or −1 (up = the one before). Null when no feed is showing.
     */
    @Volatile var turner: ((Int) -> Unit)? = null
}
