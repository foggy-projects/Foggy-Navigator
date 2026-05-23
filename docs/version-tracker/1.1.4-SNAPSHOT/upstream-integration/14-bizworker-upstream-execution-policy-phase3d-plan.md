# BizWorker Upstream Execution Policy Phase 3D Plan

## Document Purpose

- doc_type: requirement | implementation-plan | code-inventory
- version: 1.1.4-SNAPSHOT
- status: ready-for-implementation
- date: 2026-05-19
- intended_for: BizWorker implementer | Navigator reviewer | Python integration developer
- purpose: 将 BizWorker standalone/runtime 下一阶段从插件热加载和多租户 sandbox 收敛到上游执行策略约束

## Requirement

BizWorker 当前已支持 standalone SkillAgent、HTTP service、tool module、model provider、status route 和外部 sample。下一步目标不是构建插件热加载，也不是在 Worker 内部实现多租户 sandbox，而是让 Worker 对每次上游调用传入的执行边界做严格应用层校验。

上游传入的最小执行边界包括：

- `workdir`: 本次任务工作目录。
- `allowed_dirs`: 本次任务允许访问的目录集合。
- `allowed_tools`: 本次任务允许调用的工具集合。

BizWorker 必须将该边界用于：

- LLM 可见工具列表。
- LLM 伪造 tool call 的二次校验。
- Provider tool context。
- Standalone service 和 Navigator query runtime path。

## Non-goals

- 不实现插件热加载。
- 不实现多租户 sandbox、chroot、容器隔离或 OS 级权限隔离。
- 不在 Worker 内部建立租户模型。
- 不引入插件市场或动态代码安装能力。
- 不改变 Navigator Java task-scoped token 的既有业务授权链路。

## Contract

Preferred runtime context:

```json
{
  "execution_policy": {
    "workdir": "/project/demo",
    "allowed_dirs": ["/project/demo", "/tmp/demo-output"],
    "allowed_tools": ["query_order", "update_order_status"]
  }
}
```

Accepted aliases:

| Canonical | Aliases |
| --- | --- |
| `execution_policy` | `executionPolicy` |
| `workdir` | `workDir`, `working_dir`, `workingDirectory`, `working_directory` |
| `allowed_dirs` | `allowedDirs`, `allowed_directories`, `allowedDirectories` |
| `allowed_tools` | `allowedTools`, `authorized_tools`, `authorizedTools`, `tool_allowlist`, `toolAllowlist` |

Compatibility rule:

- Standalone `POST /api/v1/ask` accepts the policy inside `context`.
- Navigator `/api/v1/query` should prefer `runtime_context.execution_policy`; if policy is still sent in visible `context`, BizWorker may copy it into runtime context and strip it from visible skill input.

## Runtime Rules

1. Path normalization:
   - Resolve `workdir` and `allowed_dirs` to canonical local paths.
   - If `workdir` is provided and `allowed_dirs` is omitted, default `allowed_dirs` to `[workdir]`.
   - Reject `workdir` when it is outside every `allowed_dirs` entry.
   - Relative paths resolve against the current process working directory unless the caller sends absolute paths.

2. Tool authorization:
   - If `allowed_tools` is omitted, keep current behavior.
   - If `allowed_tools` is provided, LLM-visible external tools become:

```text
manifest.allowed_tools ∩ upstream.allowed_tools ∩ provider.available_tools
```

   - Runtime completion tools such as `submit_skill_result` remain available.
   - Actual tool execution must re-check authorization before dispatching provider, file, public resource, mock, or business function tools.
   - Unauthorized calls return a stable error beginning with `TOOL_NOT_AUTHORIZED`.

3. Provider context:
   - Provider tools receive normalized non-sensitive fields:

```json
{
  "workdir": "/project/demo",
  "allowed_dirs": ["/project/demo"],
  "allowed_tools": ["query_order"],
  "execution_policy": {
    "workdir": "/project/demo",
    "allowed_dirs": ["/project/demo"],
    "allowed_tools": ["query_order"]
  }
}
```

   - Local Python tools may opt in to this context through a reserved `tool_context` argument.

## Code Inventory

| Path | Responsibility |
| --- | --- |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/execution_policy.py` | Normalize and validate upstream execution policy |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_tool_schemas.py` | Filter LLM-visible tool schemas by runtime authorization |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/llm_skill_agent.py` | Enforce tool authorization before dispatch and pass provider context |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/tool_provider.py` | Allow local Python tools to receive `tool_context` without exposing it as model input |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/skill_agent.py` | Hide execution policy from visible skill input while keeping it in runtime context |
| `tools/langgraph-biz-worker/src/langgraph_biz_worker/routes/query.py` | Copy execution policy from visible context into hidden runtime context when needed |
| `tools/langgraph-biz-worker/docs/standalone.md` | Document standalone execution policy contract |

## Test Plan

Targeted tests:

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest `
  tests/test_execution_policy.py `
  tests/test_tool_provider.py `
  tests/test_skill_agent_facade.py `
  tests/test_standalone_service.py
```

Compatibility suite:

```powershell
cd tools/langgraph-biz-worker
.\.venv\Scripts\python.exe -m pytest
```

## Acceptance Criteria

- `workdir` outside `allowed_dirs` is rejected before execution.
- Provider tools not in `allowed_tools` are not exposed to the model.
- Provider tools not in `allowed_tools` are also rejected if the model fabricates the call.
- Provider tools receive normalized `workdir`, `allowed_dirs`, and `execution_policy`.
- Execution policy is not shown as normal skill input.
- Existing standalone and compatibility tests remain green.
