plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.chinalivetv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.chinalivetv"
        minSdk = 19
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Media3 requires API 21; ExoPlayer 2 is the last line that runs on Android 4.4.
    val exoPlayerVersion = "2.19.1"
    implementation("com.google.android.exoplayer:exoplayer-core:$exoPlayerVersion")
    implementation("com.google.android.exoplayer:exoplayer-hls:$exoPlayerVersion")
    implementation("com.google.android.exoplayer:exoplayer-ui:$exoPlayerVersion")
}
