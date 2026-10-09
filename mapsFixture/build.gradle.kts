plugins { id("com.android.application") }
android {
    namespace = "com.david.gps3dar.mapsfixture"
    compileSdk = 36
    defaultConfig { applicationId = "com.google.android.apps.maps"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "qa-only" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
