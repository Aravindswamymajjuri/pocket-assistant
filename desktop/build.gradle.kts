plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("dev.aravind.assistant.MainKt") }
dependencies {
    implementation(project(":policy"))
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("com.formdev:flatlaf:3.6")
    implementation(files("libs/sherpa-onnx-jvm-1.13.8.jar", "libs/sherpa-onnx-native-lib-win-x64-1.13.8.jar"))
}
