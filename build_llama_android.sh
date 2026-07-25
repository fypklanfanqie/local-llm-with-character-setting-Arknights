#!/usr/bin/env bash
# 交叉编译 llama.cpp for Android arm64-v8a
# - 仅 CPU 后端（禁用 Vulkan/OpenCL/CUDA/BLAS/OpenMP）
# - c++_shared（运行时依赖 libc++_shared.so）
# - 产物：libggml-base.so libggml-cpu.so libggml.so libllama.so libllama-common.so
set -euo pipefail

# ===== 路径配置 =====
SRC="/d/llama.cpp-src"
NDK="/d/android-ndk-r27c"
CMAKE="$HOME/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe"
NINJA="$HOME/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe"
BUILD="/d/llama.cpp-build-android"
ABI="arm64-v8a"
PLATFORM="android-24"

if [ ! -d "$SRC" ]; then
  echo "✗ 源码不存在: $SRC（先 git clone）" >&2
  exit 1
fi

# 清理上次失败配置的残留（仅当目录存在且非 Ninja 时；这里保留以增量复用 .o）
# rm -rf "$BUILD"

echo "=== [1/2] CMake 配置 (Ninja) ==="
"$CMAKE" -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="$PLATFORM" \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_VULKAN=OFF \
  -DGGML_OPENCL=OFF \
  -DGGML_CUDA=OFF \
  -DGGML_BLAS=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_NATIVE=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_TOOLS=OFF \
  -DLLAMA_BUILD_APP=OFF \
  -DLLAMA_BUILD_UI=OFF \
  -DBUILD_SHARED_LIBS=ON

echo "=== [2/2] 编译 ==="
"$CMAKE" --build "$BUILD" --config Release -j

echo ""
echo "=== 产物 .so 列表 ==="
find "$BUILD" -name "*.so" -type f | sort
