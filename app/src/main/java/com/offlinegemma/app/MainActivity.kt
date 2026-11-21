package com.offlinegemma.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.offlinegemma.app.ui.AppTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

enum class Screen { SETUP, CHAT, SETTINGS, ABOUT }

class MainActivity : ComponentActivity() {
  private val screenState = mutableStateOf<Screen?>(null)

  /**
   * Developer builds only: test commands sent from the computer with
   * `adb shell am start -n com.offlinegemma.app/.MainActivity --es <name> <value>`
   * (some phones block simulated taps, so tests drive the app this way).
   */
  private fun handleTestCommands(intent: android.content.Intent?) {
    if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 || intent == null) return
    val app = application as App
    intent.getStringExtra("test_demo")?.let { app.chat.injectTest(it) }
    intent.getStringExtra("go")?.let { runCatching { screenState.value = Screen.valueOf(it.uppercase()) } }
    intent.getStringExtra("theme")?.let { runCatching { app.prefs.theme = ThemeChoice.valueOf(it.uppercase()) } }
    if (intent.hasExtra("onboarded")) app.prefs.onboarded = intent.getBooleanExtra("onboarded", true)
    if (intent.hasExtra("download")) ModelDownloadWorker.start(this, app.prefs.wifiOnly)
    intent.getStringExtra("ask")?.let { q ->
      // Waits until Gemma is loaded and free, then sends.
      lifecycleScope.launch {
        while (!app.chat.canSend()) kotlinx.coroutines.delay(500)
        app.chat.sendTyped(q)
      }
    }
    if (intent.hasExtra("new_chat")) app.chat.newChat()
    intent.getStringExtra("open_chat")?.let { idx -> app.chat.chats.getOrNull(idx.toIntOrNull() ?: 0)?.let { app.chat.openChat(it.id) } }
    intent.replaceExtras(Bundle())
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    handleShare(intent)
    handleTestCommands(intent)
  }

  /** Something shared into the app from another app: pictures and files are attached, text goes in the box. */
  private fun handleShare(intent: android.content.Intent?) {
    val chat = (application as App).chat
    when (intent?.action) {
      android.content.Intent.ACTION_SEND -> {
        val uri = androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
        if (uri != null) chat.addFile(uri)
        intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { chat.sharedText = it }
      }
      android.content.Intent.ACTION_SEND_MULTIPLE -> {
        androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
          ?.forEach { chat.addFile(it) }
      }
      else -> return
    }
    intent.action = null
    screenState.value = if (ModelInfo.isDownloaded(this)) Screen.CHAT else Screen.SETUP
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val app = application as App

    if (savedInstanceState == null) handleShare(intent)
    handleTestCommands(intent)

    setContent {
      AppTheme(app.prefs.theme, app.prefs.dynamicColor) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
          var screen by rememberSaveable { mutableStateOf(if (ModelInfo.isDownloaded(this)) Screen.CHAT else Screen.SETUP) }
          // A test command asked for another screen.
          LaunchedEffect(screenState.value) { screenState.value?.let { screen = it; screenState.value = null } }
          LaunchedEffect(screen) { if (screen == Screen.CHAT && !ModelInfo.isDownloaded(this@MainActivity)) screen = Screen.SETUP }
          BackHandler(enabled = screen == Screen.SETTINGS || screen == Screen.ABOUT) {
            screen = if (screen == Screen.ABOUT) Screen.SETTINGS else Screen.CHAT
          }
          AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
            when (s) {
              Screen.SETUP -> OnboardingScreen(app.prefs, onModelReady = { screen = Screen.CHAT })
              Screen.CHAT -> ChatScreen(app.chat, app.speaker, onSettings = { screen = Screen.SETTINGS }, onAbout = { screen = Screen.ABOUT }, onTalk = { app.chat.notice("🎙️ Talk mode is coming in the next step.") })
              Screen.SETTINGS -> SettingsScreen(app, onBack = { screen = Screen.CHAT }, onAbout = { screen = Screen.ABOUT }, onModelDeleted = { screen = Screen.SETUP })
              Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.SETTINGS })
            }
          }
        }
      }
    }
  }
}
