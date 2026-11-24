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
  private val talking = mutableStateOf(false)

  private val micPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
    if (ok) beginTalk() else (application as App).chat.notice("🎙️ Talk mode needs the microphone. You can allow it in the phone's Settings.")
  }

  /** A picture shared into the app waits here while the photo permission is requested. */
  private var pendingShare: List<android.net.Uri> = emptyList()
  private val sharePermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
    val uris = pendingShare
    pendingShare = emptyList()
    if (ok) uris.forEach { (application as App).chat.addImage(it) }
    else if (uris.isNotEmpty()) (application as App).chat.notice("Allow photo access to share pictures, or use the + button.")
  }

  private fun startTalk() {
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) beginTalk()
    else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
  }

  private fun beginTalk() {
    val app = application as App
    app.speaker.stop()
    app.talk.start()
    talking.value = true
  }

  private fun stopTalk() {
    (application as App).talk.stop()
    talking.value = false
  }

  override fun onStop() {
    super.onStop()
    // Never keep listening when the app is not on screen.
    if (talking.value) stopTalk()
  }

  override fun onResume() {
    super.onResume()
    // The process can be killed while another app (camera, phone call) is in front. Loading from
    // the Activity - not only from the chat screen - means coming back always wakes the model
    // instead of sitting on "Waking up" forever. loadModel() is safe to call repeatedly.
    val app = application as App
    if (ModelInfo.isDownloaded(this)) app.chat.loadModel()
  }

  /**
   * Developer builds only: test commands sent from the computer with
   * `adb shell am start -n com.offlinegemma.app/.MainActivity --es <name> <value>`
   * (some phones block simulated taps, so tests drive the app this way).
   */
  private fun handleTestCommands(intent: android.content.Intent?) {
    if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 || intent == null) return
    val app = application as App
    intent.getStringExtra("test_demo")?.let { app.chat.injectTest(it) }
    // SPIKE: `--ez adk true` runs the agent loop through ADK (LiteRT-LM native tool calling).
    if (intent.hasExtra("adk")) {
      AdkAgent.enabled = intent.getBooleanExtra("adk", false)
      android.util.Log.i("AdkAgent", "enabled=${AdkAgent.enabled}")
    }
    // Perfect-reading test: `--es ocr_override /sdcard/Download/page.txt` (empty value = off).
    intent.getStringExtra("ocr_override")?.let { src ->
      Ocr.overrideText = if (src.isBlank()) null else runCatching { java.io.File(src).readText() }
        .onFailure { android.util.Log.w("Ocr", "ocr_override: cannot read $src", it) }.getOrNull()
      android.util.Log.i("Ocr", "ocr_override set: ${Ocr.overrideText?.length ?: 0} chars")
    }
    // OCR only, no model: `--es ocr_test /sdcard/Pictures/page.png` (copied in, then read; see logcat Ocr).
    intent.getStringExtra("ocr_test")?.let { src ->
      lifecycleScope.launch {
        val copy = java.io.File(cacheDir, "ocr_test.png")
        runCatching { java.io.File(src).copyTo(copy, overwrite = true) }
          .onFailure { android.util.Log.w("Ocr", "ocr_test: cannot read $src", it) }
        android.util.Log.i("Ocr", "ocr_test result:\n" + Ocr.readText(this@MainActivity, copy.path))
      }
    }
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
    // Talk-mode echo test: a typed question whose reply is spoken, as if it was said out loud.
    intent.getStringExtra("talk_say")?.let { q ->
      lifecycleScope.launch {
        while (!app.chat.canSend()) kotlinx.coroutines.delay(500)
        app.chat.sendTyped(q, voice = true)
      }
    }
    if (intent.hasExtra("talk")) { if (intent.getStringExtra("talk") == "off") stopTalk() else startTalk() }
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
    val isImage = intent?.type?.startsWith("image/") == true
    when (intent?.action) {
      android.content.Intent.ACTION_SEND -> {
        val uri = androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
        if (uri != null) {
          // Route by the intent's own type: without photo access getType(uri) can be null,
          // which previously sent a shared picture down the document path and failed.
          if (isImage) shareImages(listOf(uri)) else chat.addFile(uri)
        }
        intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { chat.sharedText = it }
      }
      android.content.Intent.ACTION_SEND_MULTIPLE -> {
        val uris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(
          intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java,
        ) ?: emptyList()
        if (isImage) shareImages(uris) else uris.forEach { chat.addFile(it) }
      }
      else -> return
    }
    intent.action = null
    screenState.value = if (ModelInfo.isDownloaded(this)) Screen.CHAT else Screen.SETUP
  }

  /** Pictures shared from another app need photo access on Android 13+ before we can read them. */
  private fun shareImages(uris: List<android.net.Uri>) {
    if (uris.isEmpty()) return
    val permission =
      if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
      else android.Manifest.permission.READ_EXTERNAL_STORAGE
    if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
      uris.forEach { (application as App).chat.addImage(it) }
    } else {
      pendingShare = uris
      sharePermission.launch(permission)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val app = application as App

    if (savedInstanceState == null) handleShare(intent)
    handleTestCommands(intent)

    setContent {
      // Status bar icons follow the app's own theme choice (light icons on dark screens).
      val dark = when (app.prefs.theme) {
        ThemeChoice.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
      }
      androidx.compose.runtime.LaunchedEffect(dark) {
        val style = if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
      }
      AppTheme(app.prefs.theme, app.prefs.dynamicColor) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
          var screen by rememberSaveable { mutableStateOf(if (ModelInfo.isDownloaded(this)) Screen.CHAT else Screen.SETUP) }
          // A test command asked for another screen.
          LaunchedEffect(screenState.value) { screenState.value?.let { screen = it; screenState.value = null } }
          LaunchedEffect(screen) { if (screen == Screen.CHAT && !ModelInfo.isDownloaded(this@MainActivity)) screen = Screen.SETUP }
          BackHandler(enabled = screen == Screen.SETTINGS || screen == Screen.ABOUT) {
            screen = if (screen == Screen.ABOUT) Screen.SETTINGS else Screen.CHAT
          }
          if (talking.value) {
            BackHandler { stopTalk() }
            TalkScreen(app.talk, onClose = { stopTalk() })
            return@Surface
          }
          AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
            when (s) {
              Screen.SETUP -> OnboardingScreen(app.prefs, onModelReady = { screen = Screen.CHAT })
              Screen.CHAT -> ChatScreen(app.chat, app.speaker, onSettings = { screen = Screen.SETTINGS }, onAbout = { screen = Screen.ABOUT }, onTalk = { startTalk() })
              Screen.SETTINGS -> SettingsScreen(app, onBack = { screen = Screen.CHAT }, onAbout = { screen = Screen.ABOUT }, onModelDeleted = { screen = Screen.SETUP })
              Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.SETTINGS })
            }
          }
        }
      }
    }
  }
}
