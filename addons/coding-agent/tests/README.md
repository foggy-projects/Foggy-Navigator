# Coding Agent E2E 测试套件

## 目录结构

```
tests/
├── README.md                          # 本文件 - 测试套件说明
├── playwright-cli-test-guide.md       # Playwright CLI 测试指南
├── package.json                       # Node.js 依赖配置
├── e2e/
│   └── test-create-file.mjs          # 创建文件测试脚本
└── screenshots/                       # 测试截图目录
    ├── 01-initial-page.png           # 初始页面
    ├── 02-login-page.png             # 登录页面（如有）
    └── 03-after-login.png            # 登录后页面
```

## 快速开始

### 1. 环境准备

确保以下服务正在运行：

- **后端服务**: `http://localhost:8112`
- **MySQL 数据库**: `localhost:13309`
- **Docker**: 用于运行 OpenHands 容器

### 2. 启动后端服务

```bash
# 在项目根目录
cd D:/foggy-projects/Foggy-Navigator

# 使用启动脚本（已配置 Java 17）
powershell -ExecutionPolicy Bypass -File start-launcher.ps1

# 或者直接运行 JAR
"C:\Program Files\Java\jdk-17.0.1\bin\java.exe" -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar --spring.profiles.active=docker
```

### 3. 安装测试依赖

```bash
cd addons/coding-agent/tests
npm install
npx playwright install chromium
```

### 4. 运行测试

#### 方式 A: 自动化测试（推荐）

```bash
npm run test:create-file
```

此命令会：
- 启动 Chromium 浏览器
- 自动导航到前端页面
- 尝试自动登录
- 截图并显示手动测试步骤
- 保持浏览器打开以便手动操作

#### 方式 B: 使用 playwright-cli

```bash
# 打开浏览器进行手动测试
cd ..  # 回到 coding-agent 目录
playwright-cli open http://localhost:8112
```

详细步骤请参考 [playwright-cli-test-guide.md](./playwright-cli-test-guide.md)

## 测试场景

### 创建文件测试

**测试目标**: 验证 Coding Agent 能够通过 OpenHands 创建文件

**测试步骤**:

1. **登录系统**
   - 用户名: `root`
   - 密码: `root123`

2. **创建新会话**
   - 点击"创建"或"新建会话"按钮
   - 填写会话名称: `测试会话-创建文件`
   - 等待容器启动（约10-20秒）

3. **发送创建文件指令**
   - 在消息输入框输入:
     ```
     请创建一个名为 test-hello.txt 的文件，内容为 helloworld
     ```
   - 点击"发送"按钮
   - 等待 Agent 执行（约30-60秒）

4. **验证结果**
   - 查看 Agent 响应消息
   - 确认包含成功提示（如"创建成功"、"完成"等）
   - 通过容器日志或文件浏览器确认文件存在

**预期结果**:
- ✓ 会话创建成功
- ✓ OpenHands 容器正常启动
- ✓ Agent 正确理解并执行任务
- ✓ 文件 `test-hello.txt` 被创建
- ✓ 文件内容为 `helloworld`

## 配置说明

### LLM 配置

测试使用的 LLM 配置（位于 `launcher/src/main/resources/application-docker.yml`）:

```yaml
foggy:
  coding-agent:
    openhands:
      api-key: sk-40590e5709aa4a779c93c89c5c8c70d4
      model-name: glm-4.7
      api-base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
```

### 数据库配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:13309/coding_agent
    username: foggy
    password: foggy@123
```

### 认证配置

```yaml
system:
  root:
    username: root
    password: root123
    email: root@foggy.local
```

## 故障排查

### 问题 1: 后端服务启动失败

**症状**: 端口 8112 无响应

**解决方案**:
```bash
# 检查 Java 版本（需要 Java 17+）
java -version

# 查看启动日志
cat logs/backend.log
cat logs/backend-error.log

# 检查端口占用
netstat -ano | findstr 8112
```

### 问题 2: 容器启动失败

**症状**: 创建会话后容器无法启动

**解决方案**:
```bash
# 检查 Docker 服务
docker ps

# 检查 OpenHands 镜像
docker images | grep openhands

# 拉取镜像（如果不存在）
docker pull ghcr.io/all-hands-ai/openhands:main
```

### 问题 3: Agent 不响应

**症状**: 发送消息后无响应或超时

**可能原因**:
- LLM API 配置错误
- API Key 无效或额度不足
- 网络连接问题

**解决方案**:
```bash
# 检查后端日志中的 API 调用
tail -f logs/backend.log | grep -i "llm\|api\|error"

# 测试 API 连接
curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions \
  -H "Authorization: Bearer sk-40590e5709aa4a779c93c89c5c8c70d4" \
  -H "Content-Type: application/json" \
  -d '{"model":"glm-4.7","messages":[{"role":"user","content":"测试"}]}'
```

### 问题 4: 403 错误

**症状**: 浏览器控制台显示 403 错误

**可能原因**:
- CORS 配置问题
- Spring Security 配置

**临时解决**: 403 错误通常不影响主要功能，可以忽略。如需解决，检查 Spring Security 和 CORS 配置。

## 测试报告

测试完成后，可以在以下位置找到测试输出：

- **截图**: `tests/e2e/screenshots/*.png`
- **后端日志**: `logs/backend.log`
- **容器日志**: 通过 Docker 或前端界面查看

## 测试最佳实践

1. **每次测试前重启服务**: 确保环境干净
2. **保存测试截图**: 便于问题追踪
3. **记录测试结果**: 包括成功/失败、执行时间、错误信息
4. **定期更新依赖**: `npm update` 和 `npx playwright install`

## 相关资源

- [Playwright 文档](https://playwright.dev/)
- [OpenHands 文档](https://github.com/all-hands-ai/openhands)
- [项目主 README](../../../README.md)
- [Coding Agent 开发文档](../README.md)

## 联系方式

如有问题或建议，请联系项目维护者或提交 Issue。
