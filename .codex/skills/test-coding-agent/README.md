# test-coding-agent 技能使用指南

这个技能使用 playwright-cli 自动化测试 coding-agent 前端管理控制台。

## 快速开始

### 1. 启动服务

#### Linux/macOS

```bash
cd addons/coding-agent
./start-and-test.sh test
```

#### Windows (PowerShell)

```powershell
cd addons/coding-agent
.\start-and-test.ps1 test
```

#### Windows (命令提示符)

```cmd
cd addons\coding-agent
start-and-test.bat test
```

这将：
- 启动后端服务（使用 `application-docker.yml` 配置）
- 等待服务完全启动
- 显示登录凭据

### 2. 使用技能测试

在 Claude Code 中执行：

```
/test-coding-agent
```

或手动测试：

```bash
# 打开浏览器
playwright-cli open http://localhost:8112

# 使用显示的密码登录
# 用户名: user
# 密码: (从启动日志中获取)
```

### 3. 停止服务

#### Linux/macOS
```bash
cd addons/coding-agent
./start-and-test.sh stop
```

#### Windows
```powershell
cd addons/coding-agent
.\start-and-test.ps1 stop
# 或
start-and-test.bat stop
```

## 脚本命令

### 可用命令

| 命令 | 说明 |
|------|------|
| `start` | 启动后端服务 |
| `stop` | 停止所有服务（后端、前端、playwright） |
| `status` | 查看服务状态和登录凭据 |
| `test` | 准备测试环境（启动并显示凭据） |
| `clean` | 清理日志和临时文件 |
| `restart` | 重启服务 |
| `frontend` | 启动前端开发服务器（可选） |

### 使用方式

**Linux/macOS:**
```bash
./start-and-test.sh <command>
```

**Windows (PowerShell):**
```powershell
.\start-and-test.ps1 <command>
```

**Windows (批处理):**
```cmd
start-and-test.bat <command>
```

## 测试内容

技能会测试以下内容：

### 基础功能
- ✅ 登录页面
- ✅ 系统监控页面
- ✅ 会话管理页面
- ✅ 容器管理页面
- ✅ 事件日志页面

### API 可用性
- ✅ `GET /api/v1/containers`
- ✅ `GET /api/v1/events`
- ✅ `GET /api/v1/conversations`

### 输出
- 📸 各页面截图（保存在 `.playwright-cli/` 目录）
- 📊 测试报告（Markdown 格式）

## 自定义测试

你可以要求 Claude 执行特定的测试场景，例如：

```
使用 /test-coding-agent 测试创建会话功能
```

```
使用 /test-coding-agent 生成所有页面的截图
```

## 故障排查

### 服务启动失败

1. 检查 MySQL 是否运行（端口 13309）：
   ```bash
   netstat -ano | findstr :13309
   ```

2. 检查日志：
   ```bash
   cat addons/coding-agent/logs/backend.log
   ```

### 页面加载缓慢

等待至少 30 秒让 JPA 初始化完成。

### API 返回 404

确认 Controller 已正确实现，查看启动日志中的映射信息。

## 日志位置

- 后端日志: `addons/coding-agent/logs/backend.log`
- 前端日志: `addons/coding-agent/logs/frontend.log`
- 凭据文件: `addons/coding-agent/logs/credentials`
- 进程 PID: `addons/coding-agent/logs/pids`

## 注意事项

- 服务运行在 `localhost:8112`
- 使用 `application-docker.yml` 配置
- 需要 MySQL 运行在端口 13309
- 测试完成后记得停止服务：`./start-and-test.sh stop`
