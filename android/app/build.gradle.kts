plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.saathi"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.saathi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // ARM only (all Android phones/tablets in scope); drops onnxruntime's x86 libraries.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // Walk mode models are packaged from ../../walk/models at build time (see copyWalkModels).
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/walkAssets"))
}

// Copy the two walk-mode YOLOv8n models + class lists into the APK's assets (assets/walk/...)
// without keeping a second copy in git. Source of truth: walk/models (tracked, shared with the web walk mode).
val copyWalkModels by tasks.registering(Copy::class) {
    from(file("../../walk/models")) {
        include("yolov8n-coco-256.onnx", "yolov8n-oiv7-256.onnx", "coco.json", "oiv7.json")
    }
    into(layout.buildDirectory.dir("generated/walkAssets/walk"))
}
// Everything that reads the assets source dirs must run after the copy: merge*Assets, the
// *Lint* model/analyze tasks (lintDebug, lintVital*), and any other *Assets* consumer.
tasks.configureEach {
    if (name == copyWalkModels.name) return@configureEach
    if (name.contains("Assets") || name.contains("lint", ignoreCase = true)) dependsOn(copyWalkModels)
}

dependencies {
    implementation(project(":safety"))
    implementation(project(":agent"))
    implementation(project(":braille"))
    // Braille reader: SMS User Consent (one message at a time, after the user taps Allow). No SMS permission.
    implementation("com.google.android.gms:play-services-auth-api-phone:18.1.0")

    // Walk mode: camera + the same YOLOv8n ONNX models as the web walk mode (walk/models), run on-device.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // JVM unit tests for the walk-mode logic (app/src/test).
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
