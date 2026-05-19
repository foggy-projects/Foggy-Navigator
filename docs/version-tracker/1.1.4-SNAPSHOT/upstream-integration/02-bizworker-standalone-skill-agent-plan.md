# BizWorker Standalone Skill Agent Plan

## 文档作用

- doc_type: workitem | implementation-plan | architecture-plan
- version: 1.1.4-SNAPSHOT
- status: accepted-for-planning
- date: 2026-05-19
- source_type: integration-design-follow-up
- source: [01-worker-skill-name-boundary-decision.md](./01-worker-skill-name-boundary-decision.md)
- intended_for: BizWorker maintainer | Navigator maintainer | Python integration developer | reviewer
- purpose: 规划 BizWorker 从 Navigator Java 远程执行器升级为可独立集成的 Python Skill Agent Runtime

## 背景

`skill_name` 已确认定义为 Skill 文件夹名称，即 `.codex/skills`、`.claude/skills` 或 BizWorker 本地 `skills/<skill_name>/` 的目录 basename。

当前 LangGraph BizWorker 主要服务 Navigator 企业集成链路：

```text
TMS / upstream
-> Navigator Java OpenAPI
-> BusinessAgentTaskService
-> LanggraphBusinessAgentWorkerTaskLauncher
-> Python LangGraph BizWorker
```

这条链路需要 ClientApp、upstream user、task-scoped token、function grant、审计、会话等复杂控制面。但其他 Python 项目接入 BizWorker 时，很多场景只需要一个“具备 Skill 能力和 Skill 治理能力的 Agent”：

```text
Python app
-> BizWorker Skill Agent
   -> load local skills
   -> run skill_name
   -> call local Python tools or HTTP tools
   -> return result or stream events
```

因此下一阶段目标是把 BizWorker 的 core runtime 从 Navigator 企业模型中拆出来，让 Navigator 只是一个 enterprise adapter。

## 目标

1. BizWorker 可以作为独立 Python Skill Agent 使用，不依赖 Navigator Java 启动任务。
2. 普通 Python 项目可用极低成本集成：本地 skills、本地 tools、简单 ask/run API。
3. BizWorker service mode 提供 Skill 治理能力：注册、读取、更新、删除、启用、禁用、校验、运行。
4. Navigator/TMS 场景继续可用企业 adapter：Java 负责授权、token、审计，Worker 负责 skill runtime。
5. LLM 默认只理解业务说明和可用能力，不承担 Java `skillId` 或企业授权模型。

## 非目标

- 不把 Navigator 的 ClientApp、tenant、grant、task token 模型塞进 BizWorker core。
- 不要求普通 Python 项目部署 Navigator Java。
- 不在首阶段解决所有多租户企业治理问题。
- 不立即重命名 Java 数据库字段。
- 不要求所有已有 BizWorker frame/runtime 实现一次性重写。

## Glossary and Compatibility Aliases

| Term | Status | Meaning |
| --- | --- | --- |
| `skill_name` | target canonical | Worker / Python / HTTP JSON 外部契约使用的 Skill 身份，等于 folder basename |
| `skillName` | Java-side property alias | Java 代码属性可保留 camelCase，但发送给 BizWorker 的 JSON 字段应为 `skill_name` |
| `skill_id` | legacy internal alias | 当前 Python frame/runtime/router 中大量存在的历史字段，Phase 1 不做全局改名，值语义收敛为 `skill_name` |
| Java `skillId` | legacy enterprise field | Navigator Java DB、授权、历史 OpenAPI/SDK 字段，后续逐步映射到 `skill_name` |
| `displayName` | presentation | UI/LLM 可读名称，不参与路径、授权或 manifest 主键 |
| `SkillAgent` | target facade | Python embedding 的简单入口，封装 load skill、ask/run、tool provider |
| `SkillRuntime` | existing core | 当前 frame 执行、事件、恢复、状态管理能力，Phase 1 以包装复用为主 |
| `ToolProvider` | plugin boundary | 工具发现和调用边界，允许 local-python、mock、navigator、http 等实现 |
| `ModelProvider` | optional adapter | LLM 配置/模型注入边界，Phase 1 可先明确注入方式，不强制完整抽象 |

