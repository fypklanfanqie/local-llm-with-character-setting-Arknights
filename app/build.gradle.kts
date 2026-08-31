plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp") version "2.0.0-1.0.21"
}

// 项目目录含中文，CMake 3.22.1 向中文路径写产物（.so 输出目录）会崩溃，
// 故把 buildDir 重定向到 ASCII 路径。仅在该 ASCII 路径已存在（即本机已构建过）时重定向，
// 其他克隆者使用默认 buildDir，避免硬编码路径导致他人构建失败。
// （native 库已预编译入 jniLibs，Gradle 不再调 CMake，默认 buildDir 对他人安全。）
val redirectBuildDir = file("D:/ai-build/rhodesisland/app-build")
if (redirectBuildDir.parentFile?.exists() == true) {
    layout.buildDirectory.set(redirectBuildDir)
}

android {
    namespace = "com.rhodesisland.terminal"
    // compileSdk 35：Android 15 API（FOREGROUND_SERVICE_TYPE_SPECIAL_USE 等）。需 AGP >= 8.6.1。
    compileSdk = 35
    // 与 local.properties 的 ndk.dir(D:\android-ndk-r27c)对齐，版本 27.2.12479018。
    // 否则 AGP 报 [CXX1104]：ndk.dir 版本与 android.ndkVersion 默认值(26.x)不一致。
    ndkVersion = "27.2.12479018"

    // 本地 AI：MNN 引擎（.mnn 模型，CPU/OpenCL GPU/QNN NPU）
    // 配置 MNN_DIR 后启用 libmnn_jni.so 编译。
    // 注意：cpu_sys_jni（CPU 频率/拓扑只读 JNI，性能浮窗用）始终编译，故 CMake 始终启用。
    val mnnDir: String? = providers.gradleProperty("MNN_DIR").orNull

    defaultConfig {
        applicationId = "com.rhodesisland.terminal"
        minSdk = 24
        // targetSdk 35（Android 15）：强制 edge-to-edge（本项目已自处理 insets，天然合规）；
        // dataSync FGS 引入 6h/24h 超时 -> 推理前台服务已迁移 specialUse 类型（见 InferenceForegroundService）。
        targetSdk = 35
        versionCode = 8
        versionName = "3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 仅打包 arm64-v8a：与预编译 MNN/QNN 库架构一致，避免其它 ABI 编译/缺失失败
        ndk { abiFilters.add("arm64-v8a") }

        // 只保留应用实际支持的语言，剔除依赖库中未使用的多语言资源
        resourceConfigurations += listOf("zh", "zh-rCN", "en")

        // native 库改为预编译放入 jniLibs（见 android.externalNativeBuild 处说明），不再经 Gradle 调 CMake。
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // 测试期间先用 debug 签名；正式发布前请配置 release signingConfig
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // native 库（libmnn_jni.so / libcpu_sys_jni.so）已预编译并放入 src/main/jniLibs/arm64-v8a。
    // 不再用 Gradle externalNativeBuild 调 CMake：项目目录含中文，CMake 3.22.1 在此环境会
    // STATUS_STACK_BUFFER_OVERRUN 崩溃。如需重编 native，用 cpp/CMakeLists.txt 手动编译：
    //   cmake -G Ninja -DCMAKE_SYSTEM_NAME=Android -DANDROID_ABI=arm64-v8a \
    //     -DANDROID_NDK=D:/android-ndk-r27c \
    //     -DCMAKE_TOOLCHAIN_FILE=D:/android-ndk-r27c/build/cmake/android.toolchain.cmake \
    //     -DANDROID_STL=c++_shared -DMNN_DIR=D:/mnn-matched \
    //     -S app/src/main/cpp -B <ASCII-build-dir> && ninja -C <ASCII-build-dir>
    // 然后把生成的 .so 拷入 jniLibs/arm64-v8a。

    packaging {
        // 标准构建（Task 11）：排除全部 QNN 运行时库。源文件保留在 jniLibs 供未来实验 flavor 使用，
        // 但标准 APK 不含任何 libQnn*（CI 用 apkanalyzer/unzip 断言）。NPU 不可用 -> 解析为 CPU。
        jniLibs {
            excludes += listOf("**/libQnn*")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // androidResources：内置音乐（assets/music 的 BGM mp3 + 干员语音 wav）需保持未压缩，
    // 供 MediaPlayer/ExoPlayer 的 AssetFileDescriptor 零拷贝播放（openFd 需要非压缩资源）。
    androidResources {
        noCompress.clear()
        noCompress += listOf("wav", "mp3")
    }

    // 单元测试：对调用到 Android 桩（SystemClock/Context 等）的纯 Kotlin 代码返回默认值，
    // 避免抛 "Method ... not mocked"。遥测统计等纯逻辑仍走真实实现。
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    // Coil
    implementation(libs.coil.compose)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    // media3-ui：Seedance 视频卡内联播放与全屏预览的 PlayerView 宿主（Task 8）
    implementation(libs.androidx.media3.ui)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Liquid Glass (QmDeve frosted glass overlay with refraction + dispersion + blur)
    implementation(libs.liquidglass.core)

    // WorkManager (角色问候：后台定时主动发消息，跨重启存活)
    implementation(libs.androidx.work.runtime.ktx)

    // Testing（Task 2 遥测基线）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.work.testing)

    // Compose UI 测试（Task 3 滚动 instrumentation；版本随 compose BOM）
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
