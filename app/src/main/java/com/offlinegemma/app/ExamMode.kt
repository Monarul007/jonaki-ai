package com.offlinegemma.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinegemma.app.ui.Brand
import com.offlinegemma.app.ui.ExtraColors
import com.offlinegemma.app.ui.LocalExtraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exam mode's screens.
 *
 * The look follows the rest of the app rather than Material's defaults: the aurora background
 * behind everything, large-radius soft cards, and the brand gradient kept for the single primary
 * action on each screen. A grey exam form reads like a system dialog; a paper someone is about to
 * sit should read like something worth sitting.
 *
 * [ExamModeScreen] picks the screen for the current phase, so the navigation rules (back from a
 * sub-screen must never close the app) live in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamModeScreen(exam: ExamController, onExit: () -> Unit) {
  val canGoBack = exam.phase != ExamPhase.LIST
  BackHandler(enabled = canGoBack) {
    when (exam.phase) {
      ExamPhase.BRIEF, ExamPhase.RESULT -> exam.backToList()
      // Leaving a running paper keeps it and its clock; the attempt is already on disk.
      ExamPhase.RUNNING -> if (!exam.review) exam.backToList()
      ExamPhase.REVIEW -> exam.leaveReview()
      ExamPhase.LIST -> Unit
    }
  }

  Box(Modifier.fillMaxSize()) {
    AuroraBackground()
    when (exam.phase) {
      ExamPhase.LIST -> ExamListScreen(exam, onExit)
      ExamPhase.BRIEF -> ExamBriefScreen(exam)
      ExamPhase.RUNNING, ExamPhase.REVIEW -> ExamRunnerScreen(exam)
      ExamPhase.RESULT -> ExamResultScreen(exam)
    }
  }
}

/* ------------------------------------------------------------------ shared bits */

/** Bengali digits when the app answers in Bengali, so counters read like a printed paper. */
private fun num(value: Int, bengali: Boolean): String {
  if (!bengali) return value.toString()
  val digits = "০১২৩৪৫৬৭৮৯"
  return value.toString().map { if (it in '0'..'9') digits[it - '0'] else it }.joinToString("")
}

/** One decimal place in the current script: 0.5 -> "০.৫". */
private fun num(value: Double, bengali: Boolean): String {
  if (value == value.toLong().toDouble()) return num(value.toLong().toInt(), bengali)
  val tenths = Math.round(value * 10).toInt()
  return num(tenths / 10, bengali) + "." + num(tenths % 10, bengali)
}

/** 9030 -> "9,030" (or "৯,০৩০"), because a bare four-digit count reads as a year. */
private fun groupDigits(value: Int, bengali: Boolean): String {
  val grouped = value.toString().reversed().chunked(3).joinToString(",").reversed()
  if (!bengali) return grouped
  return grouped.map { c -> if (c in '0'..'9') "০১২৩৪৫৬৭৮৯"[c - '0'] else c }.joinToString("")
}

/** The brand gradient button: the one primary action on a screen. */
@Composable
private fun AuroraButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  height: Int = 52,
) {
  Box(
    modifier
      .fillMaxWidth()
      .height(height.dp)
      .clip(MaterialTheme.shapes.medium)
      .background(
        if (enabled) Brand.aurora
        else Brush.linearGradient(listOf(Color(0xFFC9C9C9), Color(0xFFC9C9C9))),
      )
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
  }
}

/** A small tinted label: a count, a duration, a penalty, a subject. */
@Composable
private fun MetaPill(text: String, fg: Color, bg: Color) {
  Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = fg,
    maxLines = 1,
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(bg)
      .padding(horizontal = 9.dp, vertical = 3.dp),
  )
}

/** A soft card at the app's large radius. */
@Composable
private fun SoftSurface(
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
  border: BorderStroke? = null,
  content: @Composable () -> Unit,
) {
  Surface(modifier, shape = MaterialTheme.shapes.large, color = color, border = border, content = content)
}

