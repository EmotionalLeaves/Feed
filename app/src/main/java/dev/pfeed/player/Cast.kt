package dev.pfeed.player

import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import kotlinx.coroutines.launch

/**
 * Cast to a TV: plays the video's own file on a Chromecast or Google TV with Google's default
 * receiver. Discreet: no notification or lock-screen controls on the phone, and the TV is told
 * nothing but "Video". The TV fetches the file itself, so sites that only serve their files to
 * their own pages can't be cast.
 */
object Cast {
    private var pending: Triple<String, String, Long>? = null
    private var listening = false

    /** Asked for when the TV reaches the end of a video: the next one to play there (url, type), or null. */
    @Volatile var next: (suspend () -> Pair<String, String?>?)? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var watched: com.google.android.gms.cast.framework.media.RemoteMediaClient? = null
    @Volatile private var appContext: Context? = null

    /** Google Play services' cast framework, if this phone has it. */
    fun context(context: Context): CastContext? = runCatching { CastContext.getSharedInstance(context.applicationContext) }.getOrNull()

    /** Connected to a TV right now. */
    fun connected(context: Context): Boolean = context(context)?.sessionManager?.currentCastSession?.isConnected == true

    /**
     * Plays [url] ([mime]) on the TV from [startMs]: at once if one is connected, else once one is
     * picked from the list shown. Returns false if casting isn't possible on this phone.
     */
    fun play(activity: android.app.Activity, url: String, mime: String?, startMs: Long, onMessage: (String) -> Unit): Boolean {
        val cast = context(activity) ?: return false
        appContext = activity.applicationContext
        val type = mime ?: guessType(url)
        val session = cast.sessionManager.currentCastSession
        if (session?.isConnected == true) { load(session, url, type, startMs); onMessage("Playing on ${session.castDevice?.friendlyName ?: "the TV"}"); return true }
        pending = Triple(url, type, startMs)
        if (!listening) {
            listening = true
            cast.sessionManager.addSessionManagerListener(object : SessionManagerListener<CastSession> {
                override fun onSessionStarted(s: CastSession, id: String) { pending?.let { (u, t, at) -> load(s, u, t, at) }; pending = null }
                override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { pending?.let { (u, t, at) -> load(s, u, t, at) }; pending = null }
                override fun onSessionStarting(s: CastSession) {}
                override fun onSessionStartFailed(s: CastSession, error: Int) { pending = null }
                override fun onSessionEnding(s: CastSession) {}
                override fun onSessionEnded(s: CastSession, error: Int) {}
                override fun onSessionResuming(s: CastSession, id: String) {}
                override fun onSessionResumeFailed(s: CastSession, error: Int) {}
                override fun onSessionSuspended(s: CastSession, reason: Int) {}
            }, CastSession::class.java)
        }
        val selector = cast.mergedSelector ?: return false
        // The device list is an AppCompat dialog: give it that theme, whatever the app's own is.
        val themed = android.view.ContextThemeWrapper(activity, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
        androidx.mediarouter.app.MediaRouteChooserDialog(themed).apply { routeSelector = selector; show() }
        return true
    }

    /** Stop casting (and leave the TV). */
    fun stop(context: Context) { context(context)?.sessionManager?.endCurrentSession(true) }

    private fun load(session: CastSession, url: String, type: String, startMs: Long) {
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply { putString(MediaMetadata.KEY_TITLE, "Video") }
        val info = MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setContentType(type).setMetadata(meta).build()
        val client = session.remoteMediaClient ?: return
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(startMs.coerceAtLeast(0)).build())
        // When it ends on the TV, the next video of the feed follows there.
        if (watched !== client) {
            watched = client
            client.registerCallback(object : com.google.android.gms.cast.framework.media.RemoteMediaClient.Callback() {
                override fun onStatusUpdated() {
                    val st = client.mediaStatus ?: return
                    if (st.playerState != com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE || st.idleReason != com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED) return
                    val ask = next ?: return
                    scope.launch {
                        val n = runCatching { ask() }.getOrNull() ?: return@launch
                        val s = context(appContext ?: return@launch)?.sessionManager?.currentCastSession ?: return@launch
                        if (s.isConnected) load(s, n.first, n.second ?: guessType(n.first), 0)
                    }
                }
            })
        }
    }

    private fun guessType(url: String) = if (".m3u8" in url) "application/x-mpegURL" else "video/mp4"
}

/** Cast setup: Google's default receiver; no phone notification or media session (discretion). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build())
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
