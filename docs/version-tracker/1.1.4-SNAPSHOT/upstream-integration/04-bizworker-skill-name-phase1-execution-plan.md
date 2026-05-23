# BizWorker skill_name Phase 1 Execution Plan

## 文档作用

- doc_type: requirement | implementation-plan | code-inventory | execution-prompt
- version: 1.1.4-SNAPSHOT
- status: ready-for-implementation
- date: 2026-05-19
- source:
  - [01-worker-skill-name-boundary-decision.md](./01-worker-skill-name-boundary-decision.md)
  - [02-bizworker-standalone-skill-agent-plan.md](./02-bizworker-standalone-skill-agent-plan.md)
  - [03-bizworker-standalone-plan-review.md](./03-bizworker-standalone-plan-review.md)
- intended_for: BizWorker implementer | Navigator reviewer | reviewer
- purpose: 将 `skill_name` 外部契约和 BizWorker standalone Phase 1 拆成可执行开发计划

## Requirement

### Background

BizWorker 当前内部大量使用 `skill_id`：frame state、runtime、LLM router、tool schema、resource tools、service route 与测试都依赖这个字段。直接全局改名风险较高。

1.1.4 的第一步不是大迁移，而是建立清晰的边界：

```text
External BizWorker contract: skill_name
Internal legacy field:       skill_id
Java property name:          skillName
Java -> BizWorker JSON:      skill_name
```

其中 `skill_name` 的值就是 Skill 文件夹 basename，例如：

```text
skills/order-assistant/SKILL.md
=> skill_name = order-assistant
```

### Goals

1. BizWorker 对外新增契约统一使用 `skill_name`。
2. BizWorker 内部允许继续使用 `skill_id`，但值语义收敛为 folder basename。
3. 兼容输入支持 `skill_name`、`skillName`、`skill_id`、`skillId`；多个非空 alias 值相同则接受并归一为 `skill_name`，不同则 validation error。
4. 新增 standalone `SkillAgent` facade，支持普通 Python 项目直接按 `skill_name` 调用本地 Skill。
5. 新增最小 `ToolProvider` 边界，让本地 Python 函数或 mock tools 可以被 Skill 调用。
6. 为后续 Java adapter 发送 `skill_name` 预留清晰迁移点。

### Non-goals

- 不全局重命名 `SkillFrameState.skill_id`。
- 不迁移数据库列、JPA Entity、Java 企业控制面 `skillId` 字段。
- 不改变 TMS / OpenAPI 上游 `rootAgentId` 语义。
- 不实现完整 Skill CRUD service。
- 不做 CLI、packaging、HTTP provider。
- 不重写 root graph 或 frame lifecycle 主执行模型。

### Contract

Python embedding:

```python
result = await agent.ask(
    skill_name="order-assistant",
    message="帮我查一下订单 123 为什么异常",
    context={"user_id": "u1"},
)
```

HTTP / service body:

```json
{
  "skill_name": "order-assistant",
  "message": "帮我查一下订单 123 为什么异常",
  "context": {
    "user_id": "u1"
  }
}
```

Compatibility input:

```json
{
  "skill_id": "order-assistant"
}
```

Conflict rule:

```text
collect non-blank values from skill_name, skillName, skill_id, skillId
all provided values same -> accepted and normalized to skill_name
any provided values differ -> validation error
```

Java adapter target:

```java
private String skillName;
```

serialized to BizWorker:

```json
{
  "skill_name": "order-assistant"
}
```

## Module Responsibility

