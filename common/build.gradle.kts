plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}


tasks.test { useJUnitPlatform() }
