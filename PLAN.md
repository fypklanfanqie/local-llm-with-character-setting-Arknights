# 从源码编译 llama.cpp 替换损坏预编译包

## 背景

当前 `app/src/main/jniLibs/arm64-v8a/` 的 5 个 llama.cpp 预编译 .so 有两个独立硬伤：
1. `libggml.so` / `libllama.so` / `libllama-common.so` 被 GNU strip 破坏：丢失 `DT_NEEDED libc++_shared.so` 条目，但保留近 200 个 `__ndk1` libc++ 强符号引用 → 加载时找不到 libc++ 符号。
2. `libggml.so` 强引用 `ggml_backend_vk_reg`（Vulkan）和 `ggml_backend_opencl_reg`（OpenCL）后端注册符号，但包里缺 `libggml-vulkan.so` / `libggml-opencl.so` → 预编译包不完整。

继续 patch 二进制不可行。改为用已装的 NDK r27c 从源码交叉编译，禁用所有非 CPU 后端，产出干净完整且与 NDK libc++ 匹配的 .so。

## 前提（已验证）

- NDK r27c：`D:/android-ndk-r27c`（toolchain + libc++_shared.so + llvm-strip 齐全）
- SDK cmake 3.22.1：`~/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe`
- git 2.54，网络可访问 `https://github.com/ggml-org/llama.cpp.git`
- `llama_jni.c` 已适配新版 llama.cpp API（b3600+），新版默认构建产物正好是 5 个目标 .so

## 实施步骤

### 1. 下载 llama.cpp 源码
- `git clone --depth 1 https://github.com/ggml-org/llama.cpp.git` 到 `D:/llama.cpp-src`（项目外，避免污染项目）
- 用最新 master（API 与 `llama_jni.c` 兼容：`llama_model_load_from_file` / `llama_init_from_model` / vocab 分离 / sampler chain 在 b3600+ 稳定）。若编译时 API 不兼容，再 checkout 到具体 release tag。

### 2. 写交叉编译脚本 `build_llama_android.sh`（项目根）
用 NDK toolchain + SDK cmake 交叉编译，关键配置：
```
-DCMAKE_TOOLCHAIN_FILE=D:/android-ndk-r27c/build/cmake/android.toolchain.cmake
-DANDROID_ABI=arm64-v8a
-DANDROID_PLATFORM=android-24          # 与 minSdk=24 一致
-DANDROID_STL=c++_shared               # 共享 libc++，.so 间 ABI 一致
-DCMAKE_BUILD_TYPE=Release
-DGGML_VULKAN=OFF  -DGGML_OPENCL=OFF  -DGGML_CUDA=OFF  -DGGML_BLAS=OFF
-DGGML_NATIVE=OFF                      # 交叉编译关 host 检测
-DLLAMA_BUILD_TESTS=OFF  -DLLAMA_BUILD_EXAMPLES=OFF  -DLLAMA_BUILD_SERVER=OFF
-DBUILD_SHARED_LIBS=ON
```
产物（在 build 目录）：`libggml-base.so` `libggml-cpu.so` `libggml.so` `libllama.so` `libllama-common.so`

### 3. 编译 JNI 包装 `libllama_jni.so`
用 NDK clang 直接编译单文件（不走 Gradle externalNativeBuild，更可控）：
```
aarch64-linux-android24-clang -shared -fPIC -O2 \
  -I<src>/include -I<src>/ggml/include \
  app/src/main/cpp/llama_jni.c \
  -L<build> -lllama -lllama-common -lggml -lggml-cpu -lggml-base -llog \
  -o libllama_jni.so
```

### 4. 替换 jniLibs
- 删除 `app/src/main/jniLibs/arm64-v8a/` 下全部 6 个旧 .so（损坏/不完整，`_elf_backup/` 已留原始备份）
- 复制新编译的 6 个 .so 到 `app/src/main/jniLibs/arm64-v8a/`
- 复制 NDK 的 `libc++_shared.so` 到同目录：
  `D:/android-ndk-r27c/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so`

### 5. 改 `LlamaBridge.kt` 加载顺序
`LIBS` 数组开头加 `"c++_shared"`，确保最先加载（其余 .so 依赖它）：
```kotlin
private val LIBS = arrayOf(
    "c++_shared",
    "ggml-base", "ggml-cpu", "ggml", "llama", "llama-common", "llama_jni"
)
```
`bridgeLoaded` 仍以 `llama_jni` 为准，不受影响。

### 6. 验证产物健康（用现有 `inspect_elf.py`）
对新 6 个 .so 检查：
- `DT_NEEDED` 含 `libc++_shared.so`（c++_shared 编译生效）
- 无 `ggml_backend_vk_reg` / `ggml_backend_opencl_reg` 强引用（Vulkan/OpenCL 已禁用）
- `sh_size == p_filesz`（未被 strip 破坏，用 `fix_elf_dynamic.py` 诊断模式确认）

### 7. 清理与重建
- Build > Clean Project（清掉 `merged_native_libs` / `stripped_native_libs` 旧缓存）
- Build > Rebuild Project
- 部署，看 logcat：应见 `✓ c++_shared loaded` → `✓ ggml-base` → ... → `✓ llama_jni loaded` → `bridgeLoaded=true`

## 产物与保留文件
- `build_llama_android.sh`：可复用编译脚本（以后升级 llama.cpp 重跑）
- `fix_elf_dynamic.py` / `inspect_elf.py`：保留为诊断工具
- `_elf_backup/`：旧损坏 .so 备份（验证新包工作后可删）
- `AndroidManifest.xml` 的 `extractNativeLibs="true"`：保留（更稳；新 .so 结构正确后非必须，但保留无害）

## 风险与回退
- 若 master 版 API 与 `llama_jni.c` 不兼容 → checkout 到具体 release tag（如 b4xxx），调整
- 若编译失败（NDK/cmake 选项差异）→ 根据报错调 CMake 选项
- 回退：`_elf_backup/` 有原始 .so，可恢复到修补前状态（但仍无法正常加载）
