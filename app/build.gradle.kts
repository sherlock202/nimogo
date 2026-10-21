plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.yingdu"
    // Navigation SDK 7.x 需要用 API 36 编译
    compileSdk = 36

    defaultConfig {
        // NimoGo 用自己的包名：可以和原版萤读同时安装，Google Cloud 里给 API Key 加应用限制时也用这个包名
        applicationId = "io.github.nimogo"
        minSdk = 26
        // targetSdk 保持 34：萤读的界面没有适配 Android 15 起强制的全面屏（edge-to-edge），升上去状态栏会盖住内容
        targetSdk = 34
        versionCode = 11
        versionName = "1.1.1"
        // 只打包 64 位 ARM（现在的手机）和 x86_64（模拟器）：语音跟读的识别库每种 CPU 都有几十 MB
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // 固定的签名（GitHub Actions 里用仓库 Secrets 传进来）：每次编译签名相同，新版可以直接覆盖安装、设置不丢
    val ksFile = System.getenv("NIMOGO_KEYSTORE_FILE")
    signingConfigs {
        create("nimogo") {
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("NIMOGO_KEYSTORE_PASSWORD")
                keyAlias = "nimogo"
                keyPassword = System.getenv("NIMOGO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksFile != null) signingConfig = signingConfigs.getByName("nimogo")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Navigation SDK 7.0 起要求应用自己开启 Java 库脱糖
        isCoreLibraryDesugaringEnabled = true
    }
    lint {
        // targetSdk 34 低于 Google Play 现在的要求；NimoGo 只做侧载安装，不让这条检查挡住 Release 打包
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 语音跟读的离线识别（sherpa-onnx，Apache-2.0）：官方只在 GitHub 发布 AAR，编译时没有就自动下载到 app/libs（不提交到仓库）
val sherpaVersion = "1.13.8"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
if (!sherpaAar.isFile) {
    sherpaAar.parentFile.mkdirs()
    val tmp = file("libs/sherpa-onnx-$sherpaVersion.aar.part")
    uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar").toURL()
        .openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
    tmp.renameTo(sherpaAar)
}

dependencies {
    implementation("io.github.jaredmdobson:concentus:1.0.2")   // 纯 Java 的 Opus 解码（眼镜麦克风），BSD 许可
    // Google 导航（逐向导航数据流）。注意：同一个 app 里不能再加 Maps SDK（play-services-maps）
    implementation("com.google.android.libraries.navigation:navigation:7.9.0")
    implementation(files("libs/sherpa-onnx-$sherpaVersion.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")   // 解压识别模型（.tar.bz2），Apache 许可
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // 单元测试里 android.jar 的 org.json 只是空壳
}
