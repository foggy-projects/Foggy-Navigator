# Mock LLM Service 开发规范

## 1. 项目概述

### 1.1 目标

创建一个独立的 Mock LLM 服务，模拟 OpenAI API 响应，用于：
- **集成测试**：提供可预测、可重复的 LLM 响应
- **开发调试**：无需消耗真实 API 配额
- **CI/CD**：支持自动化测试流水线
- **演示环境**：离线运行演示

### 1.2 核心需求

| 需求 | 描述 | 优先级 |
|------|------|--------|
| OpenAI API 兼容 | 实现 `/v1/chat/completions` 端点 | P0 |
| 流式响应 | 支持 SSE 流式输出 | P0 |
| 关键词匹配 | 根据用户消息匹配预定义响应 | P0 |
| YAML 配置 | 响应规则通过 YAML 文件配置 | P0 |
| Docker 部署 | 提供 Dockerfile 和启动脚本 | P0 |
| 管理接口 | 运行时查看/修改响应规则 | P1 |
| 场景脚本 | 支持多轮对话测试场景 | P2 |

### 1.3 技术栈

- **语言**：Python 3.11+
- **框架**：FastAPI
- **部署**：Docker
- **配置**：YAML

---

## 2. 项目结构

```
tools/mock-llm-service/
├── pyproject.toml                 # 项目配置和依赖
├── Dockerfile                     # Docker 镜像定义
├── docker-compose.yml             # Docker Compose 配置
├── start.ps1                      # Windows 启动脚本
├── start.sh                       # Linux/Mac 启动脚本
├── stop.ps1                       # Windows 停止脚本
├── stop.sh                        # Linux/Mac 停止脚本
├── README.md                      # 使用说明
├── src/
│   └── mock_llm/
│       ├── __init__.py
│       ├── main.py                # FastAPI 应用入口
│       ├── config.py              # 配置管理
│       ├── models.py              # Pydantic 数据模型
│       ├── routes/
│       │   ├── __init__.py
│       │   ├── openai.py          # OpenAI 兼容 API
│       │   └── admin.py           # 管理接口
│       ├── strategies/
│       │   ├── __init__.py
│       │   ├── base.py            # 策略基类
│       │   ├── keyword.py         # 关键词匹配策略
│       │   └── default.py         # 默认响应策略
│       ├── store/
│       │   ├── __init__.py
│       │   └── yaml_store.py      # YAML 响应存储
│       └── stream/
│           ├── __init__.py
│           └── sse.py             # SSE 流式输出
├── responses/                      # 预定义响应配置
│   ├── default.yaml               # 默认响应规则
│   └── scenarios/
│       └── tutor-agent.yaml       # Tutor Agent 测试响应
└── tests/
    ├── __init__.py
    ├── test_openai_api.py         # API 测试
    └── test_strategies.py         # 策略测试
```

---

## 3. 核心实现

### 3.1 数据模型 (`models.py`)

```python
from pydantic import BaseModel
from typing import Optional, List, Dict, Any

# ========== OpenAI API 请求/响应模型 ==========

class ChatMessage(BaseModel):
    role: str  # system / user / assistant / tool
    content: str
    name: Optional[str] = None
    tool_call_id: Optional[str] = None

class ChatCompletionRequest(BaseModel):
    model: str
    messages: List[ChatMessage]
    temperature: Optional[float] = 0.7
    max_tokens: Optional[int] = None
    stream: Optional[bool] = False
    tools: Optional[List[Dict[str, Any]]] = None

class ChatChoice(BaseModel):
    index: int
    message: ChatMessage
    finish_reason: str

class Usage(BaseModel):
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int

class ChatCompletionResponse(BaseModel):
    id: str
    object: str = "chat.completion"
    created: int
    model: str
    choices: List[ChatChoice]
    usage: Usage

# ========== 流式响应模型 ==========

class DeltaContent(BaseModel):
    role: Optional[str] = None
    content: Optional[str] = None

class StreamChoice(BaseModel):
    index: int
    delta: DeltaContent
    finish_reason: Optional[str] = None

class ChatCompletionChunk(BaseModel):
    id: str
    object: str = "chat.completion.chunk"
    created: int
    model: str
    choices: List[StreamChoice]

# ========== 响应配置模型 ==========

class MatchRule(BaseModel):
    keywords: Optional[List[str]] = None  # 关键词列表（OR 匹配）
    pattern: Optional[str] = None          # 正则表达式
    default: Optional[bool] = False        # 是否为默认响应

class StreamConfig(BaseModel):
    chunk_size: int = 10                   # 每块字符数
    delay_ms: int = 50                     # 块间延迟（毫秒）

class MockResponseConfig(BaseModel):
    content: str                           # 响应内容
    tool_calls: Optional[List[Dict]] = None

class ResponseRule(BaseModel):
    name: str                              # 规则名称（唯一标识）
    match: MatchRule                       # 匹配规则
    response: MockResponseConfig           # 响应配置
    stream: Optional[StreamConfig] = None  # 流式配置
```

