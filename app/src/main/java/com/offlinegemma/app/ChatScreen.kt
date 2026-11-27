package com.offlinegemma.app

import androidx.compose.ui.semantics.selected
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TopAppBar
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chat: ChatController, speaker: Speaker, onSettings: () -> Unit, onAbout: () -> Unit, onTalk: () -> Unit) {
  val drawer = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  var input by rememberSaveable { mutableStateOf("") }
  var sheet by rememberSaveable { mutableStateOf(false) }
  var viewing by remember { mutableStateOf<ViewItem?>(null) }
  var cameraOpen by rememberSaveable { mutableStateOf(false) }
  var focusComposer by remember { mutableStateOf(0) }
  // Once a second while a background step runs, so "Checking…" never looks frozen.
  var clock by remember { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(chat.status) {
    while (chat.status != null) {
      clock = System.currentTimeMillis()
      delay(1000)
    }
  }
  viewing?.let { Viewer(it, onClose = { viewing = null }) }
  // Text shared into the app from another app.
  LaunchedEffect(chat.sharedText) { chat.sharedText?.let { input = it; chat.sharedText = null } }
  val listState = rememberLazyListState()

  LaunchedEffect(Unit) { chat.loadModel() }
  // Answers run as background jobs and say when they are ready: ask once for notifications.
  val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
  ) {}
  var askedNotifications by rememberSaveable { mutableStateOf(false) }
  val ctx = androidx.compose.ui.platform.LocalContext.current
  LaunchedEffect(chat.busy) {
    if (chat.busy && !askedNotifications && android.os.Build.VERSION.SDK_INT >= 33) {
      askedNotifications = true
      if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  // Follow the answer while it streams; jump to the newest message when one is added.
  val last = chat.lines.lastOrNull()
  // Follow the stream only while the reader is at the bottom; never jump while they read older messages.
  val atBottom by remember { androidx.compose.runtime.derivedStateOf { !listState.canScrollForward } }
  var follow by remember { mutableStateOf(true) }
  LaunchedEffect(atBottom, listState.isScrollInProgress) { if (listState.isScrollInProgress) follow = atBottom }
  val lastUserId = chat.lines.lastOrNull { it.who == Who.USER }?.id
  LaunchedEffect(lastUserId) {
    // A message the user just sent: always show it.
    follow = true
    if (chat.lines.isNotEmpty()) listState.scrollToItem(chat.lines.lastIndex, Int.MAX_VALUE / 2)
  }
  LaunchedEffect(chat.lines.size, last?.text?.length, last?.done, chat.figStates.size) {
    if (follow && chat.lines.isNotEmpty()) listState.scrollToItem(chat.lines.lastIndex, Int.MAX_VALUE / 2)
  }
  var actionsFor by remember { mutableStateOf<ChatLine?>(null) }
  actionsFor?.let { l -> MessageActions(chat, speaker, l, onDismiss = { actionsFor = null }) }
  // Back from a chat returns to the home screen instead of closing the app, which is what
  // users expect (and what made a shared/opened chat feel like a dead end).
  BackHandler(enabled = chat.lines.isNotEmpty() || sheet) {
    when {
      sheet -> sheet = false
      else -> chat.newChat()
    }
  }
  Box(Modifier.fillMaxSize()) {
  // The aurora lives behind everything, so the top bar can be transparent and the gradient is not
  // cut off by a hard edge under the header.
  AuroraBackground()
  ModalNavigationDrawer(
    drawerState = drawer,
    drawerContent = {
      ChatDrawer(
        chat = chat,
        // Allowed while an answer runs: it keeps going in its own chat.
        onPick = { id -> chat.openChat(id); scope.launch { drawer.close() } },
        onNew = { chat.newChat(); scope.launch { drawer.close() } },
        onSettings = { scope.launch { drawer.close() }; onSettings() },
        onAbout = { scope.launch { drawer.close() }; onAbout() },
      )
    },
  ) {
    Scaffold(
      containerColor = Color.Transparent,
      topBar = {
        Column {
        TopAppBar(
          navigationIcon = {
            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Rounded.Menu, "Chats and settings") }
          },
          title = {
            Column {
              Text(
                if (chat.lines.isEmpty()) "Jonaki AI" else chat.title,
                maxLines = if (chat.lines.isEmpty()) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                style = if (chat.lines.isEmpty()) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.titleMedium,
              )
              // Work running in another chat: say where, so the spinner is not mistaken for this chat.
              val elsewhere = chat.busy && !chat.busyHere
              val sub = if (chat.lines.isEmpty()) "" else (if (elsewhere) "Answering in “${chat.runningTitle}”" else chat.status) ?: when {
                chat.loadError != null -> "Model failed to load"
                chat.loading -> "Waking up…"
                // Mode (GPU/CPU) is in Settings; it does not need to repeat on every screen.
                else -> ""
              }
              if (sub.isNotEmpty()) {
                val working = chat.status != null || elsewhere
                val secs = if (working && chat.statusStartedAt > 0) {
                  ((clock - chat.statusStartedAt) / 1000).coerceAtLeast(0)
                } else 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                  if (working) {
                    CircularProgressIndicator(
                      modifier = Modifier.size(12.dp),
                      strokeWidth = 1.5.dp,
                      color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                  }
                  Text(
                    if (working && secs > 0) "$sub · ${secs}s" else sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            }
          },
          actions = {
            LanguagePill(bengali = chat.prefs.answerBengali, onChange = { chat.prefs.answerBengali = it })
            IconButton(onClick = { chat.newChat() }) { Icon(Icons.Rounded.Add, "New chat") }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        }
      },
      bottomBar = {
        Composer(
          chat = chat,
          text = input,
          onText = { input = it },
          onSend = { val t = input.trim(); input = ""; chat.sendTyped(t) },
          onTalk = onTalk,
          onCamera = { cameraOpen = true },
          sheetOpen = sheet,
          onSheet = { sheet = it },
          focusRequest = focusComposer,
        )
      },
    ) { pad ->
      Box(Modifier.fillMaxSize().padding(pad)) {
        when {
          chat.loadError != null -> LoadError(chat.loadError!!, onRetry = { chat.loadModel() })
          chat.lines.isEmpty() -> HomeScreen(
            chat = chat,
            loading = !chat.ready,
            onTalk = onTalk,
            onCamera = { cameraOpen = true },
            onAttach = { sheet = true },
            onOpenChat = { chat.openChat(it) },
            onOpenChats = { scope.launch { drawer.open() } },
            onStarter = { prompt -> input = prompt; focusComposer++ },
          )
          else -> {
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
              verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
              items(chat.lines, key = { it.id }) { line ->
                MessageItem(chat, speaker, line, onOpen = { viewing = it }, onLongPress = { actionsFor = line })
              }
            }
            // Scrolled up while something new arrives: a button back to the newest message.
            androidx.compose.animation.AnimatedVisibility(
              visible = !atBottom && !follow,
              enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
              exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
              modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            ) {
              androidx.compose.material3.SmallFloatingActionButton(
                onClick = { follow = true; scope.launch { listState.animateScrollToItem(chat.lines.lastIndex, Int.MAX_VALUE / 2) } },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
              ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                  Icon(androidx.compose.material.icons.Icons.Rounded.ArrowDownward, null, Modifier.size(18.dp))
                  Spacer(Modifier.size(6.dp))
                  Text(if (chat.busy) "New message" else "Latest", style = MaterialTheme.typography.labelLarge)
                }
              }
            }
          }
        }
      }
    }
  }
  if (cameraOpen) {
    CameraCapture(
      onPhoto = { bitmap -> chat.addBitmap(bitmap, "Photo"); cameraOpen = false },
      onClose = { cameraOpen = false },
    )
  }
  }
}

@Composable
private fun MessageItem(chat: ChatController, speaker: Speaker, line: ChatLine, onOpen: (ViewItem) -> Unit, onLongPress: () -> Unit) {
  when (line.who) {
    Who.USER -> UserBubble(line, onOpen, onLongPress)
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
        chat = chat,
        onOpen = onOpen,
        onLongPress = onLongPress,
      )
    }
  }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
private fun UserBubble(line: ChatLine, onOpen: (ViewItem) -> Unit, onLongPress: () -> Unit) {
  val extra = com.offlinegemma.app.ui.LocalExtraColors.current
  androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
  val maxBubble = maxWidth * 0.8f
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (line.attachments.isNotEmpty()) {
      androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // Tap a picture, document or voice clip to open it full screen.
        line.attachments.forEach { a ->
          Box(
            Modifier.clip(MaterialTheme.shapes.medium).clickable(onClickLabel = "Open ${a.name}") {
              onOpen(ViewItem(a.path, if (a.kind == Attachment.Kind.DOC) a.name else a.path.substringAfterLast('/'), a.name))
            },
          ) { AttachmentView(a) }
        }
      }
    }
    if (line.display.isNotBlank()) Surface(
      color = extra.userBubble,
      contentColor = extra.onUserBubble,
      shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp),
      modifier = Modifier.widthIn(max = maxBubble).clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp))
        .combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = "Message actions"),
    ) {
      Text(line.display, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge)
    }
  }
  }
}

