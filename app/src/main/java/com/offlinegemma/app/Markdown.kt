package com.offlinegemma.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.em

/**
 * A small Markdown reader, ported from chat.html md()/inline()/tex().
 * Handles what Gemma writes: headings, lists, quotes, tables, code, maths and interactive demos.
 */
object Markdown {

  sealed class Block {
    data class Para(val text: String) : Block()
    data class Heading(val level: Int, val text: String) : Block()
    data class Item(val ordered: Boolean, val number: Int, val text: String) : Block()
    data class Quote(val text: String) : Block()
    data class Code(val lang: String, val body: String) : Block()
    data class Math(val tex: String) : Block()
    data class Table(val head: List<String>, val rows: List<List<String>>) : Block()
    data object Rule : Block()
    /** A live HTML demo. [closed] = false while still streaming. */
    data class Demo(val html: String, val closed: Boolean) : Block()
    data class Svg(val source: String) : Block()
  }

  private val FENCE = Regex("""^\s*```\s*([\w+-]*)""")
  private val DEMO_TAGS = Regex("""<(script|canvas|input|svg|button)\b""", RegexOption.IGNORE_CASE)
  private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
  private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
  private val TABLE_SEP = Regex("""^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$""")
  private val UL = Regex("""^\s*[-*+•]\s+(.*)$""")
  private val OL = Regex("""^\s*(\d+)[.)]\s+(.*)$""")

  fun parse(src: String): List<Block> {
    val lines = Tutor.stripNext(src.replace("\r\n", "\n")).split("\n")
    val out = mutableListOf<Block>()
    val para = mutableListOf<String>()
    fun flush() {
      if (para.isNotEmpty()) {
        val t = para.joinToString("\n").trim()
        if (t.isNotEmpty()) out.add(Block.Para(t))
        para.clear()
      }
    }
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      val fence = FENCE.find(line)
      if (fence != null) {
        flush()
        val lang = fence.groupValues[1].lowercase()
        val buf = mutableListOf<String>()
        i++
        while (i < lines.size && !lines[i].trimStart().startsWith("```")) buf.add(lines[i++])
        val closed = i < lines.size
        i++
        val body = buf.joinToString("\n")
        when {
          (lang == "html" || lang == "htm") && (!closed || DEMO_TAGS.containsMatchIn(body)) -> out.add(Block.Demo(body, closed))
          lang == "svg" && closed && Regex("<svg[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(body) -> out.add(Block.Svg(body))
          else -> out.add(Block.Code(lang, body))
        }
        continue
      }
      if (line.trimStart().startsWith("$$")) {
        flush()
        var body = line.trimStart().removePrefix("$$")
        if (body.isNotBlank() && body.trimEnd().endsWith("$$")) {
          body = body.trimEnd().removeSuffix("$$")
        } else {
          val buf = if (body.isNotBlank()) mutableListOf(body) else mutableListOf()
          i++
          while (i < lines.size && !lines[i].trimEnd().endsWith("$$")) buf.add(lines[i++])
          if (i < lines.size) buf.add(lines[i].trimEnd().removeSuffix("$$"))
          body = buf.joinToString("\n")
        }
        i++
        out.add(Block.Math(body.trim()))
        continue
      }
      val h = HEADING.find(line)
      if (h != null) {
        flush(); out.add(Block.Heading(h.groupValues[1].length, h.groupValues[2])); i++
        continue
      }
      if (RULE.matches(line)) { flush(); out.add(Block.Rule); i++; continue }
      if (line.trimStart().startsWith(">")) {
        flush()
        val buf = mutableListOf<String>()
        while (i < lines.size && lines[i].trimStart().startsWith(">")) buf.add(lines[i++].trimStart().removePrefix(">").removePrefix(" "))
        out.add(Block.Quote(buf.joinToString("\n")))
        continue
      }
      if ('|' in line && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
        flush()
        val rows = mutableListOf<String>()
        while (i < lines.size && '|' in lines[i]) rows.add(lines[i++])
        fun cells(r: String) = r.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
        out.add(Block.Table(cells(rows[0]), rows.drop(2).map(::cells)))
        continue
      }
      val ul = UL.find(line)
      val ol = OL.find(line)
      if (ul != null || ol != null) {
        flush()
        if (ul != null) out.add(Block.Item(false, 0, ul.groupValues[1]))
        else out.add(Block.Item(true, ol!!.groupValues[1].toIntOrNull() ?: 1, ol.groupValues[2]))
        i++
        continue
      }
      if (line.isBlank()) { flush(); i++; continue }
      para.add(line)
      i++
    }
    flush()
    return out
  }

