package com.offlinegemma.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * One chat bubble.
 * [display] is what the user sees; [text] is what the model got/gave (may include hidden tutor rules).
 */
private data class ChatLine(
  val id: Long,
  val fromUser: Boolean,
  val text: String,
  val display: String = text,
  val done: Boolean = true,
  val demoError: String? = null,
  val autoFixing: Boolean = false,
  /** The user's real question (typed or a NEXT question); used by "Check facts" to search the web. */
  val topic: String? = null,
)

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val gemma = (application as App).gemma
    setContent {
      MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
          val context = LocalContext.current
          var hasModel by remember { mutableStateOf(ModelInfo.isDownloaded(context)) }
          if (hasModel) {
            ChatScreen(gemma, onDeleteModel = {
              gemma.close()
              ModelInfo.deleteAll(context)
              hasModel = false
            })
          } else {
            DownloadScreen(onModelReady = { hasModel = true })
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatScreen(gemma: GemmaEngine, onDeleteModel: () -> Unit) {
  val context = LocalContext.current
  val prefs = remember { Prefs(context) }
  var demos by remember { mutableStateOf(prefs.demos(context)) }
  var status by remember { mutableStateOf("Loading model…") }
  var ready by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var input by remember { mutableStateOf("") }
  val lines = remember { mutableStateListOf<ChatLine>() }
  var nextId by remember { mutableStateOf(0L) }
  val scope = rememberCoroutineScope()
  var job by remember { mutableStateOf<Job?>(null) }
  val listState = rememberLazyListState()
  var confirmDelete by remember { mutableStateOf(false) }
  // Auto-fix happens at most once per message the user sends (same as desktop).
  var autoFixes by remember { mutableIntStateOf(0) }
  var pendingFix by remember { mutableStateOf<String?>(null) }

  fun newId() = nextId++
  fun indexOf(id: Long) = lines.indexOfFirst { it.id == id }
  fun update(id: Long, f: (ChatLine) -> ChatLine) {
    val i = indexOf(id)
    if (i >= 0) lines[i] = f(lines[i])
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Delete model?") },
      text = { Text("This frees ${formatBytes(ModelInfo.SIZE_BYTES)}. You will need to download it again to chat.") },
      confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteModel() }) { Text("Delete") } },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
  }

  LaunchedEffect(Unit) {
    if (!gemma.modelFile.exists()) {
      status = "Model not found.\nPut it at:\n${gemma.modelFile.path}"
      return@LaunchedEffect
    }
    val start = System.currentTimeMillis()
    status = "Loading model… (first time can take a minute)"
    status = try {
      val mode = gemma.load()
      ready = true
      val secs = (System.currentTimeMillis() - start) / 1000
      lines.add(ChatLine(newId(), false, "Hello, model loaded! ($mode, ${secs}s). Ask me anything."))
      // Developer builds only: `adb shell am start ... --es test_demo ok|broken` drops a canned
      // tutor answer into the chat, to test demos without waiting minutes for the model.
      val activity = context as? android.app.Activity
      val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
      activity?.intent?.getStringExtra("test_demo")?.takeIf { debuggable }?.let { kind ->
        activity.intent.removeExtra("test_demo")
        lines.add(ChatLine(newId(), true, "Show me a pendulum demo", "Show me a pendulum demo (test)"))
        lines.add(ChatLine(newId(), false, if (kind == "net") TestDemos.netProbe() else TestDemos.reply(kind == "broken")))
      }
      "Ready · $mode"
    } catch (t: Throwable) {
      "Could not load model: ${t.message}"
    }
  }

  LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) {
    if (lines.isNotEmpty() && (busy || lines.last().fromUser)) listState.scrollToItem(lines.lastIndex)
  }

  /** Shows [display] as the user's bubble, sends [modelText] to Gemma, streams the answer. */
  fun sendToModel(display: String, modelText: String, topic: String? = null) {
    lines.add(ChatLine(newId(), true, modelText, display, topic = topic))
    val answerId = newId()
    lines.add(ChatLine(answerId, false, "", done = false))
    busy = true
    job = scope.launch {
      val start = System.currentTimeMillis()
      gemma.send(modelText)
        .catch { e ->
          val hint = if ("token" in (e.message ?: "").lowercase()) " The chat may be full — tap New chat." else ""
          update(answerId) { it.copy(text = it.text + "\n[error: ${e.message}]$hint") }
        }
        .collect { piece -> update(answerId) { it.copy(text = it.text + piece) } }
      update(answerId) { it.copy(done = true) }
      val secs = (System.currentTimeMillis() - start) / 1000.0
      status = "Ready · ${gemma.mode} · last answer ${"%.1f".format(secs)}s"
      busy = false
    }
  }

  fun sendTyped(text: String) {
    if (text.isBlank() || !ready || busy) return
    autoFixes = 0
    sendToModel(text, Tutor.wrap(text, demos), topic = text)
  }

  fun sendButton(prompt: String, label: String) {
    if (!ready || busy) return
    sendToModel(label, Tutor.wrap(prompt, demos))
  }

  fun checkFacts(answerId: Long) {
    if (!ready || busy) return
    val i = indexOf(answerId)
    val question = lines.subList(0, i.coerceAtLeast(0)).lastOrNull { it.topic != null }?.topic
    if (question == null) {
      lines.add(ChatLine(newId(), false, "✅ Check facts: type a question first, then I can check the answer."))
      return
    }
    busy = true
    scope.launch {
      val results = runCatching { Tutor.webSearch(question.take(150)) }
      busy = false
      results.onSuccess { sendToModel(Tutor.CHECK_FACTS, Tutor.checkFactsPrompt(it)) }
        .onFailure {
          lines.add(ChatLine(newId(), false, "✅ Check facts needs the internet. (${it.message})"))
        }
    }
  }

  fun onDemoError(answerId: Long, error: String) {
    val line = lines.getOrNull(indexOf(answerId)) ?: return
    if (line.demoError != null) return // already handled (the WebView may reload when scrolled)
    // Fix on its own only while this reply is still the newest one; never interrupt a later chat (a quiz).
    val newest = lines.lastOrNull { !it.fromUser }?.id == answerId
    val auto = newest && autoFixes < 1
    update(answerId) { it.copy(demoError = error, autoFixing = auto) }
    if (auto) {
      autoFixes++
      pendingFix = Tutor.fixPrompt(error, Tutor.firstDemo(line.text))
    }
  }

  // Sends the automatic fix as soon as Gemma is free.
  LaunchedEffect(pendingFix, busy) {
    val prompt = pendingFix ?: return@LaunchedEffect
    if (!busy) {
      pendingFix = null
      // Sent without tutor rules: with Demos off they would say "no demo" and fight the fix request.
      if (ready) sendToModel("🔧 Fix the demo (automatic)", prompt)
    }
  }

  Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp)) {
    Text(status, style = MaterialTheme.typography.labelMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = { gemma.newChat(); lines.clear(); autoFixes = 0 }, enabled = ready && !busy) { Text("New chat") }
      TextButton(onClick = { demos = !demos; prefs.setDemos(demos) }) { Text(if (demos) "Demos: On" else "Demos: Off") }
      TextButton(onClick = { confirmDelete = true }, enabled = !busy) { Text("Delete model") }
    }
    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(lines, key = { it.id }) { line ->
        if (line.fromUser) {
          UserBubble(line.display)
        } else {
          val newest = line.id == lines.lastOrNull { !it.fromUser }?.id
          AnswerBubble(
            line = line,
            onDemoError = { onDemoError(line.id, it) },
            onFix = { if (ready && !busy) sendToModel("🔧 Fix this demo", Tutor.fixPrompt(line.demoError ?: "", Tutor.firstDemo(line.text))) },
            canAct = ready && !busy,
          )
          if (newest && line.done && !busy && line.id != 0L && Tutor.wantsFollowups(line.text)) {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.padding(top = 4.dp),
            ) {
              for (q in Tutor.nextQuestions(line.text)) {
                AssistChip(onClick = { sendTyped(q) }, label = { Text(q) })
              }
              for ((label, prompt) in Tutor.BUTTONS) {
                AssistChip(onClick = { sendButton(prompt, label) }, label = { Text(label) })
              }
              AssistChip(onClick = { checkFacts(line.id) }, label = { Text(Tutor.CHECK_FACTS) })
            }
          }
        }
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text(if (ready) "Type a message" else "Waiting for model…") },
        enabled = ready,
      )
      if (busy) {
        Button(onClick = { gemma.stop(); job?.cancel(); busy = false }, modifier = Modifier.padding(start = 8.dp)) { Text("Stop") }
      } else {
        Button(
          onClick = { val t = input.trim(); input = ""; sendTyped(t) },
          enabled = ready && input.isNotBlank(),
          modifier = Modifier.padding(start = 8.dp),
        ) { Text("Send") }
      }
    }
  }
}

