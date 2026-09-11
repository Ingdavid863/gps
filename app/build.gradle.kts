import java.io.File
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.david.gps3dar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.david.gps3dar"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.5.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk:13.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// Keep the UMD build locally inside the APK. MapLibre GL JS v6 is ESM-only,
// so v5.24.0 is intentionally pinned here for a classic <script> WebView load.
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
                    connection.setRequestProperty("User-Agent", "GPS3D-AR-David-build/0.5.2")
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

tasks.named("preBuild").configure {
    dependsOn(prepareMapLibreAssets)
}
