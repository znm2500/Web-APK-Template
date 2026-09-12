# Web 游戏 APK 空壳模板

一个"只装游戏、不碰游戏"的 Android 空壳：把任意 Web 网页游戏（Scratch / TurboWarp / PenguinMod / Construct 2·3 / 普通 HTML5 项目）放进 `assets/www/`，构建即得可安装的 APK。

核心设计原则：**不修改游戏文件**。所有兼容性修复都在运行时由壳完成，游戏目录可以原样替换。

---

## 快速开始

### 1. 放入游戏资源

把游戏文件放进 `app/src/main/assets/www/`：

```
app/src/main/assets/www/
├── index.html          ← 入口必须叫这个名字，放在根部
├── script.js           ← 多文件项目的其他资源，相对路径引用
└── assets/             ← 子目录结构原样复制，支持中文文件名
```

- **单文件游戏**（Scratch / TurboWarp / PenguinMod Packager 导出）：就是这个 HTML 一个文件，直接放进去
- **多文件游戏**：整个导出目录（`index.html` + `script.js` + `assets/` 等）原样复制

> 目录里有个 `把游戏文件放到这里.txt` 占位说明，可随时删除。

### 2. 配置应用信息（可选）

| 项目 | 位置 |
|---|---|
| 应用名 | `app/src/main/res/values/strings.xml` 的 `app_name` |
| 包名 | `app/build.gradle.kts` 的 `namespace` 与 `applicationId` |
| 版本号 | 同上的 `versionCode` / `versionName` |
| 图标 | `app/src/main/res/mipmap-*/` 下的 `ic_launcher*.png`（前景层 + `drawable/ic_launcher_background.xml` 背景色） |

### 3. 构建

```bash
./gradlew :app:assembleDebug     # 调试包：不混淆，堆栈可读，适合排查游戏兼容问题
./gradlew :app:assembleRelease   # 发布包：开 R8 + 资源压缩，用作打包模板
```

产物：`app/build/outputs/apk/{debug,release}/`

> release 包默认使用 debug 证书签名（可安装测试，上架需换正式证书）。

---

## 体积设计

这个壳刻意做到"没有多余的代码"，实测体积：

| 版本 | 体积 | 说明 |
|---|---|---|
| 改造前（AS 默认模板依赖全开、不混淆） | 25.0 MB | 37294 个类，其中自己的代码只有 22 个 |
| 现在 · debug | 933 KB | 不混淆 |
| 现在 · release（R8 压缩） | **65 KB** | dex 仅 38 个类 |

之所以能这么小，是因为三件事：

1. **不打包浏览器内核**——渲染完全交给系统自带的 WebView（Chromium），APK 里一个字节的渲染引擎都没有
2. **不打包 UI 框架**——全程 `android.app.Activity` + 一个铺满屏幕的 `WebView`，没有 Compose / AndroidX / Material
3. **release 开 R8 + 资源压缩**——用不到的类和资源全部剔除，剩余类名混淆、打包时再压缩

唯一的功能依赖是 `org.nanohttpd:nanohttpd:2.3.1`（本地 HTTP 服务，约 30 个类）。整个壳的源码 500 行左右，全部集中在两个文件。

因此产物大小基本由你放进去的游戏决定：换一个 33MB 的单文件游戏，APK 就是 33MB 上下，壳本身可以忽略不计。

---

## 工作原理

### 内置 HTTP 服务器

`LocalWebServer.kt` 基于 NanoHTTPD，在 `127.0.0.1:8080` 起一个只读文件服务，递归映射 `assets/www/` 下的全部资源。

为什么不用 `file://` 直读？因为 HTTP 源更接近真实浏览器环境，可以规避部分引擎在 `file://` 协议下的限制，也便于支持媒体分段加载。服务器不可用时自动回退到 `file:///android_asset/www/index.html`。

已支持的细节：

- **完整 MIME 映射**：js / mjs / css / wasm / 图片 / 音频 / 字体 / json
- **URL 解码**：中文、空格等文件名
- **HTTP Range（206 分段响应）**：`<audio>` / `<video>` 播放外部媒体、拖动进度条依赖此能力
- 媒体文件保持未压缩存储，才能用 `openFd` 做零拷贝分段读取

### 启动修复补丁（内存注入）

这是解决"白屏但音频正常"的关键。部分 Android WebView 在首帧布局时会把视口高度算成 `0` 或 `1px`，而 TurboWarp / PenguinMod 脚手架（Scaffolding）**只在加载时量一次容器尺寸**来决定舞台画布大小——结果画布被定为 1px，游戏照常运行、音频照常播放，但画面肉眼不可见。

壳通过 `shouldInterceptRequest` 拦截主文档请求，**在内存中**给 HTML 头部注入补丁后流式返回：

1. 首帧黑底，消除加载期白屏闪烁
2. 用真实 `window.innerWidth / innerHeight` 像素尺寸修正 `html` / `body` / `#app` / `#loading` 等容器高度
3. 脚手架就绪后调用 `scaffolding.relayout()`，让舞台按 `preserve-ratio` 等比缩放居中
4. 前 10 秒兜底轮询，确保脚手架初始化完成后再次重排

**实现要点**：采用流式注入——只读头部 16KB 找 `<meta charset>` 锚点，剩余内容 `SequenceInputStream` 透传。整篇读入 33MB HTML 会产生数百 MB 峰值内存，直接 OOM 闪退。

### 舞台尺寸自适应

