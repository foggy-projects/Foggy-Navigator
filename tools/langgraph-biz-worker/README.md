# LangGraph Biz Worker

基于 LangGraph 的业务型 Worker，为 Foggy Navigator 提供**受控业务执行后端**。

与编程型 Worker（claude-worker / codex-worker）不同，Biz Worker 专注于：

- 受控工具调用与结构化结果
- Skill 按需加载并进入当前 frame 的 tool protocol
- 显式 Agent frame 的独立生命周期、`submit_frame_result` 结构化完成与 handoff
- 审批中断与恢复（Phase 6）
- 可审计的执行痕迹

## 架构概览

```
Navigator (Java)
  │
  │  POST /api/v1/query (SSE)
  ▼
LangGraph Biz Worker (FastAPI + LangGraph)
  │
  ├─ Root Graph ─────────────────────────────────────────┐
  │   persistent root frame                               │
  │     ├─ invoke_business_skill → load Skill material    │
  │     ├─ invoke_business_function / command / files     │
  │     ├─ natural final for ordinary turns               │
  │     └─ invoke_business_agent → isolated Agent frame   │
  │                                                       │
  │  SkillRuntime ← Frame 状态机 + 完成/暂停 + 上下文隔离 │
  │  FrameStore   ← Frame 持久化（首版内存）              │
  │  SkillRegistry← Manifest 加载（YAML）                │
  │  OutputContract← 三层校验（schema/business/state）    │
  └───────────────────────────────────────────────────────┘
```

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| Web 框架 | FastAPI + Uvicorn | >= 0.115.0 |
| 图执行引擎 | LangGraph | >= 0.4.0 |
| LLM 基础库 | LangChain Core | >= 0.3.0 |
| SSE 推送 | sse-starlette | >= 2.0.0 |
| 配置管理 | pydantic-settings | >= 2.0.0 |
| Manifest 格式 | YAML (PyYAML) | >= 6.0 |
| 语言 | Python | >= 3.10 |

## 快速开始

```bash
cd tools/langgraph-biz-worker

# 1. 创建虚拟环境并安装
python -m venv .venv
.venv/Scripts/pip install -e ".[dev]"   # Windows
# .venv/bin/pip install -e ".[dev]"     # Linux/Mac

# 2. 运行测试
PYTHONPATH=src .venv/Scripts/python -m pytest tests/ -v

# 3. 启动服务
powershell -ExecutionPolicy Bypass -File start.ps1
# 或直接启动：
PYTHONPATH=src .venv/Scripts/python -m uvicorn langgraph_biz_worker.main:app --port 3061

# 4. 验证
curl http://localhost:3061/health
```

## 配置

所有配置通过环境变量加载，前缀 `BIZ_WORKER_`，或写入 `.env` 文件。

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `BIZ_WORKER_PORT` | 3061 | 服务端口 |
| `BIZ_WORKER_HOST` | 0.0.0.0 | 监听地址 |
| `BIZ_WORKER_WORKER_TOKEN` | （空） | Bearer Token，空=开发模式免认证 |
| `BIZ_WORKER_WORKER_NAME` | （空） | Worker 显示名称 |
| `BIZ_WORKER_MAX_CONCURRENT_TASKS` | 5 | 最大并发任务数 |
| `BIZ_WORKER_LLM_PROVIDER` | （空） | `openai` / `anthropic`，空=禁用 LLM |
| `BIZ_WORKER_LLM_BASE_URL` | （空） | 自定义 LLM API 地址，可指向 `tools/mock-llm-service` |
| `BIZ_WORKER_LLM_EXECUTE_SKILLS` | false | 启用 LLM tool-call loop 执行 Skill |
| `BIZ_WORKER_LLM_SKILL_MAX_ITERATIONS` | 6 | 单个 Skill 最大模型轮次 |
| `BIZ_WORKER_ENABLE_COMMAND` | true | Linux worker 上启用真实 `command` 工具；仍需可写 delegated workspace、合法 `workdir` 和 `allowed_dirs` |
| `BIZ_WORKER_NAVIGATOR_API_BASE` | http://localhost:8112 | Navigator 平台地址 |

## API

| 端点 | 方法 | 认证 | 说明 |
|------|------|------|------|
| `/health` | GET | 无 | 健康检查 |
| `/api/v1/query` | POST | Bearer | 业务查询（SSE 流式返回） |

