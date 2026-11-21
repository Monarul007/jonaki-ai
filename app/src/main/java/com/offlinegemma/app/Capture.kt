package com.offlinegemma.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * A short foreground service that keeps the process (and the loaded model) alive while the
 * photo/file picker is on screen. (Taking a photo no longer leaves the app: the camera preview
 * lives in [CameraCapture].) OPPO and Redmi phones otherwise kill the app while a picker is up.
 */
class KeepAliveService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Finishing up…"
    android.util.Log.i("KeepAlive", "onStartCommand: $text")
    val n = notification(text)
    val type =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
      else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    startForeground(ID, n, type)
    return START_NOT_STICKY
  }

  /** A short service has three minutes; stop quietly instead of being killed with an ANR. */
  override fun onTimeout(startId: Int) {
    android.util.Log.w("KeepAlive", "short service timed out; stopping")
    stopSelf()
  }

  private fun notification(text: String): Notification {
    val nm = getSystemService(NotificationManager::class.java)
    nm?.createNotificationChannel(
      NotificationChannel(CHANNEL, "Camera and picks", NotificationManager.IMPORTANCE_LOW),
    )
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_splash)
      .setContentTitle("Jonaki AI")
      .setContentText(text)
      .setOngoing(true)
      .build()
  }

  companion object {
    private const val CHANNEL = "capture"
    private const val ID = 42
    private const val EXTRA_TEXT = "text"

    fun begin(context: Context, text: String) {
      runCatching {
        context.startForegroundService(Intent(context, KeepAliveService::class.java).putExtra(EXTRA_TEXT, text))
        android.util.Log.i("KeepAlive", "started for: $text")
      }.onFailure { android.util.Log.w("KeepAlive", "could not start: ${it.message}", it) }
    }

    fun end(context: Context) {
      runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
    }
  }
}
