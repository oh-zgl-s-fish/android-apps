plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.zglinus.installment.ui"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
dependencies {
    api("androidx.biometric:biometric:1.1.0")
    api(project(":core"))
    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.activity:activity-ktx:1.9.2")
    api("androidx.core:core-ktx:1.13.1")
    api("com.google.android.material:material:1.12.0")
}
