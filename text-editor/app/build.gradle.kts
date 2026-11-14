plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.pahpuhbinny.texteditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pahpuhbinny.texteditor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // A shared, non-secret key for sideloading. Every build signs with the
        // same key, so a newer APK installs over an older one. Make your own
        // private key before publishing to a store.
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.keystore")
            storePassword = "texteditor-sideload"
            keyAlias = "texteditor"
            keyPassword = "texteditor-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