修复逻辑**不含任何硬编码尺寸**。容器撑满真实视口后，舞台缩放完全交给脚手架自己的 `preserve-ratio` 策略——480×360、640×480、960×720、宽屏 Mod 都能正确等比居中，多余区域黑边填充。

补丁里针对 `#app` / `#loading` 的部分对不存在的 ID 会自动跳过，因此普通网页、Construct 2/3 项目同样安全，只会享受到通用的视口修正和黑底。

### 关键 WebView 配置（均为踩坑所得）

| 配置 | 取值 | 原因 |
|---|---|---|
| `useWideViewPort` | `false` | 开启后概览模式会把初始布局高度算成 1px → 白屏 |
| `loadWithOverviewMode` | `false` | 同上。页面自带 viewport meta，无需这两项 |
| `setLayerType` | 不设置 | 手动指定 `LAYER_TYPE_HARDWARE` 在部分 GPU 驱动/模拟器上导致白屏 |
| `mediaPlaybackRequiresUserGesture` | `false` | 允许游戏自动播放音频 |

### 返回键行为

返回键完全交给页面：单击即向页面派发 Cordova 风格的 `backbutton` 事件，**不退出、不提示**。Web-Packer 注入的 `controls.js` 触屏键盘只在 `backbutton` 事件里呼出/切换（浏览器 `keydown` 在 Android 返回键上不会触发）；没有监听器的页面则完全无感（返回键无任何反应，只能靠系统手势/任务列表退出）。

### 诊断能力

调试期开着，发布前可关：

- **控制台转发**：页面 `console.*` 与 JS 异常输出到 logcat，标签 `WebContentDebug`
- **双探针**：页面加载后 1s / 4s 各跑一次 JS，上报 WebGL 能力、视口尺寸、画布祖先链尺寸、脚手架重排结果

查看日志：

```bash
adb logcat -s WebContentDebug
```

正常输出示例（重点看 `CANVAS_AFTER` 是几百像素而非 `1x1`）：

```
probe: GL=OK | VP=640x360 | CHAIN=... | RELAYOUT=function-called | APP_AFTER=640x360 | CANVAS_AFTER=480x360 | LOADING=false
```

- **远程调试**：`WebView.setWebContentsDebuggingEnabled(true)`，Chrome 打开 `chrome://inspect` 可实时检查页面

---

## 资源请求 404 时的行为

壳里的资源全部来自内置服务器（`assets/www`），找不到文件时服务器返回 `404 text/plain "Not found"`。WebView 侧分三种情况，分清它们能省下大量排查时间：

| 情况 | 屏幕上的表现 | 壳的动作 |
|---|---|---|
| **子资源 404**（脚本 / 图片 / 音频） | 页面照常运行，只是那个资源缺失。缺 `script.js` 会白屏无反应，缺图缺音频只影响对应内容 | 不中断、不重载（刻意如此），把 `子资源 HTTP 404: <URL>` 打进 logcat |
| **主文档 404**（`assets/www` 里没有 `index.html`） | 直接把 `Not found` 当成页面显示 | 打 `主文档 HTTP 404`，**不会**回退 `file://` |
| **网络层失败**（服务器没起来 / 端口被占） | 白屏 | 回退 `file:///android_asset/www/index.html`，且**只回退一次**，避免反复回退的死循环 |

两个反直觉的点：

- **HTTP 404 不触发 `onReceivedError`**，它走 `onReceivedHttpError`。所以"主文档加载失败就回退 file://"在 404 场景下并不会发生——回退只针对网络层错误
- **assets 里的文件名区分大小写**：游戏在 Windows 上引用 `Script.js` 而实际文件叫 `script.js` 时，电脑上跑得好好的，打包后必定 404。现在日志会直接打出是哪个 URL 挂了

文件名含 `+`、中文、空格的资源都能正常访问（解码时 `+` 按字面处理，不会被当成空格）。

排查命令：

```bash
adb logcat -s WebContentDebug | grep -E "HTTP 4|error"
```

---

## 项目结构

```
app/src/main/
├── assets/www/                         游戏资源（唯一需要替换的目录）
├── java/com/example/myapplication/
│   ├── MainActivity.kt                 Activity + WebView 容器、启动补丁注入、诊断探针
│   └── LocalWebServer.kt               基于 NanoHTTPD 的 assets 文件服务
└── res/
    ├── mipmap-*/                       各密度启动图标
    ├── values/themes.xml               平台主题（不是 MaterialComponents，避免拉依赖）
    └── drawable/ic_launcher_background.xml   自适应图标背景色
```

---

## 环境要求

- JDK 11+
- Android SDK（`compileSdk 37` / `minSdk 24` / `targetSdk 37`）
- Gradle（用项目自带 `gradlew`）

---

## 已知限制

- `file://` 回退只在网络层失败时触发；回退页面能否享受启动补丁取决于系统 WebView 是否对 `file://` 调用 `shouldInterceptRequest`，因此不保证生效——但 `onPageFinished` 探针会兜底做同样的容器尺寸修复，不会白屏。回退只尝试一次，不会死循环
- release 包经过 R8 混淆，类名与行号需要 `app/build/outputs/mapping/release/mapping.txt` 才能还原堆栈；排查问题请优先用 debug 包
- 返回键只做事件转发，应用无法用返回键退出，需要系统手势或任务列表划掉
- release 包默认使用 debug 证书签名，仅适合本地测试分发；上架应用商店需替换为正式证书
- 需要联网的游戏（云变量、在线资源）依赖设备网络，`INTERNET` 权限已声明