### 3.2 YAML 响应存储 (`store/yaml_store.py`)

```python
import yaml
from pathlib import Path
from typing import List, Optional
from ..models import ResponseRule, MatchRule, MockResponseConfig, StreamConfig

class YamlResponseStore:
    """YAML 文件响应存储"""

    def __init__(self, responses_dir: str = "responses"):
        self.responses_dir = Path(responses_dir)
        self.rules: List[ResponseRule] = []
        self._load_all()

    def _load_all(self):
        """加载所有 YAML 文件"""
        self.rules = []
        if not self.responses_dir.exists():
            return

        for yaml_file in self.responses_dir.glob("**/*.yaml"):
            self._load_file(yaml_file)

    def _load_file(self, file_path: Path):
        """加载单个 YAML 文件"""
        with open(file_path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)

        if not data or "responses" not in data:
            return

        for item in data["responses"]:
            rule = ResponseRule(
                name=item["name"],
                match=MatchRule(**item.get("match", {})),
                response=MockResponseConfig(**item.get("response", {})),
                stream=StreamConfig(**item["stream"]) if "stream" in item else None
            )
            self.rules.append(rule)

    def reload(self):
        """重新加载所有配置"""
        self._load_all()

    def get_rules(self) -> List[ResponseRule]:
        """获取所有规则"""
        return self.rules

    def find_by_name(self, name: str) -> Optional[ResponseRule]:
        """按名称查找规则"""
        for rule in self.rules:
            if rule.name == name:
                return rule
        return None
```

### 3.3 匹配策略 (`strategies/keyword.py`)

```python
import re
from typing import List, Optional
from ..models import ResponseRule, ChatMessage

class KeywordMatchStrategy:
    """关键词匹配策略"""

    def match(self, messages: List[ChatMessage], rules: List[ResponseRule]) -> Optional[ResponseRule]:
        """
        匹配响应规则

        匹配优先级：
        1. 关键词匹配（keywords）
        2. 正则匹配（pattern）
        3. 默认响应（default: true）
        """
        # 获取最后一条用户消息
        user_message = self._get_last_user_message(messages)
        if not user_message:
            return self._get_default_rule(rules)

        content = user_message.content.lower()

        # 1. 关键词匹配
        for rule in rules:
            if rule.match.keywords:
                for keyword in rule.match.keywords:
                    if keyword.lower() in content:
                        return rule

        # 2. 正则匹配
        for rule in rules:
            if rule.match.pattern:
                if re.search(rule.match.pattern, content, re.IGNORECASE):
                    return rule

        # 3. 默认响应
        return self._get_default_rule(rules)

    def _get_last_user_message(self, messages: List[ChatMessage]) -> Optional[ChatMessage]:
        """获取最后一条用户消息"""
        for msg in reversed(messages):
            if msg.role == "user":
                return msg
        return None

    def _get_default_rule(self, rules: List[ResponseRule]) -> Optional[ResponseRule]:
        """获取默认规则"""
        for rule in rules:
            if rule.match.default:
                return rule
        return None
```

### 3.4 SSE 流式输出 (`stream/sse.py`)

