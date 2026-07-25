plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp") version "2.0.0-1.0.21"
}

android {
    namespace = "com.rhodesisland.terminal"
    compileSdk = 34
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
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 仅打包 arm64-v8a：与预编译 MNN/QNN 库架构一致，避免其它 ABI 编译/缺失失败
        ndk { abiFilters.add("arm64-v8a") }

        // 把预编译目录传给 CMake。CMake 始终启用（cpu_sys_jni 必须编译）。
        externalNativeBuild {
            cmake {
                val args = mutableListOf(
                    // cpu_affinity_jni.cpp / mnn_jni.cpp 引入 C++（std::vector/string），
                    // 用 c++_shared 与已随包的 libc++_shared.so 保持一致，避免静态 libc++
                    // 与其它 .so 的共享 libc++ 产生重复符号 / 状态分裂。
                    // MNN 的 libMNN.so 同为 c++_shared 构建，三者共用同一份 libc++_shared.so。
                    "-DANDROID_STL=c++_shared",
                )
                if (!mnnDir.isNullOrBlank()) args += "-DMNN_DIR=${mnnDir}"
                arguments += args
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // CMake 始终启用：cpu_sys_jni（CPU 频率/拓扑只读 JNI）必须编译，mnn_jni 需 MNN_DIR。
    // MNN_DIR 指向 MNN 预编译目录（含 include/ 与 lib/libMNN.so）。
    // CMake 参数在 defaultConfig.externalNativeBuild.cmake 中传递。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 角色语音 wav / BGM mp3 不压缩：MediaPlayer 经 AssetFileDescriptor 播放需要未压缩资源
    // （openFd 对压缩资源会抛 IOException）。noCompress 保证零拷贝路径可用。
    androidResources {
        noCompress += listOf("wav", "mp3")
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
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

    // Coil
    implementation(libs.coil.compose)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Liquid Glass (QmDeve frosted glass overlay with refraction + dispersion + blur)
    implementation(libs.liquidglass.core)
}