/** Long-press a message: Copy / Share / Read aloud / Regenerate, as a bottom sheet with big rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActions(chat: ChatController, speaker: Speaker, line: ChatLine, onDismiss: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val text = if (line.who == Who.MODEL) Markdown.plain(line.text) else line.display
  val newest = line.id == chat.lines.lastOrNull { it.who == Who.MODEL }?.id
  androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.padding(bottom = 24.dp)) {
      @Composable
      fun Item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, run: () -> Unit) {
        androidx.compose.material3.ListItem(
          headlineContent = { Text(label) },
          leadingContent = { Icon(icon, null) },
          colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
          modifier = Modifier.clickable(enabled = enabled) { run(); onDismiss() }.heightIn(min = 56.dp)
            .then(if (enabled) Modifier else Modifier.padding(0.dp)),
        )
      }
      Item(androidx.compose.material.icons.Icons.Rounded.ContentCopy, "Copy") { copyToClipboard(context, text) }
      Item(androidx.compose.material.icons.Icons.Rounded.Share, "Share") {
        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
        context.startActivity(android.content.Intent.createChooser(i, "Share"))
      }
      if (line.who == Who.MODEL) {
        Item(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.VolumeUp, "Read aloud") { speaker.readAloud(line.id, line.text) }
        if (newest) Item(androidx.compose.material.icons.Icons.Rounded.Refresh, "Regenerate", enabled = chat.canSend()) { chat.regenerate() }
      }
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

@Composable
private fun LoadError(message: String, onRetry: () -> Unit) {
  Column(
    Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text("Jonaki AI could not start", style = MaterialTheme.typography.headlineSmall)
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
  // Bulk delete: pick several chats, or clear the whole history at once.
  var selecting by remember { mutableStateOf(false) }
  val chosen = remember { androidx.compose.runtime.mutableStateListOf<String>() }
  var confirmBulk by remember { mutableStateOf(false) }
  fun stopSelecting() { selecting = false; chosen.clear() }

  ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
    Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      BrandMark(36.dp, animate = false)
      Spacer(Modifier.size(10.dp))
      Text(if (selecting) "${chosen.size} selected" else "Jonaki AI", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
      if (selecting) {
        TextButton(onClick = { stopSelecting() }) { Text("Cancel") }
      }
    }
    if (!selecting) {
      NavigationDrawerItem(
        label = { Text("New chat") },
        icon = { Icon(Icons.Rounded.Add, null) },
        selected = false,
        onClick = onNew,
        modifier = Modifier.padding(horizontal = 12.dp),
      )
    }
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(
        "Chats",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      when {
        selecting && chat.chats.isNotEmpty() -> {
          val all = chosen.size == chat.chats.size
          TextButton(onClick = { if (all) chosen.clear() else { chosen.clear(); chosen.addAll(chat.chats.map { it.id }) } }) {
            Text(if (all) "None" else "All")
          }
        }
        chat.chats.isNotEmpty() && !chat.busy -> TextButton(onClick = { selecting = true }) { Text("Select") }
      }
    }
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
        val picked = c.id in chosen
        Box {
          NavigationDrawerItem(
            label = {
              Column {
                Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                  if (c.id == chat.runningChatId) "Answering…" else relTime(c.updated),
                  style = MaterialTheme.typography.labelSmall,
                  color = if (c.id == chat.runningChatId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            },
            icon = {
              if (selecting) {
                Icon(
                  if (picked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                  if (picked) "Selected" else "Not selected",
                  tint = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else {
                Icon(Icons.Rounded.ChatBubbleOutline, null)
              }
            },
            badge = {
              if (!selecting) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${c.title}") }
              }
            },
            selected = if (selecting) picked else c.id == chat.chatId,
            onClick = {
              if (selecting) { if (picked) chosen.remove(c.id) else chosen.add(c.id) } else onPick(c.id)
            },
            modifier = Modifier.padding(horizontal = 12.dp).animateContentSize(),
          )
          if (!selecting) DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; renaming = c })
            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; deleting = c })
          }
        }
      }
    }
    if (selecting) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
          if (chosen.isEmpty()) "Choose chats to delete" else "${chosen.size} of ${chat.chats.size}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Button(
          onClick = { confirmBulk = true },
          enabled = chosen.isNotEmpty(),
        ) { Text("Delete") }
      }
    }
    if (!selecting) NavigationDrawerItem(
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
  if (confirmBulk) {
    val n = chosen.size
    AlertDialog(
      onDismissRequest = { confirmBulk = false },
      title = { Text(if (n == chat.chats.size) "Delete all chats?" else "Delete $n chats?") },
      text = {
        Text(
          if (n == chat.chats.size) "All $n saved chats and their pictures will be removed from this phone. This can't be undone."
          else "$n saved chats and their pictures will be removed from this phone. This can't be undone.",
        )
      },
      confirmButton = {
        TextButton(onClick = {
          confirmBulk = false
          chat.deleteChats(chosen.toList())
          stopSelecting()
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { confirmBulk = false }) { Text("Keep") } },
    )
  }
}

/**
 * EN | বাং — which language Gemma answers in. Two halves of one small pill; the chosen half is filled.
 * Lives in the top bar so it is always in reach (switching takes effect from the next message).
 */
@Composable
fun LanguagePill(bengali: Boolean, onChange: (Boolean) -> Unit) {
  val scheme = MaterialTheme.colorScheme
  Surface(
    onClick = { onChange(!bengali) },
    shape = RoundedCornerShape(50),
    color = scheme.surfaceContainerHigh,
    contentColor = scheme.primary,
    modifier = Modifier.padding(end = 4.dp).height(34.dp).semantics {
      contentDescription = if (bengali) "Answers in Bengali — switch to English"
      else "Answers in English — switch to Bengali"
    },
  ) {
    Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
      Text(
        if (bengali) "বাং" else "EN",
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
      )
    }
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
