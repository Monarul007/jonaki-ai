package com.offlinegemma.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Builds the semantic index in the background, as a WorkManager job with a foreground notification.
 *
 * Indexing the whole study pack takes tens of minutes on the phone's CPU. Doing it inside the app's
 * own coroutine scope meant it died with the process (a swipe from recents is enough) and started
 * over from zero. As a worker it survives the app being closed, and [Semantic] checkpoints every
 * few hundred vectors, so a kill costs seconds of work instead of the whole run.
 */
class SemanticIndexWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val ctx = applicationContext
    if (!Semantic.isDownloaded(ctx) || !Knowledge.isInstalled(ctx)) {
      Log.i(TAG, "nothing to index: embedder or pack missing")
      return@withContext Result.success()
    }
    if (Semantic.isReady) return@withContext Result.success()

    setForeground(foregroundInfo(null))
    val ok = coroutineScope {
      val ticker = launch {
        while (isActive) {
          delay(1500)
          Semantic.progress?.let { (done, total) ->
            if (total > 0) runCatching { setForeground(foregroundInfo(done * 100 / total)) }
          }
        }
      }
      try {
        Semantic.index(ctx)
      } finally {
        ticker.cancel()
      }
    }
    if (ok) {
      Log.i(TAG, "semantic index finished")
      Result.success()
    } else {
      // The model or pack may still be settling; WorkManager retries with back-off and the
      // checkpoint means the retry continues rather than restarts.
      if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }
  }

  private fun foregroundInfo(percent: Int?): ForegroundInfo {
    val nm = applicationContext.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL, "Study search", NotificationManager.IMPORTANCE_LOW),
    )
    val n = NotificationCompat.Builder(applicationContext, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setContentTitle("Preparing study search")
      .setContentText(if (percent == null) "Reading your study files…" else "$percent%")
      .setProgress(100, percent ?: 0, percent == null)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
    return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  }

  companion object {
    private const val TAG = "SemanticIndex"
    private const val CHANNEL = "study"
    private const val NOTIF_ID = 2
    private const val MAX_ATTEMPTS = 10
    const val WORK_NAME = "semantic-index"

    /** Queues the build; KEEP means a second tap or a relaunch never starts a duplicate run. */
    fun start(context: Context) {
      val request = OneTimeWorkRequestBuilder<SemanticIndexWorker>()
        .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
        .build()
      WorkManager.getInstance(context)
        .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
  }
}