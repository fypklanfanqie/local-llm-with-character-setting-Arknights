#!/bin/bash
# 全量单测（JAVA_HOME 固定 Temurin 17）
set -euo pipefail
cd "/d/ai/cc Programm/聊天终端安卓本地"
export JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8'
./gradlew :app:testDebugUnitTest --console=plain 2>&1 | tail -8
