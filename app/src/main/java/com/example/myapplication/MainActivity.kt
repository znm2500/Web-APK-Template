package com.example.myapplication

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedDispatcher
import java.io.SequenceInputStream
import java.lang.ref.WeakReference

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

/**
 * 壳本体：一个 Activity + 一个铺满屏幕的 WebView，不依赖 Compose / AndroidX。
 * 渲染全部交给系统 WebView（Chromium），这里只负责：
 * 1. 起本地 HTTP 服务（LocalWebServer）作为游戏文件的来源
 * 2. 拦截主文档，在内存里注入启动补丁（不改磁盘上的游戏文件）
 * 3. 把 Android 返回键转换成页面能听懂的 backbutton 事件
 */
class MainActivity : Activity() {
    private var server: LocalWebServer? = null

    /** 主文档加载失败时是否已经回退过 file://，避免反复回退造成死循环。 */
    private var fallbackAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动内置 HTTP 服务，把 assets/www 递归映射到 http://127.0.0.1:8080
        val serverAvailable = try {
            val localServer = LocalWebServer(assets, 8080)
            localServer.start()
            server = localServer
            Log.i("MainActivity", "LocalWebServer started on port 8080")
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start LocalWebServer", e)
            false
        }

        setContentView(createWebView(serverAvailable))

        // 必须放在 setContentView 之后：沉浸式设置要访问 WindowInsetsController，
        // 而它在 DecorView 尚未创建时是 null（PhoneWindow.getInsetsController() 抛 NPE 闪退）。
        applyImmersiveMode()

