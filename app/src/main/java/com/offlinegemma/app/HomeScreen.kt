package com.offlinegemma.app

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinegemma.app.ui.Brand
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

private data class HomeStarter(
  val category: String,
  val title: String,
  val prompt: String,
  val icon: ImageVector,
  val tint: Color,
)

/**
 * The first screen of a new chat: a quiet, data-driven study desk rather than a wall of
 * fixed suggestion cards. Continue/Recent come from the user's real chats; starters change
 * with the day, the last topic, and the shuffle button.
 */
@Composable
fun HomeScreen(
  chat: ChatController,
  loading: Boolean,
  onTalk: () -> Unit,
  onCamera: () -> Unit,
  onAttach: () -> Unit,
  onOpenChat: (String) -> Unit,
  onOpenChats: () -> Unit,
  onStarter: (String) -> Unit,
) {
  val bengali = chat.prefs.answerBengali
  val now = remember { LocalDateTime.now() }
  val recent = chat.chats.firstOrNull()
  val older = chat.chats.drop(1).take(3)
  var seed by rememberSaveable { mutableIntStateOf(0) }
  val starters = remember(seed, recent?.id, recent?.title, bengali) {
    startersFor(recent, seed, bengali)
  }
  var entered by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { entered = true }

  Box(Modifier.fillMaxSize()) {
    AuroraBackground()
    Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 28.dp),
    ) {
      AnimatedVisibility(
        visible = entered,
        enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 10 },
      ) {
        Hero(
          greeting = greetingFor(now.hour, bengali),
          date = dateLabel(now, bengali),
          subtitle = when {
            loading -> if (bengali) "জোনাকি এআই জেগে উঠছে…" else "Waking up Jonaki AI…"
            bengali -> "আজ কী বুঝতে চান? নিচে লিখুন, ছবি তুলুন, বা কথা বলুন।"
            else -> "What would you like to understand? Type below, scan a page, or just talk."
          },
        )
      }

      if (loading) {
        Spacer(Modifier.height(26.dp))
        LoadingCard(bengali)
      }

      if (recent != null) {
        Spacer(Modifier.height(28.dp))
        ContinueCard(recent, bengali) { onOpenChat(recent.id) }
      }

      if (older.isNotEmpty()) {
        Spacer(Modifier.height(30.dp))
        SectionHeader(
          title = if (bengali) "সাম্প্রতিক" else "Recent",
          action = if (bengali) "সব চ্যাট" else "All chats",
          onAction = onOpenChats,
        )
        Spacer(Modifier.height(2.dp))
        older.forEachIndexed { i, c ->
          RecentRow(c) { onOpenChat(c.id) }
          if (i < older.lastIndex) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
          }
        }
      }

      if (!loading) {
        Spacer(Modifier.height(30.dp))
        StartersSection(
          starters = starters,
          seed = seed,
          bengali = bengali,
          onShuffle = { seed++ },
          onStarter = onStarter,
        )
        Spacer(Modifier.height(28.dp))
        QuickActions(onTalk = onTalk, onCamera = onCamera, onAttach = onAttach, bengali = bengali)
        Spacer(Modifier.height(22.dp))
        PrivacyNote(bengali)
      }
    }
  }
}

@Composable
private fun AuroraBackground() {
  val scheme = MaterialTheme.colorScheme
  val dots = remember {
    val r = Random(7)
    List(9) { Triple(r.nextFloat(), r.nextFloat() * 0.52f, 1.1f + r.nextFloat() * 1.7f) }
  }
  Canvas(Modifier.fillMaxSize()) {
    drawRect(Brush.verticalGradient(listOf(scheme.surface, scheme.surfaceContainerLowest)))
    drawCircle(
      Brush.radialGradient(
        listOf(Brand.Violet.copy(alpha = 0.17f), Color.Transparent),
        center = Offset(size.width * 0.86f, size.height * 0.02f),
        radius = size.width * 0.92f,
      ),
      radius = size.width * 0.92f,
    )
    drawCircle(
      Brush.radialGradient(
        listOf(Brand.Teal.copy(alpha = 0.09f), Color.Transparent),
        center = Offset(size.width * 0.02f, size.height * 0.28f),
        radius = size.width * 0.78f,
      ),
      radius = size.width * 0.78f,
    )
    dots.forEach { (x, y, rad) ->
      drawCircle(
        scheme.onSurface.copy(alpha = 0.12f),
        radius = rad.dp.toPx(),
        center = Offset(size.width * x, size.height * y),
      )
    }
  }
}

@Composable
private fun Hero(greeting: String, date: String, subtitle: String) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Column(Modifier.weight(1f)) {
      Text(
        date.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.height(10.dp))
      Text(greeting, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
      Spacer(Modifier.height(8.dp))
      Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(10.dp))
    BrandMark(82.dp)
  }
}

