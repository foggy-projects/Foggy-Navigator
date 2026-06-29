---
type: optimization
version: 1.3.2-SNAPSHOT
ticket: OPT-002
severity: medium
status: ready-for-signoff
owner: business-agent-module | claude-worker-agent | langgraph-biz-worker
created_at: 2026-06-29
---

# OPT-002: LangGraph Biz Actor Home Readiness

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 `LANGGRAPH_BIZ` Actor-owned BizWorker 任务的目录必填契约、readiness 诊断、delegated file-root 对齐、测试证据和签收状态。

## Background

Actor-owned BizWorker 任务需要在 Actor Home 下执行命令和文件工具写入。此前链路中 `directoryId` / `cwd` 的传递不够强约束：缺目录时可能进入后续 worker/session 流程，文件工具结果也不明确写入的是 managed account layer 还是 delegated Actor Home。

本项收紧最小闭环：

- OpenAPI / BusinessAgentTaskService / LangGraph direct create 对 `LANGGRAPH_BIZ` 缺 `directoryId` fail-fast。
- A2A metadata、BusinessAgent worker launcher、direct params 透传 `directoryId` / `cwd`。
- readiness DTO 暴露 file tool root 诊断字段，说明 managed account layer 或 delegated Actor Home。
- Python `AccountFileTools` 在 delegated workspace 写入时返回 `storage_mode=delegated` 和明确 summary。

## Confirmed Contract

- `LANGGRAPH_BIZ` Actor-owned task must resolve a working directory before task creation.
- Missing `directoryId` must fail with stable marker `TASK_DIRECTORY_REQUIRED`.
- `directoryId` aliases accepted in A2A metadata: `directoryId`, `directory_id`, `workingDirectoryId`, `working_directory_id`.
- `cwd` aliases accepted in A2A metadata: `cwd`, `workdir`, `workDir`, `workingDirectory`, `working_directory`.
- `runtimeContext` and `runtime_context` are both accepted, with skill routing keys stripped before forwarding.
- Readiness diagnostics for BizWorker file tools:
  - no workspace: `fileToolRootMode=MANAGED_ACCOUNT_LAYER`
  - resolved workspace: `fileToolRootMode=DELEGATED_ACTOR_HOME`
  - delegated file root must align with command workdir.
- Python delegated file writes under Actor Home should return `storage_mode=delegated`; managed writes remain `storage_mode=managed`.

## Non-Goals

- 不改变 Codex Biz Route 的已签收范围。
- 不新增 UI。
- 不新增独立 Agent 发现机制。
- 不在 readiness 或日志中暴露凭证内容。

## Code Inventory

| Module | Path | Role |
| --- | --- | --- |
| business-agent-module | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/dto/AgentReadinessDTO.java` | readiness DTO 增加 file tool root 诊断字段。 |
| business-agent-module | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java` | `LANGGRAPH_BIZ` 缺 workspace fail-fast。 |
| business-agent-module | `business-agent-module/src/test/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskServiceTest.java` | 覆盖缺目录拒绝和默认 workspace。 |
| claude-worker-agent | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java` | OpenAPI BizWorker 缺目录提前返回 `TASK_DIRECTORY_REQUIRED`。 |
| claude-worker-agent | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/OpenApiAgentReadinessService.java` | readiness 计算 file tool root alignment。 |
| claude-worker-agent | `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java` | 覆盖 OpenAPI 缺目录 fail-fast。 |
| claude-worker-agent | `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/OpenApiAgentReadinessServiceTest.java` | 覆盖 managed/delegated file tool root 诊断。 |
| langgraph-biz-worker | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerInnerA2aAgent.java` | A2A metadata 读取并回显 `directoryId` / `cwd`，支持 `runtime_context`。 |
| langgraph-biz-worker | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessAgentWorkerTaskLauncher.java` | BusinessAgent launcher 透传目录和 workdir。 |
| langgraph-biz-worker | `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskService.java` | direct create 缺 `directoryId` fail-fast。 |
| langgraph-biz-worker | `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerInnerA2aAgentTest.java` | 覆盖 runtime directory/cwd 和 snake_case runtime context。 |
| langgraph-biz-worker | `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphBusinessAgentWorkerTaskLauncherTest.java` | 覆盖 launcher 透传目录和 cwd。 |
| langgraph-biz-worker | `addons/langgraph-biz-worker/src/test/java/com/foggy/navigator/langgraph/worker/service/LanggraphTaskServiceTest.java` | 覆盖 direct create 缺目录拒绝和 runtime_context alias。 |
| tools/langgraph-biz-worker | `tools/langgraph-biz-worker/src/langgraph_biz_worker/runtime/account_file_tools.py` | delegated / managed storage mode 标记。 |
| tools/langgraph-biz-worker | `tools/langgraph-biz-worker/tests/test_account_file_tools.py` | 覆盖 delegated write result。 |
| tools/langgraph-biz-worker | `tools/langgraph-biz-worker/tests/test_owner_aware_runtime_contract.py` | 覆盖 Actor Home 下 agent path 写入。 |

