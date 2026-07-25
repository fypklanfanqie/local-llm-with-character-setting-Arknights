cmake_minimum_required(VERSION 3.22.1)
# 启用 CXX：cpu_affinity_jni.cpp / mnn_jni.cpp 均为 C++（std::vector/string/lambda）。
project(mnn_jni CXX)

# C++ 标准固定为 14（cpu_affinity 仅用到 C++11 特性，14 留余量且 NDK 默认支持）。
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ===== CPU 系统 JNI 包装（libcpu_sys_jni.so）=====
# 只读 CPU 探测：大核 ID / 拓扑 / 频率（cpp/cpu_affinity_jni.cpp + cpu_affinity.h）。
# 仅依赖 liblog，无 llama/MNN 依赖，**始终编译**（性能浮窗与线程数推荐依赖）。
# 供 Kotlin CpuSysBridge 加载（c++_shared -> cpu_sys_jni 顺序）。
add_library(cpu_sys_jni SHARED cpu_affinity_jni.cpp)
target_link_libraries(cpu_sys_jni log)

# ===== MNN 后端 JNI 包装（libmnn_jni.so）=====
# 链接预编译 libMNN.so（MNN 低内存 LLM 引擎，含 OpenCL GPU 后端 + ARM82 + transformer-fuse），
# 实现 Java_com_rhodesisland_terminal_llm_backend_MnnBridge_* 符号。
#
# MNN 引擎运行 `.mnn` 模型目录（config.json + llm.mnn + llm.mnn.weight + tokenizer.txt），
# 通过 set_config 的 backend_type 字段选 cpu/opencl/qnn。
# libMNN.so 用 NDK 交叉编译、c++_shared STL，与本包装及 libc++_shared.so 同源，避免 ABI 不一致。
#
# 运行时前提：libMNN.so 由 jniLibs 打包；MnnBridge 按 MNN -> mnn_jni 顺序 System.loadLibrary。
# 缺 libMNN.so 时 mnn_jni 链接失败（本段被 MNN_DIR 守卫跳过），MNN 后端整体不可用。
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
else()
    message(STATUS "MNN_DIR 未配置或 libMNN.so 缺失，跳过 MNN JNI 编译（MNN 后端不可用）")
endif()
