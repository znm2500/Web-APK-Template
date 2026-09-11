package com.example.myapplication

import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import java.lang.ref.WeakReference
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.io.SequenceInputStream

// true = 走内置 NanoHTTPD（http://127.0.0.1:8080）
// false = 与 template.c2.APK 一样用 file:// 直读 assets（A/B 排查用）
private const val USE_HTTP_SERVER = true

// 当前 WebView 的弱引用：返回键回调用它向页面派发 backbutton 事件。
// 弱引用避免 Activity 销毁后泄漏 WebView。
private var activeWebView: WeakReference<WebView>? = null

private const val BOOT_PATCH_MARK = "__wb_boot_fix__"

// 启动修复补丁：由 WebView 拦截主文档时在内存中注入，assets 里的游戏 HTML 保持原样。
// 首帧背景涂黑防白屏闪烁；用真实视口像素尺寸修正 html/body/#app/#loading 等容器高度；
// 脚手架（TurboWarp/PenguinMod）就绪后触发 relayout，让舞台画布按 preserve-ratio 居中。
private const val BOOT_PATCH = """
  <style id="__wb_boot_style__">
    html, body { margin: 0; padding: 0; background: #000 !important; }
  </style>
  <script id="__wb_boot_fix__">
  (function () {
    function fixLayout() {
      var W = window.innerWidth, H = window.innerHeight;
      if (!W || !H) return;
      var de = document.documentElement;
      de.style.width = W + 'px'; de.style.height = H + 'px';
      if (document.body) {
        document.body.style.width = W + 'px'; document.body.style.height = H + 'px';
      }
      ['app', 'loading', 'error', 'launch'].forEach(function (id) {
        var el = document.getElementById(id);
        if (el) { el.style.width = W + 'px'; el.style.height = H + 'px'; }
      });
      if (window.scaffolding && window.scaffolding.relayout) {
        try { window.scaffolding.relayout(); } catch (e) {}
      }
    }
    fixLayout();
    document.addEventListener('DOMContentLoaded', fixLayout);
    window.addEventListener('load', fixLayout);
    window.addEventListener('resize', fixLayout);
    var n = 0;
    var timer = setInterval(function () {
      fixLayout();
      if (++n >= 20) clearInterval(timer);
    }, 500);
  })();
  </script>
"""

class MainActivity : ComponentActivity() {
    private var server: LocalWebServer? = null
    private var serverStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hide the system bars (status bar + navigation bar) for an immersive experience
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        // Allow system bars to be revealed transiently via swipe
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Hide both status and navigation bars
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.hide(WindowInsetsCompat.Type.navigationBars())

        // Start the embedded HTTP server that serves files from assets/www
        try {
            server = LocalWebServer(assets, 8080)
            server?.start()
            serverStarted = true
            Log.i("MainActivity", "LocalWebServer started on port 8080")
        } catch (e: Exception) {
            serverStarted = false
            Log.e("MainActivity", "Failed to start LocalWebServer", e)
        }

        // 双击返回键才退出，防止游戏中误触直接杀进程。
        // 单击返回键时，向页面派发 Cordova 风格的 backbutton 事件：
        // Web-Packer 注入的 controls.js 只在 backbutton 事件里呼出/切换触屏键盘
        // （浏览器 keydown 在 Android 返回键上不会触发，不派发的话键盘永远呼不出）。
        // 对没有 controls.js 的页面，派发一个无人监听的事件完全无害。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            private var lastBackPressTime = 0L
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = now
                    activeWebView?.get()?.evaluateJavascript(
                        "document.dispatchEvent(new Event('backbutton'))", null
                    )
                    Toast.makeText(this@MainActivity, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
                }
            }
        })

        setContent {
            MyApplicationTheme {
                // Use a WebView to load the local server root (fallback to assets file if server unavailable)
                WebContent(serverStarted)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop server when activity is destroyed
        server?.stop()
    }
}

