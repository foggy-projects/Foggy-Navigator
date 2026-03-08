#!/usr/bin/env bash
# Code Server 启动脚本（macOS）
# 用于启动本地 VS Code Server

set -e

# 配置
PORT=${CODE_SERVER_PORT:-8443}
PASSWORD=${CODE_SERVER_PASSWORD:-foggy123}
DATA_DIR="$HOME/.local/share/code-server"
CONFIG_FILE="$DATA_DIR/config.yaml"
LOG_FILE="$DATA_DIR/code-server.log"

# 创建必要的目录
mkdir -p "$DATA_DIR"

# 检查是否已经运行
check_running() {
    if pgrep -f "code-server" > /dev/null 2>&1; then
        echo "⚠️  Code Server 似乎已在运行"
        echo "   运行 'ps aux | grep code-server' 查看进程"
        return 0
    fi
    return 1
}

# 创建配置文件
create_config() {
    cat > "$CONFIG_FILE" << EOF
bind-addr: 127.0.0.1:${PORT}
auth: password
password: ${PASSWORD}
cert: false
EOF
    echo "✅ 配置文件已创建: $CONFIG_FILE"
}

# 启动 Code Server
start_server() {
    # 检查 code-server 命令
    if command -v code-server >/dev/null 2>&1; then
        CODE_SERVER_CMD="code-server"
    elif [ -x "/usr/local/opt/code-server/bin/code-server" ]; then
        CODE_SERVER_CMD="/usr/local/opt/code-server/bin/code-server"
    elif [ -x "$HOME/.local/lib/code-server/bin/code-server" ]; then
        CODE_SERVER_CMD="$HOME/.local/lib/code-server/bin/code-server"
    else
        echo "❌ 未找到 code-server 命令"
        echo "   请先安装: brew install code-server"
        exit 1
    fi

    echo "🚀 启动 Code Server..."
    echo "   命令: $CODE_SERVER_CMD"
    echo "   端口: $PORT"
    echo "   密码: $PASSWORD"

    # 启动服务器
    nohup "$CODE_SERVER_CMD" --config "$CONFIG_FILE" "$@" > "$LOG_FILE" 2>&1 &

    sleep 2

    # 验证启动
    if pgrep -f "code-server" > /dev/null 2>&1; then
        echo ""
        echo "✅ Code Server 启动成功！"
        echo ""
        echo "   访问地址: http://127.0.0.1:${PORT}"
        echo "   密码: ${PASSWORD}"
        echo "   日志: $LOG_FILE"
        echo ""
        echo "   停止命令: ./stop-code-server.sh"
    else
        echo "❌ 启动失败，请查看日志: $LOG_FILE"
        tail -n 20 "$LOG_FILE"
        exit 1
    fi
}

# 主流程
main() {
    echo "=== Code Server 启动脚本 ==="
    echo ""

    # 检查是否已运行
    if check_running; then
        echo "是否继续启动? (y/n)"
        read -r answer
        if [ "$answer" != "y" ]; then
            exit 0
        fi
    fi

    # 创建配置
    create_config

    # 启动服务器（可以传递项目路径作为参数）
    if [ $# -gt 0 ]; then
        echo "   项目路径: $1"
        start_server "$1"
    else
        start_server
    fi
}

# 执行主流程
main "$@"