@Composable
private fun ContinueCard(recent: ChatSummary, bengali: Boolean, onClick: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val shape = RoundedCornerShape(24.dp)
  Surface(
    onClick = onClick,
    shape = shape,
    color = Color.Transparent,
    modifier = Modifier.fillMaxWidth().clip(shape)
      .background(Brush.linearGradient(listOf(scheme.primary.copy(alpha = 0.20f), scheme.surfaceContainerLow)))
      .border(1.dp, scheme.outlineVariant.copy(alpha = 0.35f), shape),
  ) {
    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(50.dp).clip(RoundedCornerShape(17.dp)).background(Brand.aurora),
        contentAlignment = Alignment.Center,
      ) { Icon(Icons.Rounded.History, null, tint = Color.White, modifier = Modifier.size(25.dp)) }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Text(
          if (bengali) "যেখানে থেমেছিলেন" else "PICK UP WHERE YOU LEFT OFF",
          style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.0.sp),
          color = scheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(recent.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Text(relativeTime(recent.updated), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
      }
      Spacer(Modifier.width(10.dp))
      Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
  }
}

@Composable
private fun RecentRow(summary: ChatSummary, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = summary.title, onClick = onClick)
      .heightIn(min = 54.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
      contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp)) }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(summary.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(relativeTime(summary.updated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Icon(
      Icons.AutoMirrored.Rounded.ArrowForward,
      null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
      modifier = Modifier.size(16.dp),
    )
  }
}

@Composable
private fun StartersSection(
  starters: List<HomeStarter>,
  seed: Int,
  bengali: Boolean,
  onShuffle: () -> Unit,
  onStarter: (String) -> Unit,
) {
  Column {
    SectionHeader(
      title = if (bengali) "শুরু করুন" else "Start with",
      trailing = {
        IconButton(onClick = onShuffle) {
          Icon(
            Icons.Rounded.Refresh,
            if (bengali) "নতুন আইডিয়া" else "New ideas",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
        }
      },
    )
    Spacer(Modifier.height(2.dp))
    AnimatedContent(
      targetState = seed,
      transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
      label = "starters",
    ) {
      Column {
        starters.forEachIndexed { i, starter ->
          StarterRow(starter, onStarter)
          if (i < starters.lastIndex) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
          }
        }
      }
    }
  }
}

@Composable
private fun StarterRow(starter: HomeStarter, onStarter: (String) -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = starter.title) { onStarter(starter.prompt) }
      .padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(starter.tint.copy(alpha = 0.13f)),
      contentAlignment = Alignment.Center,
    ) { Icon(starter.icon, null, tint = starter.tint, modifier = Modifier.size(21.dp)) }
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
      Text(
        starter.category.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.9.sp),
        color = starter.tint,
      )
      Spacer(Modifier.height(3.dp))
      Text(starter.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.width(8.dp))
    Icon(
      Icons.AutoMirrored.Rounded.ArrowForward,
      null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
      modifier = Modifier.size(18.dp),
    )
  }
}

@Composable
private fun QuickActions(onTalk: () -> Unit, onCamera: () -> Unit, onAttach: () -> Unit, bengali: Boolean) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    QuickAction(
      Icons.Rounded.GraphicEq,
      if (bengali) "কথা" else "Talk",
      if (bengali) "জোনাকির সাথে কথা বলুন" else "Speak with Jonaki AI",
      onTalk,
      Modifier.weight(1f),
      accent = true,
    )
    QuickAction(
      Icons.Rounded.PhotoCamera,
      if (bengali) "স্ক্যান" else "Scan",
      if (bengali) "একটি ছবি তুলুন" else "Take a photo",
      onCamera,
      Modifier.weight(1f),
    )
    QuickAction(
      Icons.Rounded.AttachFile,
      if (bengali) "সংযুক্ত" else "Attach",
      if (bengali) "ফাইল যোগ করুন" else "Add a file",
      onAttach,
      Modifier.weight(1f),
    )
  }
}

@Composable
private fun QuickAction(
  icon: ImageVector,
  label: String,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  accent: Boolean = false,
) {
  val scheme = MaterialTheme.colorScheme
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(20.dp),
    color = if (accent) scheme.primaryContainer else scheme.surfaceContainerLow,
    border = BorderStroke(
      1.dp,
      if (accent) scheme.primary.copy(alpha = 0.35f) else scheme.outlineVariant.copy(alpha = 0.30f),
    ),
    modifier = modifier.heightIn(min = 78.dp),
  ) {
    Column(
      Modifier.padding(vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Icon(
        icon,
        description,
        tint = if (accent) scheme.onPrimaryContainer else scheme.primary,
        modifier = Modifier.size(23.dp),
      )
      Spacer(Modifier.height(7.dp))
      Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (accent) scheme.onPrimaryContainer else scheme.onSurface,
      )
    }
  }
}