```python
import json
import asyncio
import time
import uuid
from typing import AsyncGenerator
from ..models import ChatCompletionChunk, StreamChoice, DeltaContent, StreamConfig

async def generate_sse_stream(
    content: str,
    model: str,
    config: StreamConfig = None
) -> AsyncGenerator[str, None]:
    """
    生成 SSE 流式响应

    格式与 OpenAI API 完全兼容：
    data: {"id":"...","object":"chat.completion.chunk","choices":[{"delta":{"content":"..."}}]}
    """
    if config is None:
        config = StreamConfig()

    chunk_id = f"chatcmpl-{uuid.uuid4().hex[:8]}"
    created = int(time.time())

    # 发送 role
    yield _format_chunk(ChatCompletionChunk(
        id=chunk_id,
        created=created,
        model=model,
        choices=[StreamChoice(
            index=0,
            delta=DeltaContent(role="assistant"),
            finish_reason=None
        )]
    ))

    # 分块发送内容
    for i in range(0, len(content), config.chunk_size):
        chunk_content = content[i:i + config.chunk_size]
        yield _format_chunk(ChatCompletionChunk(
            id=chunk_id,
            created=created,
            model=model,
            choices=[StreamChoice(
                index=0,
                delta=DeltaContent(content=chunk_content),
                finish_reason=None
            )]
        ))
        await asyncio.sleep(config.delay_ms / 1000)

    # 发送结束标记
    yield _format_chunk(ChatCompletionChunk(
        id=chunk_id,
        created=created,
        model=model,
        choices=[StreamChoice(
            index=0,
            delta=DeltaContent(),
            finish_reason="stop"
        )]
    ))

    # 发送 [DONE]
    yield "data: [DONE]\n\n"

def _format_chunk(chunk: ChatCompletionChunk) -> str:
    """格式化为 SSE 数据行"""
    return f"data: {chunk.model_dump_json()}\n\n"
```

### 3.5 OpenAI 兼容路由 (`routes/openai.py`)

```python
import time
import uuid
from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse
from ..models import (
    ChatCompletionRequest, ChatCompletionResponse,
    ChatChoice, ChatMessage, Usage, StreamConfig
)
from ..store.yaml_store import YamlResponseStore
from ..strategies.keyword import KeywordMatchStrategy
from ..stream.sse import generate_sse_stream

router = APIRouter()

# 全局存储和策略（由 main.py 注入）
response_store: YamlResponseStore = None
match_strategy: KeywordMatchStrategy = None

def init_router(store: YamlResponseStore, strategy: KeywordMatchStrategy):
    global response_store, match_strategy
    response_store = store
    match_strategy = strategy

@router.post("/v1/chat/completions")
async def chat_completions(request: ChatCompletionRequest):
    """
    OpenAI Chat Completions API 兼容端点

    支持：
    - 同步响应
    - 流式响应（stream=true）
    - 关键词匹配
    """
    # 匹配响应规则
    rule = match_strategy.match(request.messages, response_store.get_rules())

    if rule is None:
        content = "Mock LLM: No matching response rule found."
    else:
        content = rule.response.content

    # 流式响应
    if request.stream:
        stream_config = rule.stream if rule and rule.stream else StreamConfig()
        return StreamingResponse(
            generate_sse_stream(content, request.model, stream_config),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no"
            }
        )

    # 同步响应
    return ChatCompletionResponse(
        id=f"chatcmpl-{uuid.uuid4().hex[:8]}",
        created=int(time.time()),
        model=request.model,
        choices=[ChatChoice(
            index=0,
            message=ChatMessage(role="assistant", content=content),
            finish_reason="stop"
        )],
        usage=Usage(
            prompt_tokens=_estimate_tokens(request.messages),
            completion_tokens=len(content) // 4,
            total_tokens=_estimate_tokens(request.messages) + len(content) // 4
        )
    )

def _estimate_tokens(messages: list) -> int:
    """估算 token 数量"""
    return sum(len(m.content) // 4 for m in messages)
```

### 3.6 管理接口 (`routes/admin.py`)