## Compatibility Constraint

当前 `tools/langgraph-biz-worker` 中 `skill_id` 已贯穿 frame state、LLM router、tool schema、resource tools、service route 和测试用例。1.1.4 首阶段目标不是清理所有历史字段，而是先建立新的外部契约：

- standalone API、Python embedding API 和新增文档统一使用 `skill_name`。
- 内部执行层可以继续使用 `skill_id`，但语义上应映射为 skill folder basename。
- 兼容入口可以同时接受 `skill_name`、`skillName`、`skill_id` 与 `skillId`；多个非空 alias 值相同则接受并归一为 `skill_name`，不同则返回明确校验错误。
- 新 prompt 不应要求 LLM 输出 Java `skillId`；多 skill 委派如仍复用旧 tool schema，需要在 schema 描述中说明值语义是 `skill_name`。
- Java 侧历史 `skillId` 字段不在 Phase 1 全局改名，只在 launch protocol 中补充 Java `skillName` / JSON `skill_name`。

## 设计原则

### Simple Runtime First

Standalone 模式默认面向简单 Python 项目：

- 没有 tenant 也能运行。
- 没有 ClientApp 也能运行。
- 没有 task-scoped token 也能运行。
- 可以只用本地 `SKILL.md` 和本地 Python 函数。

### Enterprise Adapter Optional

Navigator 企业集成作为 adapter：

- Java 负责 `rootAgentId -> skill_name`。
- Java 负责 ClientApp / upstream user / grant / token / audit。
- Worker 只消费 `skill_name`、materialized skill、runtime context 和 tool provider。

### Folder Basename Is Identity

`skill_name` 是 Worker runtime canonical identity：

```text
skills/order-assistant/SKILL.md
skills/tms-navigator-agent/SKILL.md
```

frontmatter 中的 `display_name` 只用于 UI/LLM 可读展示，不参与唯一性。

### Tool Provider Plugin Boundary

工具调用不绑定 Navigator：

| Provider | Usage |
| --- | --- |
| `local-python` | Python 项目注册本地函数 |
| `http` | 调用普通 REST API |
| `navigator` | Navigator enterprise adapter，使用 task-scoped token 走 Worker Gateway |
| `mock` | 单测、脚本测试、开发演示 |

## Target Architecture

```text
bizworker-core
  SkillAgent
  SkillRegistry
  SkillRuntime
  ToolProvider
  ModelProvider
  EventStream
  FrameStore

bizworker-service
  FastAPI server
  Skill governance APIs
  Ask / run / stream APIs

bizworker-integrations
  LocalPythonToolProvider
  HttpToolProvider
  NavigatorToolProvider
  MockToolProvider

navigator-adapter
  Java launch protocol
  taskScopedToken runtime context
  Worker Gateway tool provider
```

## Python Embedding Contract

目标使用方式：

```python
from langgraph_biz_worker import SkillAgent
from langgraph_biz_worker.tools import LocalPythonToolProvider

tool_provider = LocalPythonToolProvider()

@tool_provider.tool("query_order")
async def query_order(order_id: str) -> dict:
    return {"order_id": order_id, "status": "EXCEPTION"}

agent = SkillAgent(
    skills_root="./skills",
    tool_provider=tool_provider,
)

result = await agent.ask(
    skill_name="order-assistant",
    message="帮我查一下订单 123 为什么异常",
    context={"user_id": "u1"},
)
```

最小输入模型：

```json
{
  "skill_name": "order-assistant",
  "message": "帮我查一下订单 123 为什么异常",
  "context": {
    "user_id": "u1"
  },
  "runtime": {
    "toolProvider": "local-python"
  }
}
```

