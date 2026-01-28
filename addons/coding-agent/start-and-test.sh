#!/usr/bin/env bash

#
# Coding Agent 启动、测试和清理脚本
#
# 用法:
#   ./start-and-test.sh start     - 启动后端服务（使用 Maven）
#   ./start-and-test.sh stop      - 停止所有服务
#   ./start-and-test.sh status    - 查看服务状态
#   ./start-and-test.sh test      - 启动服务并运行 playwright 测试
#   ./start-and-test.sh clean     - 清理日志和临时文件
#   ./start-and-test.sh restart   - 重启服务
#   ./start-and-test.sh frontend  - 启动前端开发服务器
#

set -e

# 配置
BACKEND_PORT=8112
FRONTEND_PORT=5173
LOG_DIR="logs"
BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"
PID_FILE="$LOG_DIR/pids"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查端口是否被占用
check_port() {
    local port=$1
    if command -v netstat &> /dev/null; then
        netstat -ano | grep ":$port " | grep "LISTENING" &> /dev/null
    elif command -v lsof &> /dev/null; then
        lsof -ti:$port &> /dev/null
    else
        log_warn "无法检查端口状态"
        return 1
    fi
}

# 通过端口查找 PID
find_pid_by_port() {
    local port=$1
    if command -v netstat &> /dev/null; then
        netstat -ano | grep ":$port " | grep "LISTENING" | awk '{print $5}' | head -1
    elif command -v lsof &> /dev/null; then
        lsof -ti:$port | head -1
    fi
}

# 创建日志目录
mkdir -p "$LOG_DIR"

# 启动后端服务
start_backend() {
    log_info "正在启动 Coding Agent 后端服务..."

    # 检查端口
    if check_port $BACKEND_PORT; then
        log_warn "端口 $BACKEND_PORT 已被占用，尝试停止旧进程..."
        stop_backend
        sleep 2
    fi

    # 使用 Maven 启动
    log_info "使用 Maven 启动 Spring Boot 应用..."
    mvn spring-boot:run -Dspring-boot.run.profiles=docker > "$BACKEND_LOG" 2>&1 &
    local pid=$!
    echo "backend:$pid" >> "$PID_FILE"

    log_info "后端服务启动中 (PID: $pid)，日志: $BACKEND_LOG"
    log_info "等待服务就绪（约 20-30 秒）..."

    # 等待服务启动
    local max_wait=60
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if grep -q "Started CodingAgentApplication" "$BACKEND_LOG" 2>/dev/null; then
            log_success "后端服务启动成功！"

            # 提取并保存密码
            local password=$(grep "security password:" "$BACKEND_LOG" | tail -1 | awk '{print $NF}')
            if [ -n "$password" ]; then
                log_success "生成的登录密码: $password"
                echo "export BACKEND_PASSWORD='$password'" > "$LOG_DIR/credentials"
                echo "用户名: user"
                echo "密码: $password"
            fi
            return 0
        fi

        if grep -q "BUILD FAILURE\|Exception" "$BACKEND_LOG" 2>/dev/null; then
            log_error "后端服务启动失败！"
            tail -50 "$BACKEND_LOG"
            return 1
        fi

        sleep 2
        waited=$((waited + 2))
        printf "."
    done

    echo ""
    log_error "后端服务启动超时（超过 $max_wait 秒）"
    log_error "请查看日志: $BACKEND_LOG"
    tail -30 "$BACKEND_LOG"
    return 1
}

# 启动前端开发服务器
start_frontend() {
    log_info "正在启动前端开发服务器..."

    if check_port $FRONTEND_PORT; then
        log_warn "端口 $FRONTEND_PORT 已被占用，尝试停止旧进程..."
        stop_frontend
        sleep 2
    fi

    cd frontend

    # 检查并安装依赖
    if [ ! -d "node_modules" ]; then
        log_info "安装前端依赖..."
        npm install
    fi

    # 启动开发服务器
    nohup npm run dev > "../$FRONTEND_LOG" 2>&1 &
    local pid=$!
    echo "frontend:$pid" >> "../$PID_FILE"

    cd ..

    log_info "前端服务启动中 (PID: $pid)，日志: $FRONTEND_LOG"
    sleep 3
    log_success "前端开发服务器启动成功！"
    log_info "访问地址: http://localhost:$FRONTEND_PORT"
}

# 停止后端服务
stop_backend() {
    log_info "正在停止后端服务..."

    # 从 PID 文件读取
    if [ -f "$PID_FILE" ]; then
        local pid=$(grep "^backend:" "$PID_FILE" 2>/dev/null | cut -d: -f2)
        if [ -n "$pid" ] && ps -p $pid > /dev/null 2>&1; then
            kill $pid 2>/dev/null || true
            sleep 2
            kill -9 $pid 2>/dev/null || true
            log_success "后端服务已停止 (PID: $pid)"
        fi
        # 清除记录
        grep -v "^backend:" "$PID_FILE" > "$PID_FILE.tmp" 2>/dev/null || true
        mv "$PID_FILE.tmp" "$PID_FILE" 2>/dev/null || true
    fi

    # 通过端口查找并停止
    if check_port $BACKEND_PORT; then
        log_warn "通过端口停止后端进程..."
        local pid=$(find_pid_by_port $BACKEND_PORT)
        if [ -n "$pid" ]; then
            taskkill //F //PID $pid 2>/dev/null || kill -9 $pid 2>/dev/null || true
            log_success "已停止进程 PID: $pid"
        fi
    fi
}

