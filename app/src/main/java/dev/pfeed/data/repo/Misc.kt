package dev.pfeed.data.repo

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pfeed.domain.model.MediaItem
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Hands a fixed list (search results, a library tab) to the viewer screen without serialising it into a route. */
@Singleton
class TransientFeedHolder @Inject constructor() {
    private val lists = LinkedHashMap<String, Pair<List<MediaItem>, Int>>()
    private val continuations = HashMap<String, String>()
    private val titles = HashMap<String, String>()

    /**
     * [continueWith]: a search to keep going with once the list runs out (search results play on
     * as an endless feed of the same query instead of stopping at the last result).
     */
    @Synchronized
    fun put(items: List<MediaItem>, start: Int, continueWith: String? = null, title: String? = null): String {
        val token = UUID.randomUUID().toString()
        lists[token] = items to start
        if (continueWith != null) continuations[token] = continueWith
        if (title != null) titles[token] = title
        while (lists.size > 8) lists.remove(lists.keys.first()).also {
            continuations.keys.retainAll(lists.keys)
            titles.keys.retainAll(lists.keys)
        }
        return token
    }

    /** What the list is ("Saved", a search), shown at the top of the viewer. */
    @Synchronized
    fun title(token: String): String? = titles[token]

    @Synchronized
    fun get(token: String): Pair<List<MediaItem>, Int>? = lists[token]

    @Synchronized
    fun continuation(token: String): String? = continuations[token]
}

@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    val isMetered: Boolean get() = cm?.isActiveNetworkMetered ?: false
}
