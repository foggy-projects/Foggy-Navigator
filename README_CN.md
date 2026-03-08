# Foggy Navigator

基于 LangChain4j 的个人 AI Agent 编排中枢。

## 快速启动

### 后端

```powershell
# 完整启动（编译 + 启动），首次或代码有改动时使用
powershell -ExecutionPolicy Bypass -File start-launcher.ps1

# 跳过编译，直接用上次的 JAR 启动（代码没改动时用，省 30-60 秒）
powershell -ExecutionPolicy Bypass -File start-launcher.ps1 -SkipBuild

# Mock LLM 模式（不需要真实 LLM API Key）
powershell -ExecutionPolicy Bypass -File start-launcher-mock.ps1

# 停止
powershell -ExecutionPolicy Bypass -File stop-launcher.ps1
```

- 端口：`8112`
- 健康检查：`http://localhost:8112/actuator/health`
- 日志：`logs/backend.log`、`logs/backend-error.log`
- Profile：`docker`（读取 `application-docker.yml`，已 gitignore）

### 前端

```powershell
# 一键启动
powershell -ExecutionPolicy Bypass -File start-frontend.ps1

# 或手动
cd packages/navigator-frontend
pnpm install && pnpm dev
```

- 端口：`5174`
- 登录：`root / root123`

### Claude Worker

```powershell
# 启动
powershell -ExecutionPolicy Bypass -File tools/claude-agent-worker/start.ps1

# 停止
powershell -ExecutionPolicy Bypass -File tools/claude-agent-worker/stop.ps1
```

- 端口：`3031`

## 仅编译（不启动）

```powershell
# 后端
mvn compile -pl launcher -am -DskipTests

# 前端
cd packages/navigator-frontend && pnpm exec vite build
```

## 启动脚本参数

| 脚本 | 参数 | 说明 |
|------|------|------|
| `start-launcher.ps1` | （无） | 编译 + 启动 |
| `start-launcher.ps1` | `-SkipBuild` | 跳过 mvn package，直接启动已有 JAR |

## 配置

- **LLM / 数据库**：`launcher/src/main/resources/application-docker.yml`（gitignore，需手动创建）
- **平台配置**：首次访问 `http://localhost:5174/#/setup` 配置 Git 和 AI 模型
