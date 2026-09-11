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

// MapLibre GL JS is bundled into the APK instead of being loaded from a CDN at runtime.
// This avoids ORB/CORS/CDN failures inside Android WebView.
val mapLibreVersion = "6.9.0"
val vendorDir = layout.projectDirectory.dir("src/main/assets/vendor")

val prepareMapLibreAssets by tasks.registering {
    val jsFile = vendorDir.file("maplibre-gl.js").asFile
    val cssFile = vendorDir.file("maplibre-gl.css").asFile
    outputs.files(jsFile, cssFile)

    doLast {
        vendorDir.asFile.mkdirs()

        fun download(url: String, target: java.io.File) {
            if (target.exists() && target.length() > 1024L) return
            println("Downloading ${target.name} for bundled WebView map engine...")
            java.net.URL(url).openConnection().apply {
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", "GPS3D-AR-David-build/0.5.2")
            }.getInputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }

        download(
            "https://unpkg.com/maplibre-gl@$mapLibreVersion/dist/maplibre-gl.js",
            jsFile
        )
        download(
            "https://unpkg.com/maplibre-gl@$mapLibreVersion/dist/maplibre-gl.css",
            cssFile
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareMapLibreAssets)
}
