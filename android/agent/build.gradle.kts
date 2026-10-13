// Offline task logic: command parser + step-by-step app flows on top of the safety gate.
// Pure Kotlin (no Android) so every flow is unit-tested on the JVM against synthetic screens.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":safety"))
    // suspend loop + cancellation (Stop). Pure JVM; the same version Compose already pulls in.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

tasks.test { useJUnitPlatform() }