| Module | Responsibility | Phase 1 status |
| --- | --- | --- |
| `tools/langgraph-biz-worker` | BizWorker standalone facade、`skill_name` 归一化、provider boundary、Python tests | primary owner |
| `addons/langgraph-biz-worker` | Java -> Python Worker adapter，后续发送 `skill_name` | read-only in standalone Phase 1 |
| `business-agent-module` | Java 企业控制面、task-scoped token、授权、DB `skillId` | do-not-touch in standalone Phase 1 |
| `addons/claude-worker-agent` | OpenAPI `rootAgentId` route | do-not-touch in standalone Phase 1 |
| `navigator-open-sdk` | 上游 SDK | do-not-touch in standalone Phase 1 |
| `docs/version-tracker/1.1.4-SNAPSHOT/upstream-integration` | 跨模块决策、计划、评审、进度记录 | update progress after coding |

## Implementation Plan

### Step 1: Identity Normalization

Create a small BizWorker identity utility:

- Accept `skill_name` as canonical.
- Accept `skillName`、`skill_id`、`skillId` as aliases.
- Validate safe single path segment.
- Reject blank, `/`, `\`, `..`, absolute paths, repeated path separators.
- Reject conflicting aliases.

Output should be a normalized string named `skill_name`.

### Step 2: Standalone SkillAgent Facade

Add a thin facade for Python embedding:

- `SkillAgent(skills_root, tool_provider, model_provider=None, runtime=None)`
- `ask(skill_name, message, context=None, runtime=None)`
- Load or resolve skill manifest by folder basename.
- Internally call existing runtime with `skill_id=skill_name`.
- Keep frame/event output unchanged for now.

The facade is a compatibility wrapper over current runtime, not a replacement for frame lifecycle.

### Step 3: ToolProvider Boundary

Add the minimal provider protocol:

- `list_tools(skill_name, context) -> list[ToolSpec]`
- `call_tool(tool_name, arguments, context) -> ToolResult`

Implement:

- `MockToolProvider` for deterministic tests.
- `LocalPythonToolProvider` for simple Python project integration.

Phase 1 provider integration rule:

- `SkillAgent.ask` must invoke the configured provider when a skill declares local tools and the model/script requests one.
- The first implementation may adapt provider calls through the existing tool dispatch path or keep a narrow facade-owned execution path for standalone tests.
- Tests must prove provider invocation happens through the `SkillAgent.ask(skill_name=...)` path, not only by directly calling provider classes.

Navigator business function tools remain separate and are not migrated in this step.

### Step 4: Query / Runtime Compatibility

For current BizWorker query entrypoints:

- Accept `skill_name` in request/context where `skill_id` or `skillId` is currently accepted.
- Materialize dynamic SkillManifest id/name from `skill_name` first.
- Keep `skill_id` internally when opening frames.
- Stop adding Java `skillId` to new standalone prompts.

### Step 5: Targeted Tests

Add or update tests for:

- canonical `skill_name`
- alias precedence
- alias conflict error
- path safety validation
- `SkillAgent.ask(skill_name=...)` minimal execution
- mock/local provider invocation
- existing root graph compatibility

## Code Inventory

| Repo | Path | Role | Expected change | Notes |
| --- | --- | --- | --- | --- |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_identity.py` | identity normalization | create | Centralizes `skill_name` alias and path validation |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_agent.py` | Python embedding facade | create | Thin wrapper; calls existing runtime with `skill_id=skill_name` |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/tool_provider.py` | provider protocol and local/mock providers | create | Keep minimal; no Navigator dependency |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/__init__.py` | public Python API | update | Export `SkillAgent` for `from langgraph_biz_worker import SkillAgent` |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/tools/__init__.py` | public provider API | update | Export `LocalPythonToolProvider` for `from langgraph_biz_worker.tools import LocalPythonToolProvider` |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/models.py` | request aliases | update | Accept `skill_name` without removing `skill_id` |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_registry.py` | folder basename lookup | update | Ensure manifest lookup works with normalized `skill_name` |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/graphs/root_graph.py` | request/context resolution | update | Prefer `skill_name`; keep `skill_id`/`skillId` alias |
| Navigator | `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py` | HTTP query compatibility | update | Ensure request body can carry `skill_name` |
| Navigator | `tools/langgraph-biz-worker/tests/test_skill_identity.py` | identity tests | create | Alias, conflict, path safety |
| Navigator | `tools/langgraph-biz-worker/tests/test_skill_agent_facade.py` | facade tests | create | Local skill load + ask |
| Navigator | `tools/langgraph-biz-worker/tests/test_tool_provider.py` | provider tests | create | Mock/local provider behavior |
| Navigator | `tools/langgraph-biz-worker/tests/test_root_graph.py` | compatibility tests | update | `skill_name` context compatibility |
| Navigator | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessAgentWorkerTaskLauncher.java` | Java launch adapter | read-only-analysis | Later phase sends `skill_name`; not Phase 1 coding target |
| Navigator | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/worker/BusinessAgentWorkerTaskLaunchRequest.java` | Java launch DTO | read-only-analysis | Later phase Java property `skillName` + JSON `skill_name` |

## Test Plan

Python targeted tests:

```powershell
cd tools/langgraph-biz-worker
python -m pytest tests/test_skill_identity.py tests/test_tool_provider.py tests/test_skill_agent_facade.py tests/test_root_graph.py tests/test_query.py tests/test_skill_registry_v2.py
```

If the local virtualenv is required:

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest tests/test_skill_identity.py tests/test_tool_provider.py tests/test_skill_agent_facade.py tests/test_root_graph.py tests/test_query.py tests/test_skill_registry_v2.py
```