```python
from fastapi import APIRouter
from typing import List
from ..models import ResponseRule
from ..store.yaml_store import YamlResponseStore

router = APIRouter(prefix="/admin", tags=["Admin"])

response_store: YamlResponseStore = None

def init_router(store: YamlResponseStore):
    global response_store
    response_store = store

@router.get("/responses", response_model=List[ResponseRule])
async def list_responses():
    """列出所有响应规则"""
    return response_store.get_rules()

@router.post("/reload")
async def reload_responses():
    """重新加载响应配置"""
    response_store.reload()
    return {"message": "Responses reloaded", "count": len(response_store.get_rules())}

@router.get("/health")
async def health_check():
    """健康检查"""
    return {"status": "ok", "rules_count": len(response_store.get_rules())}
```

### 3.7 应用入口 (`main.py`)

```python
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from .store.yaml_store import YamlResponseStore
from .strategies.keyword import KeywordMatchStrategy
from .routes import openai, admin
from .config import settings

app = FastAPI(
    title="Mock LLM Service",
    description="OpenAI API 兼容的 Mock LLM 服务，用于测试和开发",
    version="1.0.0"
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 初始化存储和策略
response_store = YamlResponseStore(settings.responses_dir)
match_strategy = KeywordMatchStrategy()

# 注入到路由
openai.init_router(response_store, match_strategy)
admin.init_router(response_store)

# 注册路由
app.include_router(openai.router)
app.include_router(admin.router)

@app.on_event("startup")
async def startup():
    print(f"Mock LLM Service started")
    print(f"Loaded {len(response_store.get_rules())} response rules")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=settings.port)
```

### 3.8 配置 (`config.py`)

```python
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    port: int = 8200
    responses_dir: str = "responses"
    log_level: str = "INFO"

    class Config:
        env_prefix = "MOCK_LLM_"

settings = Settings()
```

---

## 4. 响应配置格式

### 4.1 基本格式 (`responses/default.yaml`)

```yaml
responses:
  # 规则 1：关键词匹配
  - name: "greeting"
    match:
      keywords: ["你好", "hello", "hi"]
    response:
      content: "你好！我是 Mock LLM 服务，有什么可以帮助您的？"
    stream:
      chunk_size: 5
      delay_ms: 30

  # 规则 2：正则匹配
  - name: "help-request"
    match:
      pattern: "帮.*我|help.*me"
    response:
      content: "当然，我很乐意帮助您。请告诉我具体需要什么帮助？"

  # 规则 3：默认响应（必须有且只有一个）
  - name: "default"
    match:
      default: true
    response:
      content: |
        收到您的消息。

        我是 Mock LLM 服务，用于测试和开发。
        当前没有匹配的响应规则。
```

### 4.2 Tutor Agent 测试响应 (`responses/scenarios/tutor-agent.yaml`)

```yaml
responses:
  # 数据源配置引导
  - name: "guide-datasource"
    match:
      keywords: ["配置数据源", "连接数据库", "添加数据源", "数据库连接"]
    response:
      content: |
        好的，我来帮您配置数据源。

        请选择数据库类型：
        1. MySQL
        2. PostgreSQL
        3. Oracle
        4. SQL Server

        请告诉我您要连接的数据库类型。
    stream:
      chunk_size: 15
      delay_ms: 40

  # 选择 MySQL
  - name: "mysql-selected"
    match:
      keywords: ["mysql", "MySQL"]
    response:
      content: |
        好的，您选择了 MySQL 数据库。

        请提供以下连接信息：
        - 主机地址（如：localhost 或 192.168.1.100）
        - 端口号（默认 3306）
        - 数据库名称
        - 用户名
        - 密码

        您可以按格式提供：主机:端口/数据库名 用户名 密码

  # 语义层生成引导
  - name: "guide-semantic-layer"
    match:
      keywords: ["语义层", "生成模型", "semantic"]
    response:
      content: |
        我将帮您生成语义层模型。

        语义层是数据库结构的业务抽象层，让用户可以用自然语言查询数据。

        当前数据源状态：
        - sales_db (MySQL) - localhost:3306 ✓

        是否使用此数据源生成语义层？

  # 系统状态检查
  - name: "check-status"
    match:
      keywords: ["检查状态", "系统状态", "配置进度"]
      pattern: "检查.*状态|状态.*检查"
    response:
      content: |
        **系统配置状态**

        | 项目 | 状态 |
        |------|------|
        | 数据源 | ✅ 已配置 (sales_db) |
        | 语义层 | ⏳ 待生成 |
        | 权限 | ❌ 未配置 |

        建议下一步：生成语义层

  # 下一步建议
  - name: "suggest-next"
    match:
      keywords: ["下一步", "接下来", "建议"]
    response:
      content: |
        **根据您当前的配置进度，建议下一步：**

        📋 **生成语义层**

        **为什么要做**：
        语义层是连接数据库和 AI 分析的桥梁。生成后，您的用户就可以用自然语言查询数据。

        **如何操作**：
        说"生成语义层"，我会自动分析数据库结构并生成模型。

        是否开始生成语义层？
```

