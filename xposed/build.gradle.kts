plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
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
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    buildFeatures {
        compose = true
    }

    // Disable checkAarMetadata checks for modern Jetpack Compose / Miuix libraries
    tasks.matching { it.name.contains("AarMetadata") }.configureEach {
        enabled = false
    }

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

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-common:2.1.10")
        force("org.jetbrains.kotlin:kotlin-reflect:2.1.10")
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

    // AndroidX & Activity Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Compose UI Foundation & Runtime
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")

    // Compose Miuix (MIUI / HyperOS styled components)
    implementation("top.yukonga.miuix.kmp:miuix:0.8.8")
}
