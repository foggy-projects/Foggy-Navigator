---
name: navigator-admin
description: Foggy Navigator 平台管理技能。当用户需要管理用户、接入 Worker、创建工作目录、配置 LLM 模型、管理 API 凭证、配置 Git 提供者等日常运维操作时使用。触发词：/navigator-admin, /admin, 提及"管理平台"、"配置LLM"、"接入Worker"、"创建用户"、"管理用户"。
---

# Foggy Navigator 平台管理

通过 Navigator HTTP API 完成平台日常管理操作。

## CRITICAL 约束

1. **必须**通过 Bash 工具执行 `curl` 命令调用 Navigator HTTP API — 这是唯一允许的执行方式
2. **禁止**直接修改数据库或配置文件
3. **敏感信息处理**：API Key、密码等敏感字段仅在用户明确提供时使用，禁止猜测或编造
4. 每次操作前先向用户确认参数，操作后展示结果

## 前提条件

环境变量 `NAVIGATOR_TOKEN` 和 `NAVIGATOR_API_BASE` 必须存在。如果不存在，告知用户：

> 当前未检测到 `NAVIGATOR_TOKEN` 或 `NAVIGATOR_API_BASE` 环境变量。
> 这些变量由 Foggy Navigator 后端在派发任务时自动注入。
> 请确保你是通过 Navigator 平台派发的任务（而非直接运行 Claude Code）。

检测方式：
```bash
echo "NAVIGATOR_TOKEN=${NAVIGATOR_TOKEN:+已设置}" && echo "NAVIGATOR_API_BASE=${NAVIGATOR_API_BASE:-未设置}"
```

## API 基础信息

- **Base URL**: `{{NAVIGATOR_API_BASE}}`
- **认证头**: `Authorization: Bearer $NAVIGATOR_TOKEN`
- **响应格式**: `{ "code": 200, "data": ... }` — 取 `.data` 字段
- **错误格式**: `{ "code": 4xx/5xx, "message": "..." }`

## Windows 编码注意

JSON body 中如果包含非 ASCII 字符（中文等），必须先写入临时文件再用 `--data-binary @file` 发送：

```bash
python3 -c "
import json, tempfile, os, subprocess
data = {'key': '中文值'}
tmp = os.path.join(tempfile.gettempdir(), '_nav_admin.json')
with open(tmp, 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False)
" && curl -s -X POST $NAVIGATOR_API_BASE/api/v1/... \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H 'Content-Type: application/json; charset=UTF-8' \
  --data-binary @"$(python3 -c 'import tempfile,os;print(os.path.join(tempfile.gettempdir(),\"_nav_admin.json\"))')" | jq '.data'
```

---

## 1. 平台状态

### 检查初始化状态

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/config/platform/setup-status \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

返回：`{ "gitConfigured": bool, "llmConfigured": bool, "credentialConfigured": bool, "setupComplete": bool }`

---

## 2. 用户管理

### 注册用户

```bash
curl -s -X POST $NAVIGATOR_API_BASE/api/v1/auth/register \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "用户名",
    "password": "密码",
    "email": "email@example.com",
    "displayName": "显示名称"
  }' | jq '.data'
```

### 列出所有用户

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/users \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

### 查看单个用户

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/users/{userId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

### 更新用户

```bash
curl -s -X PUT $NAVIGATOR_API_BASE/api/v1/users/{userId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "new@example.com",
    "displayName": "新名称"
  }' | jq '.data'
```

### 删除用户

```bash
curl -s -X DELETE $NAVIGATOR_API_BASE/api/v1/users/{userId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.'
```

---

## 3. Worker 管理

### 列出当前用户的 Workers

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/claude-workers \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

返回字段：`workerId`, `name`, `baseUrl`, `status`, `hostname`, `workerVersion`

### 注册新 Worker

```bash
curl -s -X POST $NAVIGATOR_API_BASE/api/v1/claude-workers \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Worker 名称",
    "baseUrl": "http://worker-host:3031",
    "authToken": "worker-bearer-token",
    "authMode": "API_KEY"
  }' | jq '.data'
```

`authMode` 可选值：`SUBSCRIPTION` | `API_KEY` | `CUSTOM_ENDPOINT`

### 健康检查

```bash
curl -s -X POST $NAVIGATOR_API_BASE/api/v1/claude-workers/{workerId}/health-check \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

### 更新 Worker

```bash
curl -s -X PUT $NAVIGATOR_API_BASE/api/v1/claude-workers/{workerId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "新名称",
    "baseUrl": "http://new-host:3031"
  }' | jq '.data'
```

### 删除 Worker

```bash
curl -s -X DELETE $NAVIGATOR_API_BASE/api/v1/claude-workers/{workerId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.'
```

---

## 4. 工作目录管理

### 列出 Worker 的工作目录

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/working-directories/worker/{workerId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

返回字段：`directoryId`, `projectName`, `path`, `directoryType`, `gitBranch`, `gitRemoteUrl`

### 创建工作目录

```bash
curl -s -X POST $NAVIGATOR_API_BASE/api/v1/working-directories \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "workerId": "worker-id",
    "projectName": "项目名称",
    "path": "/path/to/project",
    "directoryType": "STANDARD"
  }' | jq '.data'
```

`directoryType` 可选值：
- `STANDARD` — 单个 Git 仓库
- `PROJECT` — 项目组织目录（包含多个子目录）

### 同步 Git 信息

```bash
curl -s -X POST $NAVIGATOR_API_BASE/api/v1/working-directories/{directoryId}/sync \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

### 删除工作目录

```bash
curl -s -X DELETE $NAVIGATOR_API_BASE/api/v1/working-directories/{directoryId} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.'
```

---

## 5. LLM 模型配置

### 列出 LLM 模型

```bash
curl -s "$NAVIGATOR_API_BASE/api/v1/config/platform/llm" \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

可选参数：`?workerId=xxx`（筛选特定 Worker 可见的模型）

### 测试 LLM 连接

```bash
curl -s -X POST $NAVIGATOR_API_BASE/api/v1/config/platform/llm/test-connection \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "测试模型",
    "baseUrl": "https://api.anthropic.com",
    "apiKey": "sk-xxx",
    "modelName": "claude-sonnet-4-20250514"
  }' | jq '.data'
