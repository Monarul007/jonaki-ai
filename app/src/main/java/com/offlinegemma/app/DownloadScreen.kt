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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.offlinegemma.app.ModelDownloadWorker.Companion as DL
import com.offlinegemma.app.ui.LocalExtraColors

/** First launch: a welcome page, then the one-time model download. */
@Composable
fun OnboardingScreen(prefs: Prefs, onModelReady: () -> Unit) {
  val context = LocalContext.current
  var page by rememberSaveable(prefs.onboarded) { mutableIntStateOf(if (prefs.onboarded) 1 else 0) }
  AnimatedContent(
    targetState = page,
    transitionSpec = {
      val dir = if (targetState > initialState) 1 else -1
      (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
    },
    label = "onboarding",
  ) { p ->
    if (p == 0) {
      WelcomePage(onNext = { prefs.onboarded = true; page = 1 })
    } else {
      // Skip the download screen when a model this phone can actually run is already here. Asking
      // "is any file downloaded" was not the same question: a file left by an older build can be too
      // large to load, and that made the app bounce between setup and chat forever.
      val usable = remember { ModelInfo.hasUsableModel(context) }
      LaunchedEffect(usable) { if (usable) onModelReady() }
      if (!usable) DownloadPage(prefs, onBack = { page = 0 }, onModelReady = onModelReady)
    }
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
      "Jonaki AI (জোনাকি, firefly) makes its own light: a small AI model runs right on your phone. No account. No cloud.",
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

/**
 * The one-time download screen. It offers exactly the models this phone can install, says how much
 * memory each one needs next to how much the phone has, and refuses anything that would make the
 * phone itself unreliable. That last part is not advice: running a model bigger than the phone's
 * memory let Android's low-memory killer close System UI on an 8 GB test phone.
 */
@Composable
private fun DownloadPage(prefs: Prefs, onBack: () -> Unit, onModelReady: () -> Unit) {
  val context = LocalContext.current
  var refresh by remember { mutableIntStateOf(0) } // bumps the memory/space numbers and the list
  val installable = remember(refresh) { ModelInfo.installable(context) }
  val oversized = remember(refresh) { ModelInfo.oversizedInstalled(context) }
  val target = installable.firstOrNull { it == prefs.model } ?: installable.firstOrNull()

  val manager = WorkManager.getInstance(context)
  // Which row asked. The permission dialog answers asynchronously, so the tapped model has to be
  // remembered; starting the preferred model here would download the wrong one.
  var pending by remember { mutableStateOf<ChatModel?>(null) }
  val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    (pending ?: target)?.let { DL.start(context, prefs.wifiOnly, it.name) } // start either way; the notification is optional
    pending = null
  }

  fun startDownload(m: ChatModel) {
    if (Build.VERSION.SDK_INT >= 33) {
      pending = m
      notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      DL.start(context, prefs.wifiOnly, m.name)
    }
  }

  // Wait for whichever model actually finishes, not just the preferred one: downloading the second
  // row must still take the user into the app.
  for (m in installable) {
    val flow = remember(m) { manager.getWorkInfosForUniqueWorkFlow(DL.uniqueName(m.name)) }
    val infos by flow.collectAsState(initial = emptyList())
    LaunchedEffect(infos.firstOrNull()?.state) {
      if (infos.firstOrNull()?.state == WorkInfo.State.SUCCEEDED && ModelInfo.isDownloaded(context, m)) onModelReady()
    }
  }

  val free = remember(refresh) { ModelInfo.freeBytes(context) }
  val ram = remember(refresh) { ModelInfo.totalRamBytes(context) }
  val tight = remember(refresh) { ModelInfo.lowMemory(context) }
  val extra = LocalExtraColors.current
  var confirmRemove by remember { mutableStateOf<ChatModel?>(null) }

  Column(
    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
    Text("One-time download", style = MaterialTheme.typography.headlineMedium)
    Text(
      when {
        installable.isEmpty() && oversized.isEmpty() ->
          "This phone cannot run Jonaki AI at the moment. There is nothing to download yet."
        installable.size == 1 && installable.first() == ModelInfo.DEFAULT ->
          "One download and everything works offline. This phone can run 1 model, shown below."
        else ->
          "One download and everything works offline. " +
            "This phone can run ${installable.size} model${if (installable.size == 1) "" else "s"}, best first."
      },
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // A model that is on the phone and bigger than this phone is *comfortable* with. Advice only:
    // two identical 7.56 GB phones disagree about E4B — one struggled, one ran it steadily — so the
    // app never claims it "cannot run" or pushes the user to delete it. It is still selectable.
    for (m in oversized) {
      SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.size(12.dp))
            Text("${m.label} is a big model for this phone", style = MaterialTheme.typography.titleSmall)
          }
          Text(
            "It wants about ${formatBytes(m.minTotalRamBytes)} of memory and this phone has " +
              "${formatBytes(ram)}. Many phones run it fine anyway; if this one becomes slow or " +
              "unstable, switch back to ${ModelInfo.DEFAULT.label} or remove it (frees ${formatBytes(m.SIZE_BYTES)}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { prefs.model = m; onModelReady() }) { Text("Use it") }
            OutlinedButton(onClick = { confirmRemove = m }) {
              Icon(Icons.Rounded.DeleteOutline, null)
              Spacer(Modifier.size(8.dp))
              Text("Remove")
            }
          }
        }
      }
    }

    if (installable.isEmpty()) {
      EmptyFit(ram, free)
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (m in installable) {
          val here = ModelInfo.isDownloaded(context, m)
          // Each row watches its own job, so downloading the second model shows its own progress and
          // pausing it cannot touch the first model's download.
          val infos by remember(m) { manager.getWorkInfosForUniqueWorkFlow(DL.uniqueName(m.name)) }
            .collectAsState(initial = emptyList())
          val info = infos.firstOrNull()
          val part = remember(refresh, info?.state) { ModelInfo.partFile(context, m).length() }
          ModelRow(
            m = m,
            installed = here,
            running = prefs.model == m && here,
            recommended = m == ModelInfo.DEFAULT,
            slow = ModelInfo.slow(context, m),
            busy = ModelInfo.busy(context),
            phoneRam = ram,
            inProgress = info?.state == WorkInfo.State.RUNNING ||
              info?.state == WorkInfo.State.ENQUEUED || info?.state == WorkInfo.State.BLOCKED,
            partBytes = part,
            free = free,
            info = info,
            prefs = prefs,
            onStart = { startDownload(m) },
            onDeletePart = { ModelInfo.partFile(context, m).delete(); refresh++ },
          )
        }
      }
    }

    SoftCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(vertical = 8.dp)) {
        CheckRow(
          Icons.Rounded.Memory,
          "Memory",
          "${formatBytes(ram)} in total · " +
            if (tight) "the phone is short of memory right now, so close other apps first."
            else "plenty for Jonaki AI." +
              if (installable.isNotEmpty()) " The model offered needs about ${formatBytes(installable.first().minTotalRamBytes)}."
              else "",
          ok = installable.isNotEmpty() && !tight,
          okColor = extra.ok,
        )
        // Storage only means something once there is a model to download.
        if (target != null && !ModelInfo.isDownloaded(context, target)) {
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
          CheckRow(
            Icons.Rounded.SdStorage,
            "Storage",
            "${formatBytes(free)} free · needs ${formatBytes(ModelInfo.bytesNeeded(context, target))} for ${target.label}",
            ok = free >= ModelInfo.bytesNeeded(context, target),
            okColor = extra.ok,
          )
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
          Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Wifi, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
              Text("Wi-Fi only", style = MaterialTheme.typography.titleSmall)
              Text(
                if (prefs.wifiOnly) "Waits for Wi-Fi, so it never uses mobile data."
                else "Mobile data may cost money. Uses up to ${formatBytes(target.SIZE_BYTES)}.",
                style = MaterialTheme.typography.bodySmall,
                color = if (prefs.wifiOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
              )
            }
            Switch(checked = prefs.wifiOnly, onCheckedChange = { prefs.wifiOnly = it })
          }
        }
      }
    }
    Spacer(Modifier.height(8.dp))
  }

  confirmRemove?.let { victim ->
    AlertDialog(
      onDismissRequest = { confirmRemove = null },
      title = { Text("Remove ${victim.label}?") },
      text = { Text("This frees ${formatBytes(victim.SIZE_BYTES)}. Your chats stay, and the app keeps working with a model that fits this phone.") },
      confirmButton = {
        TextButton(onClick = {
          confirmRemove = null
          ModelInfo.delete(context, victim)
          prefs.usableModel()
          refresh++
        }) { Text("Remove") }
      },
      dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
    )
  }
}

