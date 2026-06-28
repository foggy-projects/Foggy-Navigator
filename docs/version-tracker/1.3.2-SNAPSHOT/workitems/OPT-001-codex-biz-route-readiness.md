---
type: optimization
version: 1.3.2-SNAPSHOT
ticket: OPT-001
severity: medium
status: ready-for-signoff
owner: codex-worker-agent | session-module | codex-agent-worker
created_at: 2026-06-28
---

# OPT-001: Codex Biz Route Readiness

## 文档作用

- doc_type: workitem
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 固化 `codex-biz-worker` readiness 的需求、调用契约、实施顺序、测试证据和后续验收状态。

## Background

既有迭代已经确认 `codex-biz-worker` 是 Codex 业务直连路由：它不把 Codex Worker 改造成 LangGraph BizWorker，也不暴露独立可发现 Agent；它通过独立 `providerType=codex-biz-worker` 复用 `OPENAI_CODEX` 模型配置，并把 actor/account scoped `CODEX_HOME` 委托给 `tools/codex-agent-worker` 的 `CODEX_BIZ_HOME_ROOT + codex_home_key` 机制。

当前下一步不是重做架构，而是把这条线按 1~5 推成可继续联调的最小闭环。

## Confirmed Invocation Contract

OpenAPI / unified dispatch request:

- `providerType=codex-biz-worker` 用于显式进入 Codex Biz route。
- `modelConfigId` 可指向 `workerBackend=OPENAI_CODEX` 的模型配置；`codex-biz-worker` 与 `codex-worker` 在模型后端上兼容。
- `workerId` / `directoryId` / `cwd` 沿用 Codex Worker 执行定位语义。
- `sessionId` 用于 Navigator 多轮会话续接；resume 时以 session 绑定 provider 为准。
- `contextId` 只作为上游上下文投影和诊断字段，不替代 `sessionId`。

Codex Biz metadata / params:

- `codexHomeKey` 或 `codex_home_key` 为 actor/account scoped home key。
- `privateAccountId` / `private_account_id` 可作为 `codexHomeKey` 的兼容别名。
- `developerInstructions` / `developer_instructions` 透传到 Worker `developer_instructions`。
- `outputSchema` / `output_schema` 透传到 Worker `output_schema`。
- `codexConfig` / `codex_config` 透传到 Worker `codex_config`。
- `sandboxMode` / `sandbox_mode`、`approvalPolicy` / `approval_policy`、`networkAccessEnabled` / `network_access_enabled`、`webSearchMode` / `web_search_mode` 支持直接字段，也支持 `codexPolicy` / `codex_policy` 包装。
- `additionalDirectories` / `additional_directories` 透传到 Worker `additional_directories`，并受 Worker allowed cwd 约束。

Worker contract:

- 带 `codex_home_key` 的请求必须配置绝对路径 `CODEX_BIZ_HOME_ROOT`。
- 缺少 `CODEX_BIZ_HOME_ROOT` 时必须稳定返回或抛出：`CODEX_BIZ_HOME_ROOT is required when codex_home_key is provided`。
- 健康检查只能暴露是否配置 scoped home root，不暴露真实 root 路径。

## Non-Goals

- 不把 Codex Biz route 改造成 LangGraph BizWorker。
- 不新增独立可发现 Agent。
- 不改变 `OPENAI_CODEX` 模型配置与 `codex-worker` 的兼容规则。
- 不要求本阶段跑真实 OpenAI 付费调用；live smoke 作为 opt-in。
- 不在健康检查或日志中输出 API Key、auth token、真实 `CODEX_BIZ_HOME_ROOT` 路径或 actor home 绝对路径。

## Module Responsibility

| Module | Responsibility |
| --- | --- |
| `docs/version-tracker/1.3.2-SNAPSHOT` | 记录 readiness 目标、契约、进度、测试证据和验收状态。 |
| `session-module` | 保护 direct create / resume 路由、provider/model 兼容、session-bound provider 优先级和 metadata 透传。 |
| `addons/codex-worker-agent` | 保护 `CodexBizTaskProvider` 参数规范化、create/resume/cancel/list/search 路由边界和 Java -> Worker body。 |
| `tools/codex-agent-worker` | 暴露非敏感 readiness 诊断，稳定 scoped home 缺失错误，提供 actor A/B scoped home smoke 入口。 |

## Code Inventory