## Skill Directory Contract

目录结构：

```text
skills/
  order-assistant/
    SKILL.md
    resources/
    examples/
    tests/
```

最小 `SKILL.md`：

```markdown
---
name: order-assistant
display_name: Order Assistant
description: Handles order lookup and exception triage.
tools:
  - query_order
  - update_order_note
context_visibility: summary
---

You help users investigate order issues.
Use the available tools only when the user asks for order data or changes.
```

规则：

- folder basename 必须等于 canonical `skill_name`。
- frontmatter `name` 应与 folder basename 一致；不一致时 validate 返回 warning 或 error，具体严格程度由运行模式决定。
- `display_name` 可变，不作为 identity。
- `tools` 是声明式能力清单，最终是否可调用由当前 `ToolProvider` 决定。

## Service Mode API

Standalone FastAPI service 目标接口：

```http
GET    /api/v1/skills
POST   /api/v1/skills
GET    /api/v1/skills/{skill_name}
PUT    /api/v1/skills/{skill_name}
DELETE /api/v1/skills/{skill_name}
POST   /api/v1/skills/{skill_name}/validate
POST   /api/v1/skills/{skill_name}/run
POST   /api/v1/skills/{skill_name}/ask
POST   /api/v1/ask
```

说明：

- `run` 偏结构化执行，可传 explicit input。
- `ask` 偏自然语言对话，可传 message/context。
- `/api/v1/ask` 可作为泛入口，body 中显式传 `skill_name`。
- 默认本地开发可无鉴权；生产暴露时必须支持 Bearer token 或由宿主应用包一层鉴权。

## Navigator Adapter Contract

Navigator 仍走企业链路，但 launch request 应逐步收敛到：

```json
{
  "tenantId": "tenant_01",
  "businessTaskId": "bt_01",
  "clientAppId": "app_01",
  "upstreamUserId": "user_01",
  "skill_name": "tms-navigator-agent",
  "skillMarkdown": "...",
  "taskScopedToken": "<masked>",
  "runtime": {
    "toolProvider": "navigator",
    "modelConfigId": "model_01"
  }
}
```

Worker core 不理解 ClientApp grant，也不做 Navigator 授权判定。Navigator adapter 的责任是：

- 把 `taskScopedToken` 放入 runtime context，不放入 prompt。
- 使用 `NavigatorToolProvider` 调 Worker Gateway。
- 将企业审计事件回传或映射到 Navigator 任务事件。
- 保留现有 frame/task 兼容。

## Implementation Plan

Phase naming note:

以下 Phase 以 BizWorker standalone runtime 为主线。`01-worker-skill-name-boundary-decision.md` 中的 protocol compatibility 是跨 Java/Worker 的后续收敛阶段，不是本文 Phase 1 的同义词。

### Phase 0: Decision and Planning

- [x] 确认 `skill_name = skill folder basename`。
- [x] 落档 Worker `skill_name` 边界决策。
- [x] 落档 BizWorker standalone runtime 规划。

### Phase 1: Core Contract Extraction

- 新增或整理 Python `SkillAgent` facade。
- 抽象 `ToolProvider` 接口。
- 抽象 `ModelProvider` 或明确当前 chat model 注入方式。
- 定义 standalone `ask(skill_name, message, context, runtime)` 输入输出。
- 保持现有 root graph 能运行，先做兼容包装，不一次性重写。

Scope guard:

- 不实现 service mode 的 skill CRUD。
- 不迁移 Java 启动链路。
- 不全局重命名 Python 内部 `skill_id`。
- 不做 packaging / CLI / HTTP provider。
- 不改变 Navigator/TMS OpenAPI `rootAgentId` 语义。

Completion gate:

- `SkillAgent.ask(skill_name=...)` 可以加载本地 `SKILL.md` 并执行最小问答。
- `LocalPythonToolProvider` 或 `MockToolProvider` 可被最小 skill 调用。
- `skill_name` 输入会被校验为安全单级路径段。
- 现有 root graph 兼容测试不回退。
- 新增 targeted Python tests 覆盖 facade、tool provider、legacy alias 映射。

