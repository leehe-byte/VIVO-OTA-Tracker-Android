// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 内置 Kotlin 支持，不能再应用 org.jetbrains.kotlin.android（见 liquidglassdemo/README.md）
    alias(libs.plugins.kotlin.compose) apply false
}
