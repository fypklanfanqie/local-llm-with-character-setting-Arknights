#!/bin/sh
# TDD 验证脚本（验证完删除）：定向测试 → 强制重编译 → 全量单测
cd "/d/ai/cc Programm/聊天终端安卓本地" || exit 1
export JAVA_HOME='D:/jdk-temurin-17/jdk-17.0.20+8'

echo "===== 1/3 定向测试（PromptWindowAnchor + GroupChatPromptBuilder）====="
./gradlew :app:testDebugUnitTest \
  --tests "com.rhodesisland.terminal.util.PromptWindowAnchorTest" \
  --tests "com.rhodesisland.terminal.ui.groupchat.GroupChatPromptBuilderTest" || exit 1

echo "===== 2/3 compileDebugKotlin --rerun-tasks --no-build-cache ====="
./gradlew :app:compileDebugKotlin --rerun-tasks --no-build-cache || exit 1

echo "===== 3/3 全量单测 ====="
./gradlew :app:testDebugUnitTest || exit 1

echo "===== ALL GREEN ====="