/** One installable model: what it is, what it costs, and whether this phone is comfortable with it. */
@Composable
private fun ModelRow(
  m: ChatModel,
  installed: Boolean,
  running: Boolean,
  recommended: Boolean,
  slow: Boolean,
  busy: Boolean,
  phoneRam: Long,
  inProgress: Boolean,
  partBytes: Long,
  free: Long,
  info: WorkInfo?,
  prefs: Prefs,
  onStart: () -> Unit,
  onDeletePart: () -> Unit,
) {
  val extra = LocalExtraColors.current
  SoftCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          if (running) Icons.Rounded.CheckCircle else if (installed) Icons.Rounded.SdStorage else Icons.Rounded.CloudDownload,
          null,
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(12.dp))
        Text(m.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (recommended) Pill("Recommended", MaterialTheme.colorScheme.primary)
        if (running) {
          Spacer(Modifier.size(6.dp))
          Pill("In use", extra.ok)
        }
      }
      Text(
        "${formatBytes(m.SIZE_BYTES)} · ${m.speed}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      // The honest capacity line: what it needs, what the phone has. This is the bit that makes the
      // choice obvious instead of a leap of faith.
      Text(
        "Needs about ${formatBytes(m.minTotalRamBytes)} of memory · this phone has ${formatBytes(phoneRam)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (slow) {
        Text(
          "Runs, but answers come a few words a second on this phone.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.tertiary,
        )
      }
      if (busy && !installed) {
        Text(
          "Another app is using memory right now. Close it before starting for the best result.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.tertiary,
        )
      }
      // The action area is the same slot whether this model is downloading or idle, so one row can
      // never show two Download buttons.
      when {
        installed -> Unit
        info?.state == WorkInfo.State.RUNNING -> Running(m, info)
        inProgress -> Waiting(m, info, prefs)
        else -> Idle(m = m, info = info, partBytes = partBytes, free = free, onStart = onStart, onDeletePart = onDeletePart)
      }
    }
  }
}

