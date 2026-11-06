package dev.pfeed.data.repo

import dev.pfeed.data.db.LibraryDao
import dev.pfeed.di.ApplicationScope
import dev.pfeed.source.SeenFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every item the user has actually swiped onto, across app launches. Backed by watch history (so
 * clearing history in Settings also makes those items eligible again) plus an in-memory set for
 * the current run, so a just-seen item is excluded immediately, before the DB write lands.
 */
@Singleton
class SeenItems @Inject constructor(
    libraryDao: LibraryDao,
    @ApplicationScope scope: CoroutineScope,
) : SeenFilter {
    @Volatile private var persisted: Set<String> = emptySet()
    private val recent: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        scope.launch {
            libraryDao.keys(LibraryKind.HISTORY.name).collect { keys ->
                val next = keys.toHashSet()
                // History shrank → it was cleared or purged; forget the in-memory marks too.
                if (next.size < persisted.size) recent.clear() else recent.removeAll(next)
                persisted = next
            }
        }
    }

    override fun isSeen(key: String): Boolean = key in recent || key in persisted

    fun mark(key: String) {
        recent += key
    }
}
