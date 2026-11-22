package com.offlinegemma.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class Notice(val name: String, val what: String, val license: String, val url: String)

private val NOTICES = listOf(
  Notice("Gemma 4 E2B", "The AI model (Google DeepMind), LiteRT-LM build from litert-community on Hugging Face.", "Apache License 2.0", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"),
  Notice("LiteRT-LM", "Runs the model on the phone (Google AI Edge).", "Apache License 2.0", "https://github.com/google-ai-edge/LiteRT-LM"),
  Notice("Tesseract OCR", "Reads the text in pictures on the phone (Bengali and English).", "Apache License 2.0", "https://github.com/tesseract-ocr/tesseract"),
  Notice("Tesseract language data", "Bengali and English trained data for the picture reader.", "Apache License 2.0", "https://github.com/tesseract-ocr/tessdata_best"),
  Notice("Google AI Edge Gallery", "Ideas for loading the model and the GPU → CPU fallback order.", "Apache License 2.0", "https://github.com/google-ai-edge/gallery"),
  Notice("Android Jetpack (Compose, Material 3, WorkManager, Splash Screen)", "Screens, design and the background download.", "Apache License 2.0", "https://developer.android.com/jetpack"),
  Notice("Material Symbols icons", "Icons.", "Apache License 2.0", "https://fonts.google.com/icons"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
  val uri = LocalUriHandler.current
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("About") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
      )
    },
  ) { pad ->
    Column(
      Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BrandMark(88.dp)
        Text("Jonaki AI", style = MaterialTheme.typography.headlineSmall)
        Text("জোনাকি · firefly", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Why “Jonaki”?", style = MaterialTheme.typography.titleMedium)
          Text(
            "Jonaki (জোনাকি) is Bengali for firefly. A firefly makes its own light in the dark — no power line, " +
              "no outside help. Small, but bright enough to find your way. This app is the same: its AI glows " +
              "right here on your phone, even when there is no internet.",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
      SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Your privacy", style = MaterialTheme.typography.titleMedium)
          Text(
            "Jonaki AI runs on this phone. Your chats, photos, files and voice never leave it. " +
              "The only exceptions: downloading the model once, and web search when you or Jonaki AI use it " +
              "(the search words go to DuckDuckGo, and pages Jonaki AI reads are fetched from their websites). " +
              "You can turn web search off in Settings.",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
      Text("Open-source licences", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
      for (n in NOTICES) {
        SoftCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp)) {
            Text(n.name, style = MaterialTheme.typography.titleSmall)
            Text(n.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(n.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = { runCatching { uri.openUri(n.url) } }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Website") }
          }
        }
      }
      Text(
        "Licensed under the Apache License, Version 2.0. You may not use these files except in compliance with " +
          "the License. Software distributed under the License is distributed on an \"AS IS\" BASIS, WITHOUT " +
          "WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
      )
      TextButton(onClick = { runCatching { uri.openUri("https://www.apache.org/licenses/LICENSE-2.0") } }) { Text("Read the Apache License 2.0") }
      Spacer(Modifier.height(16.dp))
    }
  }
}