        // 返回键完全交给页面：向页面派发 Cordova 风格的 backbutton 事件，不退出、不提示。
        // Web-Packer 注入的 controls.js 只在 backbutton 事件里呼出/切换触屏键盘
        // （浏览器 keydown 在 Android 返回键上不会触发，不派发的话键盘永远呼不出）。
        // 对没有 controls.js 的页面，派发一个无人监听的事件完全无害（返回键将无任何反应）。
        // API 33+ 走预测性返回回调，33 以下走 onBackPressed()，两条路都只派发事件。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { dispatchBackToPage() }
        }
    }

    /**
     * 沉浸式全屏：隐藏状态栏和导航栏，允许上滑临时唤出。
     * 整体包 try/catch：这是纯观感设置，任何机型的系统栏实现差异都只记日志，
     * 绝不能因为收个系统栏把整个 Activity 启动搞崩。
     */
    @Suppress("DEPRECATION")
    private fun applyImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                // 用 decorView.windowInsetsController 而不是 window.insetsController：
                // 访问 decorView 会顺带触发 DecorView 的懒创建，避免早期调用 NPE
                // （window.insetsController 在 DecorView 未创建时直接崩）。
                // 该 View 未附着到窗口时可能返回 null，所以用 ?.
                window.decorView.windowInsetsController?.apply {
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                }
            } else {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "applyImmersiveMode failed, system bars stay visible", e)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 沉浸式是"粘性"语义：从后台返回、系统弹窗关闭等重新拿到焦点时再收一次系统栏
        if (hasFocus) applyImmersiveMode()
    }

    /** 34 以下系统走这条路径；33+ 已注册 OnBackInvokedCallback。 */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        dispatchBackToPage()
    }

    /** 把返回键变成页面能听懂的事件（controls.js 用它呼出触屏键盘）。 */
    private fun dispatchBackToPage() {
        activeWebView?.get()
            ?.evaluateJavascript("document.dispatchEvent(new Event('backbutton'))", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop server when activity is destroyed
        server?.stop()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(serverAvailable: Boolean): WebView {
        val webView = WebView(this)
        activeWebView = WeakReference(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 不要开 useWideViewPort / loadWithOverviewMode：
            // 概览模式初始布局高度会被算成 1px，TurboWarp/ PenguinMod 脚手架
            // 在加载时按容器高度给舞台画布定尺寸，会把画布定成 1px 高 → 白屏（音频照播）。
            // 页面自带 viewport meta（width=device-width, initial-scale=1.0），无需这两项。
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = false
        }
        // Enable WebView debugging so you can inspect via chrome://inspect
        WebView.setWebContentsDebuggingEnabled(true)
        // 不要手动设置 LAYER_TYPE_HARDWARE：
        // 在部分 GPU 驱动/模拟器上这会导致 WebView 表面白屏但页面照常运行（音频有声音）。
        // 默认（LAYER_TYPE_NONE）下 WebView 自己会正确处理硬件加速，Cordova 模板也是这么做的。
        // 若在模拟器上仍白屏，可临时改成 LAYER_TYPE_SOFTWARE 验证是否为 GPU 合成问题：
        // webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                // 拦截主文档请求，在内存中注入启动修复补丁（不改 assets 里的游戏文件）。
                // 补丁解决：WebView 首帧布局高度可能被算成 0/1px，导致 absolute 布局的
                // #loading/#app 高度为 0（白屏、无进度条、舞台画布被定为 1px）。
                val url = request?.url ?: return null
                if (!request.isForMainFrame) return null
                val path = url.path ?: ""
                if (!(path == "/" || path.endsWith("/index.html") || path.endsWith(".htm"))) return null
                return try {
                    val input = assets.open("www/index.html")
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

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // 只对主文档失败做回退；子资源（图片/脚本/音频）失败只记日志，
                // 否则多文件项目里任何一个 404 都会触发整页重载死循环
                if (request?.isForMainFrame == true) {
                    Log.e("WebContentDebug", "main frame error: ${error?.description}, fallback to file://")
                    // 只回退一次：若 file:// 也失败（assets/www 里根本没有 index.html），
                    // 再回退就会形成 加载失败→回退→加载失败 的死循环
                    if (fallbackAttempted) {
                        Log.e("WebContentDebug", "fallback 已尝试过且失败，停止重试；确认 assets/www/index.html 是否存在")
                    } else {
                        fallbackAttempted = true
                        try {
                            view?.loadUrl("file:///android_asset/www/index.html")
                        } catch (_: Exception) { }
                    }
                } else {
                    Log.w("WebContentDebug", "subresource error: ${request?.url} ${error?.description}")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                // HTTP 状态码 >= 400（含 404）走这里，而不是 onReceivedError——
                // 后者只报网络层错误（连不上、超时、文件不存在）。所以：
                // 子资源 404 不会中断页面，只是那个资源静默失败；
                // 主文档 404（assets/www 没有 index.html）会直接把 "Not found" 当成页面渲染，
                // 也不会触发上面的 file:// 回退。这里把 URL 与状态码如实打出来便于定位。
                val url = request?.url
                val status = errorResponse?.statusCode
                if (request?.isForMainFrame == true) {
                    Log.e("WebContentDebug", "主文档 HTTP $status: $url（assets/www 里是否缺少 index.html？）")
                } else {
                    Log.w("WebContentDebug", "子资源 HTTP $status: $url（文件名大小写、中文名、路径拼写都可能是原因）")
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
                        view.evaluateJavascript(js) { result ->
                            Log.i("WebContentDebug", "probe(+${delayMs}ms): $result")
                        }
                    }, delayMs)
                }
            }
        }

        // 把页面里的 console.* 和 JS 异常转发到 logcat（过滤 WebContentDebug 标签）
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Log.i(
                    "WebContentDebug",
                    "console[${msg?.messageLevel()}] ${msg?.message()} @${msg?.sourceId()}:${msg?.lineNumber()}"
                )
                return true
            }
        }

        if (serverAvailable && USE_HTTP_SERVER) {
            webView.loadUrl("http://127.0.0.1:8080/")
        } else {
            // 与 template.c2.APK（Cordova）相同的方式：file:// 直读 assets。
            // 单文件游戏（33MB HTML 内嵌全部资源）用 file:// 完全够用，
            // 把它设为 true/false 即可 A/B 对比，快速定位是不是 HTTP 环节的问题。
            webView.loadUrl("file:///android_asset/www/index.html")
        }
        return webView
    }
}
