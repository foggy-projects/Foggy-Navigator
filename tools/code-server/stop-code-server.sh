#!/usr/bin/env bash
# Code Server 停止脚本（macOS）

set -e

echo "=== 停止 Code Server ==="
echo ""

# 查找 code-server 进程
PIDS=$(pgrep -f "code-server" 2>/dev/null || true)

if [ -z "$PIDS" ]; then
    echo "✅ Code Server 未运行"
    exit 0
fi

echo "找到以下 Code Server 进程:"
ps aux | grep -E "code-server" | grep -v grep || true
echo ""

# 停止进程
echo "🛑 停止进程..."
pkill -f "code-server" 2>/dev/null || true

sleep 2

# 强制停止（如果需要）
if pgrep -f "code-server" > /dev/null 2>&1; then
    echo "⚠️  进程未停止，强制终止..."
    pkill -9 -f "code-server" 2>/dev/null || true
    sleep 1
fi

# 验证
if pgrep -f "code-server" > /dev/null 2>&1; then
    echo "❌ 无法停止 Code Server 进程"
    echo "   请手动终止: kill -9 $(pgrep -f 'code-server')"
    exit 1
else
    echo "✅ Code Server 已停止"
fi