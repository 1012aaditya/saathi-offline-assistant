// Text -> 6-dot braille cells, and OTP extraction. Pure Kotlin so the tests run on the JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

tasks.test { useJUnitPlatform() }
