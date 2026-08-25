#!/usr/bin/env bash
# 收尾清理：把 8 个既有坏测试文件移回原路径 + 删除本脚本自身。
set -u
cd "$(dirname "$0")"

echo "=== 恢复 8 个坏测试文件 ==="
for f in /d/ai-build/broken-tests-hold/*.kt; do
  orig=$(basename "$f" | tr '_' '/')
  mkdir -p "app/$(dirname "$orig")"
  mv "$f" "app/$orig"
  echo "restored: $orig"
done
rmdir /d/ai-build/broken-tests-hold && echo "hold dir removed"

rm -f "$0" && echo "cleanup script removed"
echo "=== DONE ==="
