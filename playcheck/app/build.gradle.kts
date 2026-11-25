plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zglinus.playcheck"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zglinus.playcheck"
        minSdk = 29
        targetSdk = 36
        versionCode = 22
        versionName = "1.21"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("../src")
            kotlin.srcDirs("../src")
            res.srcDirs("../res")
            manifest.srcFile("../AndroidManifest.xml")
        }
    }

    // 签名：密钥不入库。四个环境变量都给了才启用发布签名，否则 release 产物不签名。
    //   RELEASE_KEYSTORE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
    signingConfigs {
        create("rel") {
            val ks = System.getenv("RELEASE_KEYSTORE")
            if (!ks.isNullOrBlank() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("RELEASE_KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD").orEmpty()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("RELEASE_KEYSTORE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("rel")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
