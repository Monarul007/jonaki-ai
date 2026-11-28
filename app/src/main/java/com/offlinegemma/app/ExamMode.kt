package com.offlinegemma.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.offlinegemma.app.ui.LocalExtraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exam mode's screens. One composable per phase; [ExamModeScreen] picks between them so the
 * navigation rules (back from a sub-screen must never close the app) live in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamModeScreen(exam: ExamController, onExit: () -> Unit) {
  // Back is one level up, never out of the app: first leave a sheet-free sub-screen, then exit.
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

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    when (exam.phase) {
      ExamPhase.LIST -> ExamListScreen(exam, onExit)
      ExamPhase.BRIEF -> ExamBriefScreen(exam)
      ExamPhase.RUNNING, ExamPhase.REVIEW -> ExamRunnerScreen(exam)
      ExamPhase.RESULT -> ExamResultScreen(exam)
    }
  }
}

/* ------------------------------------------------------------------ list */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExamListScreen(exam: ExamController, onExit: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var papers by remember { mutableStateOf<List<ExamPaper>>(emptyList()) }
  var category by remember { mutableStateOf<String?>(null) }
  var loading by remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    papers = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      Exams.papers(context)
    }
    exam.refreshSaved()
    loading = false
  }

  val categories = papers.map { it.category }.distinct().sorted()
  val shown = papers.filter { category == null || it.category == category }
  val bengali = exam.bengali
  fun t(bn: String, en: String) = if (bengali) bn else en

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(t("পরীক্ষা", "Exams"), fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
          IconButton(onClick = onExit) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("ফিরে যান", "Back"))
          }
        },
      )
    },
  ) { pad ->
    LazyColumn(
      Modifier.fillMaxSize().padding(pad),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Text(
          t(
            "বাংলাদেশি চাকরির পরীক্ষার বাস্তব প্রশ্নপত্র, সময় ও নম্বর কাটার নিয়মসহ।",
            "Real papers with their own time limit and marking rule.",
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (loading) {
        item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
          androidx.compose.material3.CircularProgressIndicator(Modifier.size(26.dp))
        } }
      }

      if (!loading && papers.isEmpty()) {
        item {
          Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
              Text(t("প্রশ্নপত্র পাওয়া যায়নি", "No papers found"), fontWeight = FontWeight.SemiBold)
              Spacer(Modifier.height(6.dp))
              Text(
                t(
                  "অ্যাপে পরীক্ষার প্যাক নেই। Settings → Storage থেকে স্টাডি ফাইল যোগ করুন।",
                  "The exam pack is not installed. Add your study files from Settings → Storage.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      if (categories.size > 1) {
        item {
          LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
              FilterChip(
                selected = category == null,
                onClick = { category = null },
                label = { Text(t("সব", "All")) },
              )
            }
            items(categories) { c ->
              FilterChip(
                selected = category == c,
                onClick = { category = c },
                label = { Text(categoryLabel(c, bengali)) },
              )
            }
          }
        }
      }

      // Saved attempts first: an unfinished paper is the most urgent thing on this screen.
      val saved = exam.saved
      if (saved.isNotEmpty()) {
        item {
          Text(
            t("আপনার পরীক্ষা", "Your exams"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 6.dp),
          )
        }
        items(saved) { a ->
          SavedAttemptRow(a, bengali) { exam.resume(a) }
        }
      }

      if (!loading) {
        item {
          Text(
            t("প্রশ্নপত্র", "Question papers"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 6.dp),
          )
        }
      }

      // A fresh paper in the shape of a real one, sampled from the same category.
      if (!loading && categories.isNotEmpty()) {
        item {
          var mockCategory by remember(categories) {
            mutableStateOf(category ?: categories.first())
          }
          var mockCount by remember { mutableStateOf(50) }
          Card(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
          ) {
            Column(Modifier.padding(14.dp)) {
              Text(
                t("নিজস্ব মডেল টেস্ট", "Build a mock"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Spacer(Modifier.height(4.dp))
              Text(
                t(
                  "প্রকৃত প্রশ্নপত্রের বিষয়-অনুপাত ধরে প্রশ্ন বাছা হবে; ক্রম ও অপশন বদলে দেওয়া হবে।",
                  "Sampled with the real subject mix; order and options are shuffled.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Spacer(Modifier.height(10.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 50, 100).forEach { n ->
                  FilterChip(
                    selected = mockCount == n,
                    onClick = { mockCount = n },
                    label = { Text("$n") },
                  )
                }
              }
              Spacer(Modifier.height(10.dp))
              Text(
                t("পরীক্ষা", "Exam"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Spacer(Modifier.height(4.dp))
              LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { c ->
                  FilterChip(
                    selected = mockCategory == c,
                    onClick = { mockCategory = c },
                    label = { Text(categoryLabel(c, bengali)) },
                  )
                }
              }
              Spacer(Modifier.height(8.dp))
              Button(
                onClick = {
                  // The mock's own paperMinutes() supplies the real duration for the category.
                  val mock = ExamScoring.mock(
                    papers, mockCategory, mockCount, 0,
                    seed = System.currentTimeMillis(),
                  )
                  if (mock != null) exam.openBrief(mock)
                },
                Modifier.fillMaxWidth().height(46.dp),
              ) { Text(t("তৈরি করুন", "Create")) }
            }
          }
        }
      }

      items(shown) { p ->
        PaperCard(p, bengali) { exam.openBrief(p) }
      }
    }
  }
}

private fun categoryLabel(c: String, bengali: Boolean): String = when (c) {
  "bcs" -> if (bengali) "বিসিএস" else "BCS"
  "ntrca" -> if (bengali) "এনটিআরসিএ" else "NTRCA"
  else -> if (bengali) "অন্যান্য" else "Other"
}

@Composable
private fun PaperCard(p: ExamPaper, bengali: Boolean, onClick: () -> Unit) {
  val extra = LocalExtraColors.current
  Card(
    Modifier.fillMaxWidth().clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(Modifier.padding(14.dp)) {
      Text(
        if (bengali) p.titleBn else p.title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(6.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "${p.questions.size} · ${p.durationMinutes}m",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (p.negative > 0) {
          Spacer(Modifier.width(8.dp))
          Pill("−${p.negative}", extra.bad, extra.badContainer)
        }
      }
    }
  }
}

@Composable
private fun SavedAttemptRow(a: ExamAttempt, bengali: Boolean, onClick: () -> Unit) {
  val extra = LocalExtraColors.current
  val left = a.remainingMs()
  Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (a.running && left > 0) {
            Pill(
              if (bengali) "চলমান · ${formatClock(left)}" else "Running · ${formatClock(left)}",
              MaterialTheme.colorScheme.primary,
              MaterialTheme.colorScheme.primaryContainer,
            )
          } else {
            val net = a.score?.net
            Pill(
              if (net != null) "${net} ${if (bengali) "নম্বর" else "marks"}" else if (bengali) "শেষ" else "Finished",
              extra.ok, extra.okContainer,
            )
          }
          Spacer(Modifier.width(8.dp))
          Text(
            "${a.answeredCount}/${a.questionNumbers.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Icon(
        if (a.running && left > 0) Icons.Rounded.PlayArrow else Icons.Rounded.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
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

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(t("নির্দেশনা", "Instructions"), fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
          IconButton(onClick = { exam.backToList() }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("ফিরে যান", "Back"))
          }
        },
      )
    },
  ) { pad ->
    Column(
      Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
      Text(
        if (bengali) p.titleBn else p.title,
        style = MaterialTheme.typography.headlineSmall,
      )
      Spacer(Modifier.height(14.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(p.questions.size.toString(), t("প্রশ্ন", "Questions"), Modifier.weight(1f))
        Stat(p.durationMinutes.toString(), t("মিনিট", "Minutes"), Modifier.weight(1f))
        Stat(if (p.negative > 0) "${p.negative}" else "0", t("নেগেটিভ", "Negative"), Modifier.weight(1f))
      }
      Spacer(Modifier.height(16.dp))
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
      rules.forEach { r ->
        Row(Modifier.padding(vertical = 4.dp)) {
          Text("•  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(r, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
      if (p.negative > 0) {
        Spacer(Modifier.height(12.dp))
        val extra = LocalExtraColors.current
        Card(colors = CardDefaults.cardColors(containerColor = extra.badContainer)) {
          Text(
            if (bengali) "প্রতিটি ভুল উত্তরে ${p.negative} নম্বর কাটা যাবে। উত্তর না দিলে কোনও নম্বর কাটা যাবে না।"
            else "Each wrong answer deducts ${p.negative}. An unanswered question costs nothing.",
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = extra.bad,
          )
        }
      }
      Spacer(Modifier.height(22.dp))
      Button(
        onClick = { exam.start() },
        Modifier.fillMaxWidth().height(50.dp),
      ) {
        Text(t("পরীক্ষা শুরু করুন", "Start exam"), style = MaterialTheme.typography.titleMedium)
      }
      Spacer(Modifier.height(8.dp))
      OutlinedButton(onClick = { exam.backToList() }, Modifier.fillMaxWidth().height(48.dp)) {
        Text(t("ফিরে যান", "Back"))
      }
    }
  }
}

@Composable
private fun Stat(value: String, caption: String, modifier: Modifier = Modifier) {
  Card(modifier) {
    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(value, style = MaterialTheme.typography.headlineSmall)
      Text(
        caption,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color) {
  Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = fg,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(bg)
      .padding(horizontal = 7.dp, vertical = 2.dp),
  )
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
  val extra = LocalExtraColors.current
  val review = exam.review
  val labels = paper.labels
  // Same inline palette the chat reply uses, so maths in an exam question reads exactly as it
  // would in an answer.
  val mathColors = Markdown.InlineColors(
    MaterialTheme.colorScheme.onSurface,
    MaterialTheme.colorScheme.surfaceContainerHigh,
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
  )
  val index = paper.questions.indexOfFirst { it.n == q.n } + 1
  val chosen = a.answers[q.n]

  var paletteOpen by remember { mutableStateOf(false) }
  var confirmSubmit by remember { mutableStateOf(false) }
  var confirmLeave by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              if (review) t("রিভিউ", "Review") else t("প্রশ্ন", "Question"),
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
              "$index / ${paper.questions.size}",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = {
            if (review) exam.leaveReview() else confirmLeave = true
          }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("ফিরে যান", "Back"))
          }
        },
        actions = {
          if (!review) {
            ClockChip(exam.remainingMs, bengali)
            IconButton(onClick = { paletteOpen = true }) {
              Icon(Icons.Rounded.GridView, contentDescription = t("প্রশ্ন তালিকা", "Question list"))
            }
          } else {
            IconButton(onClick = { paletteOpen = true }) {
              Icon(Icons.Rounded.GridView, contentDescription = t("প্রশ্ন তালিকা", "Question list"))
            }
          }
        },
      )
    },
    bottomBar = {
      Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedButton(onClick = { exam.step(-1) }, Modifier.height(44.dp)) {
            Text(t("আগের", "Prev"))
          }
          if (!review) {
            val marked = q.n in a.marked
            OutlinedButton(
              onClick = { exam.toggleMark() },
              Modifier.height(44.dp),
              border = if (marked) BorderStroke(1.dp, Color(0xFFF9AB00)) else null,
            ) {
              Text(t(if (marked) "✓ রিভিউ" else "রিভিউ", if (marked) "✓ Marked" else "Mark"))
            }
          }
          Spacer(Modifier.weight(1f))
          OutlinedButton(onClick = { exam.step(1) }, Modifier.height(44.dp)) {
            Text(t("পরের", "Next"))
          }
          if (!review) {
            Button(onClick = { confirmSubmit = true }, Modifier.height(44.dp)) {
              Text(t("জমা", "Submit"))
            }
          }
        }
      }
    },
  ) { pad ->
    Column(
      Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
      if (q.subject.isNotBlank()) {
        Pill(
          q.subject,
          MaterialTheme.colorScheme.primary,
          MaterialTheme.colorScheme.primaryContainer,
        )
        Spacer(Modifier.height(10.dp))
      }
      // The paper's own wording is rendered exactly as a chat reply is: many questions are
      // written as maths ("\(a+b=5\)", "\frac{1}{2}", "\sqrt{216}") and printed raw they reach
      // the reader as backslashes and braces. Markdown.inline() already converts those to serif
      // italic maths with real super/subscripts, so exam mode uses the same path as the chat.
      Text(
        buildAnnotatedString {
          append("$index.  ")
          append(Markdown.inline(q.text, mathColors))
        },
        style = MaterialTheme.typography.titleMedium,
        lineHeight = 26.sp,
      )
      Spacer(Modifier.height(16.dp))

      q.options.forEachIndexed { i, option ->
        val right = review && i == q.answer
        val wrongPick = review && i == chosen && i != q.answer
        val picked = !review && i == chosen
        val border = when {
          right -> extra.ok
          wrongPick -> extra.bad
          picked -> MaterialTheme.colorScheme.primary
          else -> MaterialTheme.colorScheme.outlineVariant
        }
        val fill = when {
          right -> extra.okContainer
          wrongPick -> extra.badContainer
          picked -> MaterialTheme.colorScheme.primaryContainer
          else -> MaterialTheme.colorScheme.surface
        }
        Row(
          Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(enabled = !review) { exam.pick(i) }
            .padding(13.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            Modifier
              .size(28.dp)
              .clip(CircleShape)
              .background(if (right || wrongPick || picked) border else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              labels[i],
              style = MaterialTheme.typography.labelLarge,
              color = if (right || wrongPick || picked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Spacer(Modifier.width(12.dp))
          Text(
            Markdown.inline(option, mathColors),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
          )
          if (right) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = extra.ok)
          } else if (wrongPick) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = extra.bad)
          }
        }
      }

      // The explanation sits below the options, so a verdict never appears above what it judges.
      if (review) {
        Spacer(Modifier.height(14.dp))
        val own = q.explain
        val generated = exam.explanationFor(q)
        val asked = exam.explanationAsked(q)
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(14.dp)) {
            Text(
              t("ব্যাখ্যা", "Explanation"),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when {
              // Paper explanations carry the same maths as the questions, so they take the
              // same renderer rather than printing TeX.
              !generated.isNullOrBlank() -> Text(
                Markdown.inline(generated, mathColors),
                style = MaterialTheme.typography.bodyMedium,
              )
              asked -> Text(
                t("এই প্রশ্নের ব্যাখ্যা পাওয়া যায়নি।", "No explanation available for this question."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              else -> {
                OutlinedButton(
                  onClick = { exam.explain() },
                  enabled = !exam.explaining,
                ) {
                  if (exam.explaining) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(t("লিখছে…", "Writing…"))
                  } else {
                    Icon(Icons.Rounded.Lightbulb, contentDescription = null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("কেন এই উত্তর?", "Why this answer?"))
                  }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                  t("উত্তরকী সঠিক; ব্যাখ্যাটি মডেল লিখবে।", "The key is correct; the model writes the reason."),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            if (own != null) {
              Spacer(Modifier.height(6.dp))
              Text(
                t("প্রশ্নপত্রের ব্যাখ্যা", "From the paper"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }

  if (paletteOpen) {
    ModalBottomSheet(
      onDismissRequest = { paletteOpen = false },
      sheetState = sheetState,
    ) {
      Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 18.dp)) {
        Text(
          t("প্রশ্ন তালিকা", "Question list"),
          style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
          columns = GridCells.Adaptive(44.dp),
          modifier = Modifier.heightIn(max = 420.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(paper.questions) { item ->
            val answered = a.answers[item.n] != null
            val marked = item.n in a.marked
            val current = item.n == a.current
            val bg = when {
              answered -> extra.okContainer
              marked -> Color(0xFFFEF7E0)
              else -> MaterialTheme.colorScheme.surface
            }
            val fg = when {
              answered -> extra.ok
              marked -> Color(0xFF8A5A00)
              else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
              Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(bg)
                .border(
                  if (current) 2.dp else 1.dp,
                  if (current) MaterialTheme.colorScheme.primary
                  else if (marked) Color(0xFFF9AB00) else MaterialTheme.colorScheme.outlineVariant,
                  RoundedCornerShape(9.dp),
                )
                .clickable {
                  paletteOpen = false
                  exam.goto(item.n)
                },
              contentAlignment = Alignment.Center,
            ) {
              Text(item.n.toString(), style = MaterialTheme.typography.labelLarge, color = fg)
            }
          }
        }
        Spacer(Modifier.height(12.dp))
        if (!review) {
          Button(
            onClick = { paletteOpen = false; confirmSubmit = true },
            Modifier.fillMaxWidth().height(46.dp),
          ) {
            val left = paper.questions.size - a.answeredCount
            Text(
              if (left > 0) t("জমা দিন ($left বাকি)", "Submit ($left unanswered)")
              else t("জমা দিন", "Submit"),
            )
          }
        }
      }
    }
  }

  if (confirmSubmit) {
    val left = paper.questions.size - a.answeredCount
    AlertDialog(
      onDismissRequest = { confirmSubmit = false },
      title = { Text(t("জমা দেবেন?", "Submit the paper?")) },
      text = {
        Text(
          if (left > 0) t("$left টি প্রশ্নের উত্তর দেওয়া হয়নি। তবুও জমা দেবেন?", "$left questions are unanswered. Submit anyway?")
          else t("সব প্রশ্নের উত্তর দেওয়া হয়েছে। জমা দেবেন?", "Every question is answered. Submit?"),
        )
      },
      confirmButton = {
        TextButton(onClick = { confirmSubmit = false; exam.submit() }) {
          Text(t("জমা দিন", "Submit"))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmSubmit = false }) { Text(t("বাতিল", "Cancel")) }
      },
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
        TextButton(onClick = { confirmLeave = false; exam.backToList() }) {
          Text(t("হ্যাঁ, যান", "Leave"))
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmLeave = false }) { Text(t("বাতিল", "Cancel")) }
      },
    )
  }
}

@Composable
private fun ClockChip(ms: Long, bengali: Boolean) {
  val extra = LocalExtraColors.current
  val danger = ms <= 60_000
  val warn = ms <= 300_000
  val bg = when {
    danger -> extra.badContainer
    warn -> Color(0xFFFEF7E0)
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  val fg = when {
    danger -> extra.bad
    warn -> Color(0xFF8A5A00)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  Row(
    Modifier
      .clip(RoundedCornerShape(9.dp))
      .background(bg)
      .padding(horizontal = 9.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Icons.Rounded.Timer, contentDescription = null, Modifier.size(14.dp), tint = fg)
    Spacer(Modifier.width(5.dp))
    Text(formatClock(ms), style = MaterialTheme.typography.labelLarge, color = fg)
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
  val extra = LocalExtraColors.current
  fun t(bn: String, en: String) = if (bengali) bn else en
  val progress by animateFloatAsState(s.accuracy.toFloat() / 100f, tween(600), label = "accuracy")

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(t("ফলাফল", "Result"), fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
          IconButton(onClick = { exam.backToList() }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = t("ফিরে যান", "Back"))
          }
        },
      )
    },
  ) { pad ->
    Column(
      Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          Row(verticalAlignment = Alignment.Bottom) {
            Text("${s.net}", style = MaterialTheme.typography.displaySmall)
            Text(
              " / ${s.maxMarks}",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Text(
            t("নেট নম্বর", "Net score") +
              if (s.negative > 0) t(" · প্রতি ভুলে ${s.negative} কাটা", " · ${s.negative} deducted per wrong")
              else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(16.dp))
          LinearProgressIndicator(
            progress = { progress },
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = when {
              s.accuracy >= 60 -> extra.ok
              s.accuracy >= 34 -> Color(0xFFF9AB00)
              else -> extra.bad
            },
          )
          Spacer(Modifier.height(6.dp))
          Text(
            "${s.accuracy}% " + t("সঠিক", "correct"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Spacer(Modifier.height(12.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberStat("${s.correct}", t("সঠিক", "Correct"), extra.ok, Modifier.weight(1f))
        NumberStat("${s.wrong}", t("ভুল", "Wrong"), extra.bad, Modifier.weight(1f))
        NumberStat("${s.skipped}", t("বাদ", "Skipped"), MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
      }

      if (s.bySubject.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text(t("বিষয়ভিত্তিক ফলাফল", "By subject"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        s.bySubject.forEach { row ->
          val pct = if (row.total > 0) 100f * row.correct / row.total else 0f
          Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                row.subject,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                "${row.correct}/${row.total}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
              progress = { pct / 100f },
              Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
              color = when {
                pct >= 60 -> extra.ok
                pct >= 34 -> Color(0xFFF9AB00)
                else -> extra.bad
              },
            )
          }
        }
      }

      val weak = s.bySubject.filter { it.total >= 3 && 100.0 * it.correct / it.total < 50.0 }
      if (weak.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF7E0))) {
          Text(
            t(
              "দুর্বল বিষয়: ${weak.joinToString(", ") { it.subject }} — এগুলো আগে পড়ুন।",
              "Weak areas: ${weak.joinToString(", ") { it.subject }} — revise these first.",
            ),
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A5A00),
          )
        }
      }

      Spacer(Modifier.height(20.dp))
      Button(onClick = { exam.openReview() }, Modifier.fillMaxWidth().height(50.dp)) {
        Text(t("উত্তর দেখুন", "Review answers"), style = MaterialTheme.typography.titleMedium)
      }
      Spacer(Modifier.height(8.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = { exam.retry() },
          Modifier.weight(1f).height(48.dp),
          enabled = paper != null,
        ) { Text(t("আবার দিন", "Retake")) }
        OutlinedButton(onClick = { exam.backToList() }, Modifier.weight(1f).height(48.dp)) {
          Text(t("তালিকা", "List"))
        }
      }
    }
  }
}

@Composable
private fun NumberStat(value: String, caption: String, color: Color, modifier: Modifier = Modifier) {
  Card(modifier) {
    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(value, style = MaterialTheme.typography.headlineSmall, color = color)
      Text(
        caption,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