/** Nothing on this phone fits. Say which number is short, and what to do about it. */
@Composable
private fun EmptyFit(ram: Long, free: Long) {
  SoftCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.size(12.dp))
        Text("Not enough room for a model", style = MaterialTheme.typography.titleSmall)
      }
      Text(
        "Jonaki AI's smallest model needs about ${formatBytes(ChatModel.E2B.minTotalRamBytes)} of memory, " +
          "and this phone has ${formatBytes(ram)} in total, with ${formatBytes(free)} of storage.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        "This screen only offers models the phone can run, so nothing can be installed here. " +
          "On a phone with more memory, come back and the model will appear.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun Pill(text: String, color: Color) {
  Box(
    Modifier
      .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
      .padding(horizontal = 10.dp, vertical = 4.dp),
  ) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
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
    progress = { fraction.coerceIn(0f, 1f) },
    modifier = Modifier.fillMaxWidth().height(10.dp),
    strokeCap = StrokeCap.Round,
    gapSize = 0.dp,
    drawStopIndicator = {},
  )
}

/**
 * Live progress for [m]. Every number here comes from [m], never from the default model: the previous
 * version divided E4B's bytes by E2B's size and paused E2B's work by name.
 */
@Composable
private fun Running(m: ChatModel, info: WorkInfo) {
  val context = LocalContext.current
  val p = info.progress
  if (p.getString(DL.KEY_PHASE) == DL.PHASE_VERIFYING) {
    Text("Checking the file is not damaged…", style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(Modifier.fillMaxWidth().height(10.dp), strokeCap = StrokeCap.Round)
    return
  }
  val bytes = p.getLong(DL.KEY_BYTES, ModelInfo.partFile(context, m).length())
  val rate = p.getLong(DL.KEY_RATE, 0)
  val left = p.getLong(DL.KEY_LEFT_MS, -1)
  val pct = bytes.toFloat() / m.SIZE_BYTES
  Row(verticalAlignment = Alignment.Bottom) {
    Text("${(pct * 100).toInt()}%", style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.size(12.dp))
    Text(
      "${formatBytes(bytes)} of ${formatBytes(m.SIZE_BYTES)}",
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
  OutlinedButton(onClick = { DL.pause(context, m.name) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Pause") }
}

@Composable
private fun Waiting(m: ChatModel, info: WorkInfo?, prefs: Prefs) {
  val context = LocalContext.current
  val part = ModelInfo.partFile(context, m).length()
  Progress(part.toFloat() / m.SIZE_BYTES)
  Text(
    when {
      (info?.runAttemptCount ?: 0) > 0 -> "Connection lost. It will carry on by itself when the network is back…"
      prefs.wifiOnly -> "Waiting for Wi-Fi…"
      else -> "Waiting for internet…"
    },
    style = MaterialTheme.typography.bodyMedium,
  )
  OutlinedButton(onClick = { DL.pause(context, m.name) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Pause") }
}

@Composable
private fun Idle(m: ChatModel, info: WorkInfo?, partBytes: Long, free: Long, onStart: () -> Unit, onDeletePart: () -> Unit) {
  if (info?.state == WorkInfo.State.FAILED) {
    Text(info.outputData.getString(DL.KEY_ERROR) ?: "Download failed.", color = MaterialTheme.colorScheme.error)
  }
  val enoughSpace = free >= ModelInfo.bytesNeeded(LocalContext.current, m)
  if (!enoughSpace) {
    Text("Not enough free space. Free up some space, then come back.", color = MaterialTheme.colorScheme.error)
  }
  val paused = partBytes > 0
  if (paused) {
    Progress(partBytes.toFloat() / m.SIZE_BYTES)
    Text("Paused at ${formatBytes(partBytes)}", style = MaterialTheme.typography.bodyMedium)
  }
  Button(onClick = onStart, enabled = enoughSpace, modifier = Modifier.fillMaxWidth().height(48.dp)) {
    Icon(Icons.Rounded.CloudDownload, null)
    Spacer(Modifier.size(10.dp))
    Text(
      when {
        paused -> "Resume download"
        info?.state == WorkInfo.State.FAILED -> "Try again"
        else -> "Download ${formatBytes(m.SIZE_BYTES)}"
      },
      style = MaterialTheme.typography.titleSmall,
    )
  }
  if (paused) {
    TextButton(onClick = onDeletePart, modifier = Modifier.fillMaxWidth()) { Text("Cancel and delete") }
  }
}
