# Coding Agent Windows 使用指南

## 🚀 快速开始

### 方式 1: 双击运行（推荐初学者）

1. 打开文件资源管理器
2. 进入 `D:\foggy-projects\Foggy-Navigator\addons\coding-agent`
3. 双击 `start-and-test.bat`
4. 在弹出的窗口中选择操作

### 方式 2: PowerShell（推荐）

```powershell
# 进入项目目录
cd D:\foggy-projects\Foggy-Navigator\addons\coding-agent

# 启动服务
.\start-and-test.ps1 start

# 查看状态
.\start-and-test.ps1 status

# 准备测试
.\start-and-test.ps1 test

# 停止服务
.\start-and-test.ps1 stop
```

### 方式 3: 命令提示符

```cmd
cd D:\foggy-projects\Foggy-Navigator\addons\coding-agent
start-and-test.bat start
```

---

## 📋 脚本说明

### PowerShell 脚本 (`start-and-test.ps1`)

**功能完整的主脚本**，包含：
- ✅ 彩色日志输出
- ✅ 进程管理
- ✅ 端口检测
- ✅ 自动提取登录密码
- ✅ 完整的错误处理

**使用方法：**
```powershell
.\start-and-test.ps1 <命令>
```

### 批处理脚本 (`start-and-test.bat`)

**简单的入口包装器**，调用 PowerShell 脚本。

**使用方法：**
```cmd
start-and-test.bat <命令>
```

---

## 🔧 可用命令

| 命令 | 说明 | 示例 |
|------|------|------|
| `start` | 启动后端服务 | `.\start-and-test.ps1 start` |
| `stop` | 停止所有服务 | `.\start-and-test.ps1 stop` |
| `status` | 查看服务状态 | `.\start-and-test.ps1 status` |
| `test` | 准备测试环境 | `.\start-and-test.ps1 test` |
| `clean` | 清理日志文件 | `.\start-and-test.ps1 clean` |
| `restart` | 重启服务 | `.\start-and-test.ps1 restart` |
| `frontend` | 启动前端服务器 | `.\start-and-test.ps1 frontend` |

---

## 📖 使用示例

### 启动并测试

```powershell
# 1. 启动服务
PS> .\start-and-test.ps1 start

# 输出示例:
# [INFO] 正在启动 Coding Agent 后端服务...
# [INFO] 使用 Maven 启动 Spring Boot 应用...
# [INFO] 后端服务启动中 (PID: 12345)，日志: logs\backend.log
# [INFO] 等待服务就绪（约 20-30 秒）...
# ......................
# [SUCCESS] 后端服务启动成功！
# [SUCCESS] 生成的登录密码: a1b2c3d4-5678-90ef-ghij-klmnopqrstuv
# 用户名: user
# 密码: a1b2c3d4-5678-90ef-ghij-klmnopqrstuv

# 2. 查看状态
PS> .\start-and-test.ps1 status

# 输出示例:
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
#   Coding Agent 服务状态
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
#
# 后端服务 (端口 8112): 运行中
#   URL: http://localhost:8112
#   用户名: user
#   密码: a1b2c3d4-5678-90ef-ghij-klmnopqrstuv
#
# 前端服务 (端口 5173): 未运行 (使用后端静态资源)
#
# 进程信息:
#   backend: 运行中 (PID: 12345)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

# 3. 使用浏览器测试
# 打开 http://localhost:8112，使用上面的凭据登录

# 4. 停止服务
PS> .\start-and-test.ps1 stop
```

---

## 🔍 故障排查

### 问题 1: 执行策略错误

**错误信息：**
```
.\start-and-test.ps1 : 无法加载文件，因为在此系统上禁止运行脚本。
```

**解决方法：**
```powershell
# 方法 1: 临时允许（推荐）
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

# 方法 2: 使用批处理文件
start-and-test.bat start

# 方法 3: 直接执行（每次）
powershell -ExecutionPolicy Bypass -File .\start-and-test.ps1 start
```

### 问题 2: 端口被占用

**错误信息：**
```
[WARN] 端口 8112 已被占用
```

**解决方法：**
```powershell
# 方法 1: 使用脚本停止
.\start-and-test.ps1 stop

# 方法 2: 手动查找并停止进程
netstat -ano | findstr :8112
# 找到 PID，然后停止
taskkill /F /PID <进程ID>
```

