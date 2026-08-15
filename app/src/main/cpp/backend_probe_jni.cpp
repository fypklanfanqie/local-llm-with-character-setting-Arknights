// ===== OpenCL 执行探测（Task 10，运行于 :mnn_probe 独立进程）=====
// 动态加载 libOpenCL.so（不直接链接 vendor 库，避免 app 进程因驱动符号缺失而崩溃），
// 解析所需符号，枚举设备，创建 context/queue/buffer，编译运行极简确定向量 kernel，同步校验输出。
// 返回 JSON：{"success":bool,"platform":...,"vendor":...,"device":...,"driver":...,"durationMs":...,"failureCode":...}
// 失败码镜像 Kotlin OpenClProbeResult 常量。
#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <chrono>
#include <sstream>
#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OpenClProbe", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "OpenClProbe", __VA_ARGS__)

// ---- OpenCL 类型（自声明，避免引入 OpenCL headers / 链接库）----
typedef void*  cl_platform_id;
typedef void*  cl_device_id;
typedef void*  cl_context;
typedef void*  cl_command_queue;
typedef void*  cl_mem;
typedef void*  cl_program;
typedef void*  cl_kernel;
typedef int    cl_int;
typedef unsigned int cl_uint;
typedef unsigned long long cl_ulong;

// ---- 所需常量 ----
static const cl_uint CL_DEVICE_TYPE_DEFAULT = (1u << 0);
static const cl_uint CL_PLATFORM_NAME      = 0x0902;
static const cl_uint CL_PLATFORM_VENDOR    = 0x0903;
static const cl_uint CL_DEVICE_NAME        = 0x102B;
static const cl_uint CL_DEVICE_VENDOR      = 0x102C;
static const cl_uint CL_DRIVER_VERSION     = 0x102D;

// ---- 函数指针类型 ----
typedef cl_int (*fn_clGetPlatformIDs)(cl_uint, cl_platform_id*, cl_uint*);
typedef cl_int (*fn_clGetPlatformInfo)(cl_platform_id, cl_uint, size_t, void*, size_t*);
typedef cl_int (*fn_clGetDeviceIDs)(cl_platform_id, cl_uint, cl_uint, cl_device_id*, cl_uint*);
typedef cl_int (*fn_clGetDeviceInfo)(cl_device_id, cl_uint, size_t, void*, size_t*);
typedef cl_context (*fn_clCreateContext)(const void*, cl_uint, const cl_device_id*, void*, void*, cl_int*);
typedef cl_command_queue (*fn_clCreateCommandQueue)(cl_context, cl_device_id, cl_ulong, cl_int*);
typedef cl_mem (*fn_clCreateBuffer)(cl_context, cl_ulong, size_t, void*, cl_int*);
typedef cl_int (*fn_clEnqueueWriteBuffer)(cl_command_queue, cl_mem, cl_int, size_t, size_t, const void*, cl_uint, const void*, void*);
typedef cl_program (*fn_clCreateProgramWithSource)(cl_context, cl_uint, const char**, const size_t*, cl_int*);
typedef cl_int (*fn_clBuildProgram)(cl_program, cl_uint, const cl_device_id*, const char*, void*, void*);
typedef cl_kernel (*fn_clCreateKernel)(cl_program, const char*, cl_int*);
typedef cl_int (*fn_clSetKernelArg)(cl_kernel, cl_uint, size_t, const void*);
typedef cl_int (*fn_clEnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const size_t*, const size_t*, const size_t*, cl_uint, const void*, void*);
typedef cl_int (*fn_clEnqueueReadBuffer)(cl_command_queue, cl_mem, cl_int, size_t, size_t, void*, cl_uint, const void*, void*);
typedef cl_int (*fn_clFinish)(cl_command_queue);
typedef cl_int (*fn_clReleaseMemObject)(cl_mem);
typedef cl_int (*fn_clReleaseCommandQueue)(cl_command_queue);
typedef cl_int (*fn_clReleaseContext)(cl_context);
typedef cl_int (*fn_clReleaseProgram)(cl_program);
typedef cl_int (*fn_clReleaseKernel)(cl_kernel);

