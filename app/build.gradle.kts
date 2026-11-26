import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing: reads keystore.properties (never commit it) or CI environment variables.
// Release packaging fails explicitly when valid release credentials are missing (never uses debug signing).
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStorePath = keyProps.getProperty("storeFile")?.trim()?.takeIf { it.isNotEmpty() }
    ?: System.getenv("RELEASE_KEYSTORE_PATH")?.trim()?.takeIf { it.isNotEmpty() }
val releaseStoreFile = releaseStorePath?.let { path ->
    val candidate = File(path)
    if (candidate.isAbsolute) candidate else rootProject.file(path)
}
val releaseStorePassword = keyProps.getProperty("storePassword")?.takeIf { it.isNotEmpty() }
    ?: System.getenv("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotEmpty() }
val releaseKeyAlias = keyProps.getProperty("keyAlias")?.trim()?.takeIf { it.isNotEmpty() }
    ?: System.getenv("RELEASE_KEY_ALIAS")?.trim()?.takeIf { it.isNotEmpty() }
val releaseKeyPassword = keyProps.getProperty("keyPassword")?.takeIf { it.isNotEmpty() }
    ?: System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
val hasReleaseSigning = releaseStoreFile != null &&
    releaseStoreFile.exists() &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null
val appProjectPath = path

android {
    signingConfigs {
        debug {
            storeFile file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword "android"
            keyAlias "androiddebugkey"
            keyPassword "android"
        }
    }

    buildTypes {
        debug {
            signingConfig signingConfigs.debug
        }
    }
}
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Shrunk build (~6.5 MB), signed with your upload key for GitHub Releases / Google Play.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("upload") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val releasePackagingTasks = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "packageReleaseBundle",
    "signReleaseBundle",
)

gradle.taskGraph.whenReady {
    val runningReleasePackaging = allTasks.any { task ->
        task.project.path == appProjectPath && task.name in releasePackagingTasks
    }
    if (runningReleasePackaging && !hasReleaseSigning) {
        val missing = buildList {
            if (releaseStorePath == null) {
                add("storeFile / RELEASE_KEYSTORE_PATH")
            } else if (releaseStoreFile == null || !releaseStoreFile.exists()) {
                add("existing keystore file at '$releaseStorePath'")
            }
            if (releaseStorePassword == null) add("storePassword / RELEASE_STORE_PASSWORD")
            if (releaseKeyAlias == null) add("keyAlias / RELEASE_KEY_ALIAS")
            if (releaseKeyPassword == null) add("keyPassword / RELEASE_KEY_PASSWORD")
        }
        throw GradleException(
            "Release build requires valid upload signing credentials. Missing: ${missing.joinToString(", ")}. " +
                "Configure keystore.properties or RELEASE_* environment variables (debug signing fallback is disabled)."
        )
    }
}

dependencies {
    val media3 = "1.11.1"
    val room = "2.7.1"
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // Room local project persistence
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    // On-device understanding (free): objects/scenes, faces/smiles, and OCR. Models come via Google Play services.
    implementation("com.google.android.gms:play-services-mlkit-image-labeling:16.0.8")
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:$room")
}
