import java.io.File
import java.net.URL
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

// Never commit cloud/provider credentials. CI injects them as environment variables;
// local builds can define the same names in local.properties.
val arcoreApiKey = System.getenv("ARCORE_API_KEY")
    ?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("ARCORE_API_KEY")
        ?.takeIf { it.isNotBlank() }
    ?: ""

val tomTomApiKey = System.getenv("TOMTOM_API_KEY")
    ?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("TOMTOM_API_KEY")
        ?.takeIf { it.isNotBlank() }
    ?: ""

val generatedTrafficAssetsDir = layout.buildDirectory.dir("generated/trafficAssets")

android {
    namespace = "com.david.gps3dar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.david.gps3dar"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.7.1-beta-live"
        manifestPlaceholders["arcoreApiKey"] = arcoreApiKey
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main").assets.srcDir(generatedTrafficAssetsDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("org.maplibre.gl:android-sdk:13.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // 4.34.x keeps the AR/Filament feature set while compiling against stable SDK 36.
    implementation("io.github.sceneview:arsceneview:4.34.0")
}

val mapLibreVersion = "5.24.0"
val vendorDir = layout.projectDirectory.dir("src/main/assets/vendor")

val prepareMapLibreAssets by tasks.registering {
    val jsFile = vendorDir.file("maplibre-gl.js").asFile
    val cssFile = vendorDir.file("maplibre-gl.css").asFile
    outputs.files(jsFile, cssFile)

    doLast {
        vendorDir.asFile.mkdirs()

        fun downloadWithFallback(fileName: String, target: File) {
            if (target.exists() && target.length() > 1024L) return

            val urls = listOf(
                "https://cdn.jsdelivr.net/npm/maplibre-gl@$mapLibreVersion/dist/$fileName",
                "https://unpkg.com/maplibre-gl@$mapLibreVersion/dist/$fileName"
            )
            var lastError: Exception? = null

            for (url in urls) {
                try {
                    println("Downloading $fileName from $url")
                    val connection = URL(url).openConnection()
                    connection.connectTimeout = 20000
                    connection.readTimeout = 30000
                    connection.setRequestProperty("User-Agent", "GPS3D-AR-David-build/0.7.1")
                    connection.getInputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (target.length() > 1024L) return
                } catch (e: Exception) {
                    lastError = e
                    if (target.exists()) target.delete()
                    println("Mirror failed for $fileName: ${e.message}")
                }
            }

            throw GradleException("Unable to bundle $fileName", lastError)
        }

        downloadWithFallback("maplibre-gl.js", jsFile)
        downloadWithFallback("maplibre-gl.css", cssFile)
    }
}

val prepareTrafficConfig by tasks.registering {
    val outputDir = generatedTrafficAssetsDir
    outputs.dir(outputDir)

    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val escapedKey = tomTomApiKey
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", "")
        File(dir, "traffic-config.js").writeText(
            "window.GPS3D_CONFIG = Object.assign({}, window.GPS3D_CONFIG || {}, { tomtomApiKey: \"$escapedKey\" });\n"
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareMapLibreAssets, prepareTrafficConfig)
}
