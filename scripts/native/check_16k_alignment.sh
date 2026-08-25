#!/bin/bash
# native 库 16KiB 页对齐诊断（Android 15+/16KB 页设备上未对齐 .so 会 dlopen 失败崩溃）
set -uo pipefail
RE=/d/android-ndk-r27c/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe

check_dir() {
    local dir="$1"
    local label="$2"
    echo "===== $label ====="
    for so in "$dir"/*.so; do
        [ -f "$so" ] || continue
        local name size align maxalign
        name=$(basename "$so")
        size=$(stat -c %s "$so" 2>/dev/null || wc -c < "$so")
        # 取全部 PT_LOAD 段的 Align 值（16KB 页要求 >= 0x4000 = 16384）
        maxalign=$("$RE" -l "$so" 2>/dev/null | awk '/LOAD/{print $NF}' | sort -n | tail -1)
        if [ -z "$maxalign" ]; then
            echo "  [读取失败] $name"
            continue
        fi
        if (( maxalign >= 16384 )); then
            echo "  [OK   $maxalign] $name ($size B)"
        else
            echo "  [!!未对齐 $maxalign] $name ($size B) <-- 16KB 页设备会 dlopen 失败"
        fi
    done
}

check_dir "/d/ai/cc Programm/本地ai聊天大众版/app/src/main/jniLibs/arm64-v8a" "大众版 jniLibs"
echo ""
check_dir "/d/ai/cc Programm/聊天终端安卓本地/app/src/main/jniLibs/arm64-v8a" "本地版 jniLibs"
echo ""
check_dir /d/ai-build/mnn-build-wave2 "新编 libMNN 构建产物"

echo ""
echo "===== 设备内核页大小（已连接设备时） ====="
if command -v adb >/dev/null 2>&1; then
    getconf PAGESIZE 2>/dev/null | xargs -I{} echo "本机 getconf: {}"
    adb shell getconf PAGESIZE 2>/dev/null | xargs -I{} echo "设备 PAGESIZE: {}" || echo "(无已连接设备)"
else
    echo "(adb 不在 PATH)"
fi

echo ""
echo "===== 最近崩溃缓冲区（已连接设备时） ====="
adb logcat -b crash -d -t 200 2>/dev/null | grep -A30 "FATAL EXCEPTION\|signal \|UnsatisfiedLinkError" | head -60 || true
