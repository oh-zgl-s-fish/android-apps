plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.zglinus.recstt"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zglinus.recstt"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.4"
    }

    // 目录布局：src / res / AndroidManifest.xml / native 都在工程根目录
    sourceSets {
        getByName("main") {
            java.srcDirs("src")
            kotlin.srcDirs("src")
            res.srcDirs("res")
            manifest.srcFile("AndroidManifest.xml")
            jniLibs.srcDirs("native")
        }
    }

    // 签名：密钥不入库。四个环境变量都给了才启用发布签名，否则 release 产物不签名。
    //   RELEASE_KEYSTORE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
    signingConfigs {
        create("release") {
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
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // 与清单里的 android:extractNativeLibs="true" 对齐：libwireproxy.so 需要装到
    // 文件系统里再 exec，压缩/解压方式沿用旧 build.sh 的行为。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    // com.wgtunnel:backend-android:1.10.3 暂时加不进来：
    //   1) 它的 AAR metadata 写死 minCompileSdk=37，AGP 8.7.3 + compileSdk 36 直接报错；
    //   2) 它会传递依赖 androidx.core:core(-ktx):1.19.1，而那个要求 AGP 9.1.0+、compileSdk 37；
    //   3) 它的 com.github.topjohnwu.libsu:core:6.0.0 在 JitPack，需要额外仓库。
    // 等把 AGP/compileSdk 提到 37 后再启用下面两行（同时给 settings.gradle.kts 加 jitpack 仓库）：
    // implementation("com.wgtunnel:backend-android:1.10.3")
}

// 把 release APK 复制成工程根目录的 recstt.apk，方便直接取件
val copyReleaseApk by tasks.registering {
    group = "build"
    description = "把 release APK 复制成工程根目录的 recstt.apk"
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val target = layout.projectDirectory.file("recstt.apk")
    inputs.dir(apkDir)
    outputs.file(target)
    doLast {
        val apks = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        check(apks.isNotEmpty()) { "release APK 不存在: ${apkDir.get().asFile}" }
        apks.sortedBy { it.name }.first().copyTo(target.asFile, overwrite = true)
    }
}

// assembleRelease 由 AGP 延迟创建，这里用 matching 懒绑定，避免配置期取不到任务
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyReleaseApk)
}