---

## 5. Docker 配置

### 5.1 Dockerfile

```dockerfile
FROM python:3.11-slim

WORKDIR /app

# 安装依赖
COPY pyproject.toml .
RUN pip install --no-cache-dir .

# 复制代码和配置
COPY src/ src/
COPY responses/ responses/

# 环境变量
ENV MOCK_LLM_PORT=8200
ENV MOCK_LLM_RESPONSES_DIR=/app/responses

EXPOSE 8200

CMD ["python", "-m", "mock_llm.main"]
```

### 5.2 docker-compose.yml

```yaml
version: '3.8'

services:
  mock-llm:
    build: .
    image: foggy/mock-llm-service:latest
    container_name: mock-llm-service
    ports:
      - "8200:8200"
    volumes:
      # 挂载响应配置，支持热更新
      - ./responses:/app/responses:ro
    environment:
      - MOCK_LLM_PORT=8200
      - MOCK_LLM_LOG_LEVEL=INFO
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8200/admin/health"]
      interval: 10s
      timeout: 5s
      retries: 3
    restart: unless-stopped
```

### 5.3 pyproject.toml

```toml
[project]
name = "mock-llm-service"
version = "1.0.0"
description = "OpenAI API 兼容的 Mock LLM 服务"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.109.0",
    "uvicorn[standard]>=0.27.0",
    "pydantic>=2.5.0",
    "pydantic-settings>=2.1.0",
    "pyyaml>=6.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=7.4.0",
    "pytest-asyncio>=0.23.0",
    "httpx>=0.26.0",
]

[build-system]
requires = ["setuptools>=61.0"]
build-backend = "setuptools.build_meta"

[tool.setuptools.packages.find]
where = ["src"]
```

---

## 6. 启动/停止脚本

### 6.1 Windows 启动脚本 (`start.ps1`)

