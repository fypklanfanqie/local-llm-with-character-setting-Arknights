#!/bin/bash
# 打 APK + 验证 native 库内容（Wave 2 收尾验证）
set -euo pipefail
cd "/d/ai/cc Programm/聊天终端安卓本地"
export JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8'

echo "== [1/3] assembleDebug =="
./gradlew :app:assembleDebug --console=plain 2>&1 | tail -4

APK=/d/ai-build/rhodesisland/app-build/outputs/apk/debug/app-debug.apk
echo ""
echo "== [2/3] APK 内 lib/arm64 清单 =="
unzip -l "$APK" | grep "lib/arm64"

echo ""
echo "== [3/3] libmnn_jni.so 关键串验证 =="
rm -rf /tmp/apkcheck2 && mkdir -p /tmp/apkcheck2 && cd /tmp/apkcheck2
unzip -o -q "$APK" "lib/arm64-v8a/libmnn_jni.so" "lib/arm64-v8a/libMNN.so"
python - << 'PYEOF'
data_jni = open('/tmp/apkcheck2/lib/arm64-v8a/libmnn_jni.so','rb').read()
data_mnn = open('/tmp/apkcheck2/lib/arm64-v8a/libMNN.so','rb').read()
ok = True
for probe in [b'sampler_hot_update', b'pinned-af0142b-samplerhot-20260824', b'summary_v2']:
    found = data_jni.count(probe) > 0
    ok = ok and found
    print(f'  libmnn_jni: {probe.decode()} -> {"存在" if found else "缺失"}')
print(f'  libMNN.so 大小 = {len(data_mnn)}（期望 ~7300328 strip 后）')
import sys
sys.exit(0 if ok else 1)
PYEOF
echo "== 验证完成 =="
