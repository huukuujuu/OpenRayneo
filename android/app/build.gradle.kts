plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openrayneo.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.openrayneo.android"
        minSdk = 33
        targetSdk = 36
        // CI builds count up with the workflow run so every APK installs over the previous one (android-apk.yml sets
        // OPENRAYNEO_VERSION_CODE); a build with only GITHUB_RUN_NUMBER uses 1000 + run; plain local builds stay at 37.
        versionCode = System.getenv("OPENRAYNEO_VERSION_CODE")?.toIntOrNull()
            ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.let { 1000 + it } ?: 37
        // CI passes the next minor version per build (see android-apk.yml); local builds keep the base name.
        versionName = System.getenv("OPENRAYNEO_VERSION_NAME") ?: "1.2.0-beta.4"
        buildConfigField("String", "GIT_COMMIT", "\"" + (System.getenv("GITHUB_SHA")?.take(7) ?: "local") + "\"")
        ndk { abiFilters += providers.gradleProperty("openrayneo.abis").orElse("arm64-v8a").get().split(',') }
    }
    // CI names the fixed key file explicitly; relying on the default ~/.android location signed every build differently.
    val fixedKey = System.getenv("OPENRAYNEO_KEYSTORE")?.let(::file)
    signingConfigs {
        if (fixedKey != null) create("fixed") {
            storeFile = fixedKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("release") {
            // Direct-distribution Beta keeps one certificate so every APK installs over the previous one.
            signingConfig = signingConfigs.getByName(if (fixedKey != null) "fixed" else "debug")
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "onnx" }
    sourceSets.getByName("main").assets.srcDir("../../ThirdParty/SherpaOnnx")
}

val prepareVoiceModels by tasks.registering(Exec::class) {
    commandLine("sh", rootProject.file("../scripts/prepare-android-voice.sh"))
    outputs.files(file("libs/sherpa-onnx-2.0.1.aar"), file("src/main/assets/voice-models/campplus-zh-16k.onnx"),
        file("src/main/assets/voice-models/silero-vad.onnx"), file("src/main/assets/voice-models/gtcrn-simple.onnx"),
        file("src/main/assets/voice-models/segmentation.int8.onnx"))
}
tasks.named("preBuild") { dependsOn(prepareVoiceModels) }

dependencies {
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