/** One big number with a caption, used on the brief and the result. */
@Composable
private fun StatTile(value: String, caption: String, tint: Color, modifier: Modifier = Modifier) {
  SoftSurface(modifier) {
    Column(
      Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(value, style = MaterialTheme.typography.headlineSmall, color = tint, maxLines = 1)
      Spacer(Modifier.height(2.dp))
      Text(
        caption,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** Circular accuracy gauge: the first thing a candidate looks at on the result. */
@Composable
private fun ScoreRing(percent: Float, tint: Color) {
  val animated by animateFloatAsState(
    targetValue = percent.coerceIn(0f, 100f),
    animationSpec = tween(900),
    label = "ring",
  )
  Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      val stroke = 11.dp.toPx()
      val arcSize = Size(size.width - stroke, size.height - stroke)
      val topLeft = Offset(stroke / 2, stroke / 2)
      drawArc(
        color = tint.copy(alpha = 0.14f),
        startAngle = -90f, sweepAngle = 360f, useCenter = false,
        topLeft = topLeft, size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
      )
      drawArc(
        color = tint,
        startAngle = -90f, sweepAngle = 360f * (animated / 100f), useCenter = false,
        topLeft = topLeft, size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
      )
    }
    Text(
      "${animated.toInt()}%",
      style = MaterialTheme.typography.titleLarge,
      fontSize = 25.sp,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    letterSpacing = 0.8.sp,
    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
  )
}

private fun categoryLabel(c: String, bengali: Boolean): String = when (c) {
  "bcs" -> if (bengali) "বিসিএস" else "BCS"
  "ntrca" -> if (bengali) "এনটিআরসিএ" else "NTRCA"
  else -> if (bengali) "অন্যান্য" else "Other"
}

/** Accent per category, so a long list of papers is scannable at a glance. */
private fun categoryTint(c: String): Color = when (c) {
  "bcs" -> Brand.Indigo
  "ntrca" -> Brand.Teal
  else -> Color(0xFFE0A94A)
}

/* ------------------------------------------------------------------ list */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamListScreen(exam: ExamController, onExit: () -> Unit) {
  val context = LocalContext.current
  var papers by remember { mutableStateOf<List<ExamPaper>>(emptyList()) }
  var category by remember { mutableStateOf<String?>(null) }
  var loading by remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    papers = withContext(Dispatchers.IO) { Exams.papers(context) }
    exam.refreshSaved()
    loading = false
  }

  val categories = papers.map { it.category }.distinct().sorted()
  val shown = papers.filter { category == null || it.category == category }
  val bengali = exam.bengali
  fun t(bn: String, en: String) = if (bengali) bn else en
  val scheme = MaterialTheme.colorScheme

  Scaffold(
    containerColor = Color.Transparent,
    topBar = {
      TopAppBar(
        title = {},
        navigationIcon = {
          IconButton(onClick = onExit) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("ফিরে যান", "Back"))
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    },
  ) { pad ->
    LazyColumn(
      Modifier.fillMaxSize().padding(pad),
      contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 34.dp),
      verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
      item {
        Column {
          BrandMark(54.dp, animate = false)
          Spacer(Modifier.height(14.dp))
          Text(t("পরীক্ষা", "Exams"), style = MaterialTheme.typography.displaySmall, fontSize = 32.sp)
          Spacer(Modifier.height(5.dp))
          Text(
            t(
              "বাংলাদেশি চাকরির পরীক্ষার বাস্তব প্রশ্নপত্র — নিজের সময়, নিজের গতিতে।",
              "Real Bangladeshi job-exam papers, with their own time and marking rule.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
          )
        }
      }

      if (!loading && papers.isNotEmpty()) {
        item {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(num(papers.size, bengali), t("প্রশ্নপত্র", "Papers"), scheme.primary, Modifier.weight(1f))
            StatTile(
              groupDigits(papers.sumOf { it.questions.size }, bengali),
              t("প্রশ্ন", "Questions"),
              Brand.Teal,
              Modifier.weight(1f),
            )
            // Attempts this person has started, not a fourth way of saying "papers".
            StatTile(
              num(exam.saved.size, bengali),
              t("আপনার পরীক্ষা", "Attempts"),
              Brand.Violet,
              Modifier.weight(1f),
            )
          }
        }
      }

      if (loading) {
        item {
          Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
          }
        }
      }

      if (!loading && papers.isEmpty()) {
        item {
          SoftSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
              Text(t("প্রশ্নপত্র পাওয়া যায়নি", "No papers found"), fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.height(6.dp))
              Text(
                t(
                  "অ্যাপে পরীক্ষার প্যাক নেই। Settings → Storage থেকে স্টাডি ফাইল যোগ করুন।",
                  "The exam pack is not installed. Add your study files from Settings → Storage.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      // Saved attempts first: an unfinished paper is the most urgent thing on this screen.
      if (exam.saved.isNotEmpty()) {
        item { SectionLabel(t("আপনার পরীক্ষা", "Your exams")) }
        items(exam.saved) { a -> SavedAttemptCard(a, bengali) { exam.resume(a) } }
      }

      if (!loading && papers.isNotEmpty()) {
        item { SectionLabel(t("প্রশ্নপত্র", "Question papers")) }
        item { BuildMockCard(exam, papers, categories, category, bengali) }

        if (categories.size > 1) {
          item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              item { CategoryChip(t("সব", "All"), category == null) { category = null } }
              items(categories) { c -> CategoryChip(categoryLabel(c, bengali), category == c) { category = c } }
            }
          }
        }
        items(shown) { p -> PaperCard(p, bengali) { exam.openBrief(p) } }
      }
    }
  }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  Box(
    Modifier
      .clip(RoundedCornerShape(50))
      .background(if (selected) scheme.primary else scheme.surfaceContainerHigh)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 9.dp),
  ) {
    Text(
      label,
      style = MaterialTheme.typography.labelLarge,
      color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun PaperCard(p: ExamPaper, bengali: Boolean, onClick: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val tint = categoryTint(p.category)
  SoftSurface(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick)) {
    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          if (p.category == "bcs") "BCS" else "NTR",
          style = MaterialTheme.typography.labelSmall,
          color = tint,
          fontWeight = FontWeight.Bold,
        )
      }
      Spacer(Modifier.width(13.dp))
      Column(Modifier.weight(1f)) {
        Text(
          if (bengali) p.titleBn else p.title,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          MetaPill(
            "${num(p.questions.size, bengali)} ${if (bengali) "প্রশ্ন" else "Q"}",
            scheme.onSurfaceVariant, scheme.surfaceContainerHigh,
          )
          MetaPill(
            "${num(p.durationMinutes, bengali)} ${if (bengali) "মিনিট" else "min"}",
            scheme.onSurfaceVariant, scheme.surfaceContainerHigh,
          )
          if (p.negative > 0) MetaPill("−${num(p.negative, bengali)}", Color(0xFF8A5A00), Color(0xFFFEF7E0))
        }
      }
      Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = scheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun SavedAttemptCard(a: ExamAttempt, bengali: Boolean, onClick: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val extra = LocalExtraColors.current
  val left = a.remainingMs()
  val running = a.running && left > 0
  val shape = MaterialTheme.shapes.large
  Surface(
    Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
    shape = shape,
    color = if (running) scheme.primaryContainer else scheme.surfaceContainerLow,
  ) {
    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(42.dp).clip(CircleShape)
          .background(if (running) scheme.primary else extra.okContainer),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          if (running) Icons.Rounded.Schedule else Icons.Rounded.CheckCircle,
          contentDescription = null,
          tint = if (running) scheme.onPrimary else extra.ok,
          modifier = Modifier.size(21.dp),
        )
      }
      Spacer(Modifier.width(13.dp))
      Column(Modifier.weight(1f)) {
        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
          if (running) {
            if (bengali) {
              "${formatClock(left)} বাকি · ${num(a.answeredCount, bengali)}/${num(a.questionNumbers.size, bengali)} উত্তর"
            } else {
              "${formatClock(left)} left · ${a.answeredCount}/${a.questionNumbers.size} answered"
            }
          } else {
            if (bengali) "শেষ · ${num(a.score?.net ?: 0.0, bengali)} নম্বর"
            else "Finished · ${a.score?.net ?: 0.0} marks"
          },
          style = MaterialTheme.typography.labelMedium,
          color = if (running) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
      }
      Text(
        if (running) (if (bengali) "চালিয়ে যান" else "Resume") else (if (bengali) "দেখুন" else "Review"),
        style = MaterialTheme.typography.labelLarge,
        color = if (running) scheme.primary else scheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.padding(start = 10.dp),
      )
    }
  }
}

@Composable
private fun BuildMockCard(
  exam: ExamController,
  papers: List<ExamPaper>,
  categories: List<String>,
  filter: String?,
  bengali: Boolean,
) {
  val scheme = MaterialTheme.colorScheme
  var mockCategory by remember(categories, filter) {
    mutableStateOf(filter ?: categories.firstOrNull() ?: "bcs")
  }
  var mockCount by remember { mutableStateOf(50) }
  fun t(bn: String, en: String) = if (bengali) bn else en

  Surface(
    Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = scheme.surfaceContainerLow,
    border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.26f)),
  ) {
    Column(Modifier.padding(17.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Brand.aurora),
          contentAlignment = Alignment.Center,
        ) {
          Icon(Icons.Rounded.Timer, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(11.dp))
        Text(t("নিজস্ব মডেল টেস্ট", "Build a mock"), style = MaterialTheme.typography.titleMedium)
      }
      Spacer(Modifier.height(8.dp))
      Text(
        t(
          "প্রকৃত প্রশ্নপত্রের বিষয়-অনুপাত ধরে প্রশ্ন বাছা হবে; ক্রম ও অপশন বদলে দেওয়া হবে।",
          "Questions are sampled with a real paper's subject mix; order and options are shuffled.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(14.dp))

      Text(
        t("প্রশ্ন সংখ্যা", "Questions"),
        style = MaterialTheme.typography.labelMedium,
        color = scheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(25, 50, 100).forEach { n ->
          val on = mockCount == n
          Box(
            Modifier
              .weight(1f)
              .clip(RoundedCornerShape(12.dp))
              .background(if (on) scheme.primary else scheme.surfaceContainerHigh)
              .clickable { mockCount = n }
              .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              num(n, bengali),
              style = MaterialTheme.typography.labelLarge,
              color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
            )
          }
        }
      }

      if (categories.size > 1) {
        Spacer(Modifier.height(12.dp))
        Text(t("পরীক্ষা", "Exam"), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          categories.forEach { c ->
            val on = mockCategory == c
            Box(
              Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (on) scheme.primaryContainer else scheme.surfaceContainerHigh)
                .clickable { mockCategory = c }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
              Text(
                categoryLabel(c, bengali),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      Spacer(Modifier.height(16.dp))
      AuroraButton(
        t("তৈরি করুন", "Create"),
        onClick = {
          // minutes = 0 lets the category supply its own real duration.
          val mock = ExamScoring.mock(papers, mockCategory, mockCount, 0, System.currentTimeMillis())
          if (mock != null) exam.openBrief(mock)
        },
        height = 48,
      )
    }
  }
}

/* ------------------------------------------------------------------ brief */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamBriefScreen(exam: ExamController) {
  val p = exam.paper ?: return
  val bengali = exam.bengali
  fun t(bn: String, en: String) = if (bengali) bn else en
  val scheme = MaterialTheme.colorScheme

  Scaffold(
    containerColor = Color.Transparent,
    topBar = {
      TopAppBar(
        title = { Text(t("নির্দেশনা", "Instructions"), fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
          IconButton(onClick = { exam.backToList() }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("ফিরে যান", "Back"))
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    },
  ) { pad ->
    Column(
      Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
        .padding(start = 18.dp, end = 18.dp, bottom = 34.dp),
    ) {
      Text(
        if (bengali) p.titleBn else p.title,
        style = MaterialTheme.typography.headlineMedium,
        lineHeight = 33.sp,
      )
      Spacer(Modifier.height(16.dp))

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(num(p.questions.size, bengali), t("প্রশ্ন", "Questions"), scheme.primary, Modifier.weight(1f))
        StatTile(num(p.durationMinutes, bengali), t("মিনিট", "Minutes"), Brand.Teal, Modifier.weight(1f))
        StatTile(
          if (p.negative > 0) "−${num(p.negative, bengali)}" else num(0, bengali),
          t("নেগেটিভ", "Penalty"),
          if (p.negative > 0) Color(0xFFC55A11) else scheme.onSurfaceVariant,
          Modifier.weight(1f),
        )
      }
      Spacer(Modifier.height(18.dp))

      val rules = if (bengali) listOf(
        "প্রতিটি প্রশ্নের জন্য চারটি অপশন; একটিই সঠিক।",
        "প্রশ্ন তালিকা থেকে যেকোনো প্রশ্নে যাওয়া যাবে।",
        "সন্দেহ হলে প্রশ্নটি রিভিউ হিসেবে চিহ্নিত করুন।",
        "সময় শেষ হলে উত্তরপত্র স্বয়ংক্রিয়ভাবে জমা হবে।",
        "পরীক্ষা ছেড়ে গেলেও উত্তর ও বাকি সময় সংরক্ষিত থাকবে।",
      ) else listOf(
        "Every question has four options; exactly one is correct.",
        "Jump to any question from the question list.",
        "Mark a question for review when you are unsure.",
        "When the time is up the paper is submitted automatically.",
        "If you leave, your answers and the remaining time are kept.",
      )
      SoftSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          rules.forEach { rule ->
            Row(verticalAlignment = Alignment.Top) {
              Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = Brand.Teal,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(10.dp))
              Text(rule, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
          }
        }
      }

      if (p.negative > 0) {
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Color(0xFFFEF7E0)) {
          Text(
            if (bengali) {
              "প্রতিটি ভুল উত্তরে ${num(p.negative, bengali)} নম্বর কাটা যাবে। উত্তর না দিলে কোনও নম্বর কাটা যাবে না।"
            } else {
              "Each wrong answer deducts ${p.negative}. An unanswered question costs nothing."
            },
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF7A5000),
          )
        }
      }

      Spacer(Modifier.height(24.dp))
      AuroraButton(t("পরীক্ষা শুরু করুন", "Start exam"), onClick = { exam.start() })
      Spacer(Modifier.height(10.dp))
      OutlinedButton(onClick = { exam.backToList() }, Modifier.fillMaxWidth().height(50.dp)) {
        Text(t("ফিরে যান", "Back"))
      }
    }
  }
}

/* ------------------------------------------------------------------ runner */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamRunnerScreen(exam: ExamController) {
  val a = exam.attempt ?: return
  val paper = exam.paper ?: return
  val q = exam.currentQuestion() ?: return
  val bengali = exam.bengali
  fun t(bn: String, en: String) = if (bengali) bn else en
  val scheme = MaterialTheme.colorScheme
  val extra = LocalExtraColors.current
  val review = exam.review
  val labels = paper.labels
  // Same inline palette the chat reply uses, so maths reads exactly as it would in an answer.
  val mathColors = Markdown.InlineColors(scheme.onSurface, scheme.surfaceContainerHigh, scheme.primary, scheme.tertiary)
  val index = paper.questions.indexOfFirst { it.n == q.n } + 1
  val chosen = a.answers[q.n]
  val marked = q.n in a.marked

  var paletteOpen by remember { mutableStateOf(false) }
  var confirmSubmit by remember { mutableStateOf(false) }
  var confirmLeave by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  Scaffold(
    containerColor = Color.Transparent,
    topBar = {
      Column {
        TopAppBar(
          title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(if (review) t("রিভিউ", "Review") else t("প্রশ্ন", "Question"), fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.width(8.dp))
              Text(
                "${num(index, bengali)} / ${num(paper.questions.size, bengali)}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
              )
            }
          },
          navigationIcon = {
            IconButton(onClick = { if (review) exam.leaveReview() else confirmLeave = true }) {
              Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("ফিরে যান", "Back"))
            }
          },
          actions = {
            if (!review) ClockChip(exam.remainingMs)
            IconButton(onClick = { paletteOpen = true }) {
              Icon(Icons.Rounded.GridView, contentDescription = t("প্রশ্ন তালিকা", "Question list"))
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        // Progress through the paper, right under the header.
        LinearProgressIndicator(
          progress = { index.toFloat() / paper.questions.size.coerceAtLeast(1) },
          modifier = Modifier.fillMaxWidth().height(3.dp),
          color = Brand.Violet,
          trackColor = scheme.surfaceContainerHigh,
          strokeCap = StrokeCap.Round,
          drawStopIndicator = {},
        )
      }
    },
    bottomBar = {
      Surface(color = scheme.surfaceContainerLowest.copy(alpha = 0.95f)) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          NavButton(
            icon = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(19.dp)) },
            label = t("আগের", "Prev"),
            onClick = { exam.step(-1) },
          )
          if (!review) {
            NavButton(
              icon = {
                Icon(
                  Icons.Rounded.CheckCircle, null, Modifier.size(19.dp),
                  tint = if (marked) Color(0xFFC55A11) else scheme.onSurfaceVariant,
                )
              },
              label = if (marked) t("চিহ্নিত", "Marked") else t("রিভিউ", "Mark"),
              tint = if (marked) Color(0xFFFEF7E0) else scheme.surfaceContainerHigh,
              onClick = { exam.toggleMark() },
            )
          }
          Spacer(Modifier.weight(1f))
          // Next is always reachable: an exam is answered question after question, and opening
          // the palette to move one step forward would be a needless detour.
          NavButton(
            icon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(19.dp)) },
            label = t("পরের", "Next"),
            onClick = { exam.step(1) },
          )
          if (!review) {
            AuroraButton(
              t("জমা", "Submit"),
              onClick = { confirmSubmit = true },
              modifier = Modifier.width(96.dp),
              height = 46,
            )
          }
        }
      }
    },
  ) { pad ->
    Column(
      Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
        .padding(start = 15.dp, end = 15.dp, top = 5.dp, bottom = 26.dp),
    ) {
      // Stem and options in one card, so the four choices read as one question rather than as a
      // row of unrelated buttons.
      SoftSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(Brand.Violet.copy(alpha = 0.13f)),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                num(index, bengali),
                style = MaterialTheme.typography.labelLarge,
                color = Brand.Violet,
                fontWeight = FontWeight.Bold,
              )
            }
            if (q.subject.isNotBlank()) {
              Spacer(Modifier.width(9.dp))
              MetaPill(q.subject, scheme.onPrimaryContainer, scheme.primaryContainer)
            }
            Spacer(Modifier.weight(1f))
            if (review && chosen != null) {
              val right = chosen == q.answer
              MetaPill(
                if (right) t("সঠিক", "Correct") else t("ভুল", "Wrong"),
                if (right) extra.ok else extra.bad,
                if (right) extra.okContainer else extra.badContainer,
              )
            }
          }
          Spacer(Modifier.height(14.dp))
          // Rendered exactly as a chat reply is: many stems are written as maths, and printed raw
          // they reach the reader as backslashes and braces.
          Text(Markdown.inline(q.text, mathColors), style = MaterialTheme.typography.titleMedium, lineHeight = 27.sp)
          Spacer(Modifier.height(16.dp))

          q.options.forEachIndexed { i, option ->
            val isRight = review && i == q.answer
            val isWrongPick = review && i == chosen && i != q.answer
            val picked = !review && i == chosen
            val accent = when {
              isRight -> extra.ok
              isWrongPick -> extra.bad
              picked -> scheme.primary
              else -> scheme.outlineVariant
            }
            val fill = when {
              isRight -> extra.okContainer
              isWrongPick -> extra.badContainer
              picked -> scheme.primaryContainer
              else -> scheme.surface
            }
            Row(
              Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(fill)
                .border(if (picked || isRight || isWrongPick) 1.6.dp else 1.dp, accent, RoundedCornerShape(14.dp))
                .clickable(enabled = !review) { exam.pick(i) }
                .padding(horizontal = 13.dp, vertical = 13.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                Modifier.size(29.dp).clip(CircleShape)
                  .background(if (picked || isRight || isWrongPick) accent else scheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  labels[i],
                  style = MaterialTheme.typography.labelLarge,
                  color = if (picked || isRight || isWrongPick) Color.White else scheme.onSurfaceVariant,
                )
              }
              Spacer(Modifier.width(12.dp))
              Text(Markdown.inline(option, mathColors), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
              if (isRight) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = extra.ok, modifier = Modifier.size(19.dp))
              } else if (isWrongPick) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = extra.bad, modifier = Modifier.size(19.dp))
              }
            }
          }
        }
      }

      // The explanation sits below the options, so a verdict never appears above what it judges.
      if (review) {
        Spacer(Modifier.height(12.dp))
        ExplanationCard(exam, q, mathColors)
      }
    }
  }

  if (paletteOpen) {
    ModalBottomSheet(onDismissRequest = { paletteOpen = false }, sheetState = sheetState) {
      PaletteSheet(
        paper = paper,
        attempt = a,
        review = review,
        bengali = bengali,
        onJump = { n -> paletteOpen = false; exam.goto(n) },
        onSubmit = { paletteOpen = false; confirmSubmit = true },
        onClose = { paletteOpen = false },
      )
    }
  }

  if (confirmSubmit) {
    val left = paper.questions.size - a.answeredCount
    AlertDialog(
      onDismissRequest = { confirmSubmit = false },
      title = { Text(t("জমা দেবেন?", "Submit the paper?")) },
      text = {
        Text(
          if (left > 0) {
            if (bengali) "${num(left, bengali)} টি প্রশ্নের উত্তর দেওয়া হয়নি। তবুও জমা দেবেন?"
            else "$left questions are unanswered. Submit anyway?"
          } else {
            t("সব প্রশ্নের উত্তর দেওয়া হয়েছে। জমা দেবেন?", "Every question is answered. Submit?")
          },
        )
      },
      confirmButton = {
        TextButton(onClick = { confirmSubmit = false; exam.submit() }) { Text(t("জমা দিন", "Submit")) }
      },
      dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text(t("বাতিল", "Cancel")) } },
    )
  }

  if (confirmLeave) {
    AlertDialog(
      onDismissRequest = { confirmLeave = false },
      title = { Text(t("পরীক্ষা ছেড়ে যাবেন?", "Leave the exam?")) },
      text = {
        Text(
          t(
            "উত্তর ও বাকি সময় সংরক্ষিত থাকবে; পরে আবার শুরু করতে পারবেন।",
            "Your answers and the remaining time are kept; you can resume later.",
          ),
        )
      },
      confirmButton = {
        TextButton(onClick = { confirmLeave = false; exam.backToList() }) { Text(t("হ্যাঁ, যান", "Leave")) }
      },
      dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(t("বাতিল", "Cancel")) } },
    )
  }
}

