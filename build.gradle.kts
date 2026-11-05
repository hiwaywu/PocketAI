plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so there's no separate kotlin-android plugin.
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
