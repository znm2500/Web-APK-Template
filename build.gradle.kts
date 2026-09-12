// Top-level build file where you can add configuration options common to all sub-projects/modules.
// 壳只需要 Android Application 插件；Compose / KSP / serialization 插件已随无用依赖一并移除。
plugins {
    alias(libs.plugins.android.application) apply false
}
