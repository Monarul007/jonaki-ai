package com.offlinegemma.app

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.Locale

private const val SAMPLE_EN = "Hi! I'm your study buddy. Let's learn something new together."
private const val SAMPLE_BN = "হ্যালো! আমি আপনার পড়াশোনার সঙ্গী। চলুন একসাথে নতুন কিছু শিখি।"

/** "Natural" for the best voices; Android rates voices 100–500. */
private fun qualityWord(v: Voice) = when {
  v.quality >= Voice.QUALITY_VERY_HIGH -> "Natural"
  v.quality >= Voice.QUALITY_HIGH -> "Good"
  v.quality >= Voice.QUALITY_NORMAL -> "Basic"
  else -> "Robotic"
}

/** "English (United States) · Natural · voice tpf" instead of "en-us-x-tpf-local". */
fun voiceLabel(v: Voice): String {
  val id = Regex("-x-([a-z]+)").find(v.name)?.groupValues?.get(1)?.uppercase()
    ?: if (v.name.endsWith("-language")) "standard" else v.name.substringAfterLast('-')
  return "${v.locale.getDisplayName(Locale.ENGLISH)} · ${qualityWord(v)} · voice $id"
}

/** Languages shown in the picker: English, Bengali and the phone's own language. */
private fun relevant(v: Voice) = v.locale.language in setOf("en", "bn", Locale.getDefault().language)

/**
 * Voice picker: Automatic (best voice, matches the language of each sentence), the best voices,
 * more voices, a speed slider and "Hear it". All are phone voices: offline, nothing to download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(app: App, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val prefs = app.prefs
  val speaker = app.speaker
  val all = remember { speaker.voices().filter(::relevant) }
  // Best = the top voice of each accent (en-US, en-GB, bn-BD…); the rest go under "More voices".
  val best = remember(all) { all.groupBy { it.locale }.values.mapNotNull { g -> g.maxByOrNull { it.quality } }.sortedByDescending { it.quality } }
  val more = remember(all, best) { all.filter { it !in best } }

  fun hear() {
    val v = prefs.voice?.let { n -> all.firstOrNull { it.name == n } }
    speaker.readAloud(-1, if (v?.locale?.language == "bn") SAMPLE_BN else SAMPLE_EN)
  }

  ModalBottomSheet(onDismissRequest = { speaker.stop(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
    Column(Modifier.padding(horizontal = 20.dp)) {
      Text("Voice", style = MaterialTheme.typography.titleLarge)
      Text(
        "Phone voices: they work offline and need no download.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.size(12.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Speed %.1f×".format(prefs.speechRate), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(88.dp))
        Slider(
          value = prefs.speechRate.coerceIn(0.7f, 1.4f),
          onValueChange = { prefs.speechRate = (it * 10).toInt() / 10f },
          valueRange = 0.7f..1.4f,
          steps = 6,
          modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        FilledTonalButton(onClick = { hear() }) {
          Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text("Hear it")
        }
      }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
      item {
        VoiceRow(
          title = "Automatic",
          subtitle = "Best voice for each sentence; Bengali sentences use a Bengali voice",
          selected = prefs.voice == null,
        ) { prefs.voice = null; speaker.applyVoice() }
      }
      if (!speaker.hasBengaliVoice()) {
        item {
          Row(
            Modifier.fillMaxWidth().clickable {
              runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(16.dp))
            Column {
              Text("Get a Bengali voice", style = MaterialTheme.typography.bodyLarge)
              Text("Opens the phone's voice settings to download it (free, once).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
      item { Header("Best voices") }
      items(best.size) { i ->
        val v = best[i]
        VoiceRow(voiceLabel(v).substringBefore(" · voice"), "voice " + voiceLabel(v).substringAfter("· voice "), prefs.voice == v.name) {
          prefs.voice = v.name; speaker.applyVoice(); hear()
        }
      }
      if (more.isNotEmpty()) {
        item { Header("More voices") }
        items(more.size) { i ->
          val v = more[i]
          VoiceRow(voiceLabel(v).substringBefore(" · voice"), "voice " + voiceLabel(v).substringAfter("· voice "), prefs.voice == v.name) {
            prefs.voice = v.name; speaker.applyVoice(); hear()
          }
        }
      }
    }
  }
}

@Composable
private fun Header(text: String) {
  Text(text, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, onPick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onPick).heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    RadioButton(selected = selected, onClick = null)
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
