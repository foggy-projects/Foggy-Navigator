# Claude Code Proxy - 使用说明

## 快速开始（推荐 Docker 方式）

### 1. 配置环境变量
编辑 `.env` 文件，设置您的 GLM API 密钥：
```bash
OPENAI_API_KEY="your-glm-api-key-here"
```

### 2. 启动代理服务器（Docker）
**使用 PowerShell 脚本（推荐）：**
```bash
cd d:\foggy-projects\Foggy-Navigator\tools\claude-code-proxy
powershell -ExecutionPolicy Bypass -File start-docker.ps1
```

**或使用 Docker Compose：**
```bash
cd d:\foggy-projects\Foggy-Navigator\tools\claude-code-proxy
docker-compose up -d
```

### 3. 停止代理服务器（Docker）
```bash
powershell -ExecutionPolicy Bypass -File stop-docker.ps1
```

**或使用 Docker Compose：**
```bash
docker-compose down
```

### 4. 验证运行
```bash
curl http://localhost:8082/health
```

## 本地运行方式（需要 Python 环境）

### 1. 安装依赖
```bash
cd d:\foggy-projects\Foggy-Navigator\tools\claude-code-proxy
pip install -r requirements.txt
```

### 2. 启动代理服务器
**使用 PowerShell 脚本：**
```bash
powershell -ExecutionPolicy Bypass -File start.ps1
```

**或直接运行：**
```bash
python start_proxy.py
```

### 3. 停止代理服务器
```bash
powershell -ExecutionPolicy Bypass -File stop.ps1
```

## 配置说明

### 基本配置
- `OPENAI_API_KEY`: GLM API 密钥（必需）
- `OPENAI_BASE_URL`: GLM API 端点（默认：https://open.bigmodel.cn/api/paas/v4）
- `PORT`: 代理服务器端口（默认：8082）

### 模型映射
代理服务器会将 Claude 模型请求映射到 GLM 模型：

| Claude 模型 | 映射到 | 环境变量 |
|------------|--------|-----------|
| haiku | SMALL_MODEL | glm-4.5-air |
| sonnet | MIDDLE_MODEL | glm-4.7 |
| opus | BIG_MODEL | glm-5 |

### 性能配置
- `MAX_TOKENS_LIMIT`: 最大 token 限制（默认：200000）
- `REQUEST_TIMEOUT`: 请求超时时间（秒，默认：120）
- `MAX_RETRIES`: 最大重试次数（默认：3）

## 使用 Claude Code 连接代理

### 临时使用
```bash
ANTHROPIC_BASE_URL=http://localhost:8082 ANTHROPIC_API_KEY="any-value" claude
```

### 永久设置
```bash
export ANTHROPIC_BASE_URL=http://localhost:8082
claude
```

## 在 Foggy-Navigator 中配置

在 LLM 模型配置中设置：
```json
{
  "name": "智谱-GLM",
  "category": "GENERAL",
  "baseUrl": "http://localhost:8082",
  "modelName": "claude-opus-4-6",
  "haikuModelName": "glm-4.5-air",
  "sonnetModelName": "glm-4.7",
  "opusModelName": "glm-5",
  "isDefault": true
}
```

## 故障排除

### 端口被占用
如果启动时提示端口被占用，修改 `.env` 文件中的 `PORT` 值，或使用 stop.ps1 停止现有进程。

### API 连接失败
1. 检查 `.env` 文件中的 `OPENAI_API_KEY` 是否正确
2. 检查网络连接是否正常
3. 查看代理服务器日志获取详细错误信息

### 模型错误
如果 Claude Code 报告模型不存在，检查：
1. 代理服务器是否正常运行
2. `.env` 文件中的模型映射配置是否正确
3. Claude Code 是否正确连接到代理服务器（检查 ANTHROPIC_BASE_URL）

## 日志

代理服务器会输出详细的日志信息，包括：
- 请求转换详情
- 模型映射信息
- API 调用状态
- 错误详情

设置 `LOG_LEVEL` 为 `DEBUG` 可以获取更详细的日志：
```bash
LOG_LEVEL="DEBUG"
```

## 支持

如遇问题，请查看：
- [Claude Code Proxy GitHub](https://github.com/fuergaosi233/claude-code-proxy)
- 项目 README.md
- 日志输出
