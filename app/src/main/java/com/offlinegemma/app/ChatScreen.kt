package com.offlinegemma.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Calendar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chat: ChatController, speaker: Speaker, onSettings: () -> Unit, onAbout: () -> Unit, onTalk: () -> Unit) {
  val drawer = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  var input by rememberSaveable { mutableStateOf("") }
  var sheet by rememberSaveable { mutableStateOf(false) }
  // Text shared into the app from another app.
  LaunchedEffect(chat.sharedText) { chat.sharedText?.let { input = it; chat.sharedText = null } }
  val listState = rememberLazyListState()

  LaunchedEffect(Unit) { chat.loadModel() }

  // Follow the answer while it streams; jump to the newest message when one is added.
  val last = chat.lines.lastOrNull()
  LaunchedEffect(chat.lines.size, last?.text?.length, last?.done) {
    if (chat.lines.isNotEmpty()) listState.animateScrollToItem(chat.lines.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
  }

  ModalNavigationDrawer(
    drawerState = drawer,
    drawerContent = {
      ChatDrawer(
        chat = chat,
        onPick = { id -> chat.openChat(id); scope.launch { drawer.close() } },
        onNew = { chat.newChat(); scope.launch { drawer.close() } },
        onSettings = { scope.launch { drawer.close() }; onSettings() },
        onAbout = { scope.launch { drawer.close() }; onAbout() },
      )
    },
  ) {
    Scaffold(
      topBar = {
        CenterAlignedTopAppBar(
          navigationIcon = {
            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Rounded.Menu, "Chats and settings") }
          },
          title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
              val sub = chat.status ?: when {
                chat.loadError != null -> "Model failed to load"
                chat.loading -> "Waking up Gemma…"
                chat.ready -> "On-device · ${chat.gemma.mode.substringBefore(" +")}"
                else -> ""
              }
              if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          },
          actions = {
            IconButton(onClick = { chat.newChat() }, enabled = !chat.busy) { Icon(Icons.Rounded.Add, "New chat") }
          },
          colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
      },
      bottomBar = {
        Composer(
          chat = chat,
          text = input,
          onText = { input = it },
          onSend = { val t = input.trim(); input = ""; chat.sendTyped(t) },
          onTalk = onTalk,
          sheetOpen = sheet,
          onSheet = { sheet = it },
        )
      },
    ) { pad ->
      Box(Modifier.fillMaxSize().padding(pad)) {
        when {
          chat.loadError != null -> LoadError(chat.loadError!!, onRetry = { chat.loadModel() })
          chat.lines.isEmpty() -> EmptyState(loading = !chat.ready, onPrompt = { i ->
            when (i) {
              0 -> { input = "Solve it"; sheet = true }
              1 -> input = "Explain black holes with a demo"
              else -> onTalk()
            }
          })
          else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
          ) {
            items(chat.lines, key = { it.id }) { line ->
              MessageItem(chat, speaker, line)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MessageItem(chat: ChatController, speaker: Speaker, line: ChatLine) {
  when (line.who) {
    Who.USER -> UserBubble(line)
    Who.NOTICE -> NoticeLine(line.display)
    Who.MODEL -> {
      val newest = line.id == chat.lines.lastOrNull { it.who == Who.MODEL }?.id
      Answer(
        line = line,
        newest = newest,
        canAct = chat.canSend(),
        speaker = speaker,
        onDemoError = { chat.onDemoError(line.id, it) },
        onFix = { chat.fixDemo(line.id) },
        onRegenerate = { chat.regenerate() },
        onFollowup = { chat.sendTyped(it) },
        onButton = { label, prompt -> chat.sendButton(label, prompt) },
        onCheckFacts = { chat.checkFacts(line.id) },
      )
    }
  }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun UserBubble(line: ChatLine) {
  val extra = com.offlinegemma.app.ui.LocalExtraColors.current
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (line.attachments.isNotEmpty()) {
      androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) { line.attachments.forEach { AttachmentView(it) } }
    }
    if (line.display.isNotBlank()) Surface(
      color = extra.userBubble,
      contentColor = extra.onUserBubble,
      shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp),
      modifier = Modifier.widthIn(max = 320.dp),
    ) {
      Text(line.display, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge)
    }
  }
}

@Composable
private fun NoticeLine(text: String) {
  Text(
    text,
    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
  )
}

private val EXAMPLES = listOf(
  "📷" to "Paste a screenshot and say “solve it”",
  "🪐" to "Explain black holes with a demo",
  "🎙️" to "Talk to me",
)

@Composable
private fun EmptyState(loading: Boolean, onPrompt: (Int) -> Unit) {
  Column(
    Modifier.fillMaxSize().padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    BrandMark(96.dp)
    Spacer(Modifier.height(16.dp))
    Text(if (loading) "Waking up Gemma…" else "How can I help?", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
      if (loading) "Loading the model into memory. The first time can take up to two minutes."
      else "Everything stays on this phone. Ask anything, add a photo, or tap the mic to talk.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (loading) {
      Spacer(Modifier.height(20.dp))
      CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
    Spacer(Modifier.height(28.dp))
    Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      for ((i, example) in EXAMPLES.withIndex()) {
        val (emoji, text) = example
        Surface(
          onClick = { onPrompt(i) },
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
          Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
    }
  }
}

@Composable
private fun LoadError(message: String, onRetry: () -> Unit) {
  Column(
    Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Gemma could not start", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
      "The phone may be short on memory. Close other apps and try again.\n\n($message)",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    androidx.compose.material3.Button(onClick = onRetry, Modifier.height(48.dp)) { Text("Try again") }
  }
}

// ---------- drawer ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatDrawer(chat: ChatController, onPick: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
  var renaming by remember { mutableStateOf<ChatSummary?>(null) }
  var deleting by remember { mutableStateOf<ChatSummary?>(null) }

  ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
    Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      BrandMark(36.dp, animate = false)
      Spacer(Modifier.size(10.dp))
      Text("Offline Gemma", style = MaterialTheme.typography.titleLarge)
    }
    NavigationDrawerItem(
      label = { Text("New chat") },
      icon = { Icon(Icons.Rounded.Add, null) },
      selected = false,
      onClick = onNew,
      modifier = Modifier.padding(horizontal = 12.dp),
    )
    Text(
      "Chats",
      Modifier.padding(start = 28.dp, top = 16.dp, bottom = 6.dp),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(Modifier.weight(1f)) {
      if (chat.chats.isEmpty()) {
        item {
          Text(
            "Your saved chats appear here.",
            Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      items(chat.chats, key = { it.id }) { c ->
        var menu by remember { mutableStateOf(false) }
        Box {
          NavigationDrawerItem(
            label = {
              Column {
                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(relTime(c.updated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            },
            icon = { Icon(Icons.Rounded.ChatBubbleOutline, null) },
            badge = {
              IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${c.title}") }
            },
            selected = c.id == chat.chatId,
            onClick = { onPick(c.id) },
            modifier = Modifier.padding(horizontal = 12.dp).animateContentSize(),
          )
          DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; renaming = c })
            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; deleting = c })
          }
        }
      }
    }
    NavigationDrawerItem(
      label = { Text("Settings") },
      icon = { Icon(Icons.Rounded.Settings, null) },
      selected = false,
      onClick = onSettings,
      modifier = Modifier.padding(horizontal = 12.dp),
    )
    NavigationDrawerItem(
      label = { Text("About") },
      icon = { Icon(Icons.Rounded.Info, null) },
      selected = false,
      onClick = onAbout,
      modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
    )
  }

  renaming?.let { c ->
    var name by remember(c.id) { mutableStateOf(c.title) }
    AlertDialog(
      onDismissRequest = { renaming = null },
      title = { Text("Rename chat") },
      text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
      confirmButton = { TextButton(onClick = { chat.renameChat(c.id, name); renaming = null }) { Text("Save") } },
      dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
    )
  }
  deleting?.let { c ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text("Delete this chat?") },
      text = { Text("“${c.title}” will be removed from this phone. This can't be undone.") },
      confirmButton = { TextButton(onClick = { chat.deleteChat(c.id); deleting = null }) { Text("Delete") } },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
    )
  }
}

fun relTime(ts: Long): String {
  val now = Calendar.getInstance()
  val then = Calendar.getInstance().apply { timeInMillis = ts }
  val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
  if (sameDay) return DateFormat.getTimeInstance(DateFormat.SHORT).format(ts)
  now.add(Calendar.DAY_OF_YEAR, -1)
  val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
  return if (yesterday) "Yesterday" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(ts)
}
