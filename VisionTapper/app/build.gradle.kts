plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.visiontapper.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.visiontapper.app"
        minSdk = 30            // AccessibilityService.takeScreenshot needs API 30+
        targetSdk = 36         // bump to 37 once the Android 17 SDK is stable in sdkmanager
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // On-device OCR (model is bundled, works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
