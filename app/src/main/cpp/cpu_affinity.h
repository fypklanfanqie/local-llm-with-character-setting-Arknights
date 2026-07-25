// cpu_affinity.h — Android big.LITTLE CPU 拓扑检测与线程亲和性绑定
//
// 仅在 CPU 推理路径上做调度优化：把推理线程（及其派生的工作线程）钉在大核上，
// 避免被 OS 调度到小核导致性能下降。不触碰任何模型量化 / GGUF 加载逻辑。
//
// 设计要点：
//  - sched_setaffinity(0, ...) 仅作用于【调用线程】。llama.cpp 的工作线程在
//    llama_init_from_model（或首次 llama_decode）时由 ggml 线程池创建，会经
//    clone() 继承创建者的 CPU 亲和性掩码。故必须在调用 nativeLoad/nativePredict
//    的同一线程上、紧挨着调用前执行 bind_to_cores，工作线程才会继承到大核绑定。
//  - 大核按最大频率降序排列，setupInferenceThreads(n) 绑定最快的 n 个大核；
//    温度降频时收窄到最快的少数核心（超核 + 顶部大核）。
//  - 所有 /sys 读取均带失败保护：读不到频率 / 无权限时返回 0 并回退到 sysconf，
//    最终再回退到按 CPU 编号取上半区的常见布局（骁龙 8 Gen2/Gen3 等）。
//
// 注意：本头文件为 C++（使用 std::vector / std::string / lambda / range-for），
// 仅被 cpu_affinity_jni.cpp 包含。函数以 static 修饰（单 TU 内部链接，无 ODR 问题）。
#ifndef CPU_AFFINITY_H
#define CPU_AFFINITY_H

#include <sched.h>
#include <unistd.h>
#include <sys/stat.h>
#include <android/log.h>
#include <cstdio>
#include <cerrno>
#include <cstring>
#include <vector>
#include <string>
#include <algorithm>

#define TAG "CpuAffinity"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    int  cpu_id;
    int  max_freq;     // KHz，0 表示读不到
    int  cur_freq;     // KHz，0 表示读不到
    bool is_big_core;  // 大核（含超核）
    bool is_prime_core;// 超核（最高频）
} CpuCoreInfo;

