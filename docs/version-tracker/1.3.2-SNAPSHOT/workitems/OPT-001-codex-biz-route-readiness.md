---
type: optimization
version: 1.3.2-SNAPSHOT
ticket: OPT-001
severity: medium
status: signed-off
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

定位原则（2026-06-29）：LangBizWorker 与 CodexBizWorker 是互补路线，不是替换关系。企业应用和正式业务编排默认继续使用 `LANGGRAPH_BIZ` / LangBizWorker，以保留 root skill、`submit_skill_result`、BusinessFunction、审批/挂起和业务审计闭环；CodexBizWorker 面向内部调试、开发者自用和 Codex-native 代码执行/诊断场景，必须通过显式 `providerType=codex-biz-worker` 进入。

当前下一步不是重做架构，也不是把企业应用默认从 LangBizWorker 切到 CodexBizWorker，而是把 CodexBizWorker 这条补充路线按 1~5 推成可继续联调的最小闭环。

## Confirmed Invocation Contract

OpenAPI / unified dispatch request:

- `providerType=codex-biz-worker` 用于显式进入 Codex Biz route。
- `modelConfigId` 可指向 `workerBackend=OPENAI_CODEX` 的模型配置；`codex-biz-worker` 与 `codex-worker` 在模型后端上兼容。
- `workerId` / `directoryId` / `cwd` 沿用 Codex Worker 执行定位语义。
- `sessionId` 用于 Navigator 多轮会话续接；resume 时以 session 绑定 provider 为准。
- `contextId` 是上游可稳定持有的 continuation key；当 `contextId` 已绑定 Navigator session 时，后续 ask 可只传 `contextId`，不必再传 `providerType` / worker type，Navigator 自动使用该 context 绑定的 session provider、worker 和 directory。
- 当上游在已绑定 `contextId` 上继续时，如果仍传入 `providerType`、`workerId`、`directoryId` 或 `sessionId`，这些值必须与 context 绑定一致；冲突时 fail-fast，错误语义为 `CONTEXT_WORKER_MISMATCH` 或 `CONTEXT_SESSION_MISMATCH`，不得静默切换到另一类 Worker。

Codex Biz metadata / params:

- `codexHomeKey` 或 `codex_home_key` 为 actor/account scoped home key。
- `privateAccountId` / `private_account_id` 可作为 `codexHomeKey` 的兼容别名。
- `developerInstructions` / `developer_instructions` 透传到 Worker `developer_instructions`。
- `outputSchema` / `output_schema` 透传到 Worker `output_schema`。
- `codexConfig` / `codex_config` 透传到 Worker `codex_config`。
- `sandboxMode` / `sandbox_mode`、`approvalPolicy` / `approval_policy`、`networkAccessEnabled` / `network_access_enabled`、`webSearchMode` / `web_search_mode` 支持直接字段，也支持 `codexPolicy` / `codex_policy` 包装。
- `additionalDirectories` / `additional_directories` 透传到 Worker `additional_directories`，并受 Worker allowed cwd 约束。
- `businessRuntimeContext` / `business_runtime_context` 是服务端运行时上下文，允许携带 `task_scoped_token`、业务 task/session/context 标识、`allowed_tools` 和 `allowed_dirs`；该字段只进入 Java -> Worker 结构化请求，不写入 prompt / `developerInstructions` / 日志。

Worker contract:

- 带 `codex_home_key` 的请求必须配置绝对路径 `CODEX_BIZ_HOME_ROOT`。
- 缺少 `CODEX_BIZ_HOME_ROOT` 时必须稳定返回或抛出：`CODEX_BIZ_HOME_ROOT is required when codex_home_key is provided`。
- 健康检查只能暴露是否配置 scoped home root，不暴露真实 root 路径。
- 当前 Codex Worker 已有 Worker-side BusinessFunction MCP bridge，但真实 `submit_skill_result` / BusinessFunction / structured output 端到端 smoke 尚未完成；依赖原 BizWorker root skill 工具的上游任务不能按“完全等价替换”切换。

## Non-Goals

- 不把 Codex Biz route 改造成 LangGraph BizWorker。
- 不把企业应用默认 Worker 从 LangBizWorker 迁移为 CodexBizWorker；两者按场景并行互补。
- 不新增独立可发现 Agent。
- 不改变 `OPENAI_CODEX` 模型配置与 `codex-worker` 的兼容规则。
- 不要求本阶段跑真实 OpenAI 付费调用；live smoke 作为 opt-in。
- 不在健康检查或日志中输出 API Key、auth token、真实 `CODEX_BIZ_HOME_ROOT` 路径或 actor home 绝对路径。
- 不在本阶段声称 Codex Biz 已具备原 BizWorker 的 `submit_skill_result` / BusinessFunction 工具等价能力；该能力需要后续独立 tool/MCP adapter。

