# LangGraph Biz Worker

基于 LangGraph 的业务型 Worker，为 Foggy Navigator 提供**受控业务执行后端**。

与编程型 Worker（claude-worker / codex-worker）不同，Biz Worker 专注于：

- 受控工具调用与结构化结果
- Skill 按需加载、独立 Frame 隔离执行
- 显式交卷协议（`submit_skill_result`）
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
  │   route_skill → open_frame → run_skill → close_frame │
  │                                  │                    │
  │                    ┌─────────────┘                    │
  │                    ▼                                  │
  │              Skill Subgraph                           │
  │   ┌──────────────────────────────────┐               │
  │   │ gather_evidence → analyze        │               │
  │   │   ├── child_skill_1 (Frame B)   │               │
  │   │   ├── child_skill_2 (Frame C)   │               │
  │   │   └── aggregate → submit_result │               │
  │   └──────────────────────────────────┘               │
  │                                                       │
  │  SkillRuntime ← 状态机 + 完成协议 + 上下文隔离       │
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
PYTHONPATH=src .venv/Scripts/python -m uvicorn langgraph_biz_worker.main:app --port 3032

# 4. 验证
curl http://localhost:3032/health
```

## 配置

所有配置通过环境变量加载，前缀 `BIZ_WORKER_`，或写入 `.env` 文件。

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `BIZ_WORKER_PORT` | 3032 | 服务端口 |
| `BIZ_WORKER_HOST` | 0.0.0.0 | 监听地址 |
| `BIZ_WORKER_WORKER_TOKEN` | （空） | Bearer Token，空=开发模式免认证 |
| `BIZ_WORKER_WORKER_NAME` | （空） | Worker 显示名称 |
| `BIZ_WORKER_MAX_CONCURRENT_TASKS` | 5 | 最大并发任务数 |
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
