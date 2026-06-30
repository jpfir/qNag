plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.exogroup.qnag"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.exogroup.qnag"
        minSdk = 24
        targetSdk = 36
        versionCode = 9
        versionName = "1.0.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Release signing uses environment variables injected by CI.
        // Debug builds work without these — this block is ignored when env vars are absent.
        create("release") {
            val keystorePath     = System.getenv("ANDROID_KEYSTORE_PATH")
            val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val keyAlias         = System.getenv("ANDROID_KEY_ALIAS")
            val keyPassword      = System.getenv("ANDROID_KEY_PASSWORD")
            if (!keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() &&
                !keyAlias.isNullOrBlank()     && !keyPassword.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            val releaseConfig = signingConfigs.getByName("release")
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = releaseConfig
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Android hardware-backed encryption
    implementation("androidx.security:security-crypto:1.1.0")
    // Google's Gson library to convert our Kotlin objects into JSON strings
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    // Bridge between ViewModels and Jetpack Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    // Extra Material Icons for the swipe and top bar gestures
    implementation("androidx.compose.material:material-icons-extended")
    // Background polling via WorkManager (minimum periodic interval 15 minutes on Android)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Jetpack Glance for home screen widgets
    implementation("androidx.glance:glance-appwidget:1.1.0")
}