package com.offlinegemma.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/** Every demo gets a 640px desktop-width page, scaled down to the screen (small models write for wide screens). */
private const val DEMO_WIDTH = 640

/**
 * Runs inside the demo: reports errors and its real size. Ported from chat.html DEMO_PROBE;
 * uses the AndroidDemo bridge instead of parent.postMessage. Also widens the page if the demo is wider.
 */
private val DEMO_PROBE = """
<meta name="viewport" content="width=$DEMO_WIDTH">
<script>(function(){
function post(m){try{AndroidDemo.post(JSON.stringify(m))}catch(e){}}
window.addEventListener("error",function(e){post({error:String(e.message||"error")+(e.lineno?" (line "+e.lineno+")":"")})});
function size(){var b=document.body;if(!b)return;var r=b.getBoundingClientRect(),h=r.bottom,w=document.documentElement.scrollWidth;
var els=b.getElementsByTagName("*");for(var i=0;i<els.length&&i<500;i++){var q=els[i].getBoundingClientRect();
if(q.width||q.height){if(q.bottom>h)h=q.bottom;if(q.right>w)w=q.right}}post({height:Math.ceil(h)+14,width:Math.ceil(w)})}
window.addEventListener("load",function(){size();setTimeout(size,400);setTimeout(size,1500)});
document.addEventListener("DOMContentLoaded",function(){if(window.ResizeObserver)new ResizeObserver(size).observe(document.body)});})();</script>
<style>html,body{overflow:hidden}body{font:15px/1.5 system-ui,sans-serif;margin:12px;color:#202124;background:#fff}
input[type=range]{width:100%}</style>
""".trimIndent()

private fun withProbe(src: String): String {
  val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(src)
  return if (head != null) src.replaceRange(head.range.last + 1, head.range.last + 1, DEMO_PROBE)
  else DEMO_PROBE + src
}

/** Only size and error reports come out of the demo; nothing goes in. */
private class DemoBridge(private val onReport: (JSONObject) -> Unit) {
  private val main = Handler(Looper.getMainLooper())

  @JavascriptInterface
  fun post(json: String) {
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
    main.post { onReport(obj) }
  }
}

/** A blocked request: empty answer, so the demo cannot reach the internet or the phone's files. */
private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), "".byteInputStream())

/**
 * A live, locked-down mini-page for one demo.
 * JavaScript on; no files, no content providers, no storage, no network, no navigation.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DemoView(html: String, onError: (String) -> Unit) {
  val errorCallback by rememberUpdatedState(onError)
  var pageW by remember(html) { mutableIntStateOf(DEMO_WIDTH) }
  var pageH by remember(html) { mutableIntStateOf(300) }

  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val scale = maxWidth.value / pageW.coerceIn(DEMO_WIDTH, 1400)
    val heightDp = (pageH.coerceIn(60, 1400) * scale).dp
    AndroidView(
      modifier = Modifier.fillMaxWidth().height(heightDp),
      factory = { ctx ->
        WebView(ctx).apply {
          settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            blockNetworkLoads = true
            blockNetworkImage = true
            setSupportZoom(false)
            useWideViewPort = true // honour the demo's 640px viewport...
            loadWithOverviewMode = true // ...and shrink it to fit the screen
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
          }
          isVerticalScrollBarEnabled = false
          isHorizontalScrollBarEnabled = false
          // Sideways drags (sliders) belong to the demo; up/down drags still scroll the chat.
          var downX = 0f
          var downY = 0f
          setOnTouchListener { v, e ->
            when (e.actionMasked) {
              android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                v.parent?.requestDisallowInterceptTouchEvent(true)
              }
              android.view.MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(e.x - downX)
                val dy = kotlin.math.abs(e.y - downY)
                if (dy > dx && dy > 24) v.parent?.requestDisallowInterceptTouchEvent(false)
              }
            }
            false
          }
          webViewClient = object : WebViewClient() {
            // Only the inline page itself may load; everything else is refused.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
              val u = request.url.toString()
              return if (u.startsWith("data:") || u == "about:blank") null else blocked()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
          }
          var errorSent = false
          addJavascriptInterface(DemoBridge { m ->
            if (m.has("height")) {
              pageH = m.optInt("height", pageH)
              pageW = m.optInt("width", pageW)
            }
            val err = m.optString("error", "")
            if (err.isNotEmpty() && !errorSent) {
              errorSent = true
              errorCallback(err)
            }
          }, "AndroidDemo")
          loadDataWithBaseURL(null, withProbe(html), "text/html", "utf-8", null)
        }
      },
      onRelease = { it.destroy() },
    )
  }
}
