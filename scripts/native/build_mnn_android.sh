#!/bin/bash
# =============================================================================
# libMNN.so Android 构建脚本（Wave 2：采样热重建补丁版）
# =============================================================================
# 从钉定 commit 的 MNN 源码（D:/MNN-src，ArGeneration 分支）交叉编译含
# llm + OpenCL + QNN + ARM82 的单份 libMNN.so，部署到 app jniLibs。
#
# 必备 CMake 标志（缺一不可，见项目记忆 mnn-android-build）：
#   -DMNN_SEP_BUILD=OFF              llm/OpenCL/QNN 全绑进单份 libMNN.so
#                                    （默认 ON 会拆分导致 Llm 符号缺失）
#   -DMNN_BUILD_FOR_ANDROID_COMMAND=ON  配合 SEP_BUILD=OFF（OBJECT 库 POST_BUILD 兼容）
#   -DMNN_LOW_MEMORY=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON -DMNN_ARM82=ON -DMNN_OPENCL=ON
#
# 用法：
#   bash scripts/native/build_mnn_android.sh            # 构建 + 验证 + 部署 + 提示 BUILD_ID
#   SKIP_DEPLOY=1 bash ...                              # 只构建不部署
#
# ⚠️ 引擎有行为变更时必须换新 CHAT_MNN_BUILD_ID（下方 BUILD_ID 变量）：
#    认证记录键含 nativeBuildId，同 ID 重编会让旧基准证据静默延续到新二进制。
# =============================================================================
set -euo pipefail

MNN_SRC="${MNN_SRC:-D:/MNN-src}"
BUILD_DIR="${BUILD_DIR:-D:/ai-build/mnn-build-wave2}"
NDK="${ANDROID_NDK_HOME:-D:/android-ndk-r27c}"
CMAKE_BIN="${CMAKE_BIN:-C:/Users/Lfq06/AppData/Local/Android/Sdk/cmake/3.22.1/bin/cmake.exe}"
NINJA_BIN="C:/Users/Lfq06/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe"
PROJECT_JNILIBS="${PROJECT_JNILIBS:-D:/ai/cc Programm/聊天终端安卓本地/app/src/main/jniLibs/arm64-v8a}"

# ⚠️ 行为变更即 bump：本次 = pinned-af0142b + 采样热重建补丁（2026-08-24）
CHAT_MNN_BUILD_ID="${CHAT_MNN_BUILD_ID:-pinned-af0142b-samplerhot-20260824}"

echo "== [1/4] CMake configure =="
# ⚠️ 16KiB 页对齐（Android 15+/16KB 页内核设备强制要求）：MNN 自家 CMake 不带此标志，
#    缺它编出的 .so 在小米 Android 16 设备上 dlopen 直接失败（PT_LOAD p_align=0x1000<0x4000）。
#    SHARED=libMNN.so 本体；MODULE/SO 一并设置防未来目标类型变化。
"$CMAKE_BIN" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
  -DCMAKE_MODULE_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
  -DMNN_SEP_BUILD=OFF -DMNN_BUILD_FOR_ANDROID_COMMAND=ON \
  -DMNN_BUILD_LLM=ON -DMNN_LOW_MEMORY=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
  -DMNN_ARM82=ON -DMNN_OPENCL=ON \
  -DMNN_USE_LOGCAT=true \
  -S "$MNN_SRC" -B "$BUILD_DIR"

echo "== [2/4] Build libMNN.so =="
"$CMAKE_BIN" --build "$BUILD_DIR" --target libMNN.so -j 8

SO_PATH="$BUILD_DIR/libMNN.so"
[ -f "$SO_PATH" ] || { echo "FATAL: 未找到产物 $SO_PATH"; exit 1; }

echo "== [3/4] Verify symbols & 16KiB alignment =="
NM_BIN="${NM_BIN:-$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe}"
READELF_BIN="${READELF_BIN:-$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe}"
# 先落盘再 grep：管道内 grep -q 提前关写端会让 nm 吃 SIGPIPE，偶发空输出造成符号误报。
SYMS_FILE="$BUILD_DIR/exports.txt"
for attempt in 1 2 3; do
    "$NM_BIN" -D "$SO_PATH" > "$SYMS_FILE" 2>/dev/null && break
    echo "  nm 第 $attempt 次运行失败，重试..."
    sleep 1
done
grep -q "createLLM" "$SYMS_FILE" || { echo "FATAL: createLLM 符号缺失（SEP_BUILD 未关？）"; exit 1; }
echo "符号验证通过：createLLM 导出正常"
# 16KiB 页对齐校验（Android 15+/16KB 内核设备 dlopen 硬要求）。
MAX_ALIGN=$("$READELF_BIN" -l "$SO_PATH" | awk '/LOAD/{print $NF}' | sort -n | tail -1)
if (( MAX_ALIGN < 16384 )); then
    echo "FATAL: PT_LOAD 对齐仅 $MAX_ALIGN < 16384，16KB 页设备会加载失败（链接标志丢失？）"
    exit 1
fi
echo "16KiB 对齐验证通过：PT_LOAD max align = $MAX_ALIGN"
ls -la "$SO_PATH"

if [ "${SKIP_DEPLOY:-0}" = "1" ]; then
  echo "== [4/4] SKIP_DEPLOY=1，跳过部署。产物: $SO_PATH =="
  exit 0
fi

echo "== [4/4] Deploy to jniLibs =="
cp "$SO_PATH" "$PROJECT_JNILIBS/libMNN.so"
echo "已部署到 $PROJECT_JNILIBS/libMNN.so"
echo ""
echo "⚠️ 后续步骤："
echo "  1. gradle.properties 确认 MNN_DIR 指向含新版 libMNN.so 与 include/ 的目录（或直接用 jniLibs）"
echo "  2. app/src/main/cpp/CMakeLists.txt 的 CHAT_MNN_BUILD_ID 改为: $CHAT_MNN_BUILD_ID"
echo "     （否则认证记录键不变，旧基准证据会静默延续）"
echo "  3. rm -rf app/.cxx && ./gradlew :app:assembleDebug"