# 停止前端服务
stop_frontend() {
    log_info "正在停止前端服务..."

    # 从 PID 文件读取
    if [ -f "$PID_FILE" ]; then
        local pid=$(grep "^frontend:" "$PID_FILE" 2>/dev/null | cut -d: -f2)
        if [ -n "$pid" ] && ps -p $pid > /dev/null 2>&1; then
            kill $pid 2>/dev/null || true
            sleep 2
            kill -9 $pid 2>/dev/null || true
            log_success "前端服务已停止 (PID: $pid)"
        fi
        # 清除记录
        grep -v "^frontend:" "$PID_FILE" > "$PID_FILE.tmp" 2>/dev/null || true
        mv "$PID_FILE.tmp" "$PID_FILE" 2>/dev/null || true
    fi

    # 通过端口查找并停止
    if check_port $FRONTEND_PORT; then
        log_warn "通过端口停止前端进程..."
        local pid=$(find_pid_by_port $FRONTEND_PORT)
        if [ -n "$pid" ]; then
            taskkill //F //PID $pid 2>/dev/null || kill -9 $pid 2>/dev/null || true
            log_success "已停止进程 PID: $pid"
        fi
    fi
}

# 停止所有服务
stop_all() {
    log_info "正在停止所有服务..."
    stop_backend
    stop_frontend

    # 停止 playwright 会话
    if command -v playwright-cli &> /dev/null; then
        log_info "正在停止 playwright 会话..."
        playwright-cli session-stop-all 2>/dev/null || true
    fi

    log_success "所有服务已停止"
}

# 查看服务状态
show_status() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Coding Agent 服务状态"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    # 后端状态
    echo -n "后端服务 (端口 $BACKEND_PORT): "
    if check_port $BACKEND_PORT; then
        echo -e "${GREEN}运行中${NC}"
        if [ -f "$LOG_DIR/credentials" ]; then
            source "$LOG_DIR/credentials" 2>/dev/null
            echo "  URL: http://localhost:$BACKEND_PORT"
            echo "  用户名: user"
            echo "  密码: $BACKEND_PASSWORD"
        fi
    else
        echo -e "${RED}未运行${NC}"
    fi

    echo ""

    # 前端状态
    echo -n "前端服务 (端口 $FRONTEND_PORT): "
    if check_port $FRONTEND_PORT; then
        echo -e "${GREEN}运行中${NC}"
        echo "  URL: http://localhost:$FRONTEND_PORT"
    else
        echo -e "${YELLOW}未运行 (使用后端静态资源)${NC}"
    fi

    echo ""

    # PID 文件信息
    if [ -f "$PID_FILE" ] && [ -s "$PID_FILE" ]; then
        echo "进程信息:"
        while IFS=: read -r service pid; do
            if ps -p $pid > /dev/null 2>&1; then
                echo -e "  ${service}: ${GREEN}运行中${NC} (PID: $pid)"
            else
                echo -e "  ${service}: ${RED}已停止${NC} (PID: $pid)"
            fi
        done < "$PID_FILE"
        echo ""
    fi

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

# 运行测试
run_test() {
    log_info "正在准备自动化测试环境..."

    # 确保服务运行
    if ! check_port $BACKEND_PORT; then
        log_info "后端服务未运行，正在启动..."
        start_backend
    fi

    # 加载凭据
    if [ -f "$LOG_DIR/credentials" ]; then
        source "$LOG_DIR/credentials"
    else
        log_error "未找到凭据文件，无法执行测试"
        return 1
    fi

    echo ""
    log_success "测试环境已就绪！"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log_info "使用以下凭据登录:"
    log_info "  URL: http://localhost:$BACKEND_PORT"
    log_info "  用户名: user"
    log_info "  密码: $BACKEND_PASSWORD"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    log_info "提示: 使用 playwright-cli 进行测试"
    log_info "示例: playwright-cli open http://localhost:$BACKEND_PORT"
    echo ""
    log_info "或使用技能: /test-coding-agent"
}

# 清理日志和临时文件
clean() {
    log_info "正在清理日志和临时文件..."
    rm -rf "$LOG_DIR" 2>/dev/null || true
    rm -rf frontend/node_modules/.vite 2>/dev/null || true
    rm -rf .playwright-cli 2>/dev/null || true
    log_success "清理完成"
}

# 主命令处理
case "${1:-}" in
    start)
        start_backend
        show_status
        ;;
    stop)
        stop_all
        ;;
    status)
        show_status
        ;;
    test)
        run_test
        ;;
    clean)
        stop_all
        clean
        ;;
    restart)
        stop_all
        sleep 2
        start_backend
        show_status
        ;;
    frontend)
        start_frontend
        show_status
        ;;
    *)
        echo ""
        echo "用法: $0 {start|stop|status|test|clean|restart|frontend}"
        echo ""
        echo "命令:"
        echo "  start     - 启动后端服务（使用 Maven + docker profile）"
        echo "  stop      - 停止所有服务（后端、前端、playwright）"
        echo "  status    - 查看服务状态和登录凭据"
        echo "  test      - 准备测试环境（启动服务并显示凭据）"
        echo "  clean     - 清理日志和临时文件"
        echo "  restart   - 重启后端服务"
        echo "  frontend  - 启动前端开发服务器（可选）"
        echo ""
        echo "示例:"
        echo "  $0 start          # 启动服务"
        echo "  $0 status         # 查看状态"
        echo "  $0 test           # 准备测试"
        echo "  $0 stop           # 停止所有"
        echo ""
        exit 1
        ;;
esac
