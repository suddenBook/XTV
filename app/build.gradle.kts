plugins {
    alias(libs.plugins.android.application)
    // NOTE: AGP 9+ has built-in Kotlin support, so the kotlin-android plugin is intentionally NOT applied.
    // The Compose and serialization compiler plugins still attach to AGP's built-in Kotlin compilation.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

private val localDebugKeystore =
    file("${System.getProperty("user.home")}/.android/debug.keystore")

android {
    namespace = "com.xtv.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xtv.app"
        // 30 covers both target boxes: Google TV Streamer (34) and the Xiaomi MiTV-MOOR2 (30).
        minSdk = 30
        targetSdk = 37
        versionCode = 5
        versionName = "1.3.0"

    }

    signingConfigs {
        // Local-only continuity key. It is the standard Android debug key, never a production secret.
        // CI deliberately leaves release unsigned and signs it in the release workflow.
        create("local") {
            storeFile = localDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Match historical local release installs when that key exists. On fresh/CI machines,
            // retain AGP's generated debug signing config instead of referencing a missing file.
            if (System.getenv("CI") != "true" && localDebugKeystore.isFile) {
                signingConfig = signingConfigs.getByName("local")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Release assembly is always unsigned. Only the guarded release workflow may apply the
            // pinned public signer; a local debug key must never create a distributable-looking APK.
            signingConfig = null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
