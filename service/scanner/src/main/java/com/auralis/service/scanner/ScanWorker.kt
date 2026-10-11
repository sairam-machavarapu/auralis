package com.auralis.service.scanner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.auralis.core.database.AuralisDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val WORK_NAME = "auralis-library-scan"
private const val CHANNEL = "library-scans"
private const val NOTIFICATION = 4001

class ScanReports(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "latest-scan.json"))
    fun write(id: String, progress: ScanProgress) {
        val root = JSONObject().put("id", id).put("issues", JSONArray().apply {
            progress.issues.forEach { put(JSONObject().put("file", it.file).put("message", it.message)) }
        })
        val stream = file.startWrite()
        try { stream.write(root.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun read(id: String): List<ScanIssue> = runCatching {
        val json = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
        if (json.optString("id") != id) return@runCatching emptyList()
        val rows = json.getJSONArray("issues")
        (0 until rows.length()).map { rows.getJSONObject(it).let { row -> ScanIssue(row.getString("file"), row.getString("message")) } }
    }.getOrDefault(emptyList())
}

private fun ScanProgress.toData() = workDataOf("checked" to checked, "indexed" to indexed, "unchanged" to unchanged,
    "errors" to errorCount, "folder" to folder, "stage" to stage)

class ScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(ScanProgress())

    private fun foreground(progress: ScanProgress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Music library scans", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Finding your music")
            .setContentText("${progress.checked} songs checked").setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .addAction(android.R.drawable.ic_delete, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val reports = ScanReports(applicationContext)
        var latest = ScanProgress()
        var notificationAt = 0L
        try {
            setForeground(foreground(latest))
            val cache = ArtworkCache(applicationContext)
            val scanner = LibraryScanner(AuralisDatabase.get(applicationContext), AndroidDocumentSource(applicationContext),
                AndroidMetadataReader(applicationContext, cache), cache)
            val result = scanner.scan { progress ->
                latest = progress
                reports.write(id.toString(), progress)
                setProgress(progress.toData())
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - notificationAt >= 1500) { setForeground(foreground(progress)); notificationAt = now }
            }
            Result.success(result.toData())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val failure = latest.copy(stage = "Scan interrupted", errorCount = latest.errorCount + 1,
                issues = (latest.issues + ScanIssue("Library", "Scanning stopped unexpectedly. Try Rescan; your saved library has been kept.")).take(100))
            runCatching { reports.write(id.toString(), failure) }
            Result.failure(failure.toData())
        }
    }
}

data class ScanState(val active: Boolean = false, val queued: Boolean = false,
    val progress: ScanProgress = ScanProgress(stage = "Ready to scan"))

class ScanScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context.applicationContext)
    private val reports = ScanReports(context.applicationContext)
    val state: Flow<ScanState> = manager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { jobs ->
        val job = jobs.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: jobs.filter { !it.state.isFinished }.maxByOrNull { it.requestTime() }
            ?: jobs.maxByOrNull { it.requestTime() }
        if (job == null) ScanState() else {
            val data = if (job.state.isFinished) job.outputData else job.progress
            val queued = job.state == WorkInfo.State.ENQUEUED || job.state == WorkInfo.State.BLOCKED
            val stage = when {
                queued -> "Waiting to scan"
                job.state == WorkInfo.State.CANCELLED -> "Scan canceled"
                job.state == WorkInfo.State.FAILED -> "Scan interrupted"
                else -> data.getString("stage") ?: "Scanning folders"
            }
            ScanState(!job.state.isFinished, queued, ScanProgress(data.getInt("checked", 0), data.getInt("indexed", 0),
                data.getInt("unchanged", 0), data.getInt("errors", 0), data.getString("folder").orEmpty(), stage, reports.read(job.id.toString())))
        }
    }.flowOn(Dispatchers.IO)

    fun request(afterCurrent: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>().addTag("scan-at:${System.currentTimeMillis()}")
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        manager.enqueueUniqueWork(WORK_NAME, if (afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }
    private fun WorkInfo.requestTime() = tags.firstOrNull { it.startsWith("scan-at:") }?.substringAfter(':')?.toLongOrNull() ?: 0L
}
