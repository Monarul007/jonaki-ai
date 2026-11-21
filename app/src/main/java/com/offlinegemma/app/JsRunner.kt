package com.offlinegemma.app

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Runs a JavaScript answer in a hidden, locked-down web page and returns what it printed.
 * Same job as the PC app's run_python auto-test: no network, no files, stops after a few seconds.
 */
object JsRunner {
  data class Result(val ok: Boolean, val output: String)

  private val BLOCK = Regex("```(?:javascript|js)\\s*\\n(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

  /** The longest JavaScript block in an answer (same choice as agent.py _check_code). */
  fun codeIn(answer: String): String? = BLOCK.findAll(answer).map { it.groupValues[1] }.maxByOrNull { it.length }

  /** Code that needs a person or never ends is not run (like agent.py skips input() / while True). */
  fun runnable(code: String) = !Regex("""\bprompt\s*\(|\balert\s*\(|while\s*\(\s*true\s*\)|for\s*\(\s*;\s*;\s*\)|\bfetch\s*\(|XMLHttpRequest|readline""").containsMatchIn(code)

  private const val TIMEOUT_MS = 8000L

  @SuppressLint("SetJavaScriptEnabled")
  suspend fun run(context: Context, code: String): Result = withContext(Dispatchers.Main) {
    val web = WebView(context.applicationContext).apply {
      settings.javaScriptEnabled = true
      settings.allowFileAccess = false
      settings.allowContentAccess = false
      settings.domStorageEnabled = false
      settings.blockNetworkLoads = true
    }
    try {
      withTimeoutOrNull(TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
          web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
              if (request.url.toString().startsWith("data:") || request.url.toString() == "about:blank") null
              else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String?) {
              view.evaluateJavascript(harness(code)) { raw ->
                val r = runCatching {
                  // evaluateJavascript returns a JSON string literal of our JSON text.
                  val json = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
                  Result(json.getBoolean("ok"), json.getString("out"))
                }.getOrElse { Result(false, "Error: the code did not finish") }
                if (cont.isActive) cont.resume(r)
              }
            }
          }
          web.loadDataWithBaseURL(null, "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
        }
      } ?: Result(false, "Error: the code ran for more than ${TIMEOUT_MS / 1000} seconds and was stopped.")
    } finally {
      web.destroy()
    }
  }

  /** Wraps the code: console.log output is collected; a crash is reported like a traceback. */
  private fun harness(code: String) = """
(function(){
  var out = [];
  function show(a){ try { return typeof a === 'object' ? JSON.stringify(a) : String(a); } catch (e) { return String(a); } }
  var log = function(){ out.push(Array.prototype.map.call(arguments, show).join(' ')); };
  console.log = log; console.info = log; console.warn = log; console.error = log;
  try {
    // new Function: a typing mistake in the code becomes a catchable SyntaxError.
    (new Function(${JSONObject.quote(code)}))();
  } catch (e) {
    out.push('Error: ' + (e && e.name ? e.name + ': ' : '') + (e && e.message ? e.message : e));
    return JSON.stringify({ ok: false, out: out.join('\n') });
  }
  return JSON.stringify({ ok: true, out: out.join('\n') || '(the code printed nothing)' });
})()
""".trimIndent()
}