@Composable
fun WebContent(serverAvailable: Boolean, modifier: Modifier = Modifier) {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            activeWebView = WeakReference(this)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 不要开 useWideViewPort / loadWithOverviewMode：
            // 概览模式初始布局高度会被算成 1px，TurboWarp/ PenguinMod 脚手架
            // 在加载时按容器高度给舞台画布定尺寸，会把画布定成 1px 高 → 白屏（音频照播）。
            // 页面自带 viewport meta（width=device-width, initial-scale=1.0），无需这两项。
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = 100
            settings.mediaPlaybackRequiresUserGesture = false
            // Enable WebView debugging so you can inspect via chrome://inspect
            WebView.setWebContentsDebuggingEnabled(true)
            // 不要手动设置 LAYER_TYPE_HARDWARE：
            // 在部分 GPU 驱动/模拟器上这会导致 WebView 表面白屏但页面照常运行（音频有声音）。
            // 默认（LAYER_TYPE_NONE）下 WebView 自己会正确处理硬件加速，Cordova 模板也是这么做的。
            // 若在模拟器上仍白屏，可临时改成 LAYER_TYPE_SOFTWARE 验证是否为 GPU 合成问题：
            // setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // 拦截主文档请求，在内存中注入启动修复补丁（不改 assets 里的游戏文件）。
                    // 补丁解决：WebView 首帧布局高度可能被算成 0/1px，导致 absolute 布局的
                    // #loading/#app 高度为 0（白屏、无进度条、舞台画布被定为 1px）。
                    val url = request?.url ?: return null
                    if (!request.isForMainFrame) return null
                    val path = url.path ?: ""
                    if (!(path == "/" || path.endsWith("/index.html") || path.endsWith(".htm"))) return null
                    return try {
                        val assetManager = view?.context?.assets ?: return null
                        val input = assetManager.open("www/index.html")
                        // 流式注入：只把头部一块读进内存找锚点（<meta charset> 在 <head> 最前面），
                        // 剩余内容流式透传。整篇读入会制造数百 MB 峰值内存导致 OOM 闪退。
                        val headSize = 16384
                        val head = ByteArray(headSize)
                        var off = 0
                        while (off < headSize) {
                            val r = input.read(head, off, headSize - off)
                            if (r < 0) break
                            off += r
                        }
                        val headStr = String(head, 0, off, Charsets.UTF_8)
                        val anchor = "<meta charset=\"utf-8\">"
                        val patchedHead = if (headStr.contains(anchor) && !headStr.contains(BOOT_PATCH_MARK)) {
                            headStr.replace(anchor, anchor + BOOT_PATCH)
                        } else {
                            headStr
                        }
                        val combined = SequenceInputStream(
                            patchedHead.byteInputStream(Charsets.UTF_8), input
                        )
                        WebResourceResponse("text/html", "utf-8", combined)
                    } catch (e: Exception) {
                        Log.e("WebContentDebug", "inject boot patch failed", e)
                        null
                    }
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    // 渲染进程（含 GPU 合成）崩溃时 WebView 会定格/白屏，但音频进程可能还在跑
                    Log.e("WebContentDebug", "onRenderProcessGone, detail=$detail")
                    return true
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    // 只对主文档失败做回退；子资源（图片/脚本/音频）失败只记日志，
                    // 否则多文件项目里任何一个 404 都会触发整页重载死循环
                    if (request?.isForMainFrame == true) {
                        Log.e("WebContentDebug", "main frame error: ${error?.description}, fallback to file://")
                        try {
                            loadUrl("file:///android_asset/www/index.html")
                        } catch (_: Exception) { }
                    } else {
                        Log.w("WebContentDebug", "subresource error: ${request?.url} ${error?.description}")
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.i("WebContentDebug", "onPageFinished: $url")
                    // 33MB 单文件游戏初始化较慢，分别在 1s / 4s 做两次诊断探针
                    listOf(1000L, 4000L).forEach { delayMs ->
                        view?.postDelayed({
                            val js = """
                                (function() {
                                  var W = window.innerWidth, H = window.innerHeight;

                                  // 1) WebGL 能力
                                  var glInfo = 'NONE';
                                  try {
                                    var c0 = document.createElement('canvas');
                                    var gl = c0.getContext('webgl2') || c0.getContext('webgl');
                                    if (gl) glInfo = 'OK';
                                  } catch (e) { glInfo = 'THROW'; }

                                  // 2) 画布祖先链尺寸（修复前）；容器逐级强制全屏（canvas 本身不碰，
                                  //    交给脚手架 preserve-ratio 自己算，否则会拉伸变形）
                                  var chain = [];
                                  var rel = 'no-scaffolding';
                                  try {
                                    var de = document.documentElement;
                                    de.style.width = W + 'px'; de.style.height = H + 'px';
                                    de.style.background = '#000';
                                    document.body.style.width = W + 'px'; document.body.style.height = H + 'px';
                                    document.body.style.background = '#000';

                                    var cv = document.querySelector('canvas');
                                    var n = cv ? cv.parentElement : null;  // 从 canvas 的父级开始，canvas 不动
                                    while (n && n !== de) {
                                      var r = n.getBoundingClientRect();
                                      var label = n.tagName + (n.id ? '#' + n.id : '') +
                                                  (n.className && typeof n.className === 'string' ? '.' + n.className.split(' ')[0] : '');
                                      chain.push(label + '=' + Math.round(r.width) + 'x' + Math.round(r.height));
                                      if (n.style) { n.style.width = W + 'px'; n.style.height = H + 'px'; }
                                      n = n.parentElement;
                                    }
                                    if (cv) chain.push('CANVAS(untouched)');

                                    window.dispatchEvent(new Event('resize'));
                                    if (window.scaffolding) {
                                      rel = typeof window.scaffolding.relayout;
                                      try { window.scaffolding.relayout(); rel += '-called'; }
                                      catch (e) { rel += '-throw'; }
                                    }
                                  } catch (e) { chain.push('FIX-THROW:' + e); }

                                  // 3) 修复后画布与 #app 实测
                                  var cv2 = document.querySelector('canvas');
                                  var r2 = cv2 ? cv2.getBoundingClientRect() : null;
                                  var canvasAfter = r2 ? Math.round(r2.width) + 'x' + Math.round(r2.height) : 'NO_CANVAS';
                                  var app = document.getElementById('app');
                                  var appAfter = app ? (function(){ var r = app.getBoundingClientRect(); return Math.round(r.width) + 'x' + Math.round(r.height); })() : 'NO_APP';
                                  var loading = document.getElementById('loading');
                                  var loadingVisible = loading && !loading.hidden && getComputedStyle(loading).display !== 'none';

                                  return 'GL=' + glInfo +
                                         ' | VP=' + W + 'x' + H +
                                         ' | CHAIN=' + (chain.join(' > ') || 'NONE') +
                                         ' | RELAYOUT=' + rel +
                                         ' | APP_AFTER=' + appAfter +
                                         ' | CANVAS_AFTER=' + canvasAfter +
                                         ' | LOADING=' + loadingVisible;
                                })();
                            """.trimIndent()
                            evaluateJavascript(js) { result ->
                                Log.i("WebContentDebug", "probe(+${delayMs}ms): $result")
                            }
                        }, delayMs)
                    }
                }
            }

            // 把页面里的 console.* 和 JS 异常转发到 logcat（过滤 WebContentDebug 标签）
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    Log.i(
                        "WebContentDebug",
                        "console[${msg?.messageLevel()}] ${msg?.message()} @${msg?.sourceId()}:${msg?.lineNumber()}"
                    )
                    return true
                }
            }

            if (serverAvailable && USE_HTTP_SERVER) {
                loadUrl("http://127.0.0.1:8080/")
            } else {
                // 与 template.c2.APK（Cordova）相同的方式：file:// 直读 assets。
                // 单文件游戏（33MB HTML 内嵌全部资源）用 file:// 完全够用，
                // 把它设为 true/false 即可 A/B 对比，快速定位是不是 HTTP 环节的问题。
                loadUrl("file:///android_asset/www/index.html")
            }
        }
    }, modifier = modifier.fillMaxSize())
}




