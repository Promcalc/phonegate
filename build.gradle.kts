// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Плагин org.jetbrains.kotlin.android больше не нужен: AGP 9 компилирует Kotlin сам
// (built-in Kotlin). См. docs/decisions.md (D1).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