Deterministic test rule:

- Phase 1 tests must not call external LLM APIs.
- `SkillAgent.ask` tests should use a mock/scripted model or a deterministic local execution path.
- If a broader test needs real LLM config, it must be excluded from the Phase 1 completion gate and recorded as not-run with reason.

Java tests are not required for standalone Phase 1 unless the implementation touches Java adapter files.

## Acceptance Criteria

- New BizWorker external fields and examples use `skill_name`.
- `skill_id` remains accepted as compatibility input and remains internal frame field.
- Conflicting aliases produce clear validation errors.
- `skill_name` is validated as a safe folder basename.
- A normal Python project can instantiate `SkillAgent` and call `ask(skill_name=...)`.
- Minimal local/mock tool provider can be invoked through the facade path.
- Existing root graph tests continue to pass.
- No Navigator Java DB/API behavior changes in this phase.

## Execution Prompt

Implement BizWorker standalone Phase 1 for 1.1.4.

Read first:

- `CLAUDE.md`; if no module-local `CLAUDE.md` exists under `tools/langgraph-biz-worker`, use the root project constraints.
- `docs/version-tracker/1.1.4-SNAPSHOT/upstream-integration/01-worker-skill-name-boundary-decision.md`
- `docs/version-tracker/1.1.4-SNAPSHOT/upstream-integration/02-bizworker-standalone-skill-agent-plan.md`
- `docs/version-tracker/1.1.4-SNAPSHOT/upstream-integration/03-bizworker-standalone-plan-review.md`
- this document

Do:

1. Add `skill_name` identity normalization and alias validation in BizWorker.
2. Add a minimal `SkillAgent` facade for Python embedding.
3. Add a minimal `ToolProvider` protocol with mock/local implementations.
4. Wire `skill_name` into current request/context resolution while keeping internal `skill_id` compatibility.
5. Add targeted tests and run them.
6. Write a progress note under this 1.1.4 directory after coding.

Do not:

- Rename all internal `skill_id` fields.
- Touch Java DB/entity/control-plane fields.
- Change TMS/OpenAPI upstream `rootAgentId`.
- Build CRUD service, CLI, package publishing, or HTTP provider.
- Rewrite frame lifecycle or root graph wholesale.

Completion definition:

- Targeted Python tests pass.
- Existing root graph compatibility does not regress.
- The final report states changed files, tests run, and any remaining aliases or migration debt.
- Progress is written to `05-bizworker-skill-name-phase1-progress.md`.
