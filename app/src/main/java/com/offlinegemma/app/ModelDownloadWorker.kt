package com.offlinegemma.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Downloads the model in the background (keeps going if the app is closed).
 * - Resumes from the ".part" file with an HTTP Range request.
 * - Checks free storage before starting.
 * - Checks the SHA-256 fingerprint at the end, then renames ".part" to the real name.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, "Starting…")

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val ctx = applicationContext
    if (ModelInfo.isDownloaded(ctx)) return@withContext Result.success()

    if (!ModelInfo.hasEnoughStorage(ctx)) {
      return@withContext Result.failure(
        error(
          ERR_STORAGE,
          "Not enough space. Need ${formatBytes(ModelInfo.bytesNeeded(ctx))}, " +
            "free ${formatBytes(ModelInfo.freeBytes(ctx))}.",
        )
      )
    }

    setForeground(foregroundInfo(0, "Starting…"))
    val part = ModelInfo.partFile(ctx)

    try {
      download(part)
    } catch (e: IOException) {
      Log.w(TAG, "Download interrupted at ${part.length()} bytes, will retry", e)
      // Partial file stays; WorkManager retries with back-off and we resume from it.
      return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
      else Result.failure(error(ERR_NETWORK, "Download keeps failing: ${e.message}"))
    }

    // Fingerprint check.
    setProgress(workDataOf(KEY_PHASE to PHASE_VERIFYING))
    setForeground(foregroundInfo(100, "Checking file…"))
    val hash = sha256(part)
    if (hash != ModelInfo.SHA256) {
      Log.e(TAG, "Checksum mismatch: $hash")
      part.delete()
      return@withContext Result.failure(
        error(ERR_CHECKSUM, "The downloaded file was damaged. Please try again.")
      )
    }
    val target = ModelInfo.modelFile(ctx)
    target.delete()
    if (!part.renameTo(target)) {
      return@withContext Result.failure(error(ERR_OTHER, "Could not save the model file."))
    }
    Log.i(TAG, "Model ready: ${target.path}")
    Result.success()
  }

  private suspend fun download(part: File) {
    var have = part.length()
    if (have >= ModelInfo.SIZE_BYTES) return

    val conn = (URL(ModelInfo.URL).openConnection() as HttpURLConnection).apply {
      connectTimeout = 20_000
      readTimeout = 30_000
      instanceFollowRedirects = true // Hugging Face redirects to its file servers
      if (have > 0) setRequestProperty("Range", "bytes=$have-")
    }
    try {
      val code = conn.responseCode
      Log.d(TAG, "HTTP $code, resuming from $have")
      val append = when (code) {
        HttpURLConnection.HTTP_PARTIAL -> {
          // Make sure the server is sending exactly the part we asked for.
          val range = conn.getHeaderField("Content-Range") ?: ""
          if (!range.startsWith("bytes $have-")) {
            part.delete(); have = 0
            throw IOException("Server sent unexpected range '$range'; restarting")
          }
          true
        }
        HttpURLConnection.HTTP_OK -> { have = 0; false } // server ignored Range: start over
        416 -> return // "range not satisfiable" = we already have it all
        else -> throw IOException("HTTP $code")
      }

      val buf = ByteArray(1 shl 20)
      var lastReport = 0L
      var windowStart = System.currentTimeMillis()
      var windowBytes = 0L
      var rate = 0L
      conn.inputStream.use { input ->
        FileOutputStream(part, append).use { out ->
          while (true) {
            coroutineContext.ensureActive() // stops cleanly on Pause / Cancel
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            have += n
            windowBytes += n
            val now = System.currentTimeMillis()
            if (now - windowStart >= 2000) {
              rate = windowBytes * 1000 / (now - windowStart)
              windowStart = now
              windowBytes = 0
            }
            if (now - lastReport >= 500) {
              lastReport = now
              val left = if (rate > 0) (ModelInfo.SIZE_BYTES - have) * 1000 / rate else -1
              setProgress(
                workDataOf(
                  KEY_PHASE to PHASE_DOWNLOADING,
                  KEY_BYTES to have,
                  KEY_RATE to rate,
                  KEY_LEFT_MS to left,
                )
              )
              val pct = (have * 100 / ModelInfo.SIZE_BYTES).toInt()
              val detail = "${formatBytes(have)} / ${formatBytes(ModelInfo.SIZE_BYTES)}" +
                (if (left > 0) " · ${formatDuration(left)} left" else "")
              setForeground(foregroundInfo(pct, detail))
            }
          }
        }
      }
      if (have < ModelInfo.SIZE_BYTES) throw IOException("Connection closed early at $have bytes")
    } finally {
      conn.disconnect()
    }
  }

  private suspend fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(1 shl 20)
    file.inputStream().use { input ->
      while (true) {
        coroutineContext.ensureActive()
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  private fun foregroundInfo(percent: Int, text: String): ForegroundInfo {
    val nm = applicationContext.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW)
    )
    val n = NotificationCompat.Builder(applicationContext, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle("Downloading ${ModelInfo.NAME}")
      .setContentText(text)
      .setProgress(100, percent, false)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
    return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  }

  private fun error(code: String, message: String): Data =
    workDataOf(KEY_ERROR_CODE to code, KEY_ERROR to message)

  companion object {
    private const val TAG = "ModelDownload"
    const val WORK_NAME = "model-download"
    private const val CHANNEL = "download"
    private const val NOTIF_ID = 1
    private const val MAX_ATTEMPTS = 20

    const val KEY_PHASE = "phase"
    const val KEY_BYTES = "bytes"
    const val KEY_RATE = "rate"
    const val KEY_LEFT_MS = "left_ms"
    const val KEY_ERROR = "error"
    const val KEY_ERROR_CODE = "error_code"
    const val PHASE_DOWNLOADING = "downloading"
    const val PHASE_VERIFYING = "verifying"
    const val ERR_STORAGE = "storage"
    const val ERR_NETWORK = "network"
    const val ERR_CHECKSUM = "checksum"
    const val ERR_OTHER = "other"

    /** Starts (or restarts with new Wi-Fi setting) the download. The partial file is kept. */
    fun start(context: Context, wifiOnly: Boolean) {
      val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
        .setConstraints(
          Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        )
        .build()
      WorkManager.getInstance(context)
        .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Pause: stops the download but keeps the partial file to resume later. */
    fun pause(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
  }
}
