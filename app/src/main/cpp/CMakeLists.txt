cmake_minimum_required(VERSION 3.22.1)
# 启用 CXX：cpu_affinity_jni.cpp / mnn_jni.cpp 均为 C++（std::vector/string/lambda）。
project(mnn_jni CXX)

# C++17（mnn_jni 的运行时信息握手与 JSON 输出使用 string_view/结构化绑定等）。
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ===== 16 KiB 页对齐（Android 15 flexible page sizes）=====
# 所有打包的 .so 必须以 PT_LOAD p_align >= 0x4000 链接，否则在 Android 15 16 KiB 页设备上
# 加载失败。由 scripts/native/verify_native_bundle.py 在构建后校验。
# 详见 docs/superpowers/specs/2026-08-08-mnn-adaptive-inference-optimization-design.md 第 8.3 节。
set(CHAT_16K_PAGE_LINKER_FLAG "-Wl,-z,max-page-size=16384")

# ===== 运行时溯源编译定义（供 mnn_jni 的 nativeGetRuntimeInfo 回传 Kotlin）=====
# CHAT_MNN_BUILD_ID 由 scripts/native/build_mnn_android.sh 生成并传入；本地/开发构建回退 "dev-unpinned"。
set(CHAT_MNN_COMMIT "af0142bcc7b76b7a5128373e285683dc04f55f69" CACHE STRING "钉定的 MNN commit")
set(CHAT_MNN_JNI_ABI 1 CACHE STRING "MNN JNI ABI 版本（与 Kotlin MnnRuntimeInfo.abiVersion 对齐）")
set(CHAT_MNN_BUILD_ID "dev-unpinned" CACHE STRING "native build ID（由 build_mnn_android.sh 设置）")

# ===== CPU 系统 JNI 包装（libcpu_sys_jni.so）=====
# 只读 CPU 探测：大核 ID / 拓扑 / 频率（cpp/cpu_affinity_jni.cpp + cpu_affinity.h）。
# 仅依赖 liblog，无 llama/MNN 依赖，**始终编译**（性能浮窗与线程数推荐依赖）。
# 供 Kotlin CpuSysBridge 加载（c++_shared -> cpu_sys_jni 顺序）。
add_library(cpu_sys_jni SHARED cpu_affinity_jni.cpp)
target_link_libraries(cpu_sys_jni log)
target_link_options(cpu_sys_jni PRIVATE ${CHAT_16K_PAGE_LINKER_FLAG})

# ===== MNN 后端 JNI 包装（libmnn_jni.so）=====
# 链接预编译 libMNN.so（MNN 低内存 LLM 引擎，含 OpenCL GPU 后端 + ARM82 + transformer-fuse），
# 实现 Java_com_chatbyyourside_llm_backend_MnnBridge_* 符号。
#
# MNN 引擎运行 `.mnn` 模型目录（config.json + llm.mnn + llm.mnn.weight + tokenizer.txt），
# 通过 set_config 的 backend_type 字段选 cpu/opencl/qnn。
# libMNN.so 用 NDK 交叉编译、c++_shared STL，与本包装及 libc++_shared.so 同源，避免 ABI 不一致。
#
# 运行时前提：libMNN.so 由 jniLibs 打包；MnnBridge 按 MNN -> mnn_jni 顺序 System.loadLibrary。
# 缺 libMNN.so 时 mnn_jni 链接失败（本段被 MNN_DIR 守卫跳过），MNN 后端整体不可用。
#
# 注意：本 CMake 不由 Gradle 调用——因本机 JDK 8 与项目 JDK 17+ 不兼容，且需保证
# 「同一 NDK/libc++ 工具链 + 钉定 MNN commit + 16 KiB 对齐」的可复现构建，native 库由
# scripts/native/build_mnn_android.sh 离线构建并校验后，作为 verified-prebuilt 打包。
set(MNN_DIR "" CACHE PATH "MNN 预编译目录（含 include/ 与 lib/libMNN.so）")
if(MNN_DIR AND EXISTS "${MNN_DIR}/lib/libMNN.so")
    set(MNN_INCLUDE "${MNN_DIR}/include")
    if(NOT EXISTS "${MNN_INCLUDE}/llm/llm.hpp")
        message(FATAL_ERROR "找不到 ${MNN_INCLUDE}/llm/llm.hpp，请确认 MNN_DIR 含 transformers/llm/engine 头文件")
    endif()
    add_library(MNN SHARED IMPORTED)
    set_target_properties(MNN PROPERTIES
        IMPORTED_LOCATION "${MNN_DIR}/lib/libMNN.so"
        IMPORTED_NO_SONAME TRUE)
    # mnn_jni.cpp 移植自 MNN apps/Android/MnnLlmChat 的 llm_session + llm_mnn_jni，
    # 适配本包名与 CpuSysBridge 的静态 nativeCallback/shouldAbort 流式机制。
    add_library(mnn_jni SHARED mnn_jni.cpp)
    target_include_directories(mnn_jni PRIVATE ${MNN_INCLUDE})
    target_link_libraries(mnn_jni MNN log)
    target_link_options(mnn_jni PRIVATE ${CHAT_16K_PAGE_LINKER_FLAG})
    # 运行时信息：ABI 版本、钉定 commit、build ID。mnn_jni.cpp 的 nativeGetRuntimeInfo 据此回传。
    target_compile_definitions(mnn_jni PRIVATE
        CHAT_MNN_COMMIT="${CHAT_MNN_COMMIT}"
        CHAT_MNN_JNI_ABI=${CHAT_MNN_JNI_ABI}
        CHAT_MNN_BUILD_ID="${CHAT_MNN_BUILD_ID}"
    )
else()
    message(STATUS "MNN_DIR 未配置或 libMNN.so 缺失，跳过 MNN JNI 编译（MNN 后端不可用）")
endif()

# ===== OpenCL 执行探测 JNI（libbackend_probe.so，Task 10）=====
# 运行于 :mnn_probe 进程，dlopen 动态加载 libOpenCL.so（不链接 vendor 库，避免 app 进程崩溃）。
# 仅依赖 log/dl；OpenCL 类型/符号在 backend_probe_jni.cpp 自声明。
add_library(backend_probe SHARED backend_probe_jni.cpp)
target_link_libraries(backend_probe log)
target_link_options(backend_probe PRIVATE ${CHAT_16K_PAGE_LINKER_FLAG})