## Acceptance Criteria

- `LANGGRAPH_BIZ` Actor-owned task missing `directoryId` is rejected before worker/session side effects.
- OpenAPI missing-directory failure returns stable marker `TASK_DIRECTORY_REQUIRED`.
- A2A/direct/launcher path can carry `directoryId` and `cwd` to LangGraph task creation.
- `runtime_context` snake_case alias is accepted and sanitized like `runtimeContext`.
- readiness response reports managed account layer vs delegated Actor Home file-root mode.
- delegated Python file writes land under Actor Home and return `storage_mode=delegated`.
- Focused Java and Python tests pass.
- No UI experience validation is required.

## Progress Tracking

### Development Progress

- [x] BusinessAgent task service rejects missing workspace for `LANGGRAPH_BIZ`.
- [x] OpenAPI controller rejects missing directory for Actor-owned BizWorker task.
- [x] readiness DTO/service reports file tool root alignment.
- [x] LangGraph A2A/direct/launcher propagates `directoryId` and `cwd`.
- [x] Python delegated file tool result marks storage mode.

### Testing Progress

| Case | Scope | Status | Notes |
| --- | --- | --- | --- |
| BusinessAgent task service | Java | pass | `mvn test -pl business-agent-module -am "-Dtest=BusinessAgentTaskServiceTest" ...`: 20 tests pass. |
| OpenAPI readiness | Java | pass | `mvn test -pl addons/claude-worker-agent -am "-Dtest=OpenApiAgentReadinessServiceTest" ...`: 18 tests pass. |
| OpenAPI message mapping | Java | pass | `mvn test -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" ...`: 33 tests pass. |
| LangGraph task service + launcher | Java | pass | `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphTaskServiceTest,LanggraphBusinessAgentWorkerTaskLauncherTest" ...`: 31 tests pass. |
| LangGraph A2A adapter | Java | pass | `mvn test -pl addons/langgraph-biz-worker -am "-Dtest=LanggraphWorkerInnerA2aAgentTest" ...`: 11 tests pass. |
| Python delegated file tools | Python | pass | `.venv\Scripts\python.exe -m pytest tests/test_account_file_tools.py tests/test_owner_aware_runtime_contract.py`: 43 passed, 2 skipped. |
| diff check | workspace | pass | `git diff --check`: pass with CRLF normalization warnings only. |

### Experience Progress

- N/A. 本事项为后端/Worker/API readiness，不新增或修改 UI 页面、表单、按钮、弹窗、权限可见性或前端交互。

## Execution Check-in

- status: ready-for-signoff
- completed work summary:
  - Actor-owned BizWorker 任务目录契约已在 BusinessAgent / OpenAPI / LangGraph direct create 三处收紧。
  - A2A metadata 和 BusinessAgent launcher 已透传运行目录线索。
  - readiness 增加 file tool root 模式和对齐诊断。
  - Python delegated file tool 写入 Actor Home，并返回 storage mode 供上游诊断。
- self-check:
  - scope conformance: pass
  - non-goals preserved: pass
  - code paths listed: pass
  - tests recorded: pass
  - experience N/A recorded: pass
- remaining risks / blockers:
  - blocking_items: none
  - operational note: production Actor Home 权限和 allowed_dirs 仍由部署环境保证。
- acceptance readiness: ready-for-signoff