```

### 添加 LLM 模型

```bash
curl -s -X POST $NAVIGATOR_API_BASE/api/v1/config/platform/llm \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "模型名称",
    "category": "CODING",
    "baseUrl": "https://api.anthropic.com",
    "apiKey": "sk-xxx",
    "modelName": "claude-sonnet-4-20250514",
    "isDefault": true,
    "scope": "GLOBAL"
  }' | jq '.data'
```

`category` 可选值：`GENERAL`（通用） | `CODING`（编程） | `REASONING`（推理） | `VISION`（视觉）
`scope` 可选值：`GLOBAL`（所有 Worker 可用） | `RESTRICTED`（需配合 `allowedWorkerIds`）

### 更新 LLM 模型

```bash
curl -s -X PUT $NAVIGATOR_API_BASE/api/v1/config/platform/llm/{id} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "新名称",
    "modelName": "claude-sonnet-4-20250514"
  }' | jq '.data'
```

### 删除 LLM 模型

```bash
curl -s -X DELETE $NAVIGATOR_API_BASE/api/v1/config/platform/llm/{id} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.'
```

---

## 6. API 凭证管理

### 列出凭证

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/config/platform/credentials \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

### 添加凭证

```bash
curl -s -X POST $NAVIGATOR_API_BASE/api/v1/config/platform/credentials \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "凭证名称",
    "category": "LLM",
    "baseUrl": "https://api.example.com",
    "apiKey": "key-xxx",
    "authType": "BEARER",
    "description": "描述"
  }' | jq '.data'
```

`authType` 可选值：`BEARER` | `API_KEY_HEADER` | `CUSTOM`

### 删除凭证

```bash
curl -s -X DELETE $NAVIGATOR_API_BASE/api/v1/config/platform/credentials/{id} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.'
```

---

## 7. Git 提供者配置

### 列出 Git 提供者

```bash
curl -s $NAVIGATOR_API_BASE/api/v1/config/platform/git \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.data'
```

### 添加 Git 提供者

```bash
curl -s -X POST $NAVIGATOR_API_BASE/api/v1/config/platform/git \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "providerType": "GITHUB",
    "accessToken": "ghp_xxx",
    "username": "github-username"
  }' | jq '.data'
```

`providerType` 可选值：`GITHUB` | `GITLAB` | `GITEE` | `GITEA`

自建 GitLab 需要 `baseUrl`：
```json
{
  "providerType": "GITLAB",
  "baseUrl": "https://gitlab.example.com",
  "accessToken": "glpat-xxx"
}
```

### 删除 Git 提供者

```bash
curl -s -X DELETE $NAVIGATOR_API_BASE/api/v1/config/platform/git/{id} \
  -H "Authorization: Bearer $NAVIGATOR_TOKEN" | jq '.'
```

---

## 典型工作流

### 全新系统初始化

1. 检查平台状态 → 确认 Git 和 LLM 未配置
2. 添加 Git 提供者（GitHub/GitLab）
3. 添加 LLM 模型配置（测试连接后保存）
4. 再次检查平台状态 → 确认已完成

### 接入新 Worker

1. 注册 Worker（提供 baseUrl 和 authToken）
2. 健康检查 → 确认 Worker 在线
3. 创建工作目录（指定 Worker 上的项目路径）
4. 同步 Git 信息

### 日常维护

- 列出用户 → 按需创建/删除
- 列出 Workers → 健康检查 → 处理离线 Worker
- 列出 LLM 模型 → 更新配置/切换默认模型

## 注意事项

- 部分操作（如列出所有用户）需要 SUPER_ADMIN 权限
- 删除操作不可逆，请先确认
- API Key 等敏感字段在响应中会被脱敏（显示为 `true/false` 标记而非原文）
