import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Version from git: versionName = `git describe` (rolling release tags excluded), versionCode = number of commits.
fun git(vararg args: String): String? =
    try {
        val out = ByteArrayOutputStream()
        val p = ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start()
        p.inputStream.copyTo(out)
        if (p.waitFor() == 0) out.toString().trim() else null
    } catch (_: Exception) {
        null
    }

android {
    namespace = "org.librehu.bridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.librehu.bridge"
        // Phones: Android 8 and later.
        minSdk = 26
        targetSdk = 35
        versionCode = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
        versionName = git("describe", "--tags", "--always", "--dirty", "--exclude", "latest-*", "--exclude", "build-*") ?: "dev"
    }
    // The LibreHU signing key from the CI secrets (LIBREHU_KEYSTORE*) when set; no key in this repository, so a
    // local build without it is signed with the debug key.
    val librehuKey = System.getenv("LIBREHU_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (librehuKey != null) {
            create("librehu") {
                storeFile = librehuKey
                storePassword = System.getenv("LIBREHU_KEYSTORE_PASSWORD") ?: "librehu"
                keyAlias = System.getenv("LIBREHU_KEY_ALIAS") ?: "librehu"
                keyPassword = System.getenv("LIBREHU_KEY_PASSWORD") ?: "librehu"
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = if (librehuKey != null) signingConfigs.getByName("librehu") else signingConfigs.getByName("debug")
            // Optimised by R8 (code and resources shrunk, without renaming: proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    // Android Auto screen (Car App Library, IoT category).
    implementation(libs.car.app)
    // QR code scanner (ZXing, Apache 2.0).
    implementation(libs.zxing.embedded)
}
