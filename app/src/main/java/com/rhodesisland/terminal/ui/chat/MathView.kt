package com.rhodesisland.terminal.ui.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * KaTeX 数学公式渲染（迁移自网页版 KaTeX CDN 方案）。
 *
 * 策略：把整段文本塞进 WebView，由 KaTeX auto-render 扫描
 * `$$...$$`（块级）与 `$...$`（行内）并渲染，行内公式保持原文流排版。
 * 纯 Compose 无可用 KaTeX 实现，故采用 WebView + jsdelivr CDN。
 *
 * 离线/加载失败时回退为纯文本（auto-render 未命中则原样显示）。
 * 仅对非流式消息使用，避免流式输出时频繁重建 WebView。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathView(text: String, modifier: Modifier = Modifier) {
    val html = remember(text) { buildMathHtml(text) }
    var contentHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun reportHeight(h: Int) {
                            contentHeight = h
                        }
                    },
                    "Android",
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // 渲染完成后上报实际内容高度
                        view.loadUrl("javascript:Android.reportHeight(document.body.scrollHeight)")
                    }
                }
                tag = html
                loadDataWithBaseURL(KATEX_BASE, html, "text/html", "UTF-8", null)
            }
        },
        update = { view ->
            // 文本变化时（如历史记录编辑）重新加载
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(KATEX_BASE, html, "text/html", "UTF-8", null)
            }
        },
        // MathView 离开组合（LazyColumn 滚出视口）时销毁 WebView，释放 native 渲染器进程，避免 OOM
        onRelease = { it.destroy() },
        modifier = modifier.then(
            if (contentHeight > 0) {
                Modifier.height(with(density) { contentHeight.toDp() })
            } else {
                Modifier.height(24.dp)
            },
        ),
    )
}

private const val KATEX_BASE = "https://cdn.jsdelivr.net/"

private fun buildMathHtml(text: String): String {
    // 先 HTML 转义，再换行 -> <br>
    val escaped = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")
    return """
<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.css">
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/katex.min.js" defer></script>
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.9/dist/contrib/auto-render.min.js" defer></script>
<style>
  html,body { margin:0; padding:0; background:transparent; }
  body { color:#E8E4E0; font-family: sans-serif; font-size:14px; line-height:1.55; padding:1px 0; word-wrap:break-word; }
  .katex { font-size:1.05em; color:#E8E4E0; }
  .katex-display { margin:6px 0; overflow-x:auto; overflow-y:hidden; }
</style></head>
<body><div id="c">$escaped</div>
<script>
  document.addEventListener("DOMContentLoaded", function(){
    try {
      if (window.renderMathInElement) {
        renderMathInElement(document.getElementById("c"), {
          delimiters: [
            {left:"$$", right:"$$", display:true},
            {left:"$", right:"$", display:false}
          ],
          throwOnError:false
        });
      }
    } catch(e){}
    // 上报高度（KaTeX 渲染可能改变高度）
    setTimeout(function(){ Android.reportHeight(document.body.scrollHeight); }, 60);
    setTimeout(function(){ Android.reportHeight(document.body.scrollHeight); }, 300);
  });
</script>
</body></html>
    """.trimIndent()
}
