package com.offlinegemma.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: App, onBack: () -> Unit, onAbout: () -> Unit, onModelDeleted: () -> Unit) {
  val prefs = app.prefs
  val chat = app.chat
  val context = LocalContext.current
  var confirmModel by remember { mutableStateOf(false) }
  var confirmChats by remember { mutableStateOf(false) }
  val chatBytes = remember(chat.chats.size) { app.store.bytesUsed() }
  val ram = remember { ModelInfo.totalRamBytes(context) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
      )
    },
  ) { pad ->
    Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
      Section("Model")
      InfoRow(Icons.Rounded.Memory, ModelInfo.NAME, if (app.gemma.isLoaded) "Running on: ${app.gemma.mode}" else "Not loaded")
      if (ram < ModelInfo.MIN_RAM_BYTES) {
        InfoRow(Icons.Rounded.Speed, "This phone is on the slow side", "${formatBytes(ram)} of memory. Answers come a few words a second, and long chats are summarised sooner.")
      }

      Section("Answers")
      SwitchRow(Icons.Rounded.Build, "Tools (agent mode)", "Gemma may search the web and test code to give better answers.", prefs.agent) { prefs.agent = it }
      SwitchRow(Icons.Rounded.Language, "Web search", "The only thing that uses the internet. Your question is sent to DuckDuckGo.", prefs.webSearch) { prefs.webSearch = it }
      SwitchRow(
        Icons.Rounded.Science, "Interactive demos",
        "Live science demos with sliders in tutor answers." + if (ram < ModelInfo.MIN_RAM_BYTES) " Slow on this phone (a few minutes each)." else "",
        prefs.demos,
      ) { prefs.demos = it }

      Section("Voice")
      VoicePicker(app)
      Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Rounded.Speed, null, tint = MaterialTheme.colorScheme.primary)
          Spacer(Modifier.size(16.dp))
          Column(Modifier.weight(1f)) {
            Text("Speech speed", style = MaterialTheme.typography.bodyLarge)
            Text("%.1f×".format(prefs.speechRate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          TextButton(onClick = { app.speaker.readAloud(-1, "Hi! This is how I sound when I read my answers.") }) { Text("Try it") }
        }
        Slider(
          value = prefs.speechRate,
          onValueChange = { prefs.speechRate = (it * 10).toInt() / 10f },
          valueRange = 0.5f..2f,
          steps = 14,
          modifier = Modifier.padding(start = 40.dp),
        )
      }

      Section("Look")
      Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
          val options = listOf(ThemeChoice.SYSTEM to "Auto", ThemeChoice.LIGHT to "Light", ThemeChoice.DARK to "Dark")
          options.forEachIndexed { i, (choice, label) ->
            SegmentedButton(
              selected = prefs.theme == choice,
              onClick = { prefs.theme = choice },
              shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
          }
        }
      }
      SwitchRow(Icons.Rounded.Palette, "Wallpaper colours", "Use your phone's colours instead of the app's.", prefs.dynamicColor) { prefs.dynamicColor = it }

      Section("Storage")
      InfoRow(Icons.Rounded.SdStorage, "Model file", formatBytes(app.gemma.modelFile.length()))
      InfoRow(Icons.Rounded.SdStorage, "Saved chats", "${chat.chats.size} chats · ${formatBytes(chatBytes)}")
      ActionRow(Icons.Rounded.DeleteForever, "Delete all chats", enabled = chat.chats.isNotEmpty() && !chat.busy) { confirmChats = true }
      ActionRow(Icons.Rounded.DeleteForever, "Delete model", "Frees ${formatBytes(ModelInfo.SIZE_BYTES)}. You'll need to download it again.", enabled = !chat.busy) { confirmModel = true }

      Section("About")
      ActionRow(Icons.Rounded.AutoAwesome, "About and licences", "Version ${BuildConfig.VERSION_NAME}", onClick = onAbout)
    }
  }

  if (confirmChats) {
    AlertDialog(
      onDismissRequest = { confirmChats = false },
      title = { Text("Delete all chats?") },
      text = { Text("All ${chat.chats.size} saved chats and their pictures will be removed. This can't be undone.") },
      confirmButton = { TextButton(onClick = { chat.deleteAllChats(); confirmChats = false }) { Text("Delete all") } },
      dismissButton = { TextButton(onClick = { confirmChats = false }) { Text("Keep") } },
    )
  }
  if (confirmModel) {
    AlertDialog(
      onDismissRequest = { confirmModel = false },
      title = { Text("Delete the model?") },
      text = { Text("This frees ${formatBytes(ModelInfo.SIZE_BYTES)}. Your chats stay, but you need to download the model again to use Gemma.") },
      confirmButton = {
        TextButton(onClick = {
          confirmModel = false
          chat.unloadModel()
          ModelInfo.deleteAll(context)
          onModelDeleted()
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { confirmModel = false }) { Text("Keep") } },
    )
  }
}

@Composable
private fun Section(title: String) {
  Text(
    title,
    Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
  )
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, body: String) {
  Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.size(16.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth()
      .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
      .heightIn(min = 56.dp)
      .padding(horizontal = 24.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.size(16.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.size(12.dp))
    Switch(checked = checked, onCheckedChange = null)
  }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, body: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
  val alpha = if (enabled) 1f else 0.4f
  Row(
    Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    Spacer(Modifier.size(16.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
      body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
  }
}

@Composable
private fun VoicePicker(app: App) {
  var open by remember { mutableStateOf(false) }
  val voices = remember(open) { app.speaker.voices() }
  val current = app.prefs.voice?.let { v -> voices.firstOrNull { it.name == v } }
  Column {
    ActionRow(
      Icons.Rounded.RecordVoiceOver,
      "Voice",
      current?.let { "${it.locale.displayName} · ${it.name.substringAfterLast('#').take(28)}" } ?: "Phone default",
    ) { open = true }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      DropdownMenuItem(text = { Text("Phone default") }, onClick = { app.prefs.voice = null; app.speaker.applyVoice(); open = false })
      for (v in voices.take(60)) {
        DropdownMenuItem(
          text = { Text("${v.locale.displayName} · ${v.name.substringAfterLast('#').take(28)}") },
          onClick = { app.prefs.voice = v.name; app.speaker.applyVoice(); open = false },
        )
      }
    }
  }
}
