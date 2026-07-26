import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // NOTE: AGP 9+ has built-in Kotlin support, so the kotlin-android plugin is intentionally NOT applied.
    // The Compose and serialization compiler plugins still attach to AGP's built-in Kotlin compilation.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The X API client id is per-builder, never committed, and never baked into a distributed APK.
 *
 * X bills API usage to the **owner of the developer app**, not to the end user who authorizes it —
 * OAuth does not transfer the bill. So a shipped APK carrying one client id would make its author pay
 * for every stranger's usage. XTV is therefore distributed as source: each person registers their own
 * X app and puts its client id in `local.properties` (git-ignored) as `xtv.clientId=...`.
 *
 * A build without it still compiles and installs; the app shows a setup guide instead of crashing.
 */
private val xtvClientId: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("xtv.clientId") ?: ""

/** Optional pre-authorised session, for a build that should install already logged in. */
private val xtvRefreshToken: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("xtv.refreshToken") ?: ""

android {
    namespace = "com.xtv.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xtv.app"
        // 30 covers both target boxes: Google TV Streamer (34) and the Xiaomi MiTV-MOOR2 (30).
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "X_CLIENT_ID", "\"$xtvClientId\"")
        // Optional. A build carrying this is *already signed in* — which also makes the APK a
        // full account credential. Never publish such a build, never upload it as a CI artifact.
        buildConfigField("String", "X_REFRESH_TOKEN", "\"$xtvRefreshToken\"")
        // Registered on the X developer app. Nothing ever listens on it: the OAuth consent page runs in
        // our own WebView and `shouldOverrideUrlLoading` intercepts the redirect *before* navigation,
        // so this address never has to resolve.
        buildConfigField("String", "X_REDIRECT_URI", "\"http://localhost:8080/callback\"")
    }

    signingConfigs {
        // Local fallback: reuse the debug keystore so local release builds stay sideloadable (CI signs via apksigner).
        create("local") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (System.getenv("CI") == "true") null else signingConfigs.getByName("local")
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
    implementation(libs.androidx.webkit)

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
