package com.offlinegemma.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.offlinegemma.app.ModelDownloadWorker.Companion as DL

/** First-launch screen: explains the download, checks the phone, shows progress. */
@Composable
fun DownloadScreen(onModelReady: () -> Unit) {
  val context = LocalContext.current
  val prefs = remember { Prefs(context) }
  var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
  var refresh by remember { mutableIntStateOf(0) } // bumps storage numbers after pause/cancel
  val infos by WorkManager.getInstance(context)
    .getWorkInfosForUniqueWorkFlow(DL.WORK_NAME)
    .collectAsState(initial = emptyList())
  val info = infos.firstOrNull()

  val notifPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { DL.start(context, wifiOnly) } // start either way; the notification is optional

  fun startDownload() {
    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    else DL.start(context, wifiOnly)
  }

  LaunchedEffect(info?.state) {
    if (info?.state == WorkInfo.State.SUCCEEDED && ModelInfo.isDownloaded(context)) onModelReady()
    refresh++
  }

  // Values read each time `refresh` changes.
  val partBytes = remember(refresh, info?.state) { ModelInfo.partFile(context).length() }
  val free = remember(refresh, info?.state) { ModelInfo.freeBytes(context) }
  val needed = remember(refresh, info?.state) { ModelInfo.bytesNeeded(context) }
  val ram = remember { ModelInfo.totalRamBytes(context) }

  Column(
    Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("Offline Gemma", style = MaterialTheme.typography.headlineMedium)
    Text(
      "To chat without internet, this app needs to download the ${ModelInfo.NAME} AI model " +
        "once (${formatBytes(ModelInfo.SIZE_BYTES)}). After that, everything runs on your phone.",
    )

    Text(
      "Free space: ${formatBytes(free)} · needed: ${formatBytes(needed)}\n" +
        "Phone memory (RAM): ${formatBytes(ram)}",
      style = MaterialTheme.typography.bodySmall,
    )
    if (ram < ModelInfo.MIN_RAM_BYTES) {
      Text(
        "⚠ This phone has less than 8 GB of memory. The model may be very slow or may not load.",
        color = MaterialTheme.colorScheme.error,
      )
    }

    when (info?.state) {
      WorkInfo.State.RUNNING -> Running(info)
      WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
        Text(
          if (info.runAttemptCount > 0) "Connection lost. Will continue by itself when the network is back…"
          else if (wifiOnly) "Waiting for Wi-Fi…" else "Waiting for internet…"
        )
        LinearProgressIndicator(
          progress = { partBytes.toFloat() / ModelInfo.SIZE_BYTES },
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { DL.pause(context) }) { Text("Pause") }
      }
      else -> Idle(
        info = info,
        partBytes = partBytes,
        enoughSpace = free >= needed,
        wifiOnly = wifiOnly,
        onWifiOnly = { wifiOnly = it; prefs.wifiOnly = it },
        onStart = ::startDownload,
        onDeletePart = { ModelInfo.partFile(context).delete(); refresh++ },
      )
    }
  }
}

@Composable
private fun Running(info: WorkInfo) {
  val context = LocalContext.current
  val p = info.progress
  if (p.getString(DL.KEY_PHASE) == DL.PHASE_VERIFYING) {
    Text("Checking the file is not damaged…")
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    return
  }
  val bytes = p.getLong(DL.KEY_BYTES, ModelInfo.partFile(context).length())
  val rate = p.getLong(DL.KEY_RATE, 0)
  val left = p.getLong(DL.KEY_LEFT_MS, -1)
  val pct = bytes.toFloat() / ModelInfo.SIZE_BYTES
  Text("Downloading… ${(pct * 100).toInt()}%")
  LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
  Text(
    "${formatBytes(bytes)} of ${formatBytes(ModelInfo.SIZE_BYTES)}" +
      (if (rate > 0) " · ${formatBytes(rate)}/s" else "") +
      (if (left > 0) " · ${formatDuration(left)} left" else ""),
    style = MaterialTheme.typography.bodySmall,
  )
  Text("You can leave the app. It keeps downloading in the background.", style = MaterialTheme.typography.bodySmall)
  OutlinedButton(onClick = { DL.pause(context) }) { Text("Pause") }
}

@Composable
private fun Idle(
  info: WorkInfo?,
  partBytes: Long,
  enoughSpace: Boolean,
  wifiOnly: Boolean,
  onWifiOnly: (Boolean) -> Unit,
  onStart: () -> Unit,
  onDeletePart: () -> Unit,
) {
  if (info?.state == WorkInfo.State.FAILED) {
    Text(
      info.outputData.getString(DL.KEY_ERROR) ?: "Download failed.",
      color = MaterialTheme.colorScheme.error,
    )
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Download on Wi-Fi only")
      if (!wifiOnly) {
        Text(
          "Mobile data may cost money. This uses up to ${formatBytes(ModelInfo.SIZE_BYTES - partBytes)}.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
    Switch(checked = wifiOnly, onCheckedChange = onWifiOnly)
  }
  if (!enoughSpace) {
    Text("Not enough free space. Please free up some space, then come back.", color = MaterialTheme.colorScheme.error)
  }
  val paused = partBytes > 0
  if (paused) {
    LinearProgressIndicator(progress = { partBytes.toFloat() / ModelInfo.SIZE_BYTES }, modifier = Modifier.fillMaxWidth())
    Text("Paused at ${formatBytes(partBytes)}", style = MaterialTheme.typography.bodySmall)
  }
  Spacer(Modifier.height(4.dp))
  Button(onClick = onStart, enabled = enoughSpace, modifier = Modifier.fillMaxWidth()) {
    Text(
      when {
        paused -> "Resume download"
        info?.state == WorkInfo.State.FAILED -> "Try again"
        else -> "Download model (${formatBytes(ModelInfo.SIZE_BYTES)})"
      }
    )
  }
  if (paused) {
    OutlinedButton(onClick = onDeletePart, modifier = Modifier.fillMaxWidth()) { Text("Cancel and delete") }
  }
}
