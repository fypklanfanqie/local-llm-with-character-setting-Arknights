#!/bin/bash
# Wave 2 收尾：部署 16KiB 对齐的 libMNN.so → 重打本地版 APK → 终验
set -euo pipefail

PROJECT="/d/ai/cc Programm/聊天终端安卓本地"
JNI_LIBS="$PROJECT/app/src/main/jniLibs/arm64-v8a"
SO=/d/ai-build/mnn-build-wave2/libMNN.so
RE=/d/android-ndk-r27c/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe
export JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8'

echo "== [1/4] 部署 libMNN.so（jniLibs + mnn-matched）=="
cp "$SO" "$JNI_LIBS/libMNN.so"
cp "$SO" /d/mnn-matched/lib/libMNN.so
ALIGN=$("$RE" -l "$JNI_LIBS/libMNN.so" | awk '/LOAD/{print $NF}' | sort -n | tail -1)
echo "  已部署，PT_LOAD align = $ALIGN（须为 0x4000）"
[ "$ALIGN" = "0x4000" ] || { echo "FATAL: 对齐不对"; exit 1; }

echo "== [2/4] assembleDebug =="
cd "$PROJECT"
./gradlew :app:assembleDebug --console=plain 2>&1 | tail -3

APK=/d/ai-build/rhodesisland/app-build/outputs/apk/debug/app-debug.apk
echo ""
echo "== [3/4] APK 内 lib/arm64 清单 =="
unzip -l "$APK" | grep "lib/arm64"

echo ""
echo "== [4/4] 抽包终验：libMNN 对齐 + 关键串 =="
rm -rf /tmp/apkfinal && mkdir -p /tmp/apkfinal && cd /tmp/apkfinal
unzip -o -q "$APK" "lib/arm64-v8a/libMNN.so" "lib/arm64-v8a/libmnn_jni.so"
A=$("$RE" -l lib/arm64-v8a/libMNN.so | awk '/LOAD/{print $NF}' | sort -n | tail -1)
echo "  APK 内 libMNN.so PT_LOAD align = $A"
[ "$A" = "0x4000" ] || { echo "FATAL: APK 内对齐不对"; exit 1; }
for probe in sampler_hot_update pinned-af0142b-samplerhot-20260824 summary_v2; do
    if grep -q "$probe" lib/arm64-v8a/libmnn_jni.so; then
        echo "  libmnn_jni: $probe 存在"
    else
        echo "  FATAL: libmnn_jni 缺 $probe"; exit 1
    fi
done
echo ""
echo "== 全部通过：新 APK 可装到 Android 16 设备 =="
