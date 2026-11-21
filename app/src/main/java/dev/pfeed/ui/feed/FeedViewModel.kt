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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val app: android.content.Context,
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
    private val clipLibrary: dev.pfeed.data.repo.ClipLibrary,
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
        if (shownPosts.add(post)) {
            mediaHealth.shown(item)
            if (shownPosts.size == THIS_OR_THAT_AFTER && isMainFeed) offerThisOrThat()
        }
    }

    /** Your tastes as chips over For You (tap For You again to see them): tap one to play it. */
    val tasteChips = MutableStateFlow<List<dev.pfeed.domain.reco.TasteEngine.View>>(emptyList())

    fun loadTasteChips() {
        viewModelScope.launch {
            val aff = runCatching { signals.affinities().tags }.getOrNull() ?: return@launch
            tasteChips.value = runCatching { signals.engine.use { it.describe(aff) } }.getOrDefault(emptyList())
                .filter { !it.sour && it.share >= 0.03 }.take(8)
        }
    }

    /** Play one taste (a mix), or with null, back to all of them. */
    fun playTaste(view: dev.pfeed.domain.reco.TasteEngine.View?) {
        if (view == null) dev.pfeed.data.repo.SteerState.clear()
        else dev.pfeed.data.repo.SteerState.set(view.tags.take(3).map { dev.pfeed.domain.reco.Steer.Directive(dev.pfeed.domain.reco.Steer.Kind.MORE, it) })
    }

    /** People you follow who are live on cam right now (by name), for the Following row. */
    val liveFollowed = MutableStateFlow<Map<String, MediaItem>>(emptyMap())

    init {
        if (isMainFeed) viewModelScope.launch {
            delay(3_000) // after construction (it reads properties declared further down)
            var rounds = 0
            while (true) {
                // In Following every couple of minutes (the row shows who's on); in For You, on Wi-Fi,
                // every ten, to say when someone you follow has just come on.
                val following = _mode.value == FeedMode.FOLLOWING
                val forYouCheck = _mode.value == FeedMode.FOR_YOU && !network.isMetered && rounds % LIVE_FOR_YOU_EVERY == 0
                if (following || forYouCheck) runCatching {
                    val names = follows.value.creators.map { it.lowercase() }.toSet()
                    val live = registry.byId(dev.pfeed.source.live.LiveCamsAdapter.SOURCE_ID)
                    if (names.isNotEmpty() && live != null && registry.enabledAdapters().any { it.id == live.id }) {
                        val rooms = live.fetchFeed(null, dev.pfeed.domain.model.FeedPrefs(FeedMode.LIVE)).items
                        val before = liveFollowed.value.keys
                        liveFollowed.value = rooms.filter { it.creator?.lowercase() in names }.associateBy { it.creator!!.lowercase() }
                        // Just came on (not on at the last look, and not said already this session).
                        val fresh = liveFollowed.value.filterKeys { it !in before && it !in announcedLive }
                        if (forYouCheck && checkedLiveOnce) fresh.values.firstOrNull()?.let { room ->
                            announcedLive += room.creator!!.lowercase()
                            offer("${room.creator} is live now", "Watch") { openViewerRequest.value = openLive(room) }
                        }
                        checkedLiveOnce = true
                    }
                }
                rounds++
                delay(LIVE_FOLLOW_CHECK_MS)
            }
        }
    }

    private val announcedLive = HashSet<String>()
    private var checkedLiveOnce = false

    /** A viewer to open (its token), asked for from an offer: the screen navigates there. */
    val openViewerRequest = MutableStateFlow<String?>(null)
    /** A tag or creator page ("@name") a page asked to open (e.g. "More with …" at a scene's end). */
    val openPageRequest = MutableStateFlow<String?>(null)

    /** Opens a live room you follow as its own viewer. */
    fun openLive(item: MediaItem): String = holder.put(listOf(item), 0, title = "Live")

    /** Ask the screen to open This or that. */
    val thisOrThatRequest = MutableStateFlow(false)

    /** New here: once, after the first few dozen posts, suggest This or that (it teaches For You fastest). */
    private fun offerThisOrThat() {
        val prefs = app.getSharedPreferences("hints", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("this_or_that", false)) return
        viewModelScope.launch {
            val reacted = runCatching { signals.engine.use { it.reactions() } }.getOrDefault(Int.MAX_VALUE)
            if (reacted > NEW_USER_REACTIONS || _mode.value != FeedMode.FOR_YOU) return@launch
            prefs.edit().putBoolean("this_or_that", true).apply()
            offer("Teach For You faster: ten quick picks between two posts", "This or that") { thisOrThatRequest.value = true }
        }
    }

    private val shownPosts = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    val liked: StateFlow<Set<String>> = library.keys(LibraryKind.LIKED).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val saved: StateFlow<Set<String>> = library.keys(LibraryKind.SAVED).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val follows: StateFlow<Follows> = library.follows
        // People you follow are recognised in any title that names them (see Mentions.inTitle).
        .onEach { f -> dev.pfeed.domain.reco.Mentions.learn(f.creators) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Follows())
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
        viewModelScope.launch {
            val st = settingsRepo.get()
            _muted.value = st.defaultMute || (st.headphonesOnly && !HeadphonesOnly.connected(app))
        }
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
        // Leaving Following: everything up to now counts as seen there.
        if (_mode.value == FeedMode.FOLLOWING) markFollowingSeen()
        if (mode == FeedMode.FOLLOWING) {
            followingSeenAt = followingPrefs.getLong(FOLLOWING_SEEN, 0L); caughtUpSaid = false
            dev.pfeed.data.repo.FollowingNews.opened(app)
        }
        _mode.value = mode
        restart()
    }

    private val followingPrefs by lazy { app.getSharedPreferences("following", android.content.Context.MODE_PRIVATE) }
    /** When you last went through Following (posts older than this you'd seen come out). */
    private var followingSeenAt = 0L
    private var caughtUpSaid = false

    private fun markFollowingSeen() {
        if (settings.value.privateMode) return
        followingPrefs.edit().putLong(FOLLOWING_SEEN, System.currentTimeMillis()).apply()
    }

    /** In Following: reaching the first post from before your last visit says you're caught up, once. */
    private fun noticeCaughtUp(list: List<MediaItem>, index: Int) {
        if (!isMainFeed || _mode.value != FeedMode.FOLLOWING || caughtUpSaid || followingSeenAt == 0L || index == 0) return
        val here = list.getOrNull(index)?.publishedAt?.toEpochMilli() ?: return
        val before = list.getOrNull(index - 1)?.publishedAt?.toEpochMilli() ?: return
        if (here < followingSeenAt && before >= followingSeenAt) {
            caughtUpSaid = true
            offer("You're all caught up: from here on, posts you've had the chance to see", "OK") {}
        }
    }

    fun refresh() = restart()

    /**
     * Opening the app after leaving a long video partway: the last thing watched, where it stopped,
     * and a route to carry on (once per launch; null if there's nothing to carry on).
     */
    suspend fun carryOn(): Triple<MediaItem, Long, String>? {
        if (carryOnOffered) return null
        carryOnOffered = true
        val last = library.items(dev.pfeed.data.repo.LibraryKind.HISTORY).first().firstOrNull() ?: return null
        val at = dev.pfeed.player.ResumePoints.get(last.key)?.takeIf { it >= CARRY_ON_MIN_MS } ?: return null
        return Triple(last, at, holder.put(listOf(last), 0, title = "Carrying on"))
    }

    /** For You's mood: how much it sticks to what you like vs. tries new things. */
    enum class Mood(val label: String, val note: String, val exploration: Double, val novelty: Double) {
        FAMILIAR("Familiar", "More of what you already like", 0.03, 0.1),
        BALANCED("Balanced", "Mostly your taste, now and then something new", 0.10, 0.2),
        ADVENTUROUS("Adventurous", "Lots of things you haven't seen", 0.30, 0.45),
    }

    val mood: StateFlow<Mood> = settingsRepo.flow.map { s ->
        Mood.entries.minByOrNull { kotlin.math.abs(it.exploration - s.weights.exploration) } ?: Mood.BALANCED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Mood.BALANCED)

    /** What the main feeds show, as one quick choice (the Settings chips, in short). */
    enum class Showing(val label: String, val hidden: Set<String>) {
        EVERYTHING("Everything", emptySet()),
        VIDEOS("Videos only", setOf("image", "gif", "comic")),
        PICTURES("Pictures & GIFs", setOf("video", "comic")),
        COMICS("Comics only", setOf("video", "image", "gif")),
    }

    val showing: StateFlow<Showing?> = settingsRepo.flow.map { s -> Showing.entries.firstOrNull { it.hidden == s.hiddenFeedTypes } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Showing.EVERYTHING)

    fun setShowing(w: Showing) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(hiddenFeedTypes = w.hidden) }
            restart()
        }
    }

    fun setMood(m: Mood) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(weights = it.weights.copy(exploration = m.exploration, novelty = m.novelty)) }
            restart()
            offer("For You: ${m.label.lowercase()}. ${m.note}", "OK") {}
        }
    }

    /**
     * This or that: pairs of upcoming posts that are as unlike each other as possible (far apart on
     * your map), so each choice teaches For You something.
     */
    suspend fun thisOrThatPairs(n: Int = 10): List<Pair<MediaItem, MediaItem>> {
        val ahead = items.value.drop(lastSettled + 1).filter { it.thumbnailUrl != null }.take(n * 4)
        if (ahead.size < 2) return emptyList()
        val vecs = runCatching { signals.engine.use { e -> ahead.map { e.vectorOf(it) } } }.getOrNull() ?: List(ahead.size) { null }
        val left = ahead.indices.toMutableList()
        val out = ArrayList<Pair<MediaItem, MediaItem>>()
        while (left.size >= 2 && out.size < n) {
            val a = left.removeAt(0)
            val b = left.minBy { j -> val va = vecs[a]; val vb = vecs[j]; if (va != null && vb != null) dev.pfeed.domain.reco.TasteEngine.cos(va, vb) else 0.5 }
            left.remove(b)
            out += ahead[a] to ahead[b]
        }
        return out
    }

    /** A this-or-that answer: [chosen] over [other], or neither (both null-ish: pass [chosen] = null). */
    fun preferred(chosen: MediaItem?, other: List<MediaItem>) {
        viewModelScope.launch {
            runCatching {
                if (chosen != null) signals.record(chosen, Signal(SignalType.REPLAY, 1.0))
                other.forEach { signals.record(it, if (chosen != null) Signal(SignalType.FAST_SKIP) else Signal(SignalType.WATCH, 0.0)) }
            }
        }
    }

    /** Tags that mean nearly the same as [tag] (see [dev.pfeed.domain.reco.TasteEngine.neighbours]). */
    suspend fun neighbours(tag: String): List<String> =
        runCatching { signals.engine.use { it.neighbours(tag) } }.getOrDefault(emptyList())

    /** Creators who make the same kind of thing as [name] (see [dev.pfeed.domain.reco.TasteEngine.similarCreators]). */
    suspend fun similarCreators(name: String, items: List<MediaItem>): List<String> {
        val found = runCatching { signals.engine.use { it.similarCreators(name, items.take(40)) } }.getOrDefault(emptyList())
        // Never suggest someone you've blocked.
        val probe = items.firstOrNull() ?: return found
        val blocklist = blocks.current()
        return found.filter { c -> blocklist.allows(probe.copy(creator = c, tags = emptyList())) }
    }

    /** What For You is being told in words right now (see [dev.pfeed.domain.reco.Steer]). */
    val steering: StateFlow<List<dev.pfeed.domain.reco.Steer.Directive>> = dev.pfeed.data.repo.SteerState.active

    /** "more redheads, less cosplay": read it, steer For You by it for this sitting, start fresh. */
    fun steer(text: String) {
        viewModelScope.launch {
            val known = HashMap<String, Int>()
            runCatching { signals.engine.use { it.space.counted(2) } }.getOrNull()?.let(known::putAll)
            runCatching { signals.affinities().tags }.getOrNull()?.keys?.forEach { known.merge(it, 50, Int::plus) }
            val list = dev.pfeed.domain.reco.Steer.parse(text, dev.pfeed.domain.reco.Steer.resolver(known))
            if (list.isEmpty()) { offer("Didn't catch that. Try \"more redheads, less cosplay\".", "OK") {}; return@launch }
            dev.pfeed.data.repo.SteerState.set(list)
            offer("For You: " + list.joinToString("  ") { it.label } + ", for the next few hours", "Undo") { clearSteer() }
        }
    }

    fun clearSteer() {
        if (dev.pfeed.data.repo.SteerState.active.value.isEmpty()) return
        dev.pfeed.data.repo.SteerState.clear()
    }

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
        if (isMainFeed && _mode.value == FeedMode.FOLLOWING) markFollowingSeen()
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
            // Its own site has lost it: the same video is usually up on another one.
            val stream = resolved ?: findElsewhere(item)
            if (stream == null) markBroken(item, "couldn't find its file")
            stream
        }
    }

    private val elsewhere = HashMap<String, Deferred<StreamInfo?>>()

    /**
     * The same video on another site (same title, same length to the second), for when this copy
     * won't load or play. Null if there's none, or for anything that isn't a tube video.
     */
    suspend fun findElsewhere(item: MediaItem): StreamInfo? {
        if (item.type != dev.pfeed.domain.model.MediaType.VIDEO || item.sourceId != dev.pfeed.source.tube.TubeAdapter.SOURCE_ID) return null
        val query = dev.pfeed.domain.reco.SameVideo.query(item) ?: return null
        return elsewhere.getOrPut(item.key) {
            viewModelScope.async {
                val tube = registry.byId(dev.pfeed.source.tube.TubeAdapter.SOURCE_ID) ?: return@async null
                val found = kotlinx.coroutines.withTimeoutOrNull(ELSEWHERE_TIMEOUT_MS) {
                    runCatching { tube.search(query, null).items }.getOrNull()
                }.orEmpty()
                val host = item.id.substringBefore('/')
                for (copy in dev.pfeed.domain.reco.SameVideo.copies(item, found.filter { it.id.substringBefore('/') != host }).take(3)) {
                    val s = runCatching { tube.resolveStream(copy) }.getOrNull()
                    if (s is StreamInfo.Direct) {
                        _messages.tryEmit("Playing a copy from ${Display.source(copy, copy.sourceId)}")
                        return@async s
                    }
                }
                null
            }
        }.await()
    }

    private val copiesFound = HashMap<String, Deferred<List<MediaItem>>>()
    private val offeredLonger = HashSet<String>()
    private val offeredSharper = HashSet<String>()

    /** The same title searched on every tube site, once per video (for a longer cut or a sharper copy). */
    private suspend fun otherCopies(item: MediaItem): List<MediaItem> {
        val query = dev.pfeed.domain.reco.SameVideo.query(item) ?: return emptyList()
        return copiesFound.getOrPut(item.key) {
            viewModelScope.async {
                val tube = registry.byId(dev.pfeed.source.tube.TubeAdapter.SOURCE_ID) ?: return@async emptyList()
                kotlinx.coroutines.withTimeoutOrNull(LONGER_TIMEOUT_MS) {
                    runCatching { tube.search(query, null).items }.getOrNull()
                }.orEmpty()
            }
        }.await()
    }

    /** A longer cut of [item] on another tube site (see SameVideo.longer), looked up once per video. */
    suspend fun longerVersion(item: MediaItem): MediaItem? {
        if (!canHaveLonger(item)) return null
        return dev.pfeed.domain.reco.SameVideo.longer(item, otherCopies(item))
    }

    /**
     * A tube video playing below 720p ([heightNow], from the player): if another site has the same
     * upload sharper, offer it, carrying on from where you are. Not with Data saver on.
     */
    fun offerSharper(item: MediaItem, heightNow: Int, positionMs: Long, durationMs: Long) {
        if (item.type != dev.pfeed.domain.model.MediaType.VIDEO || item.sourceId != dev.pfeed.source.tube.TubeAdapter.SOURCE_ID) return
        if (heightNow <= 0 || heightNow >= SHARPER_BELOW || dev.pfeed.source.tube.TubeSite.maxHeight < SHARPER_BELOW) return
        if ((item.durationSec ?: 0) < SHARPER_MIN_SEC || item.id.startsWith("clip/") || !offeredSharper.add(item.key)) return
        viewModelScope.launch {
            val s = dev.pfeed.domain.reco.SameVideo.sharper(item, otherCopies(item), heightNow) ?: return@launch
            val h = s.extras[dev.pfeed.source.tube.HEIGHT]?.toIntOrNull() ?: return@launch
            offer("Sharper copy: ${if (h >= 2160) "4K" else "${h}p"} on ${Display.source(s, s.sourceId)}", "Watch") {
                val at = NowPlaying.positionMs.takeIf { NowPlaying.key == item.key } ?: positionMs
                dev.pfeed.player.ResumePoints.save(s.key, at, durationMs)
                openViewerRequest.value = holder.put(listOf(s), 0, title = "Sharper copy")
            }
        }
    }

    /** A tube video short enough to be a cut of a longer scene. */
    fun canHaveLonger(item: MediaItem): Boolean =
        item.type == dev.pfeed.domain.model.MediaType.VIDEO && item.sourceId == dev.pfeed.source.tube.TubeAdapter.SOURCE_ID &&
            (item.durationSec ?: 0) in LONGER_MIN_SEC..LONGER_MAX_SEC && !item.id.startsWith("clip/")

    /**
     * Partway into a short cut, or when asked from the menu ([manual]): if the full scene is on
     * another site, say so, with a way to watch it there.
     */
    fun offerLonger(item: MediaItem, manual: Boolean = false) {
        if (!canHaveLonger(item)) { if (manual) _messages.tryEmit("This one isn't a cut of something longer"); return }
        if (!manual && !offeredLonger.add(item.key)) return
        if (manual) _messages.tryEmit("Looking on the other sites…")
        viewModelScope.launch {
            val l = longerVersion(item)
            if (l == null) { if (manual) _messages.tryEmit("No longer copy found on the other sites"); return@launch }
            val mins = ((l.durationSec ?: 0) + 30) / 60
            offer("Longer version: $mins min on ${Display.source(l, l.sourceId)}", "Watch") {
                openViewerRequest.value = holder.put(listOf(l), 0, title = "Longer version")
            }
        }
    }

    /**
     * Where a long video starts when you've not watched it before: past the studio intro, if
     * "Skip intros" is on (videos of 5 minutes and more; 0 otherwise).
     */
    /**
     * In a long video whose site marks its acts ("Missionary · 11:19"), the one you'd like most, by
     * what your taste makes of each act's name; null unless one clearly stands out from the rest.
     */
    suspend fun favouriteAct(item: MediaItem): Pair<String, Long>? {
        val acts = dev.pfeed.domain.reco.VideoExtras.of(item.key)?.chapters.orEmpty()
        if (acts.map { it.first.lowercase() }.distinct().size < 2) return null
        // What For You makes of the act's name, plus how that act has actually gone for you.
        val scored = runCatching {
            signals.engine.use { e ->
                acts.map { a ->
                    val taste = e.tagVector(a.first)?.let { v -> e.interestScore(v) - e.aversion(v) } ?: 0.0
                    a to taste + ACT_HISTORY_WEIGHT * kotlin.math.tanh(dev.pfeed.player.ActPrefs.score(a.first) / 3.0)
                }
            }
        }.getOrNull().orEmpty()
        return dev.pfeed.domain.reco.VideoExtras.favourite(scored)
    }

    /** What you made of each act of a video you're leaving: a small lesson per act (not in private mode). */
    fun actsWatched(item: MediaItem, tally: dev.pfeed.domain.reco.ActWatch.Tally, durationMs: Long) {
        if (settings.value.privateMode) return
        val v = tally.verdict(durationMs)
        if (v.liked.isEmpty() && v.skipped.isEmpty()) return
        dev.pfeed.player.ActPrefs.record(v.liked, v.skipped)
        viewModelScope.launch { runCatching { signals.recordActs(v.liked, v.skipped) } }
    }

    /**
     * On a tag page or a search for an act ("cowgirl"), a long video that marks that act starts right
     * at it: the search lands on the moment, not the start of the video.
     */
    fun tagMomentMs(item: MediaItem): Pair<String, Long>? {
        val t = tag?.takeIf { !it.startsWith("@") }?.let(dev.pfeed.domain.reco.Features::normalizeTag) ?: return null
        val acts = dev.pfeed.domain.reco.VideoExtras.of(item.key)?.chapters.orEmpty()
        return acts.firstOrNull { dev.pfeed.domain.reco.Features.normalizeTag(it.first) == t && it.second > 0 }
    }

    /** Where Highlights starts a long video: your favourite act, else the most-rewatched part, else a good way in. */
    suspend fun highlightStartMs(item: MediaItem): Long? {
        val d = item.durationSec ?: return null
        if (d < Highlights.MIN_SEC) return null
        favouriteAct(item)?.let { return it.second }
        dev.pfeed.domain.reco.BestPart.of(item.key)?.let { return it }
        // Where its commenters point.
        dev.pfeed.domain.reco.VideoExtras.of(item.key)?.comments?.let { dev.pfeed.domain.reco.CommentMoments.of(it, d) }?.firstOrNull()?.let { return it }
        // No better clue: the scene that starts nearest a good way in, if the scenes are known.
        val fallback = Highlights.fallbackMs(d)
        return dev.pfeed.domain.reco.Scenes.of(item.key)?.filter { it > 0 }?.minByOrNull { kotlin.math.abs(it - fallback) } ?: fallback
    }

    fun introSkipMs(item: MediaItem): Long {
        // Straight to the best part, when asked and the site keeps count.
        if (settings.value.startAtBest) dev.pfeed.domain.reco.BestPart.of(item.key)?.let { return it }
        val d = item.durationSec ?: return 0L
        if (!settings.value.skipIntros || d < INTRO_MIN_SEC) return 0L
        return minOf(INTRO_SKIP_MS, d * 1000L / 20)
    }

    val breakReminderMin: Int get() = settings.value.breakReminderMin

    fun autoplayAllowed(): Boolean = settings.value.autoplayOnMobileData || !network.isMetered

    fun toggleMute() {
        if (_muted.value && !soundAllowedHere()) {
            offer("Sound is set to play only through headphones", "Settings") { HeadphonesOnly.openSettings.tryEmit(Unit) }
            return
        }
        _muted.value = !_muted.value
    }

    /** "Sound only with headphones": false while it's on and nothing but the speaker is connected. */
    private fun soundAllowedHere(): Boolean = !settings.value.headphonesOnly || HeadphonesOnly.connected(app)

    init {
        viewModelScope.launch { VolumeKeys.up.collect { if (_muted.value && soundAllowedHere()) _muted.value = false } }
        // Asked to open a particular feed (Discover's "Live now" → See all).
        if (isMainFeed) viewModelScope.launch {
            OpenFeedMode.request.collect { m -> if (m != null) { OpenFeedMode.request.value = null; setMode(m) } }
        }
        // Steering changed (here, or from a blend in Settings): For You starts over with it.
        if (isMainFeed) viewModelScope.launch {
            dev.pfeed.data.repo.SteerState.active.drop(1).collect { list ->
                // Steering is For You's: a mix or blend started elsewhere brings you there.
                if (list.isNotEmpty() && _mode.value != FeedMode.FOR_YOU) setMode(FeedMode.FOR_YOU)
                else if (_mode.value == FeedMode.FOR_YOU) restart()
            }
        }
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
        noticeCaughtUp(list, index)
        preloadAround(list, index)
    }

    fun onVisibilityChanged(isVisible: Boolean) {
        if (isVisible == visible) return
        visible = isVisible
        if (isVisible) enteredAt = SystemClock.elapsedRealtime() else finishCurrent()
        if (!isVisible && isMainFeed && _mode.value == FeedMode.FOLLOWING && caughtUpSaid) markFollowingSeen()
    }

    /** For You posts skipped at a glance, one after another (cleared by anything you stay on). */
    private val skipRun = ArrayList<MediaItem>()

    /**
     * Skip after skip after skip: what's queued isn't landing. Shake it up: the next batch steers
     * away from what was just skipped and explores further, and the rest of the queue (bar the next
     * one, already loading) makes way for it.
     */
    private fun noteSkipRun(item: MediaItem, dwell: Long, deliberate: Boolean) {
        if (!isMainFeed || _mode.value != FeedMode.FOR_YOU || deliberate) { skipRun.clear(); return }
        if (dwell >= SKIP_RUN_DWELL_MS) { skipRun.clear(); return }
        skipRun += item
        if (skipRun.size < SKIP_RUN_LENGTH) return
        val s = session ?: return
        s.shakeUp(skipRun.toList())
        skipRun.clear()
        val here = raw.value.indexOfFirst { it.key == currentItem?.key }
        if (here >= 0 && raw.value.size > here + 2) raw.update { it.take(here + 2) }
        endReached = false
        loadMore()
    }

    private fun finishCurrent() {
        val item = currentItem ?: return
        if (enteredAt == 0L) return
        val dwell = SystemClock.elapsedRealtime() - enteredAt
        enteredAt = 0L
        // It never worked, so the time on it says nothing about taste: don't learn from it.
        if (item.key in broken.value) return
        val st = stats[item.key]
        noteSkipRun(item, dwell, deliberate = st?.explicit == true || st?.autoAdvanced == true)
        // Watched (most of) something on Watch later: it's done its job.
        val length = st?.durationMs ?: 0L
        if (item.key in watchLater.value && length > 0 && dwell >= length * WATCHED_SHARE) {
            viewModelScope.launch { runCatching { library.removeFromCollection(WATCH_LATER, item) } }
        }
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
            noticeNewTastes()
        }
    }

    private var lastNotice = 0L

    /** A taste For You has just become sure of: say so, with a way to turn it down. */
    private suspend fun noticeNewTastes() {
        if (!isMainFeed || _mode.value != FeedMode.FOR_YOU) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotice < NOTICE_GAP_MS) return
        val aff = runCatching { signals.affinities().tags }.getOrNull() ?: return
        val found = runCatching { signals.engine.use { it.snapshot(aff); it.confirmations(aff) } }.getOrNull()?.firstOrNull() ?: return
        lastNotice = now
        val name = found.tags.take(2).joinToString(" · ") { "#" + it.replace('_', ' ') }
        offer("For You noticed a new taste: $name", "Not really") {
            viewModelScope.launch { signals.engine.use { it.quieten(found.index) } }
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
                    // On Wi-Fi the next three get their opening seconds (more of each); on mobile data, two.
                    type == MediaType.VIDEO -> if (autoplay && i < (if (network.isMetered) 2 else 3))
                        preloader.video(item.key, s.url, if (network.isMetered) 2_500_000L else 4_000_000L)
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

    /**
     * Love it (hold the heart): a like, saved into the "Favourites" collection, and taught to For You
     * as strongly as replaying it twice.
     */
    fun love(item: MediaItem) {
        like(item)
        statsFor(item.key).explicit = true
        viewModelScope.launch {
            runCatching {
                library.add(LibraryKind.SAVED, item)
                library.addToCollection(FAVOURITES, item)
                signals.record(item, Signal(SignalType.REPLAY, 2.0))
            }
            offer("Loved: saved to $FAVOURITES, and you'll see more like it", "OK") {}
        }
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
                // A clip or frame the app made lives only in Saved: unsaved, its file goes too.
                ownFile(item)?.takeIf { it.parentFile?.name in setOf("clips", "frames") }?.delete()
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
    /** A clip or frame the app made: its file on the phone. */
    private fun ownFile(item: MediaItem): java.io.File? =
        item.extras["media"]?.takeIf { it.startsWith("file:") }?.let { android.net.Uri.parse(it).path }?.let { java.io.File(it) }?.takeIf { it.exists() }

    fun canExport(item: MediaItem): Boolean = android.os.Build.VERSION.SDK_INT >= 29 && !item.isGallery &&
        (ownFile(item) != null || offline.localFileCached(item.key))

    fun exportToDownloads(item: MediaItem) {
        viewModelScope.launch {
            val own = ownFile(item)
            val ok = if (own != null) offline.exportFile(own, item.title) else offline.exportToDownloads(item.key, item.title)
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
        // The people in it, and one each of those they're most often with: more of the same faces.
        val people = dev.pfeed.domain.reco.Mentions.inTitle(item).take(2)
        val costars = people.mapNotNull { p -> dev.pfeed.domain.reco.CoStars.with(p, max = 3).firstOrNull { c -> people.none { it.equals(c, true) } } }
        val queries = (listOfNotNull(creator) + people + tags + words + costars).distinctBy { it.lowercase() }.ifEmpty { listOfNotNull(item.title?.take(40)) }
        return FeedSpec.Similar(item.key, queries, features.toSet(), item.creator, item.extras[dev.pfeed.domain.reco.Visual.EXTRA])
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

    /**
     * Keeps a still from a video: written as a JPEG in the app's own storage (encrypted along with
     * the rest of the app's data by the phone), saved, and put in the "Frames" collection.
     */
    /**
     * Where [item]'s scenes start, for "Next scene": the acts its site names if it names them,
     * else the cuts found in its preview frames ([dev.pfeed.domain.reco.Scenes]); empty if neither yet.
     */
    fun sceneCuts(item: MediaItem): List<Long> {
        val acts = dev.pfeed.domain.reco.VideoExtras.of(item.key)?.chapters.orEmpty().map { it.second }.filter { it > 0 }.distinct().sorted()
        return if (acts.size >= 2) acts else dev.pfeed.domain.reco.Scenes.of(item.key).orEmpty()
    }

    /**
     * Scrubbed back to [atMs]: a rewatch (a strong signal for For You), and if it's the second time
     * back to the same bit, that bit is marked as a moment (with an undo).
     */
    fun scrubbedBack(item: MediaItem, atMs: Long) {
        statsFor(item.key).replays++
        if (settings.value.privateMode || !settings.value.autoMoments || dev.pfeed.domain.privacy.Incognito.active) return
        val bit = dev.pfeed.domain.reco.Rewinds.note(item.key, atMs) ?: return
        if (dev.pfeed.player.Moments.of(item.key).any { kotlin.math.abs(it - bit) <= dev.pfeed.domain.reco.Rewinds.SAME_BIT_MS }) return
        dev.pfeed.player.Moments.add(item.key, bit)
        offer("Marked the bit you keep going back to · ${dev.pfeed.ui.common.formatDuration((bit / 1000).toInt())}", "Undo") {
            dev.pfeed.player.Moments.remove(item.key, bit)
        }
    }

    /**
     * "Save a clip": 30 s from [atMs] cut into a file on the phone, kept in Saved → Clips
     * as a video of its own (it plays offline and loops like any clip).
     */
    fun saveClip(item: MediaItem, stream: StreamInfo.Direct, atMs: Long, lengthMs: Long = dev.pfeed.data.repo.ClipLibrary.CLIP_MS) {
        offer("Cutting a clip from ${dev.pfeed.ui.common.formatDuration((atMs / 1000).toInt())}…", "OK") {}
        viewModelScope.launch {
            val clip = clipLibrary.save(item, stream, atMs, lengthMs)
            if (clip == null) {
                offer("Couldn't cut a clip from this one (${clipLibrary.lastError ?: "the site's file wouldn't allow it"})", "OK") {}
                return@launch
            }
            if (!settings.value.privateMode && item.key !in saved.value) runCatching { signals.record(item, Signal(SignalType.SAVE)) }
            offer("Clip saved to $CLIPS", "OK") {}
        }
    }

    /** The best place in [item] to clip, by what's known: your moment, your favourite act, the site's heat, the comments. */
    suspend fun bestClipStartMs(item: MediaItem): Long? =
        dev.pfeed.player.Moments.of(item.key).firstOrNull()
            ?: favouriteAct(item)?.second
            ?: dev.pfeed.domain.reco.BestPart.of(item.key)
            ?: dev.pfeed.domain.reco.VideoExtras.of(item.key)?.comments?.let { dev.pfeed.domain.reco.CommentMoments.of(it, item.durationSec) }?.firstOrNull()

    /** "Save this moment": marks [atMs] in the video and keeps it in Saved → Moments. */
    fun saveMoment(item: MediaItem, atMs: Long) {
        dev.pfeed.player.Moments.add(item.key, atMs)
        val wasSaved = item.key in saved.value
        viewModelScope.launch {
            runCatching { library.addToCollection(MOMENTS, item) }
            // Marking a moment is keeping the video: as strong a "yes" as saving it.
            if (!wasSaved && !settings.value.privateMode) {
                statsFor(item.key).explicit = true
                runCatching { signals.record(item, Signal(SignalType.SAVE)) }
            }
        }
        offer("Moment saved at ${dev.pfeed.ui.common.formatDuration((atMs / 1000).toInt())}", "Undo") { dev.pfeed.player.Moments.remove(item.key, atMs) }
    }

    /** Watch later: a list to come back to, not downloaded like Saved. */
    val watchLater: StateFlow<Set<String>> = library.collectionItems(WATCH_LATER)
        .map { list -> list.mapTo(HashSet()) { it.key } }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    fun toggleWatchLater(item: MediaItem) {
        viewModelScope.launch {
            if (item.key in watchLater.value) {
                library.removeFromCollection(WATCH_LATER, item)
                _messages.tryEmit("Off Watch later")
            } else {
                library.addToCollectionOnly(WATCH_LATER, item)
                // Meaning to come back to it is a "yes", if a quieter one than saving it.
                if (!settings.value.privateMode) runCatching { signals.record(item, Signal(SignalType.VIEW, WATCH_LATER_TARGET)) }
                statsFor(item.key).explicit = true
                _messages.tryEmit("On Watch later (in Saved, not downloaded)")
            }
        }
    }

    /** A clip's whole video, opened where the clip starts (found in what you've watched or saved). */
    fun openFullVideo(clip: MediaItem) {
        val key = clip.extras[dev.pfeed.data.repo.ClipLibrary.FROM] ?: return
        val at = clip.extras[dev.pfeed.data.repo.ClipLibrary.FROM_AT]?.toLongOrNull() ?: 0L
        viewModelScope.launch {
            val original = library.items(LibraryKind.HISTORY).first().firstOrNull { it.key == key }
                ?: library.items(LibraryKind.SAVED).first().firstOrNull { it.key == key }
            if (original == null) { _messages.tryEmit("The full video isn't in your history any more"); return@launch }
            original.durationSec?.let { dev.pfeed.player.ResumePoints.save(original.key, at, it * 1000L) }
            openViewerRequest.value = holder.put(listOf(original), 0, title = original.title ?: "Full video")
        }
    }

    /** What's queued after the post you're on (for "Coming up"). */
    fun upcoming(n: Int = COMING_UP): List<MediaItem> = items.value.drop(lastSettled + 1).take(n)

    /**
     * Passed over in "Coming up" before it was shown: out of the queue, and taken as a skip (a
     * small "not this", not a block).
     */
    fun skipAhead(item: MediaItem) {
        raw.update { list -> list.filter { it.key != item.key } }
        seen.mark(item.key)
        if (settings.value.privateMode) return
        viewModelScope.launch { runCatching { signals.record(item, Signal(SignalType.VIEW, dev.pfeed.domain.reco.Engagement.SKIP_TARGET * AHEAD_SKIP)) } }
    }

    /** "Later" in Coming up: out of the queue and onto Watch later (no skip learned). */
    fun laterFromQueue(item: MediaItem) {
        raw.update { list -> list.filter { it.key != item.key } }
        if (item.key !in watchLater.value) toggleWatchLater(item)
    }

    /** Jump to a post further down the queue (by key): the screen scrolls there. */
    val jumpRequest = MutableStateFlow<String?>(null)

    /** Settings → Up next card. */
    val upNextCardOn: Boolean get() = settings.value.upNextCard

    /** On mobile data: extras that cost data (finding scenes) wait until they're asked for. */
    val onMeteredNetwork: Boolean get() = network.isMetered

    /** Opened from Saved → Moments: each video starts at its first moment. */
    val fromMoments: Boolean get() = viewerTitle == MOMENTS

    fun saveFrame(context: android.content.Context, from: MediaItem, bitmap: android.graphics.Bitmap, atMs: Long) {
        viewModelScope.launch {
            val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dir = java.io.File(context.filesDir, "frames").apply { mkdirs() }
                java.io.File(dir, "frame-${System.currentTimeMillis()}.jpg").also { f ->
                    f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    bitmap.recycle()
                }
            }
            val uri = android.net.Uri.fromFile(file).toString()
            val frame = MediaItem(
                id = "frame/" + file.nameWithoutExtension,
                sourceId = "direct",
                type = dev.pfeed.domain.model.MediaType.IMAGE,
                title = (from.title?.let { "$it · " } ?: "") + "at " + dev.pfeed.ui.common.formatDuration((atMs / 1000).toInt()),
                creator = from.creator,
                tags = from.tags,
                durationSec = null,
                thumbnailUrl = uri,
                pageUrl = from.pageUrl,
                publishedAt = java.time.Instant.now(),
                extras = mapOf("media" to uri),
            )
            runCatching {
                library.add(dev.pfeed.data.repo.LibraryKind.SAVED, frame)
                library.addToCollection(FRAMES, frame)
            }
            offer("Frame saved to $FRAMES", "OK") {}
        }
    }

    /**
     * For the road: the next [count] posts of this feed are saved into the "For the road" collection
     * and downloaded, to watch with no connection (on a plane, underground). Pictures and comics
     * download straight away; videos wait for Wi-Fi.
     */
    fun takeForTheRoad(fromIndex: Int, count: Int = ROAD_COUNT) {
        viewModelScope.launch {
            val picks = items.value.drop(fromIndex).filter { it.type != dev.pfeed.domain.model.MediaType.EMBED }.take(count)
            picks.forEach { runCatching { library.add(dev.pfeed.data.repo.LibraryKind.SAVED, it); library.addToCollection(dev.pfeed.data.repo.OfflineStore.FOR_THE_ROAD, it) } }
            offer(
                "${picks.size} posts going into Saved → ${dev.pfeed.data.repo.OfflineStore.FOR_THE_ROAD}" + if (network.isMetered) "; videos download on Wi-Fi" else "; downloading now",
                "OK",
            ) {}
        }
    }

    /** What people said under a Reddit post (empty for anything else, or if Reddit won't say). */
    suspend fun comments(item: MediaItem): List<dev.pfeed.source.reddit.RedditAdapter.Comment> {
        // A tube video whose page carried its comments (read when it was opened).
        dev.pfeed.domain.reco.VideoExtras.of(item.key)?.comments?.takeIf { it.isNotEmpty() }?.let { list ->
            return list.map { (who, what) -> dev.pfeed.source.reddit.RedditAdapter.Comment(who, what, 0) }
        }
        if (item.sourceId != dev.pfeed.source.parse.RedditParser.SOURCE_ID) return emptyList()
        val reddit = registry.byId(item.sourceId) as? dev.pfeed.source.reddit.RedditAdapter ?: return emptyList()
        return try { reddit.comments(item) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
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
        // What the taste engine saw in it: the taste it serves, your mood, or a look past what you know.
        if (aff != null) runCatching { signals.engine.use { it.explain(item, aff.tags) } }.getOrNull()?.forEach { r ->
            out.add(minOf(out.size, if (r.strong) out.count { it.strong } else out.size), Why(r.text, r.tag, r.strong))
        }
        return out.distinctBy { it.text }.take(5)
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
                _messages.tryEmit(
                    tag.takeIf { it.startsWith(dev.pfeed.source.tube.TubeAdapter.SITE_PREFIX) }
                        ?.let { "Following " + (dev.pfeed.source.tube.TubeSite.byHost(it.removePrefix(dev.pfeed.source.tube.TubeAdapter.SITE_PREFIX))?.label ?: it) + ": its new videos join Following" }
                        ?: "Following #${Blocklist.normalize(tag).replace('_', ' ')}",
                )
            }
        }
    }

    fun reportUrl(item: MediaItem): String? = registry.byId(item.sourceId)?.reportUrl(item)

    companion object {
        /** Once per process: the main feed asks when it first appears. */
        @Volatile private var carryOnOffered = false
        private const val CARRY_ON_MIN_MS = 60_000L
        const val FRAMES = "Frames"
        const val MOMENTS = "Moments"
        const val CLIPS = dev.pfeed.data.repo.ClipLibrary.CLIPS
        const val WATCH_LATER = "Watch later"
        private const val WATCH_LATER_TARGET = 1.0
        /** Watched this much of it: off Watch later. */
        private const val WATCHED_SHARE = 0.8
        const val FAVOURITES = "Favourites"
        private const val ROAD_COUNT = 20
        private const val ELSEWHERE_TIMEOUT_MS = 10_000L
        private const val LONGER_TIMEOUT_MS = 15_000L
        private const val SHARPER_BELOW = 720
        private const val SHARPER_MIN_SEC = 2 * 60
        private const val LONGER_MIN_SEC = 4 * 60
        private const val LONGER_MAX_SEC = 25 * 60
        private const val NOTICE_GAP_MS = 5 * 60_000L
        private const val LIVE_FOLLOW_CHECK_MS = 2 * 60_000L
        /** In For You, look every this many rounds (ten minutes). */
        private const val LIVE_FOR_YOU_EVERY = 5
        private const val THIS_OR_THAT_AFTER = 25
        private const val NEW_USER_REACTIONS = 200
        private const val INTRO_MIN_SEC = 300
        private const val INTRO_SKIP_MS = 25_000L
        /** Shorter than this on a post is a skip at a glance; this many in a row shakes the feed up. */
        private const val SKIP_RUN_DWELL_MS = 1_500L
        private const val SKIP_RUN_LENGTH = 6
        private const val COMING_UP = 12
        private const val FOLLOWING_SEEN = "seen_at"
        /** Passing one over before it's shown says less than skipping it on screen. */
        private const val AHEAD_SKIP = 0.6
        /** How much your own history with an act counts toward "your favourite", at most. */
        private const val ACT_HISTORY_WEIGHT = 0.4
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
/** "Sound only with headphones": whether any headphones (wired, Bluetooth, USB) are connected. */
object HeadphonesOnly {
    val openSettings = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun connected(context: android.content.Context): Boolean {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            )
        }
    }
}

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

/** A request, from elsewhere in the app, for the main feed to switch to a mode. */
object OpenFeedMode {
    val request = MutableStateFlow<FeedMode?>(null)
}
