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

    @Synchronized
    fun put(items: List<MediaItem>, start: Int): String {
        val token = UUID.randomUUID().toString()
        lists[token] = items to start
        while (lists.size > 8) lists.remove(lists.keys.first())
        return token
    }

    @Synchronized
    fun get(token: String): Pair<List<MediaItem>, Int>? = lists[token]
}

@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    val isMetered: Boolean get() = cm?.isActiveNetworkMetered ?: false
}