// 读取 CPU 最大频率 (KHz)。读不到返回 0。
static int get_cpu_max_freq(int cpu_id) {
    char path[256];
    snprintf(path, sizeof(path),
        "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu_id);
    FILE* f = fopen(path, "r");
    if (!f) return 0;
    int freq = 0;
    if (fscanf(f, "%d", &freq) != 1) freq = 0;
    fclose(f);
    return freq;
}

// 读取 CPU 当前频率 (KHz)。读不到返回 0。
static int get_cpu_cur_freq(int cpu_id) {
    char path[256];
    snprintf(path, sizeof(path),
        "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", cpu_id);
    FILE* f = fopen(path, "r");
    if (!f) return 0;
    int freq = 0;
    if (fscanf(f, "%d", &freq) != 1) freq = 0;
    fclose(f);
    return freq;
}

// 获取在线 CPU 数量。优先 sysconf（可靠且无文件权限问题），失败再扫 /sys。
static int get_online_cpu_count() {
    int n = (int) sysconf(_SC_NPROCESSORS_ONLN);
    if (n > 0) return n;

    int count = 0;
    for (int i = 0; i < 32; i++) {
        char path[256];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/online", i);
        FILE* f = fopen(path, "r");
        if (f) {
            int online = 0;
            if (fscanf(f, "%d", &online) == 1 && online) count++;
            fclose(f);
        } else {
            // 无 online 文件：cpu0 等不可热插拔核心默认在线，按目录是否存在判断。
            char cpupath[256];
            snprintf(cpupath, sizeof(cpupath), "/sys/devices/system/cpu/cpu%d", i);
            struct stat st;
            if (stat(cpupath, &st) == 0) count++;
        }
    }
    return count > 0 ? count : 8;
}

// 检测 CPU 拓扑结构。仅纳入能读到 max_freq 的核心（存在且 cpufreq 可读），
// 避免 cpu0 无 online 文件导致的漏计。
static std::vector<CpuCoreInfo> detect_cpu_topology() {
    std::vector<CpuCoreInfo> cores;

    int n_conf = (int) sysconf(_SC_NPROCESSORS_CONF);
    if (n_conf <= 0) n_conf = 8;
    if (n_conf > 32) n_conf = 32;   // 合理上限，防止异常平台过度扫描

    int max_freq_overall = 0;
    for (int i = 0; i < n_conf; i++) {
        int maxf = get_cpu_max_freq(i);
        if (maxf <= 0) continue;    // 核心不存在 / cpufreq 不可读 -> 跳过

        CpuCoreInfo core;
        core.cpu_id        = i;
        core.max_freq      = maxf;
        core.cur_freq      = get_cpu_cur_freq(i);
        core.is_big_core   = false;
        core.is_prime_core = false;
        if (core.max_freq > max_freq_overall) max_freq_overall = core.max_freq;
        cores.push_back(core);
    }

    // 频率阈值：超核 = 最高频率；大核 >= 80% 最高频率（含超核）。
    int prime_threshold = max_freq_overall;
    int big_threshold   = (int)(max_freq_overall * 0.8);

    for (auto& core : cores) {
        core.is_prime_core = (max_freq_overall > 0 && core.max_freq >= prime_threshold);
        core.is_big_core   = (max_freq_overall > 0 && core.max_freq >= big_threshold);
    }

    LOGI("=== CPU Topology ===");
    LOGI("Online cores: %d, Max freq: %d KHz", get_online_cpu_count(), max_freq_overall);
    for (const auto& core : cores) {
        LOGI("  CPU%d: max=%d KHz, cur=%d KHz, prime=%d, big=%d",
             core.cpu_id, core.max_freq, core.cur_freq,
             core.is_prime_core ? 1 : 0, core.is_big_core ? 1 : 0);
    }

    return cores;
}

// 绑定当前线程到指定的 CPU 核心集合。
static bool bind_to_cores(const std::vector<int>& cpu_ids) {
    if (cpu_ids.empty()) {
        LOGE("Empty CPU ID list for binding");
        return false;
    }
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int id : cpu_ids) {
        CPU_SET(id, &cpuset);
    }
    int result = sched_setaffinity(0, sizeof(cpuset), &cpuset);
    if (result != 0) {
        LOGE("sched_setaffinity failed: %s (cpu_ids may be offline)", strerror(errno));
        return false;
    }

    std::string ids_str;
    for (size_t i = 0; i < cpu_ids.size(); i++) {
        ids_str += std::to_string(cpu_ids[i]);
        if (i < cpu_ids.size() - 1) ids_str += ",";
    }
    LOGI("Thread bound to CPUs: [%s]", ids_str.c_str());
    return true;
}

// 获取大核（含超核）的 CPU ID 列表，按最大频率降序排列（最快的在最前）。
// 这样 setupInferenceThreads(n) 取前 n 个即为最快的 n 个核心。
static std::vector<int> get_big_core_ids() {
    auto cores = detect_cpu_topology();
    std::vector<CpuCoreInfo> bigs;
    for (const auto& core : cores) {
        if (core.is_big_core || core.is_prime_core) bigs.push_back(core);
    }
    // 按最大频率降序；同频率按 cpu_id 升序，保证稳定可预测。
    std::sort(bigs.begin(), bigs.end(), [](const CpuCoreInfo& a, const CpuCoreInfo& b) {
        if (a.max_freq != b.max_freq) return a.max_freq > b.max_freq;
        return a.cpu_id < b.cpu_id;
    });

    std::vector<int> big_ids;
    for (const auto& c : bigs) big_ids.push_back(c.cpu_id);

    // Fallback：拓扑检测失败（无权限 / 无 cpufreq）时的常见核心编号。
    if (big_ids.empty()) {
        LOGW("Topology detection found no big cores, using fallback");
        int online = get_online_cpu_count();
        if (online >= 8) {
            // 骁龙 8 Gen2/Gen3：高编号核心通常是大核/超核。
            big_ids = {4, 5, 6, 7};
        } else if (online >= 6) {
            big_ids = {4, 5};
        } else {
            for (int i = online / 2; i < online; i++) big_ids.push_back(i);
        }
    }
    LOGI("Big core IDs: count=%zu", big_ids.size());
    return big_ids;
}

// 获取超核的 CPU ID 列表。
static std::vector<int> get_prime_core_ids() {
    auto cores = detect_cpu_topology();
    std::vector<int> prime_ids;
    for (const auto& core : cores) {
        if (core.is_prime_core) prime_ids.push_back(core.cpu_id);
    }
    return prime_ids;
}

#endif // CPU_AFFINITY_H