  // ---------- maths ----------

  private val TEX_SYMBOLS = mapOf(
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
    "Rightarrow" to "⇒", "rightleftharpoons" to "⇌", "leftrightarrows" to "⇌",
    "approx" to "≈", "neq" to "≠", "ne" to "≠", "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
    "infty" to "∞", "propto" to "∝", "degree" to "°", "circ" to "°",
    "Delta" to "Δ", "delta" to "δ", "alpha" to "α", "beta" to "β", "gamma" to "γ", "theta" to "θ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ",
    "phi" to "φ", "omega" to "ω", "Omega" to "Ω", "eta" to "η", "kappa" to "κ",
    "sum" to "∑", "int" to "∫", "partial" to "∂", "nabla" to "∇", "hbar" to "ℏ", "sqrt" to "√",
    "ldots" to "…", "cdots" to "⋯", "in" to "∈", "forall" to "∀", "exists" to "∃",
  )

  // Function names written as commands (\max, \log): show the plain word.
  private val TEX_WORDS = setOf("max", "min", "log", "ln", "sin", "cos", "tan", "exp", "lim", "gcd", "mod", "det", "arg")

  // Private markers for superscript / subscript inside converted maths.
  const val SUP_ON = '\uE006'; const val SUP_OFF = '\uE007'
  const val SUB_ON = '\uE008'; const val SUB_OFF = '\uE009'

  /** TeX -> readable text with super/subscript markers (same rules as chat.html tex()). */
  fun tex(expr: String): String {
    var s = expr
    s = s.replace(Regex("""\\(?:text|mathrm|mathbf|mathit|textrm|ce|operatorname)\{([^{}]*)\}"""), "$1")
    repeat(2) { s = s.replace(Regex("""\\[dt]?frac\{([^{}]*)\}\{([^{}]*)\}"""), "($1)/($2)") }
    s = s.replace(Regex("""\(([A-Za-z0-9.]+)\)/\(([A-Za-z0-9.]+)\)"""), "$1/$2")
    s = s.replace(Regex("""\\sqrt\{([^{}]*)\}"""), "√($1)")
    s = s.replace("\\_", "_")
    s = s.replace("\\left", "").replace("\\right", "")
    s = Regex("""\\([A-Za-z]+)""").replace(s) { m ->
      val name = m.groupValues[1]
      TEX_SYMBOLS[name] ?: if (name in TEX_WORDS) name else m.value
    }
    s = s.replace(Regex("""\\[,;:!\s]"""), " ")
    s = s.replace("\\\\", "\n")
    s = s.replace(Regex("""\^\s*\{([^{}]*)\}"""), "$SUP_ON$1$SUP_OFF")
    s = s.replace(Regex("""_\s*\{([^{}]*)\}"""), "$SUB_ON$1$SUB_OFF")
    s = s.replace(Regex("""\^([^\s$SUP_ON$SUB_ON])"""), "$SUP_ON$1$SUP_OFF")
    s = s.replace(Regex("""(?<=[A-Za-z0-9)\]])_([^\s$SUP_ON$SUB_ON])"""), "$SUB_ON$1$SUB_OFF")
    return s.replace("{", "").replace("}", "")
  }

  // ---------- inline text ----------

  data class InlineColors(val code: Color, val codeBg: Color, val link: Color, val math: Color)

  private const val BOLD_ON = '\uE000'; private const val BOLD_OFF = '\uE001'
  private const val EM_ON = '\uE002'; private const val EM_OFF = '\uE003'
  private const val DEL_ON = '\uE004'; private const val DEL_OFF = '\uE005'
  private const val MATH_ON = '\uE00A'; private const val MATH_OFF = '\uE00B'
  private const val STASH = '\uE010'; private const val STASH_END = '\uE011'

