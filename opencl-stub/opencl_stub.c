/*
 * libOpenCL.so stub for arm64-v8a.
 *
 * 背景：jniLibs 中的 llama.cpp 预编译包把 libggml-opencl.so 链成了 libggml.so 的
 * 硬 NEEDED 依赖，而 libggml-opencl.so 又 NEEDED libOpenCL.so。本机/目标设备没有
 * libOpenCL.so -> dlopen 整条链失败 -> bridgeLoaded=false。
 *
 * 本机没有 host 编译器，无法本地重建 Vulkan（vulkan-shaders-gen 要 host 编译）。
 * 而预编译的 libggml-vulkan.so 本身是好的，只是被 opencl 那条链拖垮。
 *
 * 方案：用一个 stub libOpenCL.so 补齐符号。libggml-opencl.so 的 ggml_opencl_probe_devices
 * 第一步调 clGetPlatformIDs；这里返回 CL_SUCCESS 且 0 平台 -> probe 返回空 ->
 * opencl 后端注册 0 设备（不会被选用）-> Vulkan 后端（真 libvulkan.so）生效。
 *
 * 其余 29 个 cl* 符号 probe 不会再调到，但 libggml-opencl.so 把它们列为 UND，
 * dlopen 时必须能解析，故全部以 no-op 导出。
 *
 * 编译（NDK clang 交叉编译，无需 host 编译器）：
 *   $NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang \
 *       --target=aarch64-linux-android24 -shared -fPIC -O2 \
 *       -Wl,-soname,libOpenCL.so -o libOpenCL.so opencl_stub.c
 */

typedef int   cl_int;
typedef unsigned int cl_uint;
typedef unsigned long long cl_bitfield;
typedef void *cl_platform_id;
typedef void *cl_device_id;
typedef void *cl_context;
typedef void *cl_command_queue;
typedef void *cl_program;
typedef void *cl_kernel;
typedef void *cl_mem;
typedef void *cl_event;
typedef void *cl_queue_properties;

#define CL_SUCCESS 0

/* 唯一会被调到的：返回 0 平台，probe 优雅退出 */
cl_int clGetPlatformIDs(cl_uint num_entries, cl_platform_id *platforms, cl_uint *num_platforms) {
    (void)num_entries;
    (void)platforms;
    if (num_platforms) *num_platforms = 0;
    return CL_SUCCESS;
}

/* 其余符号：no-op，返回 CL_SUCCESS 即可（不会被调用）。
 * 用宏批量生成，签名统一为 (void)，因 probe 在 clGetPlatformIDs 后即返回空，
 * 以下函数均无调用路径，仅需符号存在以供动态链接解析。 */
#define STUB(name) cl_int name(void) { return CL_SUCCESS; }

STUB(clGetPlatformInfo)
STUB(clGetDeviceIDs)
STUB(clGetDeviceInfo)
STUB(clCreateContext)
STUB(clCreateCommandQueue)
STUB(clCreateBuffer)
STUB(clCreateBufferWithProperties)
STUB(clCreateSubBuffer)
STUB(clCreateImage)
STUB(clCreateProgramWithSource)
STUB(clBuildProgram)
STUB(clCreateKernel)
STUB(clSetKernelArg)
STUB(clEnqueueNDRangeKernel)
STUB(clEnqueueReadBuffer)
STUB(clEnqueueWriteBuffer)
STUB(clEnqueueCopyBuffer)
STUB(clEnqueueFillBuffer)
STUB(clEnqueueBarrierWithWaitList)
STUB(clEnqueueMarkerWithWaitList)
STUB(clWaitForEvents)
STUB(clFlush)
STUB(clFinish)
STUB(clGetKernelWorkGroupInfo)
STUB(clGetProgramBuildInfo)
STUB(clReleaseContext)
STUB(clReleaseProgram)
STUB(clReleaseMemObject)
STUB(clReleaseEvent)
