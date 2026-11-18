package dev.pfeed.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pfeed.BuildConfig
import dev.pfeed.data.repo.NetworkMonitor
import dev.pfeed.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Updates straight from the project's GitHub releases: each version that passes the emulator tests
 * is published there with its APK. This checks releases/latest now and then, downloads a newer APK
 * (on Wi-Fi, unless you start it yourself) and installs it with Android's package installer.
 *
 * Android asks you to confirm an app update the first time. Once this app has installed one itself
 * (Android 12 and up), later ones can go in without a prompt; those are installed when you leave
 * the app, so it never closes under you.
 */
@Singleton
class Updater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
) {
    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val at: Long) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Ready(val release: Release, val file: File) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private var work: Job? = null
    private val dir get() = File(context.cacheDir, "updates").apply { mkdirs() }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    init {
        instance = this
        // A download from last time that's still newer than this version: ready to go.
        val v = prefs.getString(KEY_READY_VERSION, null)
        val f = File(dir, APK_NAME)
        if (v != null && f.exists() && isNewer(v, currentVersion)) {
            _state.value = State.Ready(Release(v, prefs.getString(KEY_READY_NOTES, "").orEmpty(), "", f.length(), ""), f)
        } else {
            f.delete()
            prefs.edit().remove(KEY_READY_VERSION).apply()
        }
    }

    /** The automatic part: check every few hours, download on Wi-Fi. Called as the app opens. */
    fun auto() {
        if (work?.isActive == true) return
        work = scope.launch {
            if (!settings.flow.first().autoUpdate) return@launch
            val last = prefs.getLong(KEY_LAST_CHECK, 0L)
            if (_state.value !is State.Ready && System.currentTimeMillis() - last > CHECK_EVERY_MS) checkNow()
            val s = _state.value
            if (s is State.Available && !network.isMetered) download(s.release)
        }
    }

    /** "Check now" in Settings: also downloads straight away if there's something newer. */
    fun checkAndDownload() {
        if (work?.isActive == true) return
        work = scope.launch {
            checkNow()
            (_state.value as? State.Available)?.let { download(it.release) }
        }
    }

    fun downloadNow() {
        val s = _state.value
        val r = (s as? State.Available)?.release ?: (s as? State.Failed)?.release ?: return
        if (work?.isActive == true) return
        work = scope.launch { download(r) }
    }

    private suspend fun checkNow() {
        _state.value = State.Checking
        val result = try {
            fetchLatest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed("Couldn't reach GitHub")
            return
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        _state.value = when {
            result == null -> State.UpToDate(System.currentTimeMillis())
            isNewer(result.version, currentVersion) -> State.Available(result)
            else -> State.UpToDate(System.currentTimeMillis())
        }
    }

    @Serializable
    private data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        @SerialName("html_url") val page: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    private fun fetchLatest(): Release? {
        val req = Request.Builder().url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code == 404) return null // no release yet
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            val r = json.decodeFromString(GhRelease.serializer(), res.body!!.string())
            if (r.draft || r.prerelease) return null
            val apk = r.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
            return Release(r.tag.removePrefix("v"), r.body.orEmpty().trim(), apk.url, apk.size, r.page)
        }
    }

    private suspend fun download(r: Release) = withContext(Dispatchers.IO) {
        val part = File(dir, "$APK_NAME.part")
        val out = File(dir, APK_NAME)
        try {
            _state.value = State.Downloading(r, 0f)
            http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                val body = res.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: r.size
                var done = 0L
                var shown = 0f
                body.byteStream().use { input ->
                    part.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                            if (p - shown >= 0.01f) { shown = p; _state.value = State.Downloading(r, p) }
                        }
                    }
                }
            }
            // Only a real APK for this app, signed like this one, gets offered.
            if (!looksRight(part)) throw IllegalStateException("The download isn't this app")
            out.delete()
            part.renameTo(out)
            prefs.edit().putString(KEY_READY_VERSION, r.version).putString(KEY_READY_NOTES, r.notes).apply()
            _state.value = State.Ready(r, out)
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            _state.value = State.Failed(e.message?.take(80) ?: "Download failed", r)
        }
    }

    @Suppress("DEPRECATION")
    private fun looksRight(apk: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (theirs.packageName != context.packageName) return false
        val mine = pm.getPackageInfo(context.packageName, flags)
        fun sigs(p: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners?.map { it.toCharsString() }.orEmpty().toSet() else p.signatures.orEmpty().map { it.toCharsString() }.toSet()
        return sigs(theirs).isNotEmpty() && sigs(theirs) == sigs(mine)
    }

    /** Android lets this app update itself without asking once it was the one that installed it. */
    fun canInstallQuietly(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        if (prefs.getBoolean(KEY_QUIET_REFUSED, false)) return false
        return try {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName == context.packageName
        } catch (e: Exception) {
            false
        }
    }

    /** Whether Android will let this app install at all (Settings → Install unknown apps). */
    fun allowedToInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Hands the downloaded APK to Android's installer. [quiet]: only if it can go in without a
     * prompt (used when you leave the app); otherwise Android shows its confirmation.
     */
    fun install(quiet: Boolean = false) {
        val s = _state.value as? State.Ready ?: return
        if (quiet && !canInstallQuietly()) return
        scope.launch {
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, s.file.length()).use { o ->
                        s.file.inputStream().use { it.copyTo(o) }
                        session.fsync(o)
                    }
                    val intent = Intent(context, InstallResultReceiver::class.java).putExtra(EXTRA_QUIET, quiet)
                    val pi = PendingIntent.getBroadcast(
                        context, id, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                    )
                    _state.value = State.Installing(s.release)
                    session.commit(pi.intentSender)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message?.take(80) ?: "Couldn't install", s.release)
            }
        }
    }

    internal fun onInstallResult(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val quiet = intent.getBooleanExtra(EXTRA_QUIET, false)
        val ready = File(dir, APK_NAME)
        val release = (_state.value as? State.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (quiet) {
                    // Android wants a tap after all: don't pop it up out of nowhere; offer it in the app.
                    prefs.edit().putBoolean(KEY_QUIET_REFUSED, true).apply()
                    if (release != null) _state.value = State.Ready(release, ready)
                    return
                }
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Usually this process is replaced before this runs; tidy up if not.
                ready.delete()
                prefs.edit().remove(KEY_READY_VERSION).apply()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                if (release != null) {
                    _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) State.Ready(release, ready)
                    else State.Failed(msg?.take(80) ?: "Couldn't install", release)
                }
            }
        }
    }

    fun forgetDownload() {
        File(dir, APK_NAME).delete()
        prefs.edit().remove(KEY_READY_VERSION).apply()
        _state.value = State.Idle
    }

    companion object {
        @Volatile internal var instance: Updater? = null

        const val REPO = "EmotionalLeaves/Feed"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases"
        private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val APK_NAME = "update.apk"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        private const val KEY_LAST_CHECK = "lastCheck"
        private const val KEY_READY_VERSION = "readyVersion"
        private const val KEY_READY_NOTES = "readyNotes"
        private const val KEY_QUIET_REFUSED = "quietRefused"
        private const val EXTRA_QUIET = "dev.pfeed.update.QUIET"

        /** True when [a] is a later version than [b] ("4.81.0" > "4.80.2"). */
        fun isNewer(a: String, b: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val x = parts(a); val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}

/** Where Android reports how an install went (and asks for a confirmation tap). */
@dagger.hilt.android.AndroidEntryPoint
class InstallResultReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: Updater

    override fun onReceive(context: Context, intent: Intent) {
        updater.onInstallResult(context, intent)
    }
}
