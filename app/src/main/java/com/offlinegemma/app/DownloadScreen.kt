package com.offlinegemma.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.offlinegemma.app.ModelDownloadWorker.Companion as DL
import com.offlinegemma.app.ui.LocalExtraColors

/** First launch: a welcome page, then the one-time model download. */
@Composable
fun OnboardingScreen(prefs: Prefs, onModelReady: () -> Unit) {
  var page by rememberSaveable(prefs.onboarded) { mutableIntStateOf(if (prefs.onboarded) 1 else 0) }
  AnimatedContent(
    targetState = page,
    transitionSpec = {
      val dir = if (targetState > initialState) 1 else -1
      (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
    },
    label = "onboarding",
  ) { p ->
    if (p == 0) WelcomePage(onNext = { prefs.onboarded = true; page = 1 })
    else DownloadPage(prefs, onBack = { page = 0 }, onModelReady = onModelReady)
  }
}

@Composable
private fun WelcomePage(onNext: () -> Unit) {
  Column(
    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(36.dp))
    BrandMark(120.dp)
    Spacer(Modifier.height(20.dp))
    Text("Your own AI,\nright on this phone", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(
      "Jonaki (জোনাকি, firefly) makes its own light: Google's Gemma AI runs right on your phone. No account. No cloud.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(36.dp))
    Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
      FeatureRow(Icons.Rounded.Lock, "Private", "Your chats, photos and voice stay on this phone.")
      FeatureRow(Icons.Rounded.WifiOff, "Works offline", "After one download, no internet is needed.", MaterialTheme.colorScheme.tertiary)
      FeatureRow(Icons.Rounded.AutoAwesome, "Sees, hears and teaches", "Ask about photos, talk out loud, and learn with live demos.", Color(0xFF7A5AF8))
    }
    Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).height(56.dp)) {
      Text("Get started", style = MaterialTheme.typography.titleMedium)
    }
  }
}

@Composable
private fun DownloadPage(prefs: Prefs, onBack: () -> Unit, onModelReady: () -> Unit) {
  val context = LocalContext.current
  var refresh by remember { mutableIntStateOf(0) } // bumps storage numbers after pause/cancel
  val infos by WorkManager.getInstance(context)
    .getWorkInfosForUniqueWorkFlow(DL.WORK_NAME)
    .collectAsState(initial = emptyList())
  val info = infos.firstOrNull()

  val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    DL.start(context, prefs.wifiOnly) // start either way; the notification is optional
  }

  fun startDownload() {
    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    else DL.start(context, prefs.wifiOnly)
  }

  LaunchedEffect(info?.state) {
    if (info?.state == WorkInfo.State.SUCCEEDED && ModelInfo.isDownloaded(context)) onModelReady()
    refresh++
  }

  val partBytes = remember(refresh, info?.state) { ModelInfo.partFile(context).length() }
  val free = remember(refresh, info?.state) { ModelInfo.freeBytes(context) }
  val needed = remember(refresh, info?.state) { ModelInfo.bytesNeeded(context) }
  val ram = remember { ModelInfo.totalRamBytes(context) }
  val extra = LocalExtraColors.current

  Column(
    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
    Text("One-time download", style = MaterialTheme.typography.headlineMedium)
    Text(
      "The ${ModelInfo.NAME} model is ${formatBytes(ModelInfo.SIZE_BYTES)}. It is saved inside this app only, " +
        "checked for damage, and then everything works offline.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SoftCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(vertical = 8.dp)) {
        CheckRow(
          Icons.Rounded.SdStorage,
          "Storage",
          "${formatBytes(free)} free · needs ${formatBytes(needed)}",
          ok = free >= needed,
          okColor = extra.ok,
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        val bigPhone = ram >= ModelInfo.MIN_RAM_BYTES
        CheckRow(
          if (bigPhone) Icons.Rounded.Memory else Icons.Rounded.Speed,
          "Memory",
          if (bigPhone) "${formatBytes(ram)} · great for Gemma"
          else "${formatBytes(ram)} · Gemma works, but answers come slowly (a few words a second). Phones with 8 GB are faster.",
          ok = if (bigPhone) true else null,
          okColor = extra.ok,
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Rounded.Wifi, null, tint = MaterialTheme.colorScheme.primary)
          Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("Wi-Fi only", style = MaterialTheme.typography.titleSmall)
            Text(
              if (prefs.wifiOnly) "Waits for Wi-Fi, so it never uses mobile data."
              else "Mobile data may cost money. Uses up to ${formatBytes(ModelInfo.SIZE_BYTES - partBytes)}.",
              style = MaterialTheme.typography.bodySmall,
              color = if (prefs.wifiOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
          }
          Switch(checked = prefs.wifiOnly, onCheckedChange = { prefs.wifiOnly = it })
        }
      }
    }

    when (info?.state) {
      WorkInfo.State.RUNNING -> Running(info)
      WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
        Progress(partBytes.toFloat() / ModelInfo.SIZE_BYTES)
        Text(
          if (info.runAttemptCount > 0) "Connection lost. It will carry on by itself when the network is back…"
          else if (prefs.wifiOnly) "Waiting for Wi-Fi…" else "Waiting for internet…",
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { DL.pause(context) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Pause") }
      }
      else -> Idle(
        info = info,
        partBytes = partBytes,
        enoughSpace = free >= needed,
        onStart = ::startDownload,
        onDeletePart = { ModelInfo.partFile(context).delete(); refresh++ },
      )
    }
    Spacer(Modifier.height(8.dp))
  }
}