### 问题 3: Maven 命令未找到

**错误信息：**
```
'mvn' 不是内部或外部命令
```

**解决方法：**
1. 确认 Maven 已安装：`mvn -version`
2. 添加 Maven 到系统 PATH
3. 重启 PowerShell

### 问题 4: MySQL 连接失败

**错误信息：**
```
[ERROR] 后端服务启动失败！
Connection refused: localhost:13309
```

**解决方法：**
```powershell
# 1. 检查 MySQL 是否运行
netstat -ano | findstr :13309

# 2. 如果未运行，启动 Docker MySQL
cd D:\foggy-projects\Foggy-Navigator\docker
docker-compose up -d mysql
```

---

## 📁 文件说明

```
addons/coding-agent/
├── start-and-test.ps1      # PowerShell 主脚本（推荐）
├── start-and-test.bat      # 批处理入口脚本
├── start-and-test.sh       # Linux/macOS 脚本
├── start-and-test.sh.bak   # 原始备份
└── logs/                   # 日志目录（自动创建）
    ├── backend.log         # 后端日志
    ├── frontend.log        # 前端日志
    ├── credentials.txt     # 登录凭据
    └── pids.txt            # 进程 PID
```

---

## 🎯 与 Claude Code 技能集成

启动服务后，可以在 Claude Code 中使用测试技能：

```
/test-coding-agent
```

技能会自动：
1. ✅ 打开浏览器
2. ✅ 使用保存的凭据登录
3. ✅ 测试所有页面
4. ✅ 验证 API
5. ✅ 生成截图
6. ✅ 生成测试报告

---

## 💡 提示

### 自动启动

创建桌面快捷方式：
1. 右键 `start-and-test.bat`
2. 选择"发送到" → "桌面快捷方式"
3. 右键快捷方式 → 属性
4. 在"目标"后添加 ` test`
5. 完整目标示例：`"D:\...\start-and-test.bat" test`

### 定时任务

使用 Windows 任务计划程序每天自动运行测试：
```powershell
# 创建任务（以管理员身份运行）
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-File D:\foggy-projects\Foggy-Navigator\addons\coding-agent\start-and-test.ps1 test"
$trigger = New-ScheduledTaskTrigger -Daily -At 9am
Register-ScheduledTask -TaskName "CodingAgent-Test" -Action $action -Trigger $trigger
```

---

## 📞 获取帮助

### 查看帮助信息
```powershell
.\start-and-test.ps1
# 不带参数时显示帮助
```

### 查看日志
```powershell
# 实时查看后端日志
Get-Content logs\backend.log -Wait -Tail 50

# 查看最后 30 行
Get-Content logs\backend.log -Tail 30
```

### 检查服务状态
```powershell
# 使用脚本
.\start-and-test.ps1 status

# 手动检查端口
netstat -ano | findstr :8112
netstat -ano | findstr :5173
```

---

## ⚙️ 高级配置

### 修改端口

编辑 `start-and-test.ps1` 的配置部分：
```powershell
# 配置（文件开头）
$BACKEND_PORT = 8112    # 改为你的端口
$FRONTEND_PORT = 5173   # 改为你的端口
```

### 修改日志位置

```powershell
$LOG_DIR = "logs"       # 改为你的路径
```

### 自定义 Maven 参数

修改启动命令部分：
```powershell
# 在 Start-Backend 函数中
$startInfo.Arguments = "/c mvn spring-boot:run -Dspring-boot.run.profiles=docker -DskipTests > `"$BACKEND_LOG`" 2>&1"
```

---

## 🔐 安全建议

1. ⚠️ **不要提交凭据文件到 Git**
   - `logs/credentials.txt` 已在 `.gitignore` 中

2. ⚠️ **生产环境不要使用自动生成的密码**
   - 修改 `application.yml` 配置固定密码

3. ⚠️ **关闭不使用的服务**
   ```powershell
   .\start-and-test.ps1 stop
   ```

---

**祝测试顺利！** 🎉

如有问题，请查看：
- 日志文件: `logs/backend.log`
- 技能文档: `.claude/skills/test-coding-agent/README.md`
- 或使用 `/test-coding-agent` 技能获取帮助
