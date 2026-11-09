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
            // Load the history keys once, then only watch the row count. Watching the keys
            // themselves re-read the whole history (often tens of thousands of rows) on every swipe,
            // because every swipe adds a history row. New views are already in [recent].
            persisted = libraryDao.keysOnce(LibraryKind.HISTORY.name).toHashSet()
            var lastCount = persisted.size
            libraryDao.count(LibraryKind.HISTORY.name).collect { n ->
                if (n < lastCount) {
                    // History shrank → it was cleared or purged; forget the in-memory marks too.
                    persisted = libraryDao.keysOnce(LibraryKind.HISTORY.name).toHashSet()
                    recent.clear()
                }
                lastCount = n
            }
        }
    }

    override fun isSeen(key: String): Boolean = key in recent || key in persisted

    fun mark(key: String) {
        recent += key
    }
}
