package dev.pfeed

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Keeps the last crash's stack trace (app version, Android version, the trace; nothing about what was
 * on screen) so it can be copied from Settings → Help and reported.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                file(app).writeText(
                    "Feed ${BuildConfig.VERSION_NAME} on Android ${Build.VERSION.RELEASE} (${Build.MODEL})\n" +
                        "Thread: ${thread.name}\n\n" + e.stackTraceToString().take(20_000),
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) { file(context).delete() }
}