@Composable
private fun CheckRow(icon: ImageVector, title: String, body: String, ok: Boolean?, okColor: Color) {
  Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when (ok) {
      true -> Icon(Icons.Rounded.CheckCircle, "OK", tint = okColor)
      false -> Icon(Icons.Rounded.ErrorOutline, "Problem", tint = MaterialTheme.colorScheme.error)
      null -> Icon(Icons.Rounded.Speed, "Slow phone", tint = Color(0xFFE0A100))
    }
  }
}

@Composable
private fun Progress(fraction: Float) {
  LinearProgressIndicator(
    progress = { fraction },
    modifier = Modifier.fillMaxWidth().height(10.dp),
    strokeCap = StrokeCap.Round,
    gapSize = 0.dp,
    drawStopIndicator = {},
  )
}

@Composable
private fun Running(info: WorkInfo) {
  val context = LocalContext.current
  val p = info.progress
  if (p.getString(DL.KEY_PHASE) == DL.PHASE_VERIFYING) {
    Text("Checking the file is not damaged…", style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(Modifier.fillMaxWidth().height(10.dp), strokeCap = StrokeCap.Round)
    return
  }
  val bytes = p.getLong(DL.KEY_BYTES, ModelInfo.partFile(context).length())
  val rate = p.getLong(DL.KEY_RATE, 0)
  val left = p.getLong(DL.KEY_LEFT_MS, -1)
  val pct = bytes.toFloat() / ModelInfo.SIZE_BYTES
  Row(verticalAlignment = Alignment.Bottom) {
    Text("${(pct * 100).toInt()}%", style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.size(12.dp))
    Text(
      "${formatBytes(bytes)} of ${formatBytes(ModelInfo.SIZE_BYTES)}",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(bottom = 6.dp),
    )
  }
  Progress(pct)
  Text(
    listOfNotNull(
      if (rate > 0) "${formatBytes(rate)}/s" else null,
      if (left > 0) "${formatDuration(left)} left" else null,
    ).joinToString(" · ").ifEmpty { "Starting…" },
    style = MaterialTheme.typography.bodyMedium,
  )
  Text(
    "You can leave the app. It keeps downloading in the background.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  OutlinedButton(onClick = { DL.pause(context) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Pause") }
}

@Composable
private fun Idle(info: WorkInfo?, partBytes: Long, enoughSpace: Boolean, onStart: () -> Unit, onDeletePart: () -> Unit) {
  if (info?.state == WorkInfo.State.FAILED) {
    Text(info.outputData.getString(DL.KEY_ERROR) ?: "Download failed.", color = MaterialTheme.colorScheme.error)
  }
  if (!enoughSpace) {
    Text("Not enough free space. Free up some space, then come back.", color = MaterialTheme.colorScheme.error)
  }
  val paused = partBytes > 0
  if (paused) {
    Progress(partBytes.toFloat() / ModelInfo.SIZE_BYTES)
    Text("Paused at ${formatBytes(partBytes)}", style = MaterialTheme.typography.bodyMedium)
  }
  Button(onClick = onStart, enabled = enoughSpace, modifier = Modifier.fillMaxWidth().height(56.dp)) {
    Icon(Icons.Rounded.CloudDownload, null)
    Spacer(Modifier.size(10.dp))
    Text(
      when {
        paused -> "Resume download"
        info?.state == WorkInfo.State.FAILED -> "Try again"
        else -> "Download (${formatBytes(ModelInfo.SIZE_BYTES)})"
      },
      style = MaterialTheme.typography.titleMedium,
    )
  }
  if (paused) {
    TextButton(onClick = onDeletePart, modifier = Modifier.fillMaxWidth()) { Text("Cancel and delete") }
  }
}
