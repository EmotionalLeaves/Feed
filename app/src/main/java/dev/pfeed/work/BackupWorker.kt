package dev.pfeed.work

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pfeed.data.repo.SettingsExporter
import dev.pfeed.security.SecretStore
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Automatic backups: once a week, the same encrypted backup as Settings → Export, written to the
 * folder you picked, named by date. The last [KEEP] are kept; older ones are removed. The password
 * is kept on the phone, wrapped by the Android Keystore.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val exporter: SettingsExporter,
    private val secrets: SecretStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tree = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse) ?: return Result.success()
        val password = secrets.secret(SECRET) ?: return Result.success()
        val resolver = applicationContext.contentResolver
        return try {
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = "feed-backup-${LocalDate.now()}.json"
            val file = DocumentsContract.createDocument(resolver, dir, "application/json", name) ?: return Result.retry()
            resolver.openOutputStream(file, "wt")?.use { exporter.exportTo(it, password.toCharArray()) } ?: return Result.retry()
            prune(tree)
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).remove(KEY_ERROR).apply()
            Result.success()
        } catch (e: Exception) {
            prefs.edit().putString(KEY_ERROR, e.message?.take(80) ?: "Couldn't write the backup").apply()
            Result.retry()
        }
    }

    /** Keeps the newest few backups in the folder (only files this writes: feed-backup-*.json). */
    private fun prune(tree: Uri) {
        val resolver = applicationContext.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val ours = mutableListOf<Pair<String, String>>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (BACKUP_NAME.matches(name)) ours += c.getString(0) to name
            }
        }
        ours.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    companion object {
        private const val NAME = "auto-backup"
        const val PREFS = "auto_backup"
        const val KEY_FOLDER = "folder"
        const val KEY_LAST = "last"
        const val KEY_ERROR = "error"
        const val SECRET = "backup_password"
        private const val KEEP = 4
        private val BACKUP_NAME = Regex("""feed-backup-\d{4}-\d{2}-\d{2}( \(\d+\))?\.json""")

        /** Turns weekly backups on (and makes one now), or off when [on] is false. */
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build())
            wm.enqueueUniqueWork("$NAME-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BackupWorker>().build())
        }
    }
}