struct ProbeApi {
    fn_clGetPlatformIDs clGetPlatformIDs = nullptr;
    fn_clGetPlatformInfo clGetPlatformInfo = nullptr;
    fn_clGetDeviceIDs clGetDeviceIDs = nullptr;
    fn_clGetDeviceInfo clGetDeviceInfo = nullptr;
    fn_clCreateContext clCreateContext = nullptr;
    fn_clCreateCommandQueue clCreateCommandQueue = nullptr;
    fn_clCreateBuffer clCreateBuffer = nullptr;
    fn_clEnqueueWriteBuffer clEnqueueWriteBuffer = nullptr;
    fn_clCreateProgramWithSource clCreateProgramWithSource = nullptr;
    fn_clBuildProgram clBuildProgram = nullptr;
    fn_clCreateKernel clCreateKernel = nullptr;
    fn_clSetKernelArg clSetKernelArg = nullptr;
    fn_clEnqueueNDRangeKernel clEnqueueNDRangeKernel = nullptr;
    fn_clEnqueueReadBuffer clEnqueueReadBuffer = nullptr;
    fn_clFinish clFinish = nullptr;
    fn_clReleaseMemObject clReleaseMemObject = nullptr;
    fn_clReleaseCommandQueue clReleaseCommandQueue = nullptr;
    fn_clReleaseContext clReleaseContext = nullptr;
    fn_clReleaseProgram clReleaseProgram = nullptr;
    fn_clReleaseKernel clReleaseKernel = nullptr;

    bool resolve(void *handle) {
        clGetPlatformIDs = (fn_clGetPlatformIDs)dlsym(handle, "clGetPlatformIDs");
        clGetPlatformInfo = (fn_clGetPlatformInfo)dlsym(handle, "clGetPlatformInfo");
        clGetDeviceIDs = (fn_clGetDeviceIDs)dlsym(handle, "clGetDeviceIDs");
        clGetDeviceInfo = (fn_clGetDeviceInfo)dlsym(handle, "clGetDeviceInfo");
        clCreateContext = (fn_clCreateContext)dlsym(handle, "clCreateContext");
        clCreateCommandQueue = (fn_clCreateCommandQueue)dlsym(handle, "clCreateCommandQueue");
        clCreateBuffer = (fn_clCreateBuffer)dlsym(handle, "clCreateBuffer");
        clEnqueueWriteBuffer = (fn_clEnqueueWriteBuffer)dlsym(handle, "clEnqueueWriteBuffer");
        clCreateProgramWithSource = (fn_clCreateProgramWithSource)dlsym(handle, "clCreateProgramWithSource");
        clBuildProgram = (fn_clBuildProgram)dlsym(handle, "clBuildProgram");
        clCreateKernel = (fn_clCreateKernel)dlsym(handle, "clCreateKernel");
        clSetKernelArg = (fn_clSetKernelArg)dlsym(handle, "clSetKernelArg");
        clEnqueueNDRangeKernel = (fn_clEnqueueNDRangeKernel)dlsym(handle, "clEnqueueNDRangeKernel");
        clEnqueueReadBuffer = (fn_clEnqueueReadBuffer)dlsym(handle, "clEnqueueReadBuffer");
        clFinish = (fn_clFinish)dlsym(handle, "clFinish");
        clReleaseMemObject = (fn_clReleaseMemObject)dlsym(handle, "clReleaseMemObject");
        clReleaseCommandQueue = (fn_clReleaseCommandQueue)dlsym(handle, "clReleaseCommandQueue");
        clReleaseContext = (fn_clReleaseContext)dlsym(handle, "clReleaseContext");
        clReleaseProgram = (fn_clReleaseProgram)dlsym(handle, "clReleaseProgram");
        clReleaseKernel = (fn_clReleaseKernel)dlsym(handle, "clReleaseKernel");
        return clGetPlatformIDs && clGetPlatformInfo && clGetDeviceIDs && clGetDeviceInfo &&
               clCreateContext && clCreateCommandQueue && clCreateBuffer && clEnqueueWriteBuffer &&
               clCreateProgramWithSource && clBuildProgram && clCreateKernel && clSetKernelArg &&
               clEnqueueNDRangeKernel && clEnqueueReadBuffer && clFinish &&
               clReleaseMemObject && clReleaseCommandQueue && clReleaseContext &&
               clReleaseProgram && clReleaseKernel;
    }
};

static std::string json_escape(const std::string &s) {
    std::string out;
    for (char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            default: out += c;
        }
    }
    return out;
}

static std::string result_json(bool success, const std::string &platform, const std::string &vendor,
                               const std::string &device, const std::string &driver,
                               long long durationMs, const std::string &failureCode) {
    std::ostringstream oss;
    oss << "{\"success\":" << (success ? "true" : "false")
        << ",\"platform\":" << (platform.empty() ? "null" : "\"" + json_escape(platform) + "\"")
        << ",\"vendor\":" << (vendor.empty() ? "null" : "\"" + json_escape(vendor) + "\"")
        << ",\"device\":" << (device.empty() ? "null" : "\"" + json_escape(device) + "\"")
        << ",\"driver\":" << (driver.empty() ? "null" : "\"" + json_escape(driver) + "\"")
        << ",\"durationMs\":" << durationMs
        << ",\"failureCode\":" << (failureCode.empty() ? "null" : "\"" + failureCode + "\"")
        << "}";
    return oss.str();
}

