package dev.pfeed.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pfeed.domain.model.MediaItem
import dev.pfeed.domain.reco.TasteEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the [TasteEngine] for the whole app: loaded from the phone on first use, fed every post
 * that passes through a feed and every reaction, and written back a little after it changes (in the
 * app's private storage, next to the encrypted database; it holds tag statistics and directions,
 * not posts or history).
 */
@Singleton
class TasteEngineStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val engine = TasteEngine()
    private val lock = Mutex()
    private var loaded = false
    private var saveJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file get() = File(context.filesDir, "taste_engine.bin")

    /** Runs [block] with the engine, loaded, one caller at a time. */
    suspend fun <T> use(block: (TasteEngine) -> T): T = lock.withLock {
        if (!loaded) load()
        block(engine)
    }

    fun observe(items: Collection<MediaItem>) {
        if (items.isEmpty()) return
        scope.launch { use { it.observe(items) }; scheduleSave() }
    }

    suspend fun learn(item: MediaItem, target: Double) {
        use { it.learn(item, target) }
        scheduleSave()
    }

    suspend fun reset() {
        use { it.resetTaste() }
        scheduleSave()
    }

    private suspend fun load() {
        loaded = true
        withContext(Dispatchers.IO) {
            runCatching {
                if (file.exists()) DataInputStream(file.inputStream().buffered()).use { engine.readFrom(it) }
            }.onFailure { file.delete() }
        }
    }

    /** Write now (the app is going to the background): nothing learned is lost if Android ends it. */
    fun flush() {
        if (!loaded || !dirty) return
        saveJob?.cancel()
        saveJob = scope.launch { save() }
    }

    @Volatile private var dirty = false

    private fun scheduleSave() {
        dirty = true
        if (saveJob?.isActive == true) return
        // The map is a couple of megabytes: written at most once a minute while you watch, and
        // when you leave the app, rather than every few seconds.
        saveJob = scope.launch {
            delay(SAVE_AFTER_MS)
            save()
        }
    }

    private suspend fun save() {
        run {
            dirty = false
            val bytes = use { e -> java.io.ByteArrayOutputStream().also { b -> DataOutputStream(b).use { e.writeTo(it) } }.toByteArray() }
            withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(file.path + ".tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(file)
                }
            }
        }
    }

    private companion object {
        const val SAVE_AFTER_MS = 60_000L
    }
}
