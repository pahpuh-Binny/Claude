plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.pahpuhbinny.multiview"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pahpuhbinny.multiview"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // A shared, non-secret key for sideloading, so every build can be
        // installed over the previous one. See keystore/README.md.
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.keystore")
            storePassword = "multiview-sideload"
            keyAlias = "multiview"
            keyPassword = "multiview-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Browser engine extras: document-start scripts, message bridge, safe browsing.
    implementation("androidx.webkit:webkit:1.12.1")

    // Background playback notification and lock-screen / headset controls.
    implementation("androidx.media:media:1.7.0")

    // Player for downloaded (encrypted) videos.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Encryption of the download vault (keys live in the Android Keystore).
    implementation("com.google.crypto.tink:tink-android:1.15.0")
    implementation("androidx.biometric:biometric:1.1.0")

    // Chromecast.
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (Android's copy is only a stub there).
    testImplementation("org.json:json:20240303")
}
