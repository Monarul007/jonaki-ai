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

  /**
   * Whichever file this job is for: a chat model (chosen by name, so downloading E4B never touches
   * the E2B file already on the phone), or the embedder used for semantic search.
   */
  private fun target(ctx: Context): Target {
    val asked = inputData.getString(KEY_MODEL) ?: MODEL_GEMMA
    if (asked == MODEL_EMBEDDER) {
      return Target(
        name = Semantic.NAME, url = Semantic.URL, size = Semantic.SIZE_BYTES, sha = Semantic.SHA256,
        part = Semantic.partFile(ctx), file = Semantic.file(ctx),
      )
    }
    val m = runCatching { ChatModel.valueOf(asked) }.getOrDefault(ModelInfo.DEFAULT)
    return Target(
      name = m.label, url = m.URL, size = m.SIZE_BYTES, sha = m.SHA256,
      part = ModelInfo.partFile(ctx, m), file = ModelInfo.file(ctx, m),
    )
  }

  private data class Target(
    val name: String, val url: String, val size: Long, val sha: String,
    val part: File, val file: File,
  )

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val ctx = applicationContext
    val asked = inputData.getString(KEY_MODEL) ?: MODEL_GEMMA
    val t = target(ctx)
    if (t.file.length() == t.size) return@withContext Result.success()

    val free = ModelInfo.freeBytes(ctx)
    val needed = (t.size - t.part.length()).coerceAtLeast(0) + ModelInfo.SPARE_BYTES
    if (free < needed) {
      return@withContext Result.failure(
        error(ERR_STORAGE, "Not enough space. Need ${formatBytes(needed)}, free ${formatBytes(free)}.")
      )
    }

    setForeground(foregroundInfo(0, "Starting…"))

    try {
      download(t)
    } catch (e: IOException) {
      Log.w(TAG, "${t.name} interrupted at ${t.part.length()} bytes, will retry", e)
      // Partial file stays; WorkManager retries with back-off and we resume from it.
      return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
      else Result.failure(error(ERR_NETWORK, "Download keeps failing: ${e.message}"))
    }

    // Fingerprint check.
    setProgress(workDataOf(KEY_PHASE to PHASE_VERIFYING))
    setForeground(foregroundInfo(100, "Checking file…"))
    val hash = sha256(t.part)
    if (hash != t.sha) {
      Log.e(TAG, "Checksum mismatch for ${t.name}: $hash")
      t.part.delete()
      return@withContext Result.failure(
        error(ERR_CHECKSUM, "The downloaded file was damaged. Please try again.")
      )
    }
    t.file.delete()
    if (!t.part.renameTo(t.file)) {
      return@withContext Result.failure(error(ERR_OTHER, "Could not save the model file."))
    }
    Log.i(TAG, "${t.name} ready: ${t.file.path}")
    // The embedder is only useful once the pack is indexed, so start that now and independently of
    // the tap that triggered the download: nobody should have to press the row a second time.
    if (asked == MODEL_EMBEDDER) {
      setForeground(foregroundInfo(100, "Preparing study search...", t.name))
      SemanticIndexWorker.start(ctx)
    }
    Result.success()
  }

  private suspend fun download(t: Target) {
    val part = t.part
    var have = part.length()
    if (have >= t.size) return

    val conn = (URL(t.url).openConnection() as HttpURLConnection).apply {
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
              val left = if (rate > 0) (t.size - have) * 1000 / rate else -1
              setProgress(
                workDataOf(
                  KEY_PHASE to PHASE_DOWNLOADING,
                  KEY_BYTES to have,
                  KEY_RATE to rate,
                  KEY_LEFT_MS to left,
                )
              )
              val pct = (have * 100 / t.size).toInt()
              val detail = "${formatBytes(have)} / ${formatBytes(t.size)}" +
                (if (left > 0) " · ${formatDuration(left)} left" else "")
              setForeground(foregroundInfo(pct, detail, t.name))
            }
          }
        }
      }
      if (have < t.size) throw IOException("Connection closed early at $have bytes")
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

  private fun foregroundInfo(percent: Int, text: String, name: String = ModelInfo.NAME): ForegroundInfo {
    val nm = applicationContext.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW)
    )
    val n = NotificationCompat.Builder(applicationContext, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle("Downloading $name")
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
    const val KEY_MODEL = "model"
    /** The value that means "the default chat model"; any ChatModel name is also accepted. */
    const val MODEL_GEMMA = "gemma"
    const val MODEL_EMBEDDER = "embedder"
    const val WORK_EMBEDDER = "embedder-download"

    /** One queue per file, so downloading E4B cannot cancel a running E2B download. */
    private fun workName(model: String) = when (model) {
      MODEL_EMBEDDER -> WORK_EMBEDDER
      MODEL_GEMMA -> WORK_NAME
      else -> if (model.equals(ModelInfo.DEFAULT.name, ignoreCase = true)) WORK_NAME else "model-download-$model"
    }

    /** The unique-work name a screen watches to show this model's download progress. */
    fun uniqueName(model: String) = workName(model)

    /**
     * Starts a download, or resumes a paused one. The partial file is kept either way.
     *
     * [ExistingWorkPolicy.KEEP]: if a job for this model is already queued or running, leave it
     * alone. REPLACE reset a healthy 485 MB download to zero whenever the row was tapped again, and
     * since the progress bar came from that job it looked like the download had silently restarted.
     */
    fun start(context: Context, wifiOnly: Boolean, model: String = MODEL_GEMMA) {
      val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
        .setConstraints(
          Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        )
        .setInputData(workDataOf(KEY_MODEL to model))
        .build()
      WorkManager.getInstance(context)
        .enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request)
    }

    /** Pause: stops the download but keeps the partial file to resume later. */
    fun pause(context: Context, model: String = MODEL_GEMMA) {
      WorkManager.getInstance(context).cancelUniqueWork(workName(model))
    }
    const val PHASE_DOWNLOADING = "downloading"
    const val PHASE_VERIFYING = "verifying"
    const val ERR_STORAGE = "storage"
    const val ERR_NETWORK = "network"
    const val ERR_CHECKSUM = "checksum"
    const val ERR_OTHER = "other"
  }
}
