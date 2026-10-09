pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "GPS3D_AR_David"
include(":app")
// Included only in the emulator QA build; the fixture is never part of the delivered APK.
if (System.getenv("GPS3D_AUTO_SHARE_QA") == "1") include(":mapsFixture")

