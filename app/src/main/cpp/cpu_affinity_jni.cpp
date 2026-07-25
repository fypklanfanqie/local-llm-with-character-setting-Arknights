// cpu_affinity_jni.cpp - CPU 系统（大核/频率/拓扑）只读 JNI 暴露层
//
// 把 cpu_affinity.h 的**只读**能力暴露给 Kotlin（CpuSysBridge）。符号归属
// libcpu_sys_jni.so（独立 CMake target，仅链 liblog，无 llama/MNN 依赖，始终编译）。
// extern "C" 保证 JNI 名称查找不被 C++ name mangling 破坏。
//
// 对应 Kotlin 声明（com.rhodesisland.terminal.llm.CpuSysBridge，实例方法）：
//   external fun getBigCoreIds(): IntArray
//   external fun getCpuTopology(): String
//   external fun getBigCoreFreqMHz(): Float
//
// 说明：原 llama 专用的 bindToBigCores / setupInferenceThreads（sched_setaffinity 绑核，
// 供 llama 工作线程继承亲和性）已随 llama.cpp 移除--MNN 自管线程，无需外部绑核。
// 大核/频率/拓扑读取仍供性能浮窗与线程数推荐使用。
#include "cpu_affinity.h"
#include <jni.h>

extern "C" {

// 获取大核（含超核）CPU ID 列表，按最大频率降序（最快在最前）。
JNIEXPORT jintArray JNICALL
Java_com_rhodesisland_terminal_llm_CpuSysBridge_getBigCoreIds(JNIEnv* env, jobject thiz) {
    (void) thiz;
    auto big_ids = get_big_core_ids();
    jintArray result = env->NewIntArray((jsize) big_ids.size());
    if (result && !big_ids.empty()) {
        env->SetIntArrayRegion(result, 0, (jsize) big_ids.size(), big_ids.data());
    }
    return result;
}

// 获取 CPU 拓扑 JSON：[{"cpu":0,"maxFreq":..,"curFreq":..,"isBig":0,"isPrime":0}, ...]
JNIEXPORT jstring JNICALL
Java_com_rhodesisland_terminal_llm_CpuSysBridge_getCpuTopology(JNIEnv* env, jobject thiz) {
    (void) thiz;
    auto cores = detect_cpu_topology();
    std::string json = "[";
    for (size_t i = 0; i < cores.size(); i++) {
        json += "{\"cpu\":"    + std::to_string(cores[i].cpu_id);
        json += ",\"maxFreq\":" + std::to_string(cores[i].max_freq);
        json += ",\"curFreq\":" + std::to_string(cores[i].cur_freq);
        json += ",\"isBig\":"   + std::to_string(cores[i].is_big_core   ? 1 : 0);
        json += ",\"isPrime\":" + std::to_string(cores[i].is_prime_core ? 1 : 0);
        json += "}";
        if (i < cores.size() - 1) json += ",";
    }
    json += "]";
    return env->NewStringUTF(json.c_str());
}

// 获取最快大核（big_ids[0]）的当前频率，返回 MHz（KHz/1000）。读不到返回 0。
JNIEXPORT jfloat JNICALL
Java_com_rhodesisland_terminal_llm_CpuSysBridge_getBigCoreFreqMHz(JNIEnv* env, jobject thiz) {
    (void) env; (void) thiz;
    auto big_ids = get_big_core_ids();
    if (!big_ids.empty()) {
        int freq = get_cpu_cur_freq(big_ids[0]);
        return freq / 1000.0f;   // KHz -> MHz
    }
    return 0.0f;
}

}  // extern "C"