| Repo | Path | Role | Expected Change | Notes |
| --- | --- | --- | --- | --- |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/README.md` | version index | create | 本 readiness 工作项入口。 |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-001-codex-biz-route-readiness.md` | requirement / plan / progress | create/update | 对照 1~5 回写 progress、测试和风险。 |
| root | `tools/codex-agent-worker/src/routes/health.ts` | Worker health | update | 增加非敏感 Codex Biz scoped home readiness 字段。 |
| root | `tools/codex-agent-worker/src/models.ts` | Worker DTO | update | 增加 health response 字段类型。 |
| root | `tools/codex-agent-worker/src/routes/query.ts` | Worker query validation | update | 复用稳定错误常量，保持缺 root 时 403。 |
| root | `tools/codex-agent-worker/src/codex/sdk-wrapper.ts` | scoped home resolver | update | 导出稳定错误常量，保持 home key hashing 和路径脱敏。 |
| root | `tools/codex-agent-worker/tests/health.test.ts` | Worker unit | update | 覆盖 readiness 字段计算。 |
| root | `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1` | opt-in smoke helper | create | 检查 health readiness；可选择跑 actor A/B live query。 |
| root | `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java` | session routing regression | update | 覆盖 Codex Biz explicit create、目录默认模型路由、非 Codex 模型拒绝、resume 走 session-bound provider 并透传 metadata。 |
| root | `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java` | provider normalization / routing regression | update | 覆盖 snake_case alias、codexPolicy 包装字段、provider-filtered lookup/list/search 和 resume 默认 policy。 |
| root | `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java` | service provider boundary regression | update | 覆盖 `codex-biz-worker` 过滤普通 Codex 任务、snake_case Biz 参数归一化和 Worker event provider config。 |
| root | `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/client/CodexWorkerClientTest.java` | Java -> Worker body regression | update | 覆盖 Biz 字段透传，并补普通 Codex 请求不携带 Biz 专属字段。 |
| root | `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/controller/CodexTaskControllerTest.java` | controller entry regression | update | 覆盖 legacy create endpoint 保留显式 `providerType=codex-biz-worker` 和 `codexHomeKey`。 |

## Implementation Plan

1. 建立 `1.3.2-SNAPSHOT` readiness 工作项和进度骨架。
2. 冻结并回写 OpenAPI / unified dispatch / Worker 调用契约。
3. 补回归覆盖：
   - Codex Biz create 需要 `codexHomeKey` 或 `privateAccountId`。
   - resume 优先使用 session-bound provider。
   - `OPENAI_CODEX` modelConfig 可显式走 `codex-biz-worker`。
   - snake_case / camelCase / `codexPolicy` 字段能进入 Java provider 规范化。
   - Java -> Worker request body 包含 Biz 字段且普通 Codex 不污染。
4. 补 Worker readiness / diagnostics：
   - `/health` 返回非敏感 scoped home readiness。
   - 缺 `CODEX_BIZ_HOME_ROOT` 的 `codex_home_key` 请求返回稳定 403 错误。
   - scoped home resolver 不在日志或 response 中暴露真实路径。
5. 补 CLI profile / live smoke 入口：
   - health readiness 检查默认不触发真实 LLM。
   - opt-in actor A/B query 使用不同 `codex_home_key`。
   - opt-in resume 使用同一 actor key 和上一次 `session_id`。
   - smoke 输出只打印 task/session/status 摘要。

## Acceptance Criteria

- `1.3.2-SNAPSHOT` 文档入口和 workitem 已创建。
- 调用契约明确说明 `providerType`、`modelConfigId`、`directoryId/cwd`、`privateAccountId/codexHomeKey`、policy、`sessionId`、`contextId` 的语义。
- session / Java / Worker 聚焦回归覆盖 Codex Biz create、resume、model compatibility、body passthrough 和缺配置错误。
- Worker `/health` 暴露 `codex_biz_home_root_configured`，不暴露真实 root path。
- `codex_home_key` 在缺 `CODEX_BIZ_HOME_ROOT` 时返回稳定错误。
- 有可执行 smoke helper 支持 actor A/B scoped home 检查，live query 为 opt-in。
- 完成后 execution check-in 记录测试命令、结果、未跑项和剩余风险。

## Verification Plan

```powershell
npm --prefix tools/codex-agent-worker run typecheck
npm --prefix tools/codex-agent-worker test
mvn test -pl navigator-common -am "-Dtest=ProviderRouteRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexBizTaskProviderTest,CodexTaskServiceTest,CodexStreamRelayTest,CodexWorkerClientTest,CodexTaskControllerTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3051
git diff --check
```

Live query smoke requires explicit opt-in:

```powershell
powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3051 -RunLiveQueries
```

## Progress Tracking

### Development Progress

- [x] Step 1: `1.3.2-SNAPSHOT` readiness workitem and progress skeleton created.
- [x] Step 2: invocation contract frozen and linked to implementation docs.
- [x] Step 3: session / Java / Worker regression coverage updated.
- [x] Step 4: Worker readiness diagnostics updated.
- [x] Step 5: CLI profile / live smoke entry added.

### Testing Progress