### SSE 事件类型

| 事件 | 说明 | 阶段 |
|------|------|------|
| `system` | 运行时状态消息 | Phase 1+ |
| `assistant_text` | 文本输出 | Phase 1+ |
| `result` | 最终结果（含 structured_output） | Phase 1+ |
| `error` | 错误 | Phase 1+ |
| `skill_frame_open` | Skill Frame 创建 | Phase 2+ |
| `skill_frame_close` | Skill Frame 关闭 | Phase 2+ |
| `approval_request` | 审批请求（预留） | Phase 6 |

## 测试

```bash
# 运行全部测试（103 个）
PYTHONPATH=src .venv/Scripts/python -m pytest tests/ -v

# 按模块运行
pytest tests/test_frame_lifecycle.py -v    # Frame 状态机
pytest tests/test_output_contract.py -v    # 输出校验
pytest tests/test_single_skill.py -v       # 单 Skill E2E
pytest tests/test_multi_child_aggregation.py -v  # 多子聚合
pytest tests/test_auth.py -v               # 认证
pytest tests/test_rate_limit.py -v         # 并发限制
pytest tests/test_llm_skill_agent.py -v    # LLM tool-call Skill 执行
```

### 使用 Mock LLM 联调 Skill

1. 启动 `tools/mock-llm-service`。
2. 配置 Worker：

```env
BIZ_WORKER_LLM_PROVIDER=openai
BIZ_WORKER_LLM_API_KEY=mock-key
BIZ_WORKER_LLM_BASE_URL=http://localhost:8200/v1
BIZ_WORKER_LLM_MODEL=mock-model
BIZ_WORKER_LLM_EXECUTE_SKILLS=true
```

`mock-llm-service/responses/scenarios/langgraph-biz-worker-skill.yaml` 已内置一条结构化 frame 完成链路：路由到 `exception_triage`，依次调用 `mock_get_order`、`mock_get_vehicle_status`，最后调用 frame result 工具。新提示和 manifest 使用 `submit_frame_result`；`submit_skill_result` 仍作为旧脚本兼容别名保留。普通 Root 回合或顶层工具 smoke 可以直接用自然语言结束，不要求调用该工具。

也可以通过 `BIZ_WORKER_ENV_FILE` 或启动参数切换配置：

```powershell
# Mock LLM
$env:BIZ_WORKER_ENV_FILE=".env.mock-llm.local"

# 真实 OpenAI-compatible LLM
$env:BIZ_WORKER_ENV_FILE=".env.real-llm.local"

# 启动服务时指定
powershell -ExecutionPolicy Bypass -File start.ps1 -EnvFile .env.real-llm.local
```

## 目录结构

```
tools/langgraph-biz-worker/
├── src/langgraph_biz_worker/
│   ├── main.py                 # FastAPI 应用入口
│   ├── config.py               # 配置（pydantic-settings）
│   ├── auth.py                 # Bearer Token 认证
│   ├── models.py               # 数据模型（Frame/Manifest/Event/状态机）
│   ├── routes/
│   │   ├── health.py           # GET /health
│   │   └── query.py            # POST /api/v1/query (SSE)
│   ├── runtime/
│   │   ├── skill_runtime.py    # 核心：Frame 生命周期管理
│   │   ├── frame_store.py      # Frame 存储（内存实现）
│   │   ├── skill_registry.py   # Skill Manifest 注册中心
│   │   └── output_contract.py  # 输出契约三层校验
│   ├── graphs/
│   │   ├── root_graph.py       # Root Graph（路由 + 编排）
│   │   └── skills/             # Skill 子图
│   │       ├── exception_triage.py
│   │       ├── order_evidence_collect.py
│   │       └── rule_check.py
│   ├── manifests/              # Skill Manifest（YAML）
│   │   ├── exception_triage.yaml
│   │   ├── order_evidence_collect.yaml
│   │   └── rule_check.yaml
│   └── tools/
│       └── mock_biz_tools.py   # Mock 业务工具
├── tests/                      # 103 个测试
├── docs/                       # 设计文档
├── start.ps1 / stop.ps1        # 启停脚本
├── pyproject.toml
└── .env.example
```

## 文档索引

- [设计文档](docs/architecture.md) — 核心概念、Frame 生命周期、完成协议
- [版本规划](../../docs/version-tracker/1.1.0-SNAPSHOT/) — 1.1.0 版本跟踪