```powershell
# Mock LLM Service 启动脚本 (Windows)
# 自动构建并启动 Docker 容器

param(
    [switch]$Rebuild,      # 强制重新构建镜像
    [switch]$Detach = $true # 后台运行（默认）
)

$ErrorActionPreference = "Stop"
$ServiceName = "mock-llm-service"
$ImageName = "foggy/mock-llm-service:latest"
$Port = 8200

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Mock LLM Service Launcher" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 进入脚本所在目录
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# 检查 Docker
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "[ERROR] Docker not found. Please install Docker Desktop." -ForegroundColor Red
    exit 1
}

# 停止已存在的容器
$existingContainer = docker ps -aq -f "name=$ServiceName"
if ($existingContainer) {
    Write-Host "[INFO] Stopping existing container..." -ForegroundColor Yellow
    docker stop $ServiceName 2>$null
    docker rm $ServiceName 2>$null
}

# 构建镜像
$needBuild = $Rebuild -or (-not (docker images -q $ImageName))
if ($needBuild) {
    Write-Host "[INFO] Building Docker image..." -ForegroundColor Yellow
    docker build -t $ImageName .
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] Docker build failed!" -ForegroundColor Red
        exit 1
    }
    Write-Host "[OK] Image built successfully" -ForegroundColor Green
}

# 启动容器
Write-Host "[INFO] Starting container..." -ForegroundColor Yellow

$dockerArgs = @(
    "run"
    "--name", $ServiceName
    "-p", "${Port}:8200"
    "-v", "${ScriptDir}/responses:/app/responses:ro"
    "-e", "MOCK_LLM_LOG_LEVEL=INFO"
)

if ($Detach) {
    $dockerArgs += "-d"
}

$dockerArgs += $ImageName

docker @dockerArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Failed to start container!" -ForegroundColor Red
    exit 1
}

# 等待服务就绪
Write-Host "[INFO] Waiting for service to be ready..." -ForegroundColor Yellow
$maxRetries = 30
$retries = 0
while ($retries -lt $maxRetries) {
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:$Port/admin/health" -UseBasicParsing -TimeoutSec 2
        if ($response.StatusCode -eq 200) {
            break
        }
    } catch {
        # 继续等待
    }
    Start-Sleep -Seconds 1
    $retries++
}

if ($retries -eq $maxRetries) {
    Write-Host "[ERROR] Service failed to start!" -ForegroundColor Red
    docker logs $ServiceName
    exit 1
}

# 显示信息
Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Mock LLM Service Started!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "  API Endpoint:  http://localhost:$Port/v1/chat/completions" -ForegroundColor White
Write-Host "  Admin API:     http://localhost:$Port/admin/responses" -ForegroundColor White
Write-Host "  Health Check:  http://localhost:$Port/admin/health" -ForegroundColor White
Write-Host ""
Write-Host "  Stop command:  .\stop.ps1" -ForegroundColor Gray
Write-Host ""

# 显示加载的规则数
$health = Invoke-RestMethod -Uri "http://localhost:$Port/admin/health"
Write-Host "[INFO] Loaded $($health.rules_count) response rules" -ForegroundColor Cyan
```

### 6.2 Windows 停止脚本 (`stop.ps1`)

```powershell
# Mock LLM Service 停止脚本 (Windows)

$ServiceName = "mock-llm-service"

Write-Host "[INFO] Stopping Mock LLM Service..." -ForegroundColor Yellow

$container = docker ps -aq -f "name=$ServiceName"
if ($container) {
    docker stop $ServiceName
    docker rm $ServiceName
    Write-Host "[OK] Service stopped" -ForegroundColor Green
} else {
    Write-Host "[INFO] Service is not running" -ForegroundColor Gray
}
```

### 6.3 Linux/Mac 启动脚本 (`start.sh`)

```bash
#!/bin/bash
# Mock LLM Service 启动脚本 (Linux/Mac)

set -e

SERVICE_NAME="mock-llm-service"
IMAGE_NAME="foggy/mock-llm-service:latest"
PORT=8200
REBUILD=false

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --rebuild) REBUILD=true; shift ;;
        *) shift ;;
    esac
done

echo "========================================"
echo "  Mock LLM Service Launcher"
echo "========================================"

# 进入脚本目录
cd "$(dirname "$0")"

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo "[ERROR] Docker not found. Please install Docker."
    exit 1
fi

# 停止已存在的容器
if docker ps -aq -f "name=$SERVICE_NAME" | grep -q .; then
    echo "[INFO] Stopping existing container..."
    docker stop $SERVICE_NAME 2>/dev/null || true
    docker rm $SERVICE_NAME 2>/dev/null || true
fi

# 构建镜像
if [ "$REBUILD" = true ] || ! docker images -q $IMAGE_NAME | grep -q .; then
    echo "[INFO] Building Docker image..."
    docker build -t $IMAGE_NAME .
    echo "[OK] Image built successfully"
fi

# 启动容器
echo "[INFO] Starting container..."
docker run -d \
    --name $SERVICE_NAME \
    -p $PORT:8200 \
    -v "$(pwd)/responses:/app/responses:ro" \
    -e MOCK_LLM_LOG_LEVEL=INFO \
    $IMAGE_NAME

# 等待服务就绪
echo "[INFO] Waiting for service to be ready..."
for i in {1..30}; do
    if curl -s "http://localhost:$PORT/admin/health" > /dev/null 2>&1; then
        break
    fi
    sleep 1
done

# 检查是否启动成功
if ! curl -s "http://localhost:$PORT/admin/health" > /dev/null 2>&1; then
    echo "[ERROR] Service failed to start!"
    docker logs $SERVICE_NAME
    exit 1
fi

echo ""
echo "========================================"
echo "  Mock LLM Service Started!"
echo "========================================"
echo ""
echo "  API Endpoint:  http://localhost:$PORT/v1/chat/completions"
echo "  Admin API:     http://localhost:$PORT/admin/responses"
echo "  Health Check:  http://localhost:$PORT/admin/health"
echo ""
echo "  Stop command:  ./stop.sh"
echo ""

# 显示加载的规则数
RULES=$(curl -s "http://localhost:$PORT/admin/health" | grep -o '"rules_count":[0-9]*' | cut -d: -f2)
echo "[INFO] Loaded $RULES response rules"
```