## Route Positioning

| Route | Primary Use | Default Owner | Notes |
| --- | --- | --- | --- |
| `LANGGRAPH_BIZ` / LangBizWorker | 企业应用、正式业务编排、上游 SaaS 业务链路 | 业务系统 / 上游应用 | 保持默认路线；继续承载 root skill、BusinessFunction、审批/挂起、业务审计和现有 DB binding。 |
| `providerType=codex-biz-worker` / CodexBizWorker | 内部调试、开发者自用、Codex-native 代码执行和诊断 | Navigator / 调试操作者 | 显式 opt-in；要求 `OPENAI_CODEX` model grant、Codex Worker readiness、`CODEX_BIZ_HOME_ROOT`、`directoryId` 和稳定 `codexHomeKey/privateAccountId`。 |

## Module Responsibility

| Module | Responsibility |
| --- | --- |
| `docs/version-tracker/1.3.2-SNAPSHOT` | 记录 readiness 目标、契约、进度、测试证据和验收状态。 |
| `session-module` | 保护 direct create / resume 路由、provider/model 兼容、session-bound provider 优先级和 metadata 透传。 |
| `business-agent-module` | 通过 worker backend 选择业务 Agent worker launcher，并把 BusinessAgentTask 与物理 Worker/会话/context 绑定。 |
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
| root | `tools/codex-agent-worker/tests/query-route-paths.test.ts` | Worker allowed cwd regression | create | 覆盖 `D:\repo` 不允许匹配 `D:\repo2` 这类同前缀 sibling path。 |
| root | `tools/codex-agent-worker/src/codex/sdk-wrapper.ts` | scoped home resolver | update | 导出稳定错误常量，保持 home key hashing 和路径脱敏。 |
| root | `tools/codex-agent-worker/tests/health.test.ts` | Worker unit | update | 覆盖 readiness 字段计算。 |
| root | `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1` | opt-in smoke helper | create | 检查 health readiness；可选择跑 actor A/B live query。 |
| root | `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java` | session routing regression | update | 覆盖 Codex Biz explicit create、目录默认模型路由、非 Codex 模型拒绝、resume 走 session-bound provider 并透传 metadata。 |
| root | `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java` | context continuation binding | update | 已绑定 `contextId` 的 create/ask 自动转 resume，并复用 session-bound provider/worker/directory。 |
| root | `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java` | session-bound provider guard | update | 显式 provider 与 session-bound provider 冲突时 fail-fast，不再静默改写。 |
| root | `session-module/src/main/java/com/foggy/navigator/session/repository/AgentConversationContextRepository.java` | context lookup source | reuse | 通过 `contextId + userId` 反查 Navigator session 绑定。 |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java` | OpenAPI ask context projection | update | OpenAPI ask 使用 Agent owner 作为 Navigator effective user，保证 context binding 能在 open ask 路径落库和续接。 |
| root | `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java` | OpenAPI ask regression | update | 覆盖 `AgentResolveContext.userId` 投影到 Agent owner，避免 contextId 续聊丢 user 维度。 |
| root | `addons/codex-worker-agent/pom.xml` | addon dependency | update | Codex addon 引入 `business-agent-module`，用于注册 BusinessAgent worker launcher。 |
| root | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncher.java` | Business Agent Codex launcher | create | `OPENAI_CODEX` backend 在 BusinessAgentTask 路径显式创建 `providerType=codex-biz-worker` 任务。 |
| root | `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncherTest.java` | Business Agent launcher regression | create | 覆盖 scoped account key、pool member fallback、providerType 固定为 `codex-biz-worker`。 |
| root | `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java` | provider normalization / routing regression | update | 覆盖 snake_case alias、codexPolicy 包装字段、provider-filtered lookup/list/search 和 resume 默认 policy。 |
| root | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/model/form/CreateCodexTaskForm.java` | legacy request compatibility | update | legacy create endpoint 支持 snake_case alias 和 `privateAccountId`。 |
| root | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java` | legacy Biz guard | update | 所有 create/resume 路径对 `codex-biz-worker` 强制要求 `codexHomeKey` 或 `privateAccountId`。 |
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
mvn test -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexBizTaskProviderTest,CodexTaskServiceTest,CodexBusinessAgentWorkerTaskLauncherTest,CodexStreamRelayTest,CodexWorkerClientTest,CodexTaskControllerTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3051
git diff --check
```

Live query smoke requires explicit opt-in:

```powershell
powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3051 -RunLiveQueries
```

## Deployment Preflight Checklist

| Item | Required Before Production | Status |
| --- | --- | --- |
| Configure absolute `CODEX_BIZ_HOME_ROOT` outside the repository on the target Worker host. | yes | external-deployment-check |
| Protect scoped home directories and copied `auth.json` files with credential-grade filesystem permissions. | yes | external-deployment-check |
| Confirm `OPENAI_CODEX` model configs are valid for both `codex-worker` and explicit `codex-biz-worker` route use. | yes | external-deployment-check |
| Start target Worker and verify `/health` returns `codex_biz_home_root_configured=true` and `codex_biz_scoped_home_ready=true`. | yes | external-deployment-check |
| Run `codex-biz-smoke.ps1` without `-RunLiveQueries` in the target environment. | yes | external-deployment-check |
| Run actor A/B live smoke only with explicit approval and valid credentials. | optional | opt-in |
| Confirm logs and health output do not expose API keys, auth token contents, real `CODEX_BIZ_HOME_ROOT`, or scoped home absolute paths. | yes | external-deployment-check |

## Progress Tracking

### Development Progress

- [x] Step 1: `1.3.2-SNAPSHOT` readiness workitem and progress skeleton created.
- [x] Step 2: invocation contract frozen and linked to implementation docs.
- [x] Step 3: session / Java / Worker regression coverage updated.
- [x] Step 4: Worker readiness diagnostics updated.
- [x] Step 5: CLI profile / live smoke entry added.
- [x] 2026-06-29 follow-up 1: OpenAPI ask 已投影 Agent owner user 到 `AgentResolveContext`，`contextId` 续聊可在 open ask 路径使用同一用户维度绑定。
- [x] 2026-06-29 follow-up 2: context 绑定冲突已提前到派发前校验，跨用户、跨 agent、跨 session 的 `contextId` 不会先启动 Worker 再失败。
- [x] 2026-06-29 follow-up 3: BusinessAgentTask 的 `OPENAI_CODEX` backend 已新增独立 launcher，创建 `codex-biz-worker` 而不是普通 `codex-worker`。
- [x] 2026-06-29 follow-up 4: 上游迁移原则已回写：已绑定 `contextId` 续接时无需继续传 `workerType/providerType`，传了则必须与绑定一致。
- [x] 2026-06-29 follow-up 5: 定向测试与 diff 校验已执行。
- [x] 2026-06-29 follow-up 6: `task_scoped_token` 已从 `developerInstructions` 移到结构化 `businessRuntimeContext` / `business_runtime_context`，避免把业务运行时 token 暴露给模型提示词。
- [x] 2026-06-29 follow-up 7: 已复核 `contextId` continuation 的 OpenAPI 入口；只传 `contextId` 且不传 `providerType` 时，OpenAPI 不会强制补 provider，后续由 session/context binding 恢复原 worker。
- [x] 2026-06-29 follow-up 8: Actor Home / `codexHomeKey` / `directoryId` A/B 隔离已有 Java 与 Worker 回归覆盖，scoped home root 仍要求部署环境显式配置。
- [x] 2026-06-29 follow-up 9: 已记录灰度/回滚边界：生产切换前必须保留原 BizWorker route/profile，并先完成工具桥 smoke。
- [x] 2026-06-29 follow-up 10: Codex Biz Worker 已新增内置 `navigator_business` MCP bridge，基于 `business_runtime_context.task_scoped_token` 注入模型可见的 `list_business_functions` / `get_business_function_schema` / `invoke_business_function`；`report_tool_message` 改为 Worker 内部审计上报，token 不进入 prompt 或 developer instructions。
- [x] 2026-06-29 follow-up 11: 已执行本地 Navigator / Codex Biz smoke attempt；独立启动的 Codex Worker 3070 在临时 `CODEX_BIZ_HOME_ROOT` 下通过 health 和 actor A/B live smoke，但本地上游 profile 在 Navigator 8112 侧被 readiness 拦截：`tms-agent-v305` 为 disabled，且缺少有效 `OPENAI_CODEX` model grant、client directory、physical worker / worker pool binding。
- [ ] 2026-06-29 follow-up 12: 仍需在非生产 Navigator 环境补齐 enabled 灰度 Agent、`OPENAI_CODEX` model grant、client directory、Codex Biz physical worker / worker pool binding，再跑真实 `submit_skill_result` / BusinessFunction / `contextId` continuation 端到端 smoke，并与原 BizWorker 的 TOOL_CALL / TOOL_RESULT / structured output 形态做等价验收。
- [x] 2026-06-29 follow-up 13: 已固化 LangBizWorker 与 CodexBizWorker 互补定位；企业应用默认继续走 LangBizWorker，CodexBizWorker 仅作为显式内部调试/开发者通道推进 readiness。
- [x] 2026-06-29 follow-up 14: 已按 1~5 重跑本地 readiness 盘点；CLI/control plane 可用，但灰度资源仍缺 enabled Agent、`OPENAI_CODEX` grant、client directory 和 Codex Biz physical worker binding，未进入 live ask。
- [x] 2026-06-29 follow-up 15: 已启动本地灰度 `codex-worker-biz-gray` on `127.0.0.1:3070`，使用仓库外 `CODEX_BIZ_HOME_ROOT`，`/health`、`codex-biz-smoke.ps1` readiness 与 actor A/B live smoke 均通过；前一轮使用 TMS/88800 profile 验证 readiness 被证明不合适，后续不再依赖 TMS profile、TMS tenant binding 或 `tms-agent-v305` 做 CodexBizWorker 基础验证。
- [ ] 2026-06-29 follow-up 16: CodexBizWorker 端到端 smoke 改为 self-owned Navigator test upstream：新增 gitignored profile `.navigator/codex-biz-smoke.env`，`upstreamSystemId=navi-codex-biz-smoke`，本地 bootstrap 目标 tenant 为 `navi-codex-biz-smoke-local`，已发起 multi-tenant admin-key request（status `PENDING`，suffix `WH1A43OE`，expires `2026-06-30T22:35:33`；服务端 status 的 `requestedTenantId` 为空，目标 tenant 由后续 provision 参数指定）。审批并 claim 后，只在该独立 ClientApp / Agent / Directory / Worker 资源内完成 `OPENAI_CODEX` grant、Codex Biz gray Agent、BusinessFunction 与 context continuation smoke。
- [x] 2026-06-29 follow-up 17: 已准备 self-owned smoke 审批与 bootstrap 入口：`tools/navigator-upstream/scripts/codex-biz-smoke-approve.ps1` 用有效 operator key 执行 admin-key approve；`tools/navigator-upstream/scripts/codex-biz-smoke-bootstrap.ps1` 串起 admin-key claim、ClientApp runtime/control key、`worker-host apply`、`OPENAI_CODEX` model create、directory init、Agent sync/default binding、grant 与 `owner-smoke`；真实 `ask` 仅在显式 `-RunAsk` 时执行。运行时 JSON 写入 gitignored `temp/codex-biz-smoke/`，profile 使用 gitignored `.navigator/codex-biz-smoke.env` 与 `.navigator/tenants/codex-biz-smoke-local.env`。

### Testing Progress

| Case | Scope | Status | Notes |
| --- | --- | --- | --- |
| Worker typecheck | TypeScript | pass | `npm --prefix tools/codex-agent-worker run typecheck`. |
| Worker unit tests | TypeScript | pass | `npm --prefix tools/codex-agent-worker test`: 89 tests pass, including Navigator Business MCP config/gateway mapping coverage. |
| Provider route registry regression | Java | pass | `mvn test -pl navigator-common -am "-Dtest=ProviderRouteRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 9 tests pass. |
| Session routing regression | Java | pass | `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 59 tests pass. |
| Codex addon regression | Java | pass | `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexBizTaskProviderTest,CodexTaskServiceTest,CodexStreamRelayTest,CodexWorkerClientTest,CodexTaskControllerTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 44 tests pass. |
| OpenAPI context projection regression | Java | pass | `mvn test -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 34 tests pass. |
| Business Agent Codex launcher regression | Java | pass | `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest,CodexBusinessAgentWorkerTaskLauncherTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 28 tests pass. |
| Codex Biz runtime context regression | Java / TypeScript | pass | `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexBusinessAgentWorkerTaskLauncherTest,CodexTaskServiceTest,CodexStreamRelayTest,CodexWorkerClientTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: 34 tests pass; Worker validation tests pass in `npm test`. |
| ContextId continuation without providerType | Java | pass | `TaskDispatchFacadeTest` covers context-bound `codex-biz-worker` continuation without worker/provider type; `OpenApiControllerMessageMappingTest` asserts OpenAPI ask leaves `metadata.providerType` unset when the form omits it. |
| BusinessFunction gateway smoke | Java | pass | `WorkerGatewayServiceTest`: 22 tests pass, covering BusinessFunction gateway behavior independent of Codex Worker. |
| Business Agent E2E smoke | Java | blocked | `BusinessAgentE2ESampleTest` and `RestAdapterUpstreamE2ETest` currently fail with `TASK_DIRECTORY_REQUIRED: directoryId is required for Actor-owned BizWorker task`; fixtures/upstream payloads must be updated for the new directory contract before this can be used as migration evidence. |
| Codex Biz tool bridge unit coverage | Codex Worker | pass | Worker-side `navigator_business` MCP bridge maps list/schema/invoke calls to Navigator WorkerGateway with `X-Task-Scoped-Token`; `invoke_business_function` performs best-effort internal tool-message audit reporting. Covered by `navigator-business-mcp.test.ts` and `sdk-wrapper.test.ts`. |
| Codex Biz tool parity smoke | Codex Worker / Navigator | blocked | 2026-06-29 refreshed preflight against the legacy TMS profile cannot create a Navigator task-scoped runtime: `owner-smoke` and `verify-agent-readiness` fail with `Agent is disabled: tms-agent-v305`. This path is now superseded; live `ask` remains intentionally skipped until the self-owned `navi-codex-biz-smoke` profile has its own ClientApp, Agent, Directory, Worker and `OPENAI_CODEX` grant. |
| Default Codex Worker Biz readiness | PowerShell / local Worker | blocked | Existing current-workspace worker on `127.0.0.1:3051` reports `codex_biz_home_root_configured=false` and `codex_biz_scoped_home_ready=false`; it must be restarted with absolute `CODEX_BIZ_HOME_ROOT` before serving `codex-biz-worker` traffic. |
| Gray Codex Worker Biz readiness | PowerShell / local Worker | pass | `codex-worker-biz-gray` on `127.0.0.1:3070` reports `codex_auth_mode=codex_login`, `codex_biz_home_root_configured=true`, `codex_biz_scoped_home_ready=true`; `codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3070` passed health readiness. |
| Route positioning doc update | Versioned docs | pass | LangBizWorker / CodexBizWorker complementary positioning is now explicit; production enterprise apps should not treat CodexBizWorker readiness as a default route replacement. |
| Smoke helper parse check | PowerShell | pass | `codex-biz-smoke.ps1` parsed with PowerShell parser after Windows PowerShell SSE compatibility fix. |
| Smoke helper health check | PowerShell / local Worker | pass | Local Worker on `127.0.0.1:3070` with temp `CODEX_BIZ_HOME_ROOT`: `bizHomeConfigured=True`, `bizReady=True`. |
| Live actor A/B smoke | PowerShell / real Codex | pass | 2026-06-29 latest local 3070 run: Actor A session `019f13c8-af2b-7f13-852a-8f17aa788b2a`; Actor B session `019f13c8-f03c-7a31-8716-a38ec2f99dfd`; Actor A resume reused Actor A session. |
| Legacy TMS upstream profile inventory | Navigator CLI / local 8112 | superseded | CLI `1.0.16`; `.navigator/upstream.env` exists and is gitignored. `route list` shows 2 enabled upstream routes; `directory client-list` returns `directoryCount=0`; `model grants` returns 3 enabled grants, all `LANGGRAPH_BIZ`; no `OPENAI_CODEX` grant is available for this ClientApp. This profile is not used for CodexBizWorker foundation smoke because it belongs to the TMS integration boundary. |
| Codex Biz smoke profile bootstrap | Navigator CLI / local 8112 | blocked | `.navigator/codex-biz-smoke.env` exists, is gitignored, and carries a pending self-owned upstream admin-key request for `upstreamSystemId=navi-codex-biz-smoke`; local bootstrap target tenant is `navi-codex-biz-smoke-local`, while service-side multi-tenant status currently returns empty `requestedTenantId`. Status is `PENDING`, suffix `WH1A43OE`. A local historical operator key returned 401, so resource provisioning waits for a valid Navigator operator/admin approval and claim. |
| Codex Biz smoke bootstrap script | PowerShell / local files | pass | `tools/navigator-upstream/scripts/codex-biz-smoke-approve.ps1` and `tools/navigator-upstream/scripts/codex-biz-smoke-bootstrap.ps1` parse successfully. Bootstrap generates worker-host / directory / agent JSON under gitignored `temp/codex-biz-smoke/`, requires the admin-key request to be approved first, and requires `NAVI_LLM_API_KEY` before creating the enforced `OPENAI_CODEX` model config. |
| Upstream admin resource provisioning | Navigator CLI / local 8112 | blocked | Do not use the available TMS/88800 credential for this smoke. With a valid operator key, run `powershell -ExecutionPolicy Bypass -File tools/navigator-upstream/scripts/codex-biz-smoke-approve.ps1`, then run `powershell -ExecutionPolicy Bypass -File tools/navigator-upstream/scripts/codex-biz-smoke-bootstrap.ps1`; it provisions only the isolated smoke ClientApp, `OPENAI_CODEX` model grant, directory, Codex worker anchor and gray Agent under `navi-codex-biz-smoke`. |
| Legacy BusinessFunction visibility | Navigator CLI / local 8112 | superseded | The legacy TMS profile can list 65 client-visible functions, but those grants belong to the old integration boundary and are not used as CodexBizWorker smoke evidence. The self-owned smoke ClientApp must import/grant its own low-risk test function before WorkerGateway invoke evidence is accepted. |
| ContextId continuation smoke | Navigator CLI / local 8112 | blocked | Deferred until the self-owned gray route can create its first Navigator task and return a `contextId`; the positive continuation case must omit `providerType`, and the negative case must pass a conflicting provider to verify fail-fast behavior. |
| `git diff --check` | workspace | pass | No whitespace errors; Git reported existing CRLF normalization warnings. |

### Next Smoke Plan

1. 使用独立 Navigator smoke 上游推进，不复用 TMS profile、TMS DB binding 或 `tms-agent-v305`：profile 为 `.navigator/codex-biz-smoke.env`，`upstreamSystemId=navi-codex-biz-smoke`，目标资源命名统一加 `codex-biz-smoke` 前缀。拿到有效 operator key 后先运行 `powershell -ExecutionPolicy Bypass -File tools/navigator-upstream/scripts/codex-biz-smoke-approve.ps1`；审批通过后设置本 shell 的 `NAVI_LLM_API_KEY`，再运行 `powershell -ExecutionPolicy Bypass -File tools/navigator-upstream/scripts/codex-biz-smoke-bootstrap.ps1` 完成 isolated ClientApp / Worker / model / directory / Agent provisioning。保留并继续使用原 `LANGGRAPH_BIZ` / LangBizWorker profile 作为企业应用默认路线；CodexBizWorker 只在该独立 smoke ClientApp 下作为显式内部调试通道验证。确认 `CODEX_BIZ_HOME_ROOT`、`CODEX_NAVIGATOR_WORKER_GATEWAY_BASE_URL`、`directoryId`、`privateAccountId/codexHomeKey` 都显式可观测。
2. 用同一个上游调用入口发起 deterministic `submit_skill_result` task，记录 `taskId/contextId/sessionId/providerType/workerBackend/directoryId`，并确认 token 只进入 `businessRuntimeContext`，不进入 prompt 或 developer instructions。
3. 让 Codex Biz Worker 执行一次低风险 BusinessFunction：先 `get_business_function_schema(function_id, version)`，再 `invoke_business_function(function_id, version, input)`；核对 WorkerGateway invoke、内部 tool-message audit、SSE `tool_use/tool_result` 和终态 structured output。
4. 对比原 BizWorker 与 Codex Biz Worker 的 `/tasks`、`/messages`、`/evidence`、`/diagnostics`：重点看 `submit_skill_result`、`structuredOutput.value`、`metadata.args.structured_output`、`artifactRefs/reportRefs`、错误传播和 approval/suspend 形态。
5. 使用同一 `contextId` 续接且不传 `workerType/providerType`，确认 Navigator 自动使用 context 绑定的 worker；同时做一次显式传错 provider 的负例，确认 fail-fast 而不是静默切换。

### Experience Progress

- N/A. 本事项为后端/Worker/API readiness，不新增或修改 UI 页面、表单、按钮、弹窗、权限可见性或前端交互。

### Signoff Progress

- [x] Implementation quality gate completed: `docs/version-tracker/1.3.2-SNAPSHOT/quality/OPT-001-implementation-quality.md`
- [x] Test coverage audit completed: `docs/version-tracker/1.3.2-SNAPSHOT/coverage/OPT-001-coverage-audit.md`
- [x] Feature acceptance signed off: `docs/version-tracker/1.3.2-SNAPSHOT/acceptance/OPT-001-codex-biz-route-readiness-acceptance.md`

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
  - Signoff follow-up 已完成正式 implementation quality gate、test coverage audit 和 feature acceptance signoff。
  - 2026-06-29 migration follow-up: 已按上游 SIM 调研结论固化 `contextId` continuation 规则；已绑定 context 可不再传 `providerType` / worker type，Navigator 自动反查 session-bound provider/worker/directory 并转 resume。
  - 2026-06-29 migration follow-up: 已将 session-bound provider 冲突从静默改写改为 fail-fast `CONTEXT_WORKER_MISMATCH`，避免上游误把旧 BizWorker 会话切到 `codex-biz-worker` 或反向切换。
  - 2026-06-29 migration follow-up: 已补 legacy Codex create endpoint 的 Biz home 强校验和 `privateAccountId` alias，防止绕过 OpenAPI 时启动未隔离的 `codex-biz-worker`。
  - 2026-06-29 migration follow-up: 已修复 Worker allowed cwd 前缀匹配边界，`D:\repo` 不再允许 `D:\repo2` 这类同前缀 sibling path。
  - 2026-06-29 migration follow-up 2: 已修复 OpenAPI ask 的 `AgentResolveContext.userId` 投影，使用 Agent owner 作为 Navigator effective user，使 `contextId + userId` 绑定能覆盖 `/api/v1/open/agents/{agentId}/ask`。
  - 2026-06-29 migration follow-up 2: 已在 `TaskDispatchFacade.createTask` 派发前预检已有 `contextId` 绑定，跨用户/agent/session 冲突直接失败，不再先启动 Worker。
  - 2026-06-29 migration follow-up 2: 已新增 `CodexBusinessAgentWorkerTaskLauncher`，BusinessAgentTask 选择 `OPENAI_CODEX` backend 时进入 `providerType=codex-biz-worker`，并按 `tenant/clientApp/upstreamUser` 派生 scoped `codexHomeKey/privateAccountId`。
  - 2026-06-29 migration follow-up 3: 已在 Codex Worker 内新增 `navigator_business` MCP bridge；当 Java 侧传入 `businessRuntimeContext.task_scoped_token` 时，Worker 动态注入模型可见的 BusinessFunction list/schema/invoke 工具，并在 invoke 后内部上报 tool-message，token 仅进入 MCP 子进程环境变量。
  - 2026-06-29 migration follow-up 4: 已补充路线定位原则：LangBizWorker 保持企业应用默认通道，CodexBizWorker 作为显式内部调试/开发者通道，不作为原 BizWorker 的透明替换。
  - 2026-06-29 migration follow-up 5: 已重跑 Navigator CLI readiness 盘点和 Worker health；当前控制面可读，但灰度资源未满足 live ask 前置。
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
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
  - `addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiControllerMessageMappingTest.java`
  - `addons/codex-worker-agent/pom.xml`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncher.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBusinessAgentWorkerTaskLauncherTest.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/model/form/CreateCodexTaskForm.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexBizTaskProviderTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/client/CodexWorkerClientTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/controller/CodexTaskControllerTest.java`
  - `tools/codex-agent-worker/tests/query-route-paths.test.ts`
