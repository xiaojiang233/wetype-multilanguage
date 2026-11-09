// Requires the Android SDK (ANDROID_HOME) and Google Maven access; see README.
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "top.xiaojiang233.wetypemulti.xposed"
    // 36: required by io.github.libxposed:service (its AAR metadata). targetSdk stays 34.
    compileSdk = 36
    defaultConfig {
        applicationId = "top.xiaojiang233.wetypemulti.xposed"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Native librime bridge. Prebuilt librime.so (arm64-v8a) lives in third_party/librime-android;
    // rebuild it with scripts/build-librime-android.sh. Override with -PrimeDir=<dir> or disable with -PnoNative.
    val rimeDir = (project.findProperty("rimeDir") as String?) ?: rootProject.file("third_party/librime-android").path
    if (!project.hasProperty("noNative") && file("$rimeDir/lib/arm64-v8a/librime.so").exists()) {
        defaultConfig {
            ndk { abiFilters += listOf("arm64-v8a") } // WeType 4.0.0 ships arm64-v8a only
            externalNativeBuild { cmake { arguments += "-DRIME_DIR=$rimeDir" } }
        }
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
        sourceSets["main"].jniLibs.srcDir("$rimeDir/lib")
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(project(":common"))
    // Modern Xposed API. Pinned to API_101 (not the newer 102) to maximize compatibility with
    // whatever libxposed framework version the user's specific LSPatch build ships.
    compileOnly("io.github.libxposed:api:101.0.1")
    // Companion service: its XposedProvider is how LSPatch Manager finds and connects a modern (libxposed)
    // module. Without it the module is not loaded into WeType, even when listed (see the manifest comment).
    implementation("io.github.libxposed:service:101.0.0") {
        // Built against the Kotlin 2.2 stdlib, which the Kotlin 2.0.21 compiler here cannot read. Keep the
        // project's own stdlib instead; the service classes are only used at runtime by LSPatch Manager.
        exclude(group = "org.jetbrains.kotlin")
    }
}