  private sealed class Stashed {
    data class Code(val text: String) : Stashed()
    data class Link(val label: String, val url: String) : Stashed()
  }

  /** Inline Markdown (bold, italic, code, links, $maths$) -> styled text. */
  fun inline(text: String, colors: InlineColors): AnnotatedString {
    val stash = mutableListOf<Stashed>()
    fun keep(x: Stashed): String { stash.add(x); return "$STASH${stash.size - 1}$STASH_END" }
    var s = text
    s = Regex("`([^`\\n]+)`").replace(s) { keep(Stashed.Code(it.groupValues[1])) }
    s = Regex("""\$\$([\s\S]+?)\$\$""").replace(s) { "$MATH_ON${tex(it.groupValues[1])}$MATH_OFF" }
    s = Regex("""\\\[([\s\S]+?)\\\]""").replace(s) { "$MATH_ON${tex(it.groupValues[1])}$MATH_OFF" }
    s = Regex("""\$([^$\n]+?)\$""").replace(s) { "$MATH_ON${tex(it.groupValues[1])}$MATH_OFF" }
    s = Regex("""\\\(([\s\S]+?)\\\)""").replace(s) { "$MATH_ON${tex(it.groupValues[1])}$MATH_OFF" }
    s = Regex("""\[([^\]]+)\]\((https?://[^)\s]+)\)""").replace(s) { keep(Stashed.Link(it.groupValues[1], it.groupValues[2])) }
    s = Regex("""(?<![(\w/])(https?://[^\s)<>\]]+[^\s)<>\].,;:!?'"])""").replace(s) { keep(Stashed.Link(it.groupValues[1], it.groupValues[1])) }
    s = Regex("""\*\*([^*]+)\*\*""").replace(s) { "$BOLD_ON${it.groupValues[1]}$BOLD_OFF" }
    s = Regex("""__([^_]+)__""").replace(s) { "$BOLD_ON${it.groupValues[1]}$BOLD_OFF" }
    s = Regex("""(^|[\s(])\*([^*\n]+)\*""").replace(s) { "${it.groupValues[1]}$EM_ON${it.groupValues[2]}$EM_OFF" }
    s = Regex("""~~([^~]+)~~""").replace(s) { "$DEL_ON${it.groupValues[1]}$DEL_OFF" }
    // Stray TeX escapes outside $…$ (e.g. "x\_1" or "\max"): same cleanup as inside maths.
    s = s.replace("\\_", "_")
    s = Regex("""\\(max|min|log|ln|sin|cos|tan|exp|lim|gcd|mod|det|arg)\b""").replace(s, "$1")

    val sup = SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.72.em)
    val sub = SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.72.em)
    val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = colors.code, background = colors.codeBg, fontSize = 0.9.em)
    val linkStyles = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))

    return buildAnnotatedString {
      var i = 0
      while (i < s.length) {
        when (val c = s[i]) {
          BOLD_ON -> pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
          EM_ON -> pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
          DEL_ON -> pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
          MATH_ON -> pushStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, color = colors.math))
          SUP_ON -> pushStyle(sup)
          SUB_ON -> pushStyle(sub)
          BOLD_OFF, EM_OFF, DEL_OFF, MATH_OFF, SUP_OFF, SUB_OFF -> runCatching { pop() }
          STASH -> {
            val end = s.indexOf(STASH_END, i)
            val item = stash.getOrNull(s.substring(i + 1, end).toIntOrNull() ?: -1)
            when (item) {
              is Stashed.Code -> { pushStyle(codeStyle); append(" ${item.text} "); pop() }
              is Stashed.Link -> withLink(LinkAnnotation.Url(item.url, linkStyles)) { append(item.label) }
              null -> {}
            }
            i = end
          }
          else -> append(c)
        }
        i++
      }
    }
  }

  /** Plain text of a reply for "Copy": no NEXT line. */
  fun plain(src: String) = Tutor.stripNext(src).trim()
}
