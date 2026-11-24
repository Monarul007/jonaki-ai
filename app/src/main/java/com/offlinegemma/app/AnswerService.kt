package com.offlinegemma.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Answers run as background jobs. While any job runs (or waits), this foreground service keeps the app
 * and the loaded model alive when the user leaves the app, and shows an ongoing "Answering…" notice.
 * When an answer is done and the user is not looking at that chat, a separate "Answer ready" notice
 * opens the chat when tapped.
 */
class AnswerService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Answering…"
    val n = ongoing(this, text)
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      } else {
        startForeground(ONGOING_ID, n)
      }
    }.onFailure { Log.w(TAG, "could not go foreground: ${it.message}") }
    return START_NOT_STICKY
  }

  /** Android 15 limits this kind of service; stop quietly instead of being killed. */
  override fun onTimeout(startId: Int, fgsType: Int) {
    Log.w(TAG, "background time limit reached; stopping")
    stopSelf()
  }

  companion object {
    private const val TAG = "AnswerService"
    private const val ONGOING_CHANNEL = "answering"
    private const val READY_CHANNEL = "answers"
    private const val ONGOING_ID = 77
    private const val EXTRA_TEXT = "text"
    /** Extra on the launch intent: the chat a tapped "answer ready" notice opens. */
    const val EXTRA_CHAT_ID = "open_chat_id"

    @Volatile private var started = false

    private fun channels(context: Context) {
      val nm = context.getSystemService(NotificationManager::class.java) ?: return
      nm.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "Answering in the background", NotificationManager.IMPORTANCE_LOW))
      nm.createNotificationChannel(NotificationChannel(READY_CHANNEL, "Answers ready", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(context: Context, chatId: String?): PendingIntent {
      val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
      if (chatId != null) i.putExtra(EXTRA_CHAT_ID, chatId)
      return PendingIntent.getActivity(
        context, chatId?.hashCode() ?: 0, i,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    }

    private fun ongoing(context: Context, text: String): Notification {
      channels(context)
      return Notification.Builder(context, ONGOING_CHANNEL)
        .setSmallIcon(R.drawable.ic_splash)
        .setContentTitle("Jonaki AI")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(context, null))
        .build()
    }

    /** Starts the service, or just updates its notice when it is already running. */
    fun progress(context: Context, text: String) {
      if (started) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ONGOING_ID, ongoing(context, text)) }
        return
      }
      runCatching {
        context.startForegroundService(Intent(context, AnswerService::class.java).putExtra(EXTRA_TEXT, text))
        started = true
      }.onFailure { Log.w(TAG, "could not start: ${it.message}") }
    }

    /** No job runs or waits any more. */
    fun finish(context: Context) {
      if (!started) return
      started = false
      runCatching { context.stopService(Intent(context, AnswerService::class.java)) }
    }

    /** "Answer ready" for a chat the user is not looking at. */
    fun ready(context: Context, chatId: String, title: String, answer: String) {
      channels(context)
      val preview = answer.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(120)
      val n = Notification.Builder(context, READY_CHANNEL)
        .setSmallIcon(R.drawable.ic_splash)
        .setContentTitle("Answer ready · $title")
        .setContentText(preview)
        .setStyle(Notification.BigTextStyle().bigText(answer.take(400)))
        .setAutoCancel(true)
        .setContentIntent(openApp(context, chatId))
        .build()
      runCatching { context.getSystemService(NotificationManager::class.java)?.notify(chatId.hashCode(), n) }
        .onFailure { Log.w(TAG, "could not post: ${it.message}") }
    }
  }
}
