package dev.pfeed.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pfeed.data.db.BlockEntity
import dev.pfeed.data.repo.BlockKind
import dev.pfeed.data.repo.BlockRepository
import dev.pfeed.data.repo.BooruKeys
import dev.pfeed.data.repo.SettingsExporter
import dev.pfeed.data.repo.SignalRepository
import dev.pfeed.data.repo.WrongPasswordException
import dev.pfeed.data.settings.AppSettings
import dev.pfeed.data.settings.BooruFeed
import dev.pfeed.data.settings.Disguise
import dev.pfeed.data.settings.JsonFeed
import dev.pfeed.data.settings.MastodonFeed
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.domain.model.AuthContext
import dev.pfeed.domain.model.AuthResult
import dev.pfeed.domain.reco.RankWeights
import dev.pfeed.player.MediaCache
import dev.pfeed.security.AppLockManager
import dev.pfeed.security.LauncherDisguise
import dev.pfeed.source.RegisteredSource
import dev.pfeed.source.SourceRegistry
import dev.pfeed.source.discovery.DiscoveredSource
import dev.pfeed.source.discovery.SourceDiscovery
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val query: String, val items: List<DiscoveredSource>) : SearchState
    data class Error(val message: String) : SearchState
}

/** Shared by all settings screens (each gets its own instance; state lives in the repositories). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val settingsRepo: SettingsRepository,
    private val registry: SourceRegistry,
    private val blocks: BlockRepository,
    private val signals: SignalRepository,
    private val exporter: SettingsExporter,
    private val mediaCache: MediaCache,
    private val lock: AppLockManager,
    private val discovery: SourceDiscovery,
    private val booruKeys: BooruKeys,
    private val feedHealth: dev.pfeed.data.repo.FeedHealthStore,
    private val checker: dev.pfeed.source.SourceChecker,
    private val mediaHealth: dev.pfeed.data.repo.MediaHealth,
    private val tube: dev.pfeed.source.tube.TubeAdapter,
) : ViewModel() {

    /** Per-site result of checking the tube sites, by site label; null while checking. */
    private val _tubeReport = MutableStateFlow<Map<String, String?>>(emptyMap())
    val tubeReport: StateFlow<Map<String, String?>> = _tubeReport

    fun checkTubeSites() {
        _tubeReport.value = dev.pfeed.source.tube.TubeSite.ALL.associate { it.label to null }
        viewModelScope.launch { _tubeReport.value = tube.siteReport().toMap() }
    }

    /**
     * Sites of [sourceId] being left alone right now: not answering requests (down, rate-limiting),
     * or answering but with files that won't load. They're retried automatically after ~10 minutes.
     */
    fun restingSites(sourceId: String): List<String> {
        val adapter = sources.value.firstOrNull { it.adapter.id == sourceId }?.adapter
        val notAnswering = (adapter as? dev.pfeed.source.net.CachingAdapter)?.restingGroups().orEmpty()
        val filesDown = mediaHealth.resting().filter { it == sourceId || it.startsWith("$sourceId:") || (sourceId == "reddit" && it.startsWith("r/")) }
            .map { it.substringAfter(':') }
        return (notAnswering + filesDown).distinct()
    }

    /** Results of "Check sources", by source id; null value = being checked right now. */
    private val _checks = MutableStateFlow<Map<String, dev.pfeed.source.SourceChecker.Result?>>(emptyMap())
    val checks: StateFlow<Map<String, dev.pfeed.source.SourceChecker.Result?>> = _checks
    private var checkJob: kotlinx.coroutines.Job? = null

    /** Tests every enabled source from this phone, a few at a time, filling [checks] as they finish. */
    fun checkSources() {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            val enabled = sources.value.filter { it.enabled }.map { it.adapter }
            _checks.value = enabled.associate { it.id to null }
            val gate = kotlinx.coroutines.sync.Semaphore(3)
            kotlinx.coroutines.coroutineScope {
                enabled.forEach { a ->
                    launch {
                        gate.acquire()
                        try {
                            val r = checker.check(a)
                            _checks.update { it + (a.id to r) }
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
        }
    }

    /** Feeds of [sourceId] currently skipped because they kept answering "not found" or empty. */
    fun skippedFeeds(sourceId: String): Int = feedHealth.deadCount(sourceId)

    /** Give every skipped feed another try now instead of after a week. */
    fun retrySkippedFeeds() {
        feedHealth.clear()
        _messages.tryEmit("Skipped feeds will be tried again")
    }

    /** Boards (rule34.xxx, gelbooru.com) that have an API key saved. */
    val booruKeyHosts: StateFlow<Set<String>> = booruKeys.configured

    init {
        viewModelScope.launch { booruKeys.refresh() }
    }

    /** [text] is what the board's account page shows, e.g. `&api_key=…&user_id=…`. */
    fun saveBooruKey(host: String, text: String): Boolean {
        val key = BooruKeys.parse(text)
        if (key == null) {
            _messages.tryEmit("Paste the whole line from $host, e.g. &api_key=…&user_id=…")
            return false
        }
        viewModelScope.launch {
            booruKeys.set(host, key) // the board's feeds join the stream on the next load
            _messages.tryEmit("$host unlocked")
        }
        return true
    }

    fun clearBooruKey(host: String) = viewModelScope.launch {
        booruKeys.clear(host)
        _messages.tryEmit("Removed key for $host")
    }

    val settings: StateFlow<AppSettings> = settingsRepo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val sources: StateFlow<List<RegisteredSource>> = registry.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val blockEntries: StateFlow<List<BlockEntity>> = blocks.entries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _authStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val authStatus: StateFlow<Map<String, String>> = _authStatus

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    // ---- source discovery / search ----
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    fun searchSources(query: String) {
        if (query.isBlank()) { _searchState.value = SearchState.Idle; return }
        viewModelScope.launch {
            _searchState.value = SearchState.Loading
            runCatching { discovery.search(query) }
                .onSuccess { results ->
                    _searchState.value = if (results.isEmpty())
                        SearchState.Error("No sources found for \"$query\". Try a different term.")
                    else
                        SearchState.Results(query, results)
                }
                .onFailure { e ->
                    _searchState.value = SearchState.Error(e.message ?: "Search failed")
                }
        }
    }

    fun clearSearch() { _searchState.value = SearchState.Idle }

    fun addDiscovered(sources: List<DiscoveredSource>) = viewModelScope.launch {
        val count = discovery.addAll(sources)
        _messages.tryEmit(if (count > 0) "Added $count source${if (count == 1) "" else "s"}" else "Already added")
        _searchState.value = SearchState.Idle
    }

    private fun update(f: (AppSettings) -> AppSettings) = viewModelScope.launch { settingsRepo.update(f) }

    // ---- playback / privacy ----
    fun setCacheSize(mb: Int) {
        mediaCache.configuredSizeMb = mb
        update { it.copy(cacheSizeMb = mb) }
    }
    fun setAutoplayOnMobile(v: Boolean) = update { it.copy(autoplayOnMobileData = v) }
    fun setDefaultMute(v: Boolean) = update { it.copy(defaultMute = v) }
    fun setHistoryRetention(days: Int) = update { it.copy(historyRetentionDays = days) }
    fun setLockTimeout(sec: Int) = update { it.copy(lockTimeoutSec = sec) }

    fun setDisguise(d: Disguise) {
        LauncherDisguise.apply(app, d)
        update { it.copy(disguise = d) }
        _messages.tryEmit("Launcher updated. Some launchers take a few seconds to refresh.")
    }

    val lockEnabled: StateFlow<Boolean> = lock.enabled

    fun enableLock(pin: String, confirm: String) = viewModelScope.launch {
        when {
            pin.length < 4 -> _messages.tryEmit("PIN must be at least 4 digits")
            pin != confirm -> _messages.tryEmit("PINs don't match")
            else -> {
                lock.createPin(pin.toCharArray())
                _messages.tryEmit("App lock on")
            }
        }
    }

    fun disableLock(current: String) = viewModelScope.launch {
        _messages.tryEmit(if (lock.disable(current.toCharArray())) "App lock off" else "Wrong PIN")
    }

    fun changePin(current: String, new: String) = viewModelScope.launch {
        if (new.length < 4) { _messages.tryEmit("New PIN must be at least 4 digits"); return@launch }
        val ok = lock.changePin(current.toCharArray(), new.toCharArray())
        _messages.tryEmit(if (ok) "PIN changed" else "Current PIN is wrong")
    }

    // ---- recommendations ----
    fun setWeights(w: RankWeights) = update { it.copy(weights = w) }
    fun setHalfLife(days: Double) = update { it.copy(halfLifeDays = days) }
    fun resetWeights() = update { it.copy(weights = RankWeights(), halfLifeDays = 14.0) }
    private val _interests = MutableStateFlow<Pair<List<Pair<String, Double>>, List<Pair<String, Double>>>?>(null)
    /** (likes, dislikes), strongest first; null until loaded. */
    val interests: StateFlow<Pair<List<Pair<String, Double>>, List<Pair<String, Double>>>?> = _interests

    fun loadInterests() = viewModelScope.launch { _interests.value = signals.topInterests() }

    fun resetRecommendations() = viewModelScope.launch {
        signals.reset()
        _interests.value = signals.topInterests()
        _messages.tryEmit("For You will start learning again")
    }

    // ---- sources ----
    fun setSourceEnabled(id: String, enabled: Boolean) = viewModelScope.launch { registry.setEnabled(id, enabled) }
    fun moveSource(id: String, delta: Int) = viewModelScope.launch { registry.move(id, delta) }

    fun authenticate(activity: Activity, id: String) {
        val adapter = registry.byId(id) ?: return
        _authStatus.update { it + (id to "Signing in…") }
        viewModelScope.launch {
            val status = when (val r = adapter.authenticate(AuthContext(activity))) {
                AuthResult.Success -> "Signed in"
                AuthResult.NotRequired -> "No sign-in needed"
                is AuthResult.Failure -> r.message
            }
            _authStatus.update { it + (id to status) }
        }
    }

    fun addFeed(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false.also { _messages.tryEmit("That isn't a valid http(s) URL") }
        update { s -> if (u.toString() in s.rssFeeds) s else s.copy(rssFeeds = s.rssFeeds + u.toString()) }
        return true
    }
    fun removeFeed(url: String) = update { it.copy(rssFeeds = it.rssFeeds - url) }

    fun addDirect(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false.also { _messages.tryEmit("That isn't a valid http(s) URL") }
        update { s -> if (u.toString() in s.directUrls) s else s.copy(directUrls = s.directUrls + u.toString()) }
        return true
    }
    fun removeDirect(url: String) = update { it.copy(directUrls = it.directUrls - url) }

    fun addReddit(name: String): Boolean {
        val sub = name.trim().removePrefix("/").removePrefix("r/").trim('/')
        if (sub.isBlank() || sub.contains(' ')) { _messages.tryEmit("Enter a single subreddit name"); return false }
        update { s -> if (sub in s.redditSubs) s else s.copy(redditSubs = s.redditSubs + sub) }
        return true
    }
    fun removeReddit(sub: String) = update { it.copy(redditSubs = it.redditSubs - sub) }

    fun addMastodon(host: String, tag: String): Boolean {
        val h = host.trim()
        if (h.isBlank() || h.contains(' ')) { _messages.tryEmit("Enter a server, e.g. mastodon.social"); return false }
        val feed = MastodonFeed(h, tag.trim().removePrefix("#").ifBlank { null })
        update { s -> if (feed in s.mastodonFeeds) s else s.copy(mastodonFeeds = s.mastodonFeeds + feed) }
        return true
    }
    fun removeMastodon(feed: MastodonFeed) = update { it.copy(mastodonFeeds = it.mastodonFeeds - feed) }

    fun addBooru(host: String, tags: String): Boolean {
        val h = host.trim()
        if (h.isBlank() || h.contains(' ')) { _messages.tryEmit("Enter a board, e.g. rule34.xxx"); return false }
        val feed = BooruFeed(h, tags.trim().ifBlank { null })
        update { s -> if (feed in s.booruFeeds) s else s.copy(booruFeeds = s.booruFeeds + feed) }
        return true
    }
    fun removeBooru(feed: BooruFeed) = update { it.copy(booruFeeds = it.booruFeeds - feed) }

    fun addJson(feed: JsonFeed): Boolean {
        if (feed.name.isBlank() || feed.urlTemplate.toHttpUrlOrNull() == null || feed.itemsPath.isBlank()) {
            _messages.tryEmit("Fill in a name, a valid URL and where the items are")
            return false
        }
        update { s -> s.copy(jsonFeeds = s.jsonFeeds.filterNot { it.name == feed.name } + feed) }
        return true
    }
    fun removeJson(feed: JsonFeed) = update { it.copy(jsonFeeds = it.jsonFeeds - feed) }

    fun addFolder(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { _messages.tryEmit("Couldn't keep access to that folder"); return }
        update { s -> if (uri.toString() in s.localFolders) s else s.copy(localFolders = s.localFolders + uri.toString()) }
    }
    fun removeFolder(uri: String) {
        runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        update { it.copy(localFolders = it.localFolders - uri) }
    }

    // ---- blocklist ----
    fun block(kind: BlockKind, value: String) = viewModelScope.launch { blocks.block(kind, value) }
    fun unblock(row: BlockEntity) = viewModelScope.launch { blocks.unblock(row.kind, row.value) }
    fun clearNotInterested() = viewModelScope.launch { blocks.clearNotInterested() }

    // ---- export / import ----
    fun exportSettings(uri: Uri, password: String) = viewModelScope.launch {
        runCatching {
            app.contentResolver.openOutputStream(uri, "wt")?.use { exporter.exportTo(it, password.toCharArray()) }
                ?: error("Couldn't open file")
        }.onSuccess { _messages.tryEmit("Settings exported") }
            .onFailure { _messages.tryEmit("Export failed: ${it.message}") }
    }

    fun importSettings(uri: Uri, password: String) = viewModelScope.launch {
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { exporter.importFrom(it, password.toCharArray()) }
                ?: error("Couldn't open file")
        }.onSuccess {
            LauncherDisguise.apply(app, settingsRepo.get().disguise)
            _messages.tryEmit("Settings imported")
        }.onFailure { e ->
            _messages.tryEmit(if (e is WrongPasswordException) "Wrong password" else "Import failed: ${e.message}")
        }
    }
}