### Phase 2: Local Skill Governance

- 完善 `SkillRegistry` 对 folder basename 的 canonical identity 支持。
- 先增加 Python `SkillAgent` facade 本地治理 API。
- FastAPI skill CRUD service API 作为后续 service mode 复用项。
- 增加 validate：路径安全、frontmatter、tool 声明、资源引用。
- 增加本地文件写入保护，避免路径穿越和误删 skills root 外文件。

Completion gate:

- create/update/delete 均验证目标路径必须位于 configured skills root 内。
- delete 只能删除一个明确的 `skill_name` 目录，不能删除 skills root、父目录或通配路径。
- path traversal、绝对路径、空 `skill_name`、重复分隔符都有失败测试。

### Phase 3: Tool Provider Plugins

- 实现 `LocalPythonToolProvider`。
- 实现 `MockToolProvider`。
- 整理现有 business function tools 为 `NavigatorToolProvider`。
- 评估 `HttpToolProvider`，首版可先作为后续项。

### Phase 4: Prompt and Runtime Decoupling

- 单 root skill ask 不要求 LLM 选择 skill。
- prompt context 过滤内部字段：token、Java `skillId`、ClientApp grant、business task id 等。
- LLM 多 skill 路由只展示 `displayName + description`；工具参数使用 `skill_name`。

### Phase 5: Navigator Adapter Convergence

- Java launch request 增加 Java `skillName` / JSON `skill_name`，保留 `skillId` 兼容。
- LangGraph Java adapter 不再把 Java skill id 写入 prompt。
- Navigator enterprise tool provider 继续使用 task-scoped token。
- 保持现有 OpenAPI rootAgentId 语义不变。

### Phase 6: Packaging and Samples

- 提供 standalone README。
- 提供 Python script sample。
- 提供 FastAPI app embedding sample。
- 提供 skill CRUD + run smoke test。
- 评估是否发布为内部 Python package。

## Module Responsibility

| Module | Responsibility |
| --- | --- |
| `tools/langgraph-biz-worker` | Standalone BizWorker core、service API、local skill registry、tool provider plugins |
| `addons/langgraph-biz-worker` | Java to Python Worker adapter，兼容 Navigator enterprise launch protocol |
| `business-agent-module` | Navigator 企业控制面：ClientApp、grant、task token、function authorization |
| `addons/claude-worker-agent` | OpenAPI rootAgentId route 和 ask/readiness 入口 |
| `navigator-open-sdk` | 上游企业集成 SDK；不作为 standalone Python runtime 的依赖 |
| docs under `upstream-integration` | 记录跨模块契约、迁移计划、验收证据 |

## Code Inventory