### 6.4 Linux/Mac 停止脚本 (`stop.sh`)

```bash
#!/bin/bash
# Mock LLM Service 停止脚本 (Linux/Mac)

SERVICE_NAME="mock-llm-service"

echo "[INFO] Stopping Mock LLM Service..."

if docker ps -aq -f "name=$SERVICE_NAME" | grep -q .; then
    docker stop $SERVICE_NAME
    docker rm $SERVICE_NAME
    echo "[OK] Service stopped"
else
    echo "[INFO] Service is not running"
fi
```

---

## 7. 使用指南

### 7.1 启动服务

```bash
# Windows
cd tools/mock-llm-service
.\start.ps1

# Linux/Mac
cd tools/mock-llm-service
chmod +x start.sh stop.sh
./start.sh

# 强制重新构建
.\start.ps1 -Rebuild   # Windows
./start.sh --rebuild   # Linux/Mac
```

### 7.2 配置 Foggy Navigator 使用 Mock 服务

```yaml
# launcher/src/main/resources/application-test.yml
agent:
  llm:
    openai:
      base-url: http://localhost:8200/v1
      api-key: mock-key  # 任意值
```

### 7.3 测试 API

```bash
# 健康检查
curl http://localhost:8200/admin/health

# 查看响应规则
curl http://localhost:8200/admin/responses

# 测试对话（同步）
curl -X POST http://localhost:8200/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "mock-model",
    "messages": [{"role": "user", "content": "配置数据源"}]
  }'

# 测试对话（流式）
curl -X POST http://localhost:8200/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "mock-model",
    "messages": [{"role": "user", "content": "你好"}],
    "stream": true
  }'

# 重新加载配置
curl -X POST http://localhost:8200/admin/reload
```

### 7.4 添加新的响应规则

1. 在 `responses/` 目录下创建或编辑 YAML 文件
2. 调用 `/admin/reload` 接口重新加载
3. 或重启服务

---

## 8. 验收标准

### 8.1 功能验收

- [ ] `/v1/chat/completions` 端点正常工作
- [ ] 同步响应返回正确格式
- [ ] 流式响应返回 SSE 格式
- [ ] 关键词匹配正确触发对应响应
- [ ] 正则匹配正确工作
- [ ] 默认响应在无匹配时返回
- [ ] `/admin/health` 返回健康状态
- [ ] `/admin/responses` 返回所有规则
- [ ] `/admin/reload` 能重新加载配置

### 8.2 Docker 验收

- [ ] `start.ps1` / `start.sh` 能自动构建并启动
- [ ] 容器健康检查通过
- [ ] 响应配置文件挂载正确
- [ ] `stop.ps1` / `stop.sh` 能正确停止服务

### 8.3 集成验收

- [ ] Foggy Navigator 配置 Mock 服务后能正常对话
- [ ] SSE 流式输出在前端正确渲染

---

## 9. 后续扩展（可选）

以下功能为 P2，本期不要求实现：

1. **场景脚本**：支持多轮对话的状态机
2. **录制回放**：代理真实请求并录制响应
3. **请求日志**：记录所有请求用于调试
4. **Web UI**：可视化管理响应规则
5. **延迟模拟**：模拟网络延迟和超时

---

## 10. 联系方式

开发完成后，请通知主会话进行集成测试验收。

验收时需要：
1. 启动 Mock LLM 服务
2. 配置 Foggy Navigator 使用 Mock 服务
3. 运行 session-module 集成测试
4. 验证 Skill 匹配和响应流程
