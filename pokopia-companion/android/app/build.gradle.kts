import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
}

/**
 * Copies the web app (../../web) into the APK's assets under www/.
 * The web folder is the single source of truth for both the website
 * and the Android app.
 */
abstract class SyncWebAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val webDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun sync() {
        fs.sync {
            from(webDir) {
                exclude("manifest.webmanifest")
            }
            into(outputDir.dir("www"))
        }
    }
}

val syncWebAssets = tasks.register<SyncWebAssets>("syncWebAssets") {
    webDir.set(layout.projectDirectory.dir("../../web"))
}

android {
    namespace = "io.github.pahpuhbinny.pokopia"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pahpuhbinny.pokopia"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // A shared, non-secret key for sideloading. Every build signs with the
        // same key, so a newer APK installs over an older one and your saved
        // progress is kept. Make your own private key before publishing to a store.
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.keystore")
            storePassword = "pokopia-sideload"
            keyAlias = "pokopia"
            keyPassword = "pokopia-sideload"
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

    // Sprites are already compressed WebP files.
    androidResources {
        noCompress += listOf("webp", "woff2")
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncWebAssets, SyncWebAssets::outputDir)
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