- quality gate conclusion: formal quality gate completed on 2026-06-29; no blocking implementation issues; ready-for-coverage-audit.
- coverage audit conclusion: formal coverage audit completed on 2026-06-29; no blocking evidence gaps; ready-for-acceptance.
- test status:
  - `npm --prefix tools/codex-agent-worker run typecheck`: pass.
  - `npm --prefix tools/codex-agent-worker test`: pass, 70 tests.
  - `mvn test -pl navigator-common -am "-Dtest=ProviderRouteRegistryTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: pass, 9 tests.
  - `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: pass, 59 tests.
  - `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexBizTaskProviderTest,CodexTaskServiceTest,CodexStreamRelayTest,CodexWorkerClientTest,CodexTaskControllerTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"`: pass, 44 tests.
  - 2026-06-29 signoff rerun: Worker typecheck pass; Worker unit tests pass, 70 tests; provider registry pass, 9 tests; session routing pass, 59 tests; Codex addon regression pass, 44 tests; `git diff --check` pass.
  - 2026-06-29 migration follow-up verification: `npm --prefix tools/codex-agent-worker run typecheck` pass.
  - 2026-06-29 migration follow-up verification: `npm --prefix tools/codex-agent-worker test` pass, 74 tests.
  - 2026-06-29 migration follow-up verification: `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` pass, 61 tests.
  - 2026-06-29 migration follow-up verification: `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` pass, 25 tests.
  - 2026-06-29 migration follow-up verification: `git diff --check` pass, with CRLF normalization warnings only.
  - 2026-06-29 migration follow-up 2 verification: `mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest,CodexBusinessAgentWorkerTaskLauncherTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` pass, 28 tests.
  - 2026-06-29 migration follow-up 2 verification: `mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` pass, 62 tests.
  - 2026-06-29 migration follow-up 2 verification: `mvn test -pl addons/claude-worker-agent -am "-Dtest=OpenApiControllerMessageMappingTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"` pass, 34 tests.
  - 2026-06-29 migration follow-up 2 verification: `git diff --check` pass in Navi repo and SIM repo, with CRLF normalization warnings only.
  - 2026-06-29 migration follow-up 3 verification: `npm --prefix tools/codex-agent-worker test` pass, 87 tests.
  - 2026-06-29 migration follow-up 3 verification: `npm --prefix tools/codex-agent-worker run typecheck` pass.
  - PowerShell parser check for `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1`: pass.
  - `powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3070`: pass.
  - `powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3070 -Cwd /home/sa/workspace/Foggy-Navigator -RunLiveQueries`: pass.
  - 2026-06-29 follow-up 5 verification: `navi.ps1 version` pass, CLI `1.0.16`; `upstream config check` pass with gitignored profile; `upstream model grants` shows 3 enabled grants, all `LANGGRAPH_BIZ`; `upstream directory client-list` shows `directoryCount=0`; `upstream route list` shows 2 enabled routes; `upstream function visible` shows 65 functions.
  - 2026-06-29 follow-up 5 verification: `upstream owner-smoke` blocked before Worker dispatch with `Agent is disabled: tms-agent-v305` and missing effective model config, worker backend, directory and physical worker.
  - 2026-06-29 follow-up 5 verification: `upstream verify-agent-readiness` blocked with the same disabled Agent readiness failure; live `ask` was skipped.
  - 2026-06-29 follow-up 5 verification: `GET http://127.0.0.1:3051/health` pass as a health endpoint but blocked for Biz readiness because `codex_biz_home_root_configured=false` and `codex_biz_scoped_home_ready=false`.
  - 2026-06-29 self-owned smoke bootstrap verification: `tools/navigator-upstream/scripts/codex-biz-smoke-approve.ps1` and `tools/navigator-upstream/scripts/codex-biz-smoke-bootstrap.ps1` PowerShell parser checks pass; `git check-ignore` confirms `.navigator/codex-biz-smoke.env`, `.navigator/tenants/codex-biz-smoke-local.env` and generated `temp/codex-biz-smoke/*` are ignored.
  - `git diff --check`: pass, with CRLF normalization warnings only.
