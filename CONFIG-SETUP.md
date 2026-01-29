# 配置文件设置指南

## 敏感信息安全说明

本项目的配置文件可能包含敏感信息（如 API Key、数据库密码等），已添加到 `.gitignore` 以防止提交到版本控制。

## 配置文件列表

### 已忽略的配置文件（包含敏感信息）

以下文件已添加到 `.gitignore`，不会提交到 Git：

- `**/application-docker.yml` - Docker 环境配置（包含 LLM API Key）
- `**/.env` - 环境变量文件

### 模板文件（可以提交）

提供了不包含敏感信息的模板文件：

- `**/application-docker.yml.example` - Docker 配置模板
- 使用环境变量占位符，需要在运行时提供实际值

## 首次设置步骤

### 1. 复制模板文件

```bash
# Launcher 模块
cd launcher/src/main/resources
cp application-docker.yml.example application-docker.yml

# Coding Agent 模块
cd addons/coding-agent/src/main/resources
cp application-docker.yml.example application-docker.yml
```

### 2. 配置环境变量

有两种方式配置敏感信息：

#### 方式 A: 使用环境变量（推荐）

在系统环境变量或 shell 中设置：

**Windows (PowerShell)**:
```powershell
$env:OPENAI_API_KEY = "your-api-key-here"
$env:OPENAI_API_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
```

**Linux/Mac (Bash)**:
```bash
export OPENAI_API_KEY="your-api-key-here"
export OPENAI_API_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
```

#### 方式 B: 使用 .env 文件

在项目根目录或模块目录创建 `.env` 文件：

```env
# addons/coding-agent/.env
OPENAI_API_KEY=your-api-key-here
OPENAI_API_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
```

**注意**: `.env` 文件已在 `.gitignore` 中，不会被提交。

### 3. 启动应用

使用启动脚本会自动加载环境变量：

```bash
# 使用启动脚本（推荐）
powershell -ExecutionPolicy Bypass -File start-launcher.ps1

# 或者手动指定环境变量
"C:\Program Files\Java\jdk-17.0.1\bin\java.exe" ^
  -Dspring.profiles.active=docker ^
  -jar launcher/target/launcher-1.0.0-SNAPSHOT.jar
```

## 配置项说明

### LLM API 配置

```yaml
foggy:
  coding-agent:
    openhands:
      # LLM API Key - 从环境变量读取
      api-key: ${OPENAI_API_KEY:sk-test-key}

      # LLM 模型名称
      model-name: glm-4.7

      # LLM API Base URL - 从环境变量读取
      api-base-url: ${OPENAI_API_BASE_URL:}
```

**说明**:
- `${OPENAI_API_KEY:sk-test-key}`: 从环境变量 `OPENAI_API_KEY` 读取，如果未设置则使用默认值 `sk-test-key`
- `${OPENAI_API_BASE_URL:}`: 从环境变量 `OPENAI_API_BASE_URL` 读取，如果未设置则为空

### 数据库配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:13309/coding_agent
    username: foggy
    password: 'foggy@123'
```

**生产环境建议**:
- 使用环境变量存储数据库密码: `${DB_PASSWORD:foggy@123}`
- 或使用 Spring Cloud Config Server 集中管理配置

## 团队协作指南

### 开发者设置

1. 克隆代码后，复制配置模板：
   ```bash
   find . -name "application-docker.yml.example" -exec sh -c 'cp {} $(dirname {})/application-docker.yml' \;
   ```

2. 向团队负责人获取 API Key 等敏感信息

3. 在本地配置环境变量或修改 `application-docker.yml`（不提交）

### 添加新的敏感配置

如果需要添加新的敏感配置项：

1. 在配置文件中使用环境变量占位符：
   ```yaml
   new-service:
     api-key: ${NEW_SERVICE_API_KEY:default-value}
   ```

2. 更新 `application-docker.yml.example` 模板

3. 更新本文档，说明新增的环境变量

4. 通知团队成员更新配置

## 安全检查清单

在提交代码前，请确认：

- [ ] 没有硬编码 API Key、密码等敏感信息
- [ ] 所有敏感配置都使用环境变量
- [ ] `.gitignore` 包含了所有敏感配置文件
- [ ] 提供了 `.example` 模板文件
- [ ] 更新了配置文档

## 常见问题

### Q: 为什么启动时提示找不到配置？

A: 确保已复制 `application-docker.yml.example` 为 `application-docker.yml`

### Q: 为什么 LLM API 调用失败？

A: 检查环境变量 `OPENAI_API_KEY` 和 `OPENAI_API_BASE_URL` 是否正确设置

### Q: 如何在 CI/CD 中配置？

A: 在 CI/CD 平台（如 GitHub Actions、GitLab CI）的 Secret/Variables 中配置环境变量

### Q: 生产环境如何管理配置？

A: 推荐使用：
- Spring Cloud Config Server
- Kubernetes ConfigMap/Secret
- 专业的配置管理工具（如 HashiCorp Vault）

## 相关文件

- `.gitignore` - Git 忽略规则
- `start-launcher.ps1` - 启动脚本（可在此设置环境变量）
- `TEST-SETUP-SUMMARY.md` - 测试环境设置
- `launcher/src/main/resources/application.yml` - 主配置文件
- `launcher/src/main/resources/application-docker.yml.example` - Docker 配置模板

---

**最后更新**: 2026-01-29