@Composable
private fun NavButton(
  icon: @Composable () -> Unit,
  label: String,
  tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
  onClick: () -> Unit,
) {
  Column(
    Modifier
      .clip(RoundedCornerShape(13.dp))
      .background(tint)
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 7.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    icon()
    Spacer(Modifier.height(1.dp))
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )
  }
}

@Composable
private fun ClockChip(ms: Long) {
  val extra = LocalExtraColors.current
  val danger = ms <= 60_000
  val warn = ms <= 300_000
  val bg = when {
    danger -> extra.badContainer
    warn -> Color(0xFFFEF7E0)
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
  }
  val fg = when {
    danger -> extra.bad
    warn -> Color(0xFF8A5A00)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  Row(
    Modifier.clip(RoundedCornerShape(11.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(15.dp), tint = fg)
    Spacer(Modifier.width(5.dp))
    Text(formatClock(ms), style = MaterialTheme.typography.labelLarge, color = fg)
  }
}

@Composable
private fun ExplanationCard(exam: ExamController, q: ExamQuestion, mathColors: Markdown.InlineColors) {
  val bengali = exam.bengali
  fun t(bn: String, en: String) = if (bengali) bn else en
  val scheme = MaterialTheme.colorScheme
  val generated = exam.explanationFor(q)
  val asked = exam.explanationAsked(q)

  Surface(
    Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = scheme.surfaceContainerLow,
    border = BorderStroke(1.dp, Brand.Teal.copy(alpha = 0.34f)),
  ) {
    Column(Modifier.padding(17.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = Brand.Teal, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(
          t("ব্যাখ্যা", "Explanation").uppercase(),
          style = MaterialTheme.typography.labelMedium,
          color = Brand.Teal,
          letterSpacing = 0.7.sp,
        )
      }
      Spacer(Modifier.height(10.dp))
      when {
        // Paper explanations carry the same maths as the questions, so they take the same
        // renderer rather than printing TeX.
        !generated.isNullOrBlank() -> {
          Text(Markdown.inline(generated, mathColors), style = MaterialTheme.typography.bodyMedium)
          Spacer(Modifier.height(8.dp))
          Text(
            if (q.explain != null) t("প্রশ্নপত্রের ব্যাখ্যা", "From the paper")
            else t("মডেলের ব্যাখ্যা", "Written by the model"),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
          )
        }
        asked -> Text(
          t("এই প্রশ্নের ব্যাখ্যা পাওয়া যায়নি।", "No explanation available for this question."),
          style = MaterialTheme.typography.bodyMedium,
          color = scheme.onSurfaceVariant,
        )
        else -> {
          Button(
            onClick = { exam.explain() },
            enabled = !exam.explaining,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(containerColor = Brand.Teal),
          ) {
            if (exam.explaining) {
              CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
              Spacer(Modifier.width(9.dp))
              Text(t("লিখছে…", "Writing…"))
            } else {
              Text(t("কেন এই উত্তর?", "Why this answer?"))
            }
          }
          Spacer(Modifier.height(8.dp))
          Text(
            t("উত্তরকী সঠিক; ব্যাখ্যাটি মডেল লিখবে।", "The key is correct; the model writes the reason."),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun PaletteSheet(
  paper: ExamPaper,
  attempt: ExamAttempt,
  review: Boolean,
  bengali: Boolean,
  onJump: (Int) -> Unit,
  onSubmit: () -> Unit,
  onClose: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val extra = LocalExtraColors.current
  fun t(bn: String, en: String) = if (bengali) bn else en
  val left = paper.questions.size - attempt.answeredCount

  Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 22.dp)) {
    Text(t("প্রশ্ন তালিকা", "Question list"), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
      if (bengali) {
        "${num(attempt.answeredCount, bengali)} উত্তর দেওয়া · ${num(attempt.marked.size, bengali)} রিভিউ চিহ্নিত"
      } else {
        "${attempt.answeredCount} answered · ${attempt.marked.size} marked"
      },
      style = MaterialTheme.typography.labelMedium,
      color = scheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    LazyVerticalGrid(
      columns = GridCells.Adaptive(46.dp),
      modifier = Modifier.heightIn(max = 420.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(paper.questions) { item ->
        val answered = attempt.answers[item.n] != null
        val isMarked = item.n in attempt.marked
        val current = item.n == attempt.current
        val bg = when {
          answered -> extra.okContainer
          isMarked -> Color(0xFFFEF7E0)
          else -> scheme.surface
        }
        val fg = when {
          answered -> extra.ok
          isMarked -> Color(0xFF8A5A00)
          else -> scheme.onSurfaceVariant
        }
        Box(
          Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(
              if (current) 2.dp else 1.dp,
              if (current) Brand.Violet else if (isMarked) Color(0xFFF9AB00) else scheme.outlineVariant,
              RoundedCornerShape(12.dp),
            )
            .clickable { onJump(item.n) },
          contentAlignment = Alignment.Center,
        ) {
          Text(num(item.n, bengali), style = MaterialTheme.typography.labelLarge, color = fg)
        }
      }
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Legend(extra.okContainer, extra.ok, t("উত্তর দেওয়া", "Answered"))
      Legend(Color(0xFFFEF7E0), Color(0xFFF9AB00), t("রিভিউ", "Marked"))
      Legend(scheme.surface, scheme.outlineVariant, t("খালি", "Blank"))
    }
    Spacer(Modifier.height(16.dp))
    if (review) {
      OutlinedButton(onClick = onClose, Modifier.fillMaxWidth().height(48.dp)) {
        Text(t("বন্ধ করুন", "Close"))
      }
    } else {
      AuroraButton(
        if (left > 0) {
          if (bengali) "জমা দিন · ${num(left, bengali)} বাকি" else "Submit · $left left"
        } else {
          t("জমা দিন", "Submit")
        },
        onClick = onSubmit,
        height = 48,
      )
    }
  }
}

@Composable
private fun Legend(swatch: Color, border: Color, label: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
      Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(swatch)
        .border(1.dp, border, RoundedCornerShape(4.dp)),
    )
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/* ------------------------------------------------------------------ result */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamResultScreen(exam: ExamController) {
  val a = exam.attempt ?: return
  val s = a.score ?: return
  val paper = exam.paper
  val bengali = exam.bengali
  fun t(bn: String, en: String) = if (bengali) bn else en
  val scheme = MaterialTheme.colorScheme
  val extra = LocalExtraColors.current
  val ringTint = when {
    s.accuracy >= 60 -> extra.ok
    s.accuracy >= 34 -> Color(0xFFF9AB00)
    else -> extra.bad
  }
  var started by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { started = true }
  val shownNet by animateFloatAsState(
    targetValue = if (started) s.net.toFloat() else 0f,
    animationSpec = tween(800),
    label = "net",
  )

  Scaffold(
    containerColor = Color.Transparent,
    topBar = {
      TopAppBar(
        title = { Text(t("ফলাফল", "Result"), fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
          IconButton(onClick = { exam.backToList() }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("ফিরে যান", "Back"))
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    },
  ) { pad ->
    Column(
      Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
        .padding(start = 18.dp, end = 18.dp, bottom = 34.dp),
    ) {
      SoftSurface(Modifier.fillMaxWidth(), color = scheme.surfaceContainerLow) {
        Column(
          Modifier.fillMaxWidth().padding(vertical = 26.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          ScoreRing(s.accuracy.toFloat(), ringTint)
          Spacer(Modifier.height(16.dp))
          Row(verticalAlignment = Alignment.Bottom) {
            Text(
              num(shownNet.toDouble(), bengali),
              style = MaterialTheme.typography.displaySmall,
              fontSize = 40.sp,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              " / ${num(s.maxMarks, bengali)}",
              style = MaterialTheme.typography.titleMedium,
              color = scheme.onSurfaceVariant,
              modifier = Modifier.padding(bottom = 6.dp),
            )
          }
          Text(
            t("নেট নম্বর", "Net score") +
              if (s.negative > 0) {
                if (bengali) " · প্রতি ভুলে ${num(s.negative, bengali)} কাটা"
                else " · ${s.negative} deducted per wrong"
              } else "",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
          )
        }
      }

      Spacer(Modifier.height(12.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(num(s.correct, bengali), t("সঠিক", "Correct"), extra.ok, Modifier.weight(1f))
        StatTile(num(s.wrong, bengali), t("ভুল", "Wrong"), extra.bad, Modifier.weight(1f))
        StatTile(num(s.skipped, bengali), t("বাদ", "Skipped"), scheme.onSurfaceVariant, Modifier.weight(1f))
      }

      if (s.bySubject.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        SectionLabel(t("বিষয়ভিত্তিক ফলাফল", "By subject"))
        Spacer(Modifier.height(4.dp))
        SoftSurface(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            s.bySubject.forEach { row -> SubjectBar(row, bengali, extra) }
          }
        }
      }

      val weak = s.bySubject.filter { it.total >= 3 && 100.0 * it.correct / it.total < 50.0 }
      if (weak.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = Color(0xFFFEF7E0)) {
          Text(
            if (bengali) {
              "দুর্বল বিষয়: ${weak.joinToString(", ") { it.subject }} — এগুলো আগে পড়ুন।"
            } else {
              "Weak areas: ${weak.joinToString(", ") { it.subject }} — revise these first."
            },
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF7A5000),
          )
        }
      }

      Spacer(Modifier.height(24.dp))
      AuroraButton(t("উত্তর দেখুন", "Review answers"), onClick = { exam.openReview() })
      Spacer(Modifier.height(10.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
          onClick = { exam.retry() },
          modifier = Modifier.weight(1f).height(50.dp),
          enabled = paper != null,
        ) { Text(t("আবার দিন", "Retake")) }
        OutlinedButton(onClick = { exam.backToList() }, Modifier.weight(1f).height(50.dp)) {
          Text(t("তালিকা", "List"))
        }
      }
    }
  }
}

@Composable
private fun SubjectBar(row: ExamSubjectScore, bengali: Boolean, extra: ExtraColors) {
  val scheme = MaterialTheme.colorScheme
  val pct = if (row.total > 0) 100f * row.correct / row.total else 0f
  val tint = when {
    pct >= 60 -> extra.ok
    pct >= 34 -> Color(0xFFF9AB00)
    else -> extra.bad
  }
  Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        row.subject,
        Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "${num(row.correct, bengali)}/${num(row.total, bengali)}",
        style = MaterialTheme.typography.labelMedium,
        color = scheme.onSurfaceVariant,
      )
      Spacer(Modifier.width(8.dp))
      Text(
        "${num(pct.toInt(), bengali)}%",
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        fontWeight = FontWeight.SemiBold,
      )
    }
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(scheme.surfaceContainerHigh)) {
      Box(
        Modifier.fillMaxWidth(pct / 100f).height(6.dp).clip(RoundedCornerShape(3.dp))
          .background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.72f), tint))),
      )
    }
  }
}