static std::string get_platform_info(ProbeApi &api, cl_platform_id plat, cl_uint key) {
    size_t sz = 0;
    if (api.clGetPlatformInfo(plat, key, 0, nullptr, &sz) != 0 || sz == 0) return "";
    std::vector<char> buf(sz + 1, 0);
    if (api.clGetPlatformInfo(plat, key, sz, buf.data(), nullptr) != 0) return "";
    return std::string(buf.data());
}

static std::string get_device_info(ProbeApi &api, cl_device_id dev, cl_uint key) {
    size_t sz = 0;
    if (api.clGetDeviceInfo(dev, key, 0, nullptr, &sz) != 0 || sz == 0) return "";
    std::vector<char> buf(sz + 1, 0);
    if (api.clGetDeviceInfo(dev, key, sz, buf.data(), nullptr) != 0) return "";
    return std::string(buf.data());
}

// 极简确定向量 kernel：b[i] = a[i] + 1.0f。
static const char *KERNEL_SRC =
    "__kernel void add1(__global const float *a, __global float *b) {"
    "  int i = get_global_id(0); b[i] = a[i] + 1.0f; }";

// JNI 导出必须 extern "C"：否则 C++ 编译器按 name-mangling 生成符号
// （如 _Z66Java_..._nativeProbeP7_JNIEnvP8_jobject），JVM 按未 mangled 的
// `Java_..._nativeProbe` 查找不到 -> 调用抛 UnsatisfiedLinkError
// （"No implementation found"）-> probe 失败 -> OpenCL 不入链 -> GPU 不可用。
// 对比 mnn_jni.cpp 的 extern "C" 块。本函数是文件内唯一 JNI 导出。
extern "C" {

JNIEXPORT jstring JNICALL
Java_com_chatbyyourside_llm_backend_OpenClProbeService_nativeProbe(JNIEnv *env, jobject) {
    auto t0 = std::chrono::steady_clock::now();
    void *handle = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        LOGE("dlopen libOpenCL.so failed: %s", dlerror());
        return env->NewStringUTF(result_json(false, "", "", "", "", 0, "OPENCL_NOT_LOADABLE").c_str());
    }

    ProbeApi api;
    if (!api.resolve(handle)) {
        LOGE("dlsym 解析失败");
        dlclose(handle);
        return env->NewStringUTF(result_json(false, "", "", "", "", 0, "SYMBOL_RESOLUTION").c_str());
    }

    cl_uint num_platforms = 0;
    if (api.clGetPlatformIDs(0, nullptr, &num_platforms) != 0 || num_platforms == 0) {
        dlclose(handle);
        return env->NewStringUTF(result_json(false, "", "", "", "", 0, "NO_DEVICE").c_str());
    }
    std::vector<cl_platform_id> platforms(num_platforms);
    api.clGetPlatformIDs(num_platforms, platforms.data(), nullptr);

    // 取第一个平台与默认设备（GPU 优先的驱动通常把 GPU 列为 default）。
    cl_platform_id platform = platforms[0];
    std::string platform_name = get_platform_info(api, platform, CL_PLATFORM_NAME);
    std::string platform_vendor = get_platform_info(api, platform, CL_PLATFORM_VENDOR);

    cl_uint num_devices = 0;
    if (api.clGetDeviceIDs(platform, CL_DEVICE_TYPE_DEFAULT, 0, nullptr, &num_devices) != 0 || num_devices == 0) {
        dlclose(handle);
        return env->NewStringUTF(result_json(false, platform_name, platform_vendor, "", "", 0, "NO_DEVICE").c_str());
    }
    std::vector<cl_device_id> devices(num_devices);
    api.clGetDeviceIDs(platform, CL_DEVICE_TYPE_DEFAULT, num_devices, devices.data(), nullptr);
    cl_device_id device = devices[0];
    std::string device_name = get_device_info(api, device, CL_DEVICE_NAME);
    std::string device_vendor = get_device_info(api, device, CL_DEVICE_VENDOR);
    std::string driver = get_device_info(api, device, CL_DRIVER_VERSION);

    cl_int err = 0;
    cl_context context = api.clCreateContext(nullptr, 1, &device, nullptr, nullptr, &err);
    if (!context || err != 0) {
        dlclose(handle);
        return env->NewStringUTF(result_json(false, platform_name, device_vendor, device_name, driver, 0, "KERNEL_BUILD").c_str());
    }
    cl_command_queue queue = api.clCreateCommandQueue(context, device, 0, &err);
    if (!queue || err != 0) {
        api.clReleaseContext(context); dlclose(handle);
        return env->NewStringUTF(result_json(false, platform_name, device_vendor, device_name, driver, 0, "KERNEL_EXECUTION").c_str());
    }

    const size_t N = 1024;
    std::vector<float> a(N), b(N, 0.0f);
    for (size_t i = 0; i < N; i++) a[i] = (float)i;

    cl_mem bufA = api.clCreateBuffer(context, 1 /*CL_MEM_READ_ONLY*/, N * sizeof(float), nullptr, &err);
    cl_mem bufB = api.clCreateBuffer(context, 2 /*CL_MEM_WRITE_ONLY*/, N * sizeof(float), nullptr, &err);
    if (!bufA || !bufB || err != 0) {
        api.clReleaseCommandQueue(queue); api.clReleaseContext(context); dlclose(handle);
        return env->NewStringUTF(result_json(false, platform_name, device_vendor, device_name, driver, 0, "KERNEL_EXECUTION").c_str());
    }
    api.clEnqueueWriteBuffer(queue, bufA, 1 /*blocking*/, 0, N * sizeof(float), a.data(), 0, nullptr, nullptr);

    cl_program program = api.clCreateProgramWithSource(context, 1, &KERNEL_SRC, nullptr, &err);
    if (!program || err != 0) {
        api.clReleaseMemObject(bufA); api.clReleaseMemObject(bufB);
        api.clReleaseCommandQueue(queue); api.clReleaseContext(context); dlclose(handle);
        return env->NewStringUTF(result_json(false, platform_name, device_vendor, device_name, driver, 0, "KERNEL_BUILD").c_str());
    }
    if (api.clBuildProgram(program, 1, &device, nullptr, nullptr, nullptr) != 0) {
        api.clReleaseProgram(program); api.clReleaseMemObject(bufA); api.clReleaseMemObject(bufB);
        api.clReleaseCommandQueue(queue); api.clReleaseContext(context); dlclose(handle);
        return env->NewStringUTF(result_json(false, platform_name, device_vendor, device_name, driver, 0, "KERNEL_BUILD").c_str());
    }
    cl_kernel kernel = api.clCreateKernel(program, "add1", &err);
    if (!kernel || err != 0) {
        api.clReleaseProgram(program); api.clReleaseMemObject(bufA); api.clReleaseMemObject(bufB);
        api.clReleaseCommandQueue(queue); api.clReleaseContext(context); dlclose(handle);
        return env->NewStringUTF(result_json(false, platform_name, device_vendor, device_name, driver, 0, "KERNEL_BUILD").c_str());
    }
    api.clSetKernelArg(kernel, 0, sizeof(cl_mem), &bufA);
    api.clSetKernelArg(kernel, 1, sizeof(cl_mem), &bufB);

    size_t global = N;
    if (api.clEnqueueNDRangeKernel(queue, kernel, 1, nullptr, &global, nullptr, 0, nullptr, nullptr) != 0 ||
        api.clEnqueueReadBuffer(queue, bufB, 1 /*blocking*/, 0, N * sizeof(float), b.data(), 0, nullptr, nullptr) != 0 ||
        api.clFinish(queue) != 0) {
        api.clReleaseKernel(kernel); api.clReleaseProgram(program);
        api.clReleaseMemObject(bufA); api.clReleaseMemObject(bufB);
        api.clReleaseCommandQueue(queue); api.clReleaseContext(context); dlclose(handle);
        return env->NewStringUTF(result_json(false, platform_name, device_vendor, device_name, driver, 0, "KERNEL_EXECUTION").c_str());
    }

    bool match = true;
    for (size_t i = 0; i < N; i++) {
        if (b[i] != a[i] + 1.0f) { match = false; break; }
    }

    api.clReleaseKernel(kernel); api.clReleaseProgram(program);
    api.clReleaseMemObject(bufA); api.clReleaseMemObject(bufB);
    api.clReleaseCommandQueue(queue); api.clReleaseContext(context);
    dlclose(handle);

    long long dur = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    if (!match) {
        LOGE("probe kernel output mismatch");
        return env->NewStringUTF(result_json(false, platform_name, device_vendor, device_name, driver, dur, "OUTPUT_MISMATCH").c_str());
    }
    LOGI("probe OK vendor=%s device=%s driver=%s %lldms", device_vendor.c_str(), device_name.c_str(), driver.c_str(), dur);
    return env->NewStringUTF(result_json(true, platform_name, device_vendor, device_name, driver, dur, "").c_str());
}

}  // extern "C"
