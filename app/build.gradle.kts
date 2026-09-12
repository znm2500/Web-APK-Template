plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(37) {
            // keep minorApiLevel if needed by your environment
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // 调试包不混淆：构建快、崩溃堆栈可读，方便排查游戏兼容问题
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            // 用 debug 证书签名 release：模板壳的定位是"塞游戏即出包"，
            // 与 Web-Packer 的 APK 模板一致（debug 签名可安装测试，上架再换正式证书）
            signingConfig = signingConfigs.getByName("debug")
            // R8 + 资源压缩：壳用不到的类、资源全部剔除（这是把空壳从 25MB 压到 1MB 级的开关）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 唯一的功能依赖：轻量嵌入式 HTTP 服务器（编译后仅约 30 个类）。
    // 壳只做三件事——给文件来源、给 WebView 容器、内存注入启动补丁——
    // 渲染与游戏运行时全部由系统 WebView（Chromium）提供，不需要任何 UI 框架。
    implementation(libs.nanohttpd)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.runner)
}
