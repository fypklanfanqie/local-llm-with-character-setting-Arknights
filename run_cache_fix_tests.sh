#!/usr/bin/env bash
# 缓存修复验证脚本：跑新增测试 + 回归批次，输出每步退出码。
set -u
cd "$(dirname "$0")"
export JAVA_HOME="D:/jdk-temurin-17/jdk-17.0.20+8"

echo "=== Step 1/2: 新增契约测试（GreetingPromptBuilder + ChatDaoOrdering） ==="
./gradlew :app:testDebugUnitTest \
  --tests "com.rhodesisland.terminal.data.local.ChatDaoOrderingContractTest" \
  --tests "com.rhodesisland.terminal.work.GreetingPromptBuilderTest" \
  --console=plain
s1=$?

echo ""
echo "=== Step 2/2: 回归批次（work / data.local / data.repository / groupchat prompt） ==="
./gradlew :app:testDebugUnitTest \
  --tests "com.rhodesisland.terminal.work.*" \
  --tests "com.rhodesisland.terminal.data.local.*" \
  --tests "com.rhodesisland.terminal.data.repository.*" \
  --tests "com.rhodesisland.terminal.ui.groupchat.GroupChatPromptBuilderTest" \
  --console=plain
s2=$?

echo ""
echo "=== SUMMARY: step1(exit=$s1) step2(exit=$s2) ==="
[ $s1 -eq 0 ] && [ $s2 -eq 0 ] && echo "ALL GREEN" || echo "HAS FAILURES"