@Composable
private fun UserBubble(text: String) {
  Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    Text(
      text = text,
      modifier = Modifier
        .clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.primaryContainer)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    )
  }
}

@Composable
private fun AnswerBubble(line: ChatLine, onDemoError: (String) -> Unit, onFix: () -> Unit, canAct: Boolean) {
  val parts = remember(line.text) { Tutor.split(line.text) }
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (parts.isEmpty()) Text("…")
    for (part in parts) {
      when (part) {
        is Tutor.Part.Text -> Text(part.text)
        is Tutor.Part.Code -> Text(
          part.body,
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        )
        is Tutor.Part.Demo -> when {
          !part.closed -> Text(
            "⚙️ Building an interactive demo… (${part.html.lines().size} lines so far)",
            style = MaterialTheme.typography.bodySmall,
          )
          !line.done -> Text("⚙️ Demo ready — it appears when the answer finishes.", style = MaterialTheme.typography.bodySmall)
          else -> {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
              DemoView(part.html, onError = onDemoError)
            }
            line.demoError?.let { err ->
              Text(
                "⚠ The demo hit an error: $err" + if (line.autoFixing) " — asking for a fix…" else "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
              )
              if (!line.autoFixing) {
                OutlinedButton(onClick = onFix, enabled = canAct) { Text("🔧 Fix this demo") }
              }
            }
          }
        }
      }
    }
  }
}
