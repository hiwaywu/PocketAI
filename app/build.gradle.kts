import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.pocketai.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketai.chat"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "1.13"
    }

    // One permanent release key so every build can install over the previous version without losing
    // chats. The key is never committed: it comes from keystore.properties (git-ignored, local builds)
    // or from environment variables (the GitHub release workflow, filled from repository secrets).
    val signing = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)
    val keystorePath = signingValue("storeFile", "POCKETAI_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) create("pocketai") {
            storeFile = file(keystorePath)
            storePassword = signingValue("storePassword", "POCKETAI_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "POCKETAI_KEY_ALIAS") ?: "pocketai"
            keyPassword = signingValue("keyPassword", "POCKETAI_KEY_PASSWORD") ?: storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the release key (e.g. a fresh checkout) fall back to the debug key; such an APK
            // can't update an installed Pocket AI, so real releases must use the release key.
            signingConfig = signingConfigs.findByName("pocketai") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
}
