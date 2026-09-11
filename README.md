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
./gradlew :app:assembleDebug     # 调试包，可直接安装
./gradlew :app:assembleRelease   # 发布包（默认使用 debug 证书签名，可安装测试）
```

产物：`app/build/outputs/apk/{debug,release}/`

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

- **单击返回键**：向页面派发 Cordova 风格的 `backbutton` 事件，并 Toast 提示"再按一次返回键退出"。Web-Packer 注入的 `controls.js` 触屏键盘只在 `backbutton` 事件里呼出/切换（浏览器 `keydown` 在 Android 返回键上不会触发）；没有监听器的页面则完全无感
- **双击返回键（2 秒内）**：退出应用，防止游戏中误触杀进程

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

## 项目结构

```
app/src/main/
├── assets/www/                         游戏资源（唯一需要替换的目录）
├── java/com/example/myapplication/
│   ├── MainActivity.kt                 WebView 容器、启动补丁注入、诊断探针
│   └── LocalWebServer.kt               基于 NanoHTTPD 的 assets 文件服务
└── res/
    ├── mipmap-*/                       各密度启动图标
    └── drawable/ic_launcher_background.xml   自适应图标背景色
```

---

## 环境要求

- JDK 11+
- Android SDK（`compileSdk 37` / `minSdk 24` / `targetSdk 37`）
- Gradle（用项目自带 `gradlew`）

---

## 已知限制

- `file://` 回退路径不经过 `shouldInterceptRequest`，启动补丁仅在 HTTP 主路径生效（此时靠 `onPageFinished` 探针兜底修复，不会白屏）
- release 包默认使用 debug 证书签名，仅适合本地测试分发；上架应用商店需替换为正式证书
- 需要联网的游戏（云变量、在线资源）依赖设备网络，`INTERNET` 权限已声明
