package dev.pfeed.data.repo

import android.content.Context
import dev.pfeed.domain.reco.Engagement
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

/**
 * How long you usually stay on an image and on a video, as a slow moving average (in log time, so
 * one very long view doesn't drag it). Used to judge each view relative to your own pace.
 */
@Singleton
class DwellBaseline @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("pfeed_dwell", Context.MODE_PRIVATE)

    fun typicalMs(video: Boolean): Double = exp(log(video))

    fun observe(video: Boolean, dwellMs: Long) {
        val next = Engagement.updateBaseline(log(video), dwellMs)
        prefs.edit().putFloat(key(video), next.toFloat()).apply()
    }

    fun reset() = prefs.edit().clear().apply()

    private fun log(video: Boolean): Double =
        prefs.getFloat(key(video), ln(if (video) Engagement.DEFAULT_VIDEO_MS else Engagement.DEFAULT_IMAGE_MS).toFloat()).toDouble()

    private fun key(video: Boolean) = if (video) "video" else "image"
}
