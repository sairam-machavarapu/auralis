package com.auralis.feature.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.auralis.core.database.AuralisDatabase
import com.auralis.core.database.DownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.max

object DownloadStatus {
    const val Queued = "queued"; const val Downloading = "downloading"; const val Paused = "paused"
    const val Completed = "completed"; const val Failed = "failed"; const val Canceled = "canceled"
}

internal object DownloadRules {
    fun canPause(status: String) = status == DownloadStatus.Queued || status == DownloadStatus.Downloading
    fun canResume(status: String) = status in setOf(DownloadStatus.Paused, DownloadStatus.Failed, DownloadStatus.Canceled)
    fun canCancel(status: String) = status !in setOf(DownloadStatus.Completed, DownloadStatus.Canceled)
    fun retryDelaySeconds(attempt: Int) = (10L shl attempt.coerceIn(0, 5)).coerceAtMost(300L)
    fun hasSpace(available: Long, required: Long, reserve: Long) = available >= max(required, reserve)
    fun transferComplete(actual: Long, expected: Long?) = actual > 0 && (expected == null || actual == expected)
}

class AuralisDownloadManager(context: Context) {
    private val app = context.applicationContext
    private val db = AuralisDatabase.get(app)
    private val work = WorkManager.getInstance(app)
    val jobs = db.downloads().observeAll()