| Case | Scope | Status | Notes |
| --- | --- | --- | --- |
| Worker typecheck | TypeScript | pass | `npm --prefix tools/codex-agent-worker run typecheck`. |
| Worker unit tests | TypeScript | pass | `npm --prefix tools/codex-agent-worker test`: 70 tests pass. |
| Provider route registry regression | Java | pass | `mvn test -pl navigator-common -am "-Dtest=ProviderRouteRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 9 tests pass. |
| Session routing regression | Java | pass | `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 59 tests pass. |
| Codex addon regression | Java | pass | `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexBizTaskProviderTest,CodexTaskServiceTest,CodexStreamRelayTest,CodexWorkerClientTest,CodexTaskControllerTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 44 tests pass. |
| Smoke helper parse check | PowerShell | pass | `codex-biz-smoke.ps1` parsed with PowerShell parser after Windows PowerShell SSE compatibility fix. |
| Smoke helper health check | PowerShell / local Worker | pass | Local Worker on `127.0.0.1:3070` with temp `CODEX_BIZ_HOME_ROOT`: `bizHomeConfigured=True`, `bizReady=True`. |
| Live actor A/B smoke | PowerShell / real Codex | pass | Actor A session `019f0c56-bccf-7df2-ab4d-7ea110486b8d`; Actor B session `019f0c56-fb4e-7193-9a62-10157833a9da`; Actor A resume reused Actor A session. |
| `git diff --check` | workspace | pass | No whitespace errors; Git reported existing CRLF normalization warnings. |

### Experience Progress

- N/A. 本事项为后端/Worker/API readiness，不新增或修改 UI 页面、表单、按钮、弹窗、权限可见性或前端交互。

## Execution Check-in

- status: completed
- completed work summary:
  - Step 1 文档入口和 readiness 工作项已创建。
  - Step 2 已回写 Worker 上游接入文档，补齐 Codex Biz 字段、稳定缺配置错误、`/health` readiness 和 smoke helper 用法。
  - Step 3 已补 session-bound Codex Biz resume metadata 回归，以及 `CodexBizTaskProvider` snake_case account / policy alias 回归；既有 `CodexWorkerClientTest` 继续覆盖 Java -> Worker body passthrough。
  - Follow-up 已收紧 `codex-biz-worker` 独立 provider 注册链路：补齐显式 create、目录默认 `OPENAI_CODEX` 模型 + 显式 biz provider、非 Codex 模型拒绝路径。
  - Follow-up 已收紧 Java addon 层 `codex-biz-worker` 执行边界：补齐 Biz provider lookup/list/search 强制带 provider filter、普通 Codex 任务不进入 Biz 视图、snake_case Biz 参数归一化、普通 Codex Worker body 不携带 Biz 专属字段，以及 legacy create endpoint 不丢失显式 Biz provider。
  - Step 4 已在 Worker `/health` 返回 `codex_biz_home_root_configured` 和 `codex_biz_scoped_home_ready`，并统一 `CODEX_BIZ_HOME_ROOT` 缺失错误常量。
  - Step 5 已新增 `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1`，默认只做 health readiness，`-RunLiveQueries` 才执行 actor A/B 和 resume live query。
  - Live smoke 过程中补齐 Worker 子进程 credential env hygiene：无有效 key 时清理 `OPENAI_API_KEY` / `CODEX_API_KEY`，有有效 key 时钉住有效值；同时为 scoped `CODEX_HOME` 种子化默认 Codex home 的 `auth.json`。
- touched code paths:
  - `docs/version-tracker/1.3.2-SNAPSHOT/README.md`
  - `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-001-codex-biz-route-readiness.md`
  - `tools/codex-agent-worker/docs/upstream-integration.md`
  - `tools/codex-agent-worker/src/models.ts`
  - `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
  - `tools/codex-agent-worker/src/routes/query.ts`
  - `tools/codex-agent-worker/src/routes/health.ts`
  - `tools/codex-agent-worker/tests/health.test.ts`
  - `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1`
  - `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/client/CodexWorkerClientTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/controller/CodexTaskControllerTest.java`
- self-check conclusion: scope closed for planned Steps 1~5; formal quality gate can be run before signoff if this becomes a release checkpoint.
- test status:
  - `npm --prefix tools/codex-agent-worker run typecheck`: pass.
  - `npm --prefix tools/codex-agent-worker test`: pass, 70 tests.
  - `mvn test -pl navigator-common -am "-Dtest=ProviderRouteRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: pass, 9 tests.
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: pass, 59 tests.
  - `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexBizTaskProviderTest,CodexTaskServiceTest,CodexStreamRelayTest,CodexWorkerClientTest,CodexTaskControllerTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: pass, 44 tests.
  - PowerShell parser check for `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1`: pass.
  - `powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3070`: pass.
  - `powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3070 -Cwd D:\foggy-projects\Foggy-Navigator-wt-qd-win11-dev -RunLiveQueries`: pass.
  - `git diff --check`: pass, with CRLF normalization warnings only.
- remaining risks / blockers:
  - blocking_items: none
  - Production deployment still must configure `CODEX_BIZ_HOME_ROOT` outside the repository and protect copied scoped `auth.json` files as credentials.
- acceptance readiness: ready-for-signoff
