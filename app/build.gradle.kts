plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "dev.aravind.assistant"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.aravind.assistant"
        minSdk = 31
        targetSdk = 32
        versionCode = 7
        versionName = "0.4.2"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_ARM_NEON=ON") }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("SIGNING_STORE_FILE") ?: "release.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
            pickFirsts += "**/libonnxruntime.so"
        }
    }
    // This app is sideloaded, so only one lint check matters: calls that are too new for Android 12.
    lint {
        checkReleaseBuilds = false
        checkOnly += "NewApi"
        abortOnError = true
    }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":policy"))
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("net.zetetic:sqlcipher-android:4.20.0@aar")
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
}
