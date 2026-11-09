pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "wetype-multilanguage"

include(":common")

// The Xposed module needs the Android SDK (Google Maven). Only include it when available.
if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists()
) {
    include(":xposed")
}