    suspend fun enqueue(title: String, sourceUrl: String, extensionId: String,
        mimeType: String = "audio/mpeg", wifiOnly: Boolean = false): Long {
        val uri = URI(sourceUrl)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Downloads require a secure HTTPS URL." }
        AuthorizedProviderAdapter(app).verify(extensionId, sourceUrl)
        val id = db.downloads().insert(DownloadEntity(extensionId = extensionId, sourceId = sourceUrl,
            title = title.take(160), targetUri = null, status = DownloadStatus.Queued, createdAt = System.currentTimeMillis(),
            mimeType = mimeType.take(100), wifiOnly = wifiOnly))
        schedule(id, wifiOnly)
        return id
    }
    suspend fun pause(id: Long) { val item = db.downloads().get(id) ?: return; if (!DownloadRules.canPause(item.status)) return
        db.downloads().setStatus(id, DownloadStatus.Paused); work.cancelUniqueWork(name(id)) }
    suspend fun resume(id: Long) { val item = db.downloads().get(id) ?: return; if (!DownloadRules.canResume(item.status)) return
        db.downloads().setStatus(id, DownloadStatus.Queued); schedule(id, item.wifiOnly) }
    suspend fun cancel(id: Long) { val item = db.downloads().get(id) ?: return; if (!DownloadRules.canCancel(item.status)) return
        db.downloads().setStatus(id, DownloadStatus.Canceled); work.cancelUniqueWork(name(id)) }
    suspend fun remove(id: Long) { val item = db.downloads().get(id) ?: return; work.cancelUniqueWork(name(id)); item.tempPath?.let { File(it).delete() }; db.downloads().delete(id) }
    private fun schedule(id: Long, wifiOnly: Boolean) {
        val constraints = Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("job_id" to id))
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("download:$id").build()
        work.enqueueUniqueWork(name(id), ExistingWorkPolicy.REPLACE, request)
    }
    private fun name(id: Long) = "auralis-download-$id"
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("job_id", -1).takeIf { it > 0 } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { AuralisDownloadManager(context).pause(id) } finally { pending.finish() }
        }
    }
}

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val db = AuralisDatabase.get(context)
    private val jobId = inputData.getLong("job_id", -1)
    override suspend fun getForegroundInfo() = foreground(db.downloads().get(jobId))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val job = db.downloads().get(jobId) ?: return@withContext Result.failure()
        if (job.status == DownloadStatus.Canceled || job.status == DownloadStatus.Paused) return@withContext Result.success()
        val tempDir = File(applicationContext.filesDir, "download-parts").apply { mkdirs() }
        val temp = job.tempPath?.let(::File) ?: File(tempDir, "$jobId.part")
        try {
            setForeground(foreground(job))
            val outcome = transfer(job, temp)
            if (!outcome) return@withContext Result.success()
            val current = db.downloads().get(jobId) ?: return@withContext Result.failure()
            if (current.status != DownloadStatus.Downloading) return@withContext Result.success()
            val imported = AuthorizedImporter(applicationContext, db).import(current, temp)
            db.downloads().update(current.copy(targetUri = imported.uri.toString(), status = DownloadStatus.Completed,
                progress = 1f, bytesDone = temp.length(), bytesTotal = current.bytesTotal ?: temp.length(),
                completedAt = System.currentTimeMillis(), tempPath = null, error = null))
            temp.delete()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (low: LowStorage) { db.downloads().setStatus(jobId, DownloadStatus.Failed, "Not enough storage space. Free some space and resume."); Result.failure() }
        catch (failure: ImportFailure) {
            temp.delete()
            val latest = db.downloads().get(jobId)
            if (latest != null) db.downloads().update(latest.copy(status = DownloadStatus.Failed, progress = 0f,
                bytesDone = 0, bytesTotal = null, tempPath = null, error = failure.message))
            Result.failure()
        }
        catch (error: Exception) {
            val latest = db.downloads().get(jobId)
            if (latest?.status == DownloadStatus.Paused || latest?.status == DownloadStatus.Canceled) Result.success()
            else if (runAttemptCount < MAX_RETRIES) { db.downloads().setStatus(jobId, DownloadStatus.Queued, "Connection interrupted. Retrying…"); Result.retry() }
            else { db.downloads().setStatus(jobId, DownloadStatus.Failed, friendly(error)); Result.failure() }
        }
    }

    private suspend fun transfer(job: DownloadEntity, temp: File): Boolean {
        var existing = temp.takeIf(File::exists)?.length() ?: 0L
        val connection = URL(job.sourceId).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 10_000; connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Auralis/1")
            if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            if (existing > 0 && code != 206) { temp.delete(); existing = 0 }
            val responseLength = connection.contentLengthLong.takeIf { it >= 0 }
            val total = responseLength?.plus(existing)
            ensureSpace(total?.minus(existing) ?: MIN_FREE_BYTES)
            db.downloads().progress(jobId, DownloadStatus.Downloading,
                if (total != null && total > 0) existing.toFloat() / total else 0f, existing, total, temp.absolutePath)
            connection.inputStream.use { input -> FileOutputStream(temp, existing > 0).buffered().use { output ->
                val buffer = ByteArray(32 * 1024); var done = existing; var lastUpdate = 0L
                while (true) {
                    val state = db.downloads().get(jobId)?.status
                    if (state == DownloadStatus.Paused || state == DownloadStatus.Canceled) return false
                    val read = input.read(buffer); if (read < 0) break
                    output.write(buffer, 0, read); done += read
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 250) {
                        ensureSpace(MIN_FREE_BYTES)
                        val fraction = if (total != null && total > 0) done.toFloat() / total else 0f
                        db.downloads().progress(jobId, DownloadStatus.Downloading, fraction.coerceIn(0f, .99f), done, total, temp.absolutePath)
                        setProgress(workDataOf("bytes_done" to done, "bytes_total" to (total ?: -1L)))
                        if (now - lastUpdate >= 1_500) setForeground(foreground(db.downloads().get(jobId)))
                        lastUpdate = now
                    }
                }
            } }
            if (!DownloadRules.transferComplete(temp.length(), total)) error("Download ended before all bytes arrived")
            db.downloads().progress(jobId, DownloadStatus.Downloading, .99f, temp.length(), total ?: temp.length(), temp.absolutePath)
            return true
        } finally { connection.disconnect() }
    }

    private fun ensureSpace(required: Long) {
        if (!DownloadRules.hasSpace(StatFs(applicationContext.filesDir.absolutePath).availableBytes, required, MIN_FREE_BYTES)) throw LowStorage()
    }
    private fun foreground(job: DownloadEntity?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Music downloads", NotificationManager.IMPORTANCE_LOW))
        val total = job?.bytesTotal ?: 0; val done = job?.bytesDone ?: 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job?.title ?: "Downloading music").setContentText(bytes(done, total)).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(1000, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            .addAction(android.R.drawable.ic_media_pause, "Pause", PendingIntent.getBroadcast(applicationContext, jobId.toInt(),
                Intent(applicationContext, DownloadActionReceiver::class.java).putExtra("job_id", jobId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo((5000 + jobId % 1000).toInt(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo((5000 + jobId % 1000).toInt(), notification)
    }
    private fun friendly(error: Exception) = when (error) {
        is java.net.UnknownHostException -> "The server could not be found."
        is java.net.SocketTimeoutException -> "The connection timed out. Resume to try again."
        else -> "Download stopped. Resume to try again."
    }
    private fun bytes(done: Long, total: Long) = if (total > 0) "${formatBytes(done)} of ${formatBytes(total)}" else formatBytes(done)
    private class LowStorage : Exception()
    companion object { const val CHANNEL = "music-downloads"; const val MAX_RETRIES = 4; const val MIN_FREE_BYTES = 64L * 1024 * 1024 }
}

internal fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
    value >= 1024 -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}