- remaining risks / blockers:
  - blocking_items:
    - Self-owned `navi-codex-biz-smoke` admin-key request 仍处于 `PENDING`；本地历史 operator key 审批返回 401，需要有效 Navigator operator/admin 凭证审批后才能 claim 和 provision。
    - `navigator-upstream model create` 当前强制要求 `NAVI_LLM_API_KEY`；即使本地 Codex Worker 使用 `codex_login`，创建 `OPENAI_CODEX` model config 前仍需在执行 shell 中提供该环境变量。
    - Codex Biz 已有 Worker-side BusinessFunction MCP bridge，但尚未完成真实 `submit_skill_result` / BusinessFunction 端到端 smoke；依赖原 BizWorker root skill 工具的上游任务不能直接做全量替代验收。
    - 上游切换时如果把 CodexBizWorker 当成企业应用默认路线，仍可能绕过 LangBizWorker 已覆盖的审批、挂起、业务审计和 root skill 语义；正式业务链路应继续保留 LangBizWorker。
    - BusinessAgent E2E fixtures 仍需补齐 actor-owned `directoryId`，否则会被 `TASK_DIRECTORY_REQUIRED` fail-fast 拦截。
  - Production deployment still must configure `CODEX_BIZ_HOME_ROOT` outside the repository and protect copied scoped `auth.json` files as credentials.
- acceptance readiness: signed-off

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex
- signed_off_at: 2026-06-29
- acceptance_record: docs/version-tracker/1.3.2-SNAPSHOT/acceptance/OPT-001-codex-biz-route-readiness-acceptance.md
- blocking_items: none for original route readiness scope
- post_signoff_follow_up_required: yes
- post_signoff_follow_up:
  - Run Codex Biz Navigator BusinessFunction / `submit_skill_result` end-to-end smoke before full BizWorker migration acceptance.
  - Keep LangBizWorker enabled as the enterprise app default while CodexBizWorker is used for explicit internal debug / developer scenarios.
  - Update BusinessAgent E2E fixtures/payloads to include actor-owned `directoryId`.
