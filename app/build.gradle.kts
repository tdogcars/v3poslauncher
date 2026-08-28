import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// Release signing.
//
// CI injects the keystore via environment variables (see .github/workflows/release.yml).
// Locally, if the variables are absent, the release build type falls back to the debug
// keystore so `./gradlew assembleRelease` still works for smoke tests. A debug-signed
// APK will NOT pass the QR's PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM — only the
// real release keystore does. See PROVISIONING.md, "Why the keystore must never change".
// ---------------------------------------------------------------------------
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.flo.v3poslauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flo.v3poslauncher"
        // minSdk 29 (Android 10). Justification in PROVISIONING.md, "API level floor".
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "3.0.0-local"

        // Install-site Wi-Fi password is injected from the CI secret, never committed to source.
        // Empty in a plain local build; the QR supplies it per-device at provision time anyway.
        buildConfigField(
            "String",
            "WIFI_PASSWORD_DEFAULT",
            "\"${System.getenv("WIFI_PASSWORD") ?: ""}\"",
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 is required for the ManagedProvisioning downloader on some OEM builds
                // that still verify with the legacy scheme; v2 is required on Android 11+.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Deliberately not minified: the app has no third-party code, is tiny,
            // and un-obfuscated stack traces in the provisioning log are worth more
            // than a few hundred KB.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
        }
    }

    buildFeatures {
        // We inject the Wi-Fi password default via BuildConfig.
        buildConfig = true
        // The launcher UI (home screen, Launcher configuration) is Jetpack Compose,
        // ported from the v1 POS Launcher (com.blurredlimes.poslauncher).
        compose = true
    }

    lint {
        // The build must not be blocked by lint on a device-owner app that intentionally
        // uses deprecated-but-DO-permitted APIs (WifiManager.addNetwork, WifiConfiguration).
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Provisioning / Device Owner code is framework-only. The visible launcher UI is the
    // v1 Compose UI, so Compose + a few AndroidX libraries are pulled in for it. No
    // networking library: the only network call is the technician's speed test.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