@Composable
private fun PrivacyNote(bengali: Boolean) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    Spacer(Modifier.width(6.dp))
    Text(
      if (bengali) "প্রাইভেসি আপনার হাতে — চ্যাট ফোনেই থাকে।"
      else "Private by design — your chats stay on this phone.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun SectionHeader(
  title: String,
  action: String? = null,
  onAction: (() -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
) {
  Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
      title.uppercase(),
      style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.weight(1f),
    )
    if (action != null && onAction != null) {
      TextButton(onClick = onAction) { Text(action, style = MaterialTheme.typography.labelLarge) }
    }
    trailing?.invoke()
  }
}

@Composable
private fun LoadingCard(bengali: Boolean) {
  Surface(
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
      CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
      Spacer(Modifier.width(14.dp))
      Column {
        Text(
          if (bengali) "জোনাকি এআই জেগে উঠছে…" else "Waking up Jonaki AI…",
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          if (bengali) "মডেলটি মেমরিতে লোড হচ্ছে। প্রথমবার কিছুটা সময় লাগতে পারে।"
          else "Loading the model into memory. The first time can take a little while.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private fun greetingFor(hour: Int, bengali: Boolean): String = if (bengali) {
  when {
    hour < 5 -> "এখনো জেগে আছেন?"
    hour < 12 -> "শুভ সকাল"
    hour < 17 -> "শুভ অপরাহ্ন"
    hour < 21 -> "শুভ সন্ধ্যা"
    else -> "শুভ রাত্রি"
  }
} else {
  when {
    hour < 5 -> "Still up?"
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    hour < 21 -> "Good evening"
    else -> "Good night"
  }
}

private fun dateLabel(now: LocalDateTime, bengali: Boolean): String {
  val locale = if (bengali) Locale.forLanguageTag("bn") else Locale.ENGLISH
  return DateTimeFormatter.ofPattern("EEEE, d MMMM", locale).format(now)
}

private fun relativeTime(ms: Long): String =
  DateUtils.getRelativeTimeSpanString(
    ms,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_RELATIVE,
  ).toString()

private fun startersFor(recent: ChatSummary?, seed: Int, bengali: Boolean): List<HomeStarter> {
  if (recent != null) return topicStarters(recent.title, seed, bengali)
  val all = curatedStarters(bengali)
  val start = (seed * 3 + LocalDate.now().dayOfYear) % all.size
  return (0 until 3).map { all[(start + it) % all.size] }
}

private fun topicStarters(topicRaw: String, seed: Int, bengali: Boolean): List<HomeStarter> {
  val topic = topicRaw.take(64)
  val all = if (bengali) {
    listOf(
      HomeStarter(
        "গভীরে যান",
        "“$topic” নিয়ে আরও গভীরে যান",
        "“$topic” নিয়ে আরও গভীরে যান। আসল বিজ্ঞান, মূল ধারণা এবং প্রতিটি অংশের অর্থ ব্যাখ্যা করুন।",
        Icons.Rounded.Science,
        Brand.Violet,
      ),
      HomeStarter(
        "অনুশীলন",
        "“$topic” নিয়ে কুইজ",
        "“$topic” নিয়ে আমাকে একটি বহুনির্বাচনী প্রশ্ন করুন; একবারে একটি।",
        Icons.Rounded.Quiz,
        Brand.Indigo,
      ),
      HomeStarter(
        "সহজভাবে",
        "“$topic” সহজভাবে বুঝুন",
        "“$topic” আরও সহজভাবে, ১০ বছরের শিশুকে বোঝানোর মতো করে, দৈনন্দিন উপমা দিয়ে ব্যাখ্যা করুন।",
        Icons.Rounded.Lightbulb,
        Brand.Teal,
      ),
      HomeStarter(
        "উদাহরণ",
        "“$topic” এর বাস্তব উদাহরণ",
        "“$topic” এর একটি বাস্তব জীবনের উদাহরণ দিন এবং কেন এটি গুরুত্বপূর্ণ তা বলুন।",
        Icons.Rounded.AutoAwesome,
        Color(0xFFE0A94A),
      ),
    )
  } else {
    listOf(
      HomeStarter(
        "Study",
        "Go deeper on “$topic”",
        "Go deeper on “$topic”. Explain the real science, the key ideas and what each part means.",
        Icons.Rounded.Science,
        Brand.Violet,
      ),
      HomeStarter(
        "Practice",
        "Quiz me on “$topic”",
        "Quiz me on “$topic”. Ask one multiple-choice question at a time.",
        Icons.Rounded.Quiz,
        Brand.Indigo,
      ),
      HomeStarter(
        "Explain",
        "Explain “$topic” simply",
        "Explain “$topic” more simply, like I'm 10, with an everyday analogy.",
        Icons.Rounded.Lightbulb,
        Brand.Teal,
      ),
      HomeStarter(
        "Example",
        "A real example of “$topic”",
        "Give me a real-world example of “$topic” and why it matters.",
        Icons.Rounded.AutoAwesome,
        Color(0xFFE0A94A),
      ),
    )
  }
  return (0 until 3).map { all[(seed + it) % all.size] }
}

private fun curatedStarters(bengali: Boolean): List<HomeStarter> = if (bengali) {
  listOf(
    HomeStarter("পড়াশোনা", "সালোকসংশ্লেষণ সহজভাবে", "সালোকসংশ্লেষণ ১২ বছরের শিশুকে বোঝানোর মতো করে, দৈনন্দিন উপমা দিয়ে ব্যাখ্যা করুন।", Icons.Rounded.Science, Brand.Violet),
    HomeStarter("গণিত", "দ্বিঘাত সমীকরণ ধাপে ধাপে", "2x² − 5x + 2 = 0 সমাধান করুন ধাপে ধাপে, প্রতিটি ধাপ ব্যাখ্যা করে।", Icons.Rounded.Calculate, Brand.Indigo),
    HomeStarter("কোড", "একটি ফাংশন লিখুন ও পরীক্ষা করুন", "একটি Python ফাংশন লিখুন যা একটি স্ট্রিং উল্টে দেয়, এবং এটি পরীক্ষা করুন।", Icons.Rounded.Code, Brand.Teal),
    HomeStarter("নথি", "PDF সংক্ষেপ করুন", "আমি যে PDF সংযুক্ত করব, সেটির ৫টি মূল পয়েন্টে সংক্ষেপ দিন।", Icons.Rounded.Description, Color(0xFF5B8DEF)),
    HomeStarter("রসায়ন", "পর্যায় সারণি কুইজ", "পর্যায় সারণি নিয়ে আমাকে একটি করে বহুনির্বাচনী প্রশ্ন করুন।", Icons.Rounded.Quiz, Brand.Indigo),
    HomeStarter("ভাষা", "বাংলা অনুচ্ছেদ ব্যাখ্যা", "একটি বাংলা অনুচ্ছেদ অনুবাদ করুন এবং সহজ ভাষায় ব্যাখ্যা করুন।", Icons.Rounded.Translate, Color(0xFFE0A94A)),
    HomeStarter("পদার্থ", "লিথিয়াম-আয়ন ব্যাটারি", "একটি লিথিয়াম-আয়ন ব্যাটারি কীভাবে কাজ করে? সহজ ভাষায় ব্যাখ্যা করুন।", Icons.Rounded.Science, Brand.Violet),
    HomeStarter("পরিকল্পনা", "২০ মিনিটের পড়ার পরিকল্পনা", "আমার পরবর্তী পরীক্ষার জন্য ২০ মিনিটের একটি পড়ার পরিকল্পনা তৈরি করুন।", Icons.AutoMirrored.Rounded.MenuBook, Brand.Teal),
  )
} else {
  listOf(
    HomeStarter("Study", "Photosynthesis, simply", "Explain photosynthesis like I'm 12, with an everyday analogy.", Icons.Rounded.Science, Brand.Violet),
    HomeStarter("Maths", "Solve a quadratic", "Solve 2x² − 5x + 2 = 0 step by step, explaining each step.", Icons.Rounded.Calculate, Brand.Indigo),
    HomeStarter("Code", "Write and test a function", "Write a Python function that reverses a string, and test it.", Icons.Rounded.Code, Brand.Teal),
    HomeStarter("Docs", "Summarise a PDF", "Summarise the PDF I attach in 5 key points.", Icons.Rounded.Description, Color(0xFF5B8DEF)),
    HomeStarter("Chemistry", "Periodic table quiz", "Quiz me on the periodic table, one multiple-choice question at a time.", Icons.Rounded.Quiz, Brand.Indigo),
    HomeStarter("Language", "Explain a Bengali paragraph", "Translate and explain a Bengali paragraph in simple language.", Icons.Rounded.Translate, Color(0xFFE0A94A)),
    HomeStarter("Physics", "How a lithium-ion battery works", "How does a lithium-ion battery work? Explain it simply.", Icons.Rounded.Science, Brand.Violet),
    HomeStarter("Plan", "A 20-minute study plan", "Make a 20-minute study plan for my next exam.", Icons.AutoMirrored.Rounded.MenuBook, Brand.Teal),
  )
}
