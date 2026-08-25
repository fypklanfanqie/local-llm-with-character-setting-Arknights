#!/bin/bash
# 诊断：重链接后的 libMNN.so 符号与对齐
set -uo pipefail
SO=/d/ai-build/mnn-build-wave2/libMNN.so
NM=/d/android-ndk-r27c/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe
RE=/d/android-ndk-r27c/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe

echo "== 文件信息 =="
ls -la "$SO"
echo ""
echo "== nm -D 前 5 行（含 stderr） =="
"$NM" -D "$SO" 2>&1 | head -5
echo ""
echo "== createLLM 计数 =="
"$NM" -D "$SO" 2>/dev/null | grep -c "createLLM" || true
echo ""
echo "== PT_LOAD 对齐 =="
"$RE" -l "$SO" 2>/dev/null | awk '/LOAD/{print $NF}'