| Repo | Path | Role | Expected change | Notes |
| --- | --- | --- | --- | --- |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_registry.py` | Skill manifest loading | update | folder basename canonical identity、validate |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_runtime.py` | Frame/runtime execution | update | 兼容 standalone SkillAgent facade |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py` | Ask routing and prompt assembly | update | single root skill direct run、context filtering |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/skills.py` | Skill materialize route | update | 扩展为治理 CRUD 或拆新 route |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py` | Ask/run HTTP entry | update | 支持 standalone `skill_name` input |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/models.py` | Pydantic models | update | 增加 standalone ask/governance models |
| Navigator | `tools/langgraph-biz-worker/tests/test_skill_registry.py` | Registry tests | update | folder basename、validate、path guard |
| Navigator | `tools/langgraph-biz-worker/tests/test_root_graph.py` | Runtime tests | update | `skill_name` direct execution、prompt filtering |
| Navigator | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessAgentWorkerTaskLauncher.java` | Java adapter | update-later | 传 JSON `skill_name`，不向 prompt 暴露 Java `skillId` |
| Navigator | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/worker/BusinessAgentWorkerTaskLaunchRequest.java` | Java launch DTO | update-later | 新增 Java `skillName` / JSON `skill_name` 兼容字段 |
| Navigator | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java` | Java task orchestration | update-later | 解析/传递 Java `skillName` / JSON `skill_name` |
| Navigator | `docs/version-tracker/1.1.4-SNAPSHOT/upstream-integration/01-worker-skill-name-boundary-decision.md` | Prior decision | read-only-analysis | 本计划的前置决策 |

## Required Tests

Python:

- `test_skill_registry.py`：folder basename identity、frontmatter mismatch、path traversal。
- `test_root_graph.py`：指定 `skill_name` 时直接执行目标 skill，不触发 LLM skill selection。
- 新增 standalone facade tests：local Python tool provider 可被 skill 调用。
- 新增 service API tests：skill CRUD、validate、ask。

Java:

- `LanggraphBusinessAgentWorkerTaskLauncherTest`：launch request 传 JSON `skill_name`，prompt 不含内部 `skillId`。
- `BusinessAgentTaskServiceTest`：企业链路保留 task-scoped token 与授权校验。
- OpenAPI ask/readiness 相关测试保持 #125 rootAgentId 语义。

Manual / smoke:

- 本地 Python script 直接运行 `skills/order-assistant`。
- FastAPI service 启动后完成 skill create -> validate -> ask。
- Navigator ask 仍能通过 `rootAgentId` 跑通 TMS root skill。

## Acceptance Criteria

- 独立 Python 项目可以不启动 Navigator Java，直接加载本地 skill 并执行 ask。
- Standalone service 可以完成 Skill CRUD 和 validate。
- `skill_name` 是 Worker runtime identity，来源为 folder basename。
- 普通 standalone prompt 不出现 Java `skillId`、ClientApp、grant、task token。
- Navigator enterprise adapter 仍保留授权和审计边界，不让 Worker core 绕过 Java 控制面。
- 现有 Navigator/TMS OpenAPI 行为保持兼容。

## Review Gate Before Coding

进入 Phase 1 前必须满足：

- 本文的 glossary、compatibility constraint、Phase 1 scope guard 已被实现方确认。
- Phase 1 只建立 standalone facade 和 provider 边界，不展开治理 API 与 Java 迁移。
- 若实现中发现需要修改 `SkillRuntime` 主执行模型，应先回到本文补充设计，不直接重写 frame/runtime。
- 所有新外部 API 文档与测试使用 `skill_name`；旧字段仅作为兼容 alias 出现。

## Security and Safety Boundaries

- Standalone 本地开发可无鉴权，但 service mode 暴露到网络时必须配置 Bearer token 或宿主鉴权。
- Skill CRUD 必须做 path traversal 防护。
- 删除 skill 只能删除 skills root 内目标目录。
- runtime context 中的 token、API key、adapter config 不进入 prompt。
- Local Python tool provider 默认是受信宿主代码能力，不面向不可信用户开放任意代码执行。

## Progress Tracking

| Area | Status | Notes |
| --- | --- | --- |
| Planning | done | 本文档 |
| Development | implemented | Phase 1 standalone facade + Phase 2 local governance facade 已完成；Java 控制面未改 |
| Testing | passed | 目标 Python 兼容套件 120 passed |
| Experience | N/A | 无 UI 改动；后续如新增管理 UI 再补体验验证 |

## Open Questions

- Standalone package 名称是否继续用 `langgraph_biz_worker`，还是抽象为 `bizworker`。
- 首版是否需要 CLI，例如 `bizworker skill list/create/run`。
- `HttpToolProvider` 是否进入 Phase 3 首版，还是先由用户项目用 LocalPythonToolProvider 包装 HTTP 调用。
- Skill governance 是否需要启用/禁用状态文件，还是首版以目录存在即启用。
- FrameStore 首版使用 memory/file/sqlite 哪一种默认实现。
