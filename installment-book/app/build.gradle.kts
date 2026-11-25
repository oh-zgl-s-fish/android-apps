plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.zglinus.installment"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zglinus.installment"
        minSdk = 29
        targetSdk = 36
        versionCode = 121
        versionName = "1.9.5"
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
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:plans"))
    implementation(project(":feature:newplan"))
    implementation(project(":feature:ai"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:accounts"))
}
