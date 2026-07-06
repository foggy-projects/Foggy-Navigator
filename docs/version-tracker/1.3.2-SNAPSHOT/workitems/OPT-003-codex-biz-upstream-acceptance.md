---
type: optimization
version: 1.3.2-SNAPSHOT
ticket: OPT-003
severity: high
status: signed-off-with-risks
owner: session-module | business-agent-module | codex-worker-agent | codex-agent-worker | navigator-upstream-cli
created_at: 2026-06-30
---

# OPT-003: Codex Biz Upstream Acceptance

## 文档作用

- doc_type: workitem
- intended_for: root-controller | execution-agent | reviewer | signoff-owner
- purpose: 将 CodexBizWorker 从 route readiness 推进到可交付上游验收的通用接入、smoke、证据和签收闭环。

## Background

`OPT-001` 已完成 `codex-biz-worker` route readiness 签收，确认该路线作为显式内部调试 / 开发者通道可继续联调；`OPT-002` 已补齐 `LANGGRAPH_BIZ` Actor Home 目录契约。SIM 侧迁移评估进一步暴露了正式业务链路的关键阻塞点：`submit_skill_result`、BusinessFunction、structured output、tool message、`contextId` continuation 和 workspace 边界需要真实端到端 smoke 才能交付上游验收。

本项不把 SIM 文档中的所有假设提升为全局协议。SIM、TMS 和后续上游共享的是 Navigator OpenAPI / BusinessAgentTask 的通用派发契约，各上游可以通过不同 profile、route、client-app、actor/account 映射拿到运行时上下文。

## Relationship To Existing Work

- Upstream source: SIM migration assessment `bizworker-usage-codex-biz-worker-migration-assessment-20260629.md`.
- Prior readiness: `OPT-001-codex-biz-route-readiness.md`.
- Directory hardening: `OPT-002-langgraph-biz-actor-home-readiness.md`.
- This work item owns the post-readiness acceptance plan for CodexBizWorker as a shared upstream route.

## Target Outcome

交付一套可复核的 CodexBizWorker 上游验收包：

- Navigator 能为 CodexBizWorker 任务解析出 effective directory / workspace，并在 diagnostics 或 evidence 中可观测。
- self-owned smoke upstream 完成 ClientApp、Agent、Directory、Worker、`OPENAI_CODEX` model grant 和 route/profile provisioning。
- `submit_skill_result`、BusinessFunction、structured output、tool message、错误传播、`contextId` continuation 通过真实端到端 smoke。
- SIM 和 TMS 都有接入差异说明，不要求二者采用相同 runtime 字段形态。
- 验收材料足以支撑对外说明：CodexBizWorker 可作为显式灰度 / 内部调试 / 开发者通道交付；正式企业业务默认路线继续保留 LangBizWorker，除非另开生产切换评审。

## Glossary

- ClientApp: Navigator 登记的外部业务调用方身份，不是浏览器 client、LLM provider 或 Worker 进程。
- Upstream / consumer: 从 Navigator Java / OpenAPI 视角调用 Navigator 的外部业务系统，例如 SIM、TMS 或 self-owned smoke upstream；不是 LLM provider 或 Worker。
- self-owned smoke upstream: Navigator 自己维护的隔离测试 ClientApp、tenant、directory、agent 和 profile，用于基础 smoke，不复用 SIM / TMS 生产配置。
- effective directory: Worker 派发前已解析出的 Navigator workspace 身份；可能来自请求字段、context binding、route/profile/client-app/agent/actor/account 映射或 smoke 默认目录，不等同于 ask body 必传 `directoryId`，也不是本机文件系统路径。
- Codex scoped home: Codex Worker 侧由 `codexHomeKey` / `privateAccountId` 派生的隔离 home，用于区分 actor/account 的 Codex auth/state；不替代 Navigator directory。
- business runtime context: Java -> Worker 的结构化运行时上下文，例如 `business_runtime_context.task_scoped_token`；该 token 只允许进入结构化字段和 MCP 子进程环境，不进入 prompt、日志或 health response。

## Confirmed Contract

### Route Positioning

- `LANGGRAPH_BIZ` / LangBizWorker 继续作为企业应用和正式业务编排默认路线。
- `providerType=codex-biz-worker` / CodexBizWorker 是显式 opt-in route，用于内部调试、开发者自用、Codex-native 执行和诊断。
- 不把 CodexBizWorker 声明为 LangBizWorker 的透明替代。

### Effective Directory

`directoryId` 不再定义为所有上游 ask body 的必传字段。通用契约改为：

- Worker 派发前必须解析出 effective directory / workspace。
- effective directory 可以来自请求显式字段、route/profile、ClientApp 默认目录、actor/account 映射、或已绑定 `contextId`。
- 缺少 effective directory 且当前任务需要 actor-owned workspace 时，必须 fail-fast，稳定错误语义沿用 `TASK_DIRECTORY_REQUIRED` 或等价 readiness marker。
- diagnostics / evidence / readiness 应暴露非敏感 effective directory 线索，例如 `directoryId`、workspace mode、provider worker binding 或 readiness blocker；不得暴露本机绝对路径中的敏感凭证。

建议解析优先级：

1. 已绑定 `contextId` 的 session/provider/worker/directory。
2. 请求显式 `directoryId` / `workingDirectoryId`。
3. route/profile/client-app/agent/actor/account 绑定目录。
4. ClientApp 或测试 upstream 默认目录。
5. 无法解析时 fail-fast。

### Context Continuation

- 已绑定 `contextId` 后，上游不必再传 `providerType`、`workerId`、`directoryId` 或 `sessionId`；Navigator 使用 context/session 绑定恢复 provider、worker 和 directory。
- CodexBizWorker 新建 context 的首轮请求仍需要 route/profile/upstream-user 映射或显式 `privateAccountId` / `codexHomeKey` 提供 Codex scoped-home 身份；一旦 `contextId` 绑定完成，Navigator 会从 session provider state 回放 scoped-home，后续 continuation 可以只传 `contextId`。
- 如果上游仍显式传入 provider/worker/directory/session/scoped-home 字段，必须与 context 绑定一致。
- 冲突时 fail-fast，不允许静默切换到另一类 Worker。
- 2026-06-30 scoped-home replay 修复之前创建、且 session provider state 中没有 scoped-home 的旧 context，建议重新建 context；必要时可用同一 actor/account 的 `privateAccountId` / `codexHomeKey` 续接一次以补齐绑定。

### Codex Scoped Home

- `codexHomeKey` 是 Codex scoped home 隔离 key。
- `privateAccountId` / `private_account_id` 可作为兼容 alias 或派生来源。
- 两者同时存在时，`codexHomeKey` 是 effective scoped-home key，`privateAccountId` 作为 account alias/fallback 保留。
- 新建 context 时默认应由 Navigator route/profile/upstream user/account 映射派生；只有同一 actor/account 需要多个 Codex home 时，上游才需要显式传 `codexHomeKey`。
- 已绑定 context continuation 不应重复选择 scoped-home；如显式传入不同 `privateAccountId` / `codexHomeKey`，Navigator 返回 `CONTEXT_WORKER_MISMATCH`。
- `directoryId` 只表达 Navigator workspace，不参与替代 `codexHomeKey`。

### Business Runtime Context

- `businessRuntimeContext` / `business_runtime_context` 是 Java -> Worker 的结构化运行时上下文。
- `task_scoped_token` 只允许进入该结构化字段和 MCP 子进程环境，不得进入 prompt、developer instructions、health response 或普通日志。
- Worker-side `navigator_business` MCP bridge 可以提供 BusinessFunction list/schema/invoke；真实验收必须验证 Navigator WorkerGateway、tool message audit、SSE / messages / evidence 形态。

## Non-Goals

- 不默认把 SIM 或 TMS 正式业务链路切离 LangBizWorker。
- 不要求所有上游都在 ask body 显式传 `directoryId`。
- 不把 TMS 现有 profile、TMS tenant binding 或 `tms-agent-v305` 用作 CodexBizWorker 基础 smoke 的前置依赖。
- 不新增 UI。
- 不在文档、日志、health 或 evidence 中记录 API key、task scoped token、auth 文件内容、真实 `CODEX_BIZ_HOME_ROOT`。

## Module Responsibility

| Module / Repo | Responsibility | Can Start Now | Depends On |
| --- | --- | --- | --- |
| root version tracker | 维护通用契约、实施顺序、验收矩阵和签收结论。 | yes | none |
| `session-module` | 保持 context-bound provider/worker/directory continuation 规则和冲突 fail-fast。 | yes | current tests |
| `business-agent-module` | BusinessAgentTask 侧解析 effective directory、BusinessFunction token/runtime context、WorkerGateway 审计与 readiness。 | yes | smoke resources |
| `addons/codex-worker-agent` | CodexBizWorker provider 边界、Java -> Worker body、scoped home、BusinessAgent launcher。 | yes | worker readiness |
| `tools/codex-agent-worker` | Worker `/health`、scoped home、`navigator_business` MCP bridge、live smoke helper。 | yes | `CODEX_BIZ_HOME_ROOT`, Codex auth |
| `tools/navigator-upstream` / `navigator-open-sdk` | self-owned smoke upstream provisioning、admin-key approve/claim、model grant、directory、agent、owner-smoke、model key clear/rotate。 | yes | smoke model credential; current verified path uses `CODEX_API_KEY` env + proxy baseUrl |
| SIM upstream | 消费通用 contract，保留 actor-owned directory mapping，执行 SIM-specific regression。 | after Stage 3 | shared smoke pass |
| TMS upstream | 消费通用 contract，通过 TMS profile/route/client-app 解析 effective directory，执行 TMS-specific regression。 | after Stage 3 | shared smoke pass |

## Code Inventory

| Repo | Path | Role | Expected Change | Notes |
| --- | --- | --- | --- | --- |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-003-codex-biz-upstream-acceptance.md` | source-of-truth workitem | create | 本文件。 |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/README.md` | version index | update | 增加 OPT-003 入口和当前状态。 |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/quality/OPT-003-implementation-quality.md` | quality gate | created | 正式质量门禁已执行。 |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/coverage/OPT-003-coverage-audit.md` | coverage audit | created | 测试证据覆盖审计已执行。 |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/acceptance/OPT-003-codex-biz-upstream-acceptance.md` | acceptance record | created | 对外交付签收记录；结论为 accepted-with-risks。 |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/handoff/OPT-003-consumer-handoff.md` | consumer handoff | created | SIM / TMS consumer smoke 协议、wrapper gate 和 evidence checklist。 |
| root | `tools/navigator-upstream/scripts/codex-biz-smoke-approve.ps1` | admin-key approval helper | update if needed | 已存在；需用有效 operator/admin key 运行。 |
| root | `tools/navigator-upstream/scripts/codex-biz-smoke-bootstrap.ps1` | isolated smoke provisioning | updated | 生成 smoke ClientApp / Agent / Directory / Worker / model grant；已补 worker token env、managed directory、owner-aware grant 和 explicit ask 参数。 |
| root | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/cli/UpstreamCli.java` | upstream CLI | updated | 已补 `model clear-key` / `model system-clear-key` 与 ask `allowedTools` runtime option；项目本地 wrapper 已更新到 1.0.18。 |
| root | `tools/navigator-upstream` | project-local CLI wrapper | updated | 本地 jar / VERSION / BUILD_INFO / RELEASE_MANIFEST 更新到 1.0.18；保留 `scripts/codex-biz-smoke-*.ps1`。 |
| root | `business-agent-module` / `metadata-config-module` / `navigator-common` | model config key lifecycle | updated | 已补 `clearApiKey` 支持，允许清空 model config provider key 以验证 Worker local auth fallback。 |
| root | `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1` | Worker health/live query smoke | update if needed | 已存在；用于 local Worker readiness 和 actor A/B scoped home。 |
| root | `session-module` | context binding / continuation | read-only-analysis or update | 只有发现 OPT-001 覆盖不足时才补代码。 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/model/entity/BusinessAgentSessionEntity.java` | BusinessAgent session binding | updated | 会话读模型新增 `directoryId` / `workerId` / `workerProviderType` / `modelConfigId`，用于后续 context 资源兼容校验；属于 additive schema change。 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentSessionService.java` | BusinessAgent context continuation guard | updated | `bindTask` 现在保存 task 解析出的 agent/directory/model/worker 绑定；新增 context resource compatibility 校验，并兼容历史误把 `skillId` 写入 `agentId` 的旧 session。 |
| root | `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/BusinessAgentTaskService.java` | BusinessAgent task create path | updated | 在 task/token 创建和 Worker launch 前执行 context 资源冲突校验，避免同一 context 静默切换 agent/directory/model。 |
| root | `addons/codex-worker-agent` | Codex Biz provider and launcher | read-only-analysis or update | 重点验证 runtime fields 和 tool message 形态。 |
| root | `tools/codex-agent-worker/src/business-mcp` | Navigator BusinessFunction MCP bridge | updated | list/schema/invoke、token hygiene、Content-Length framing 和 MCP tool 粒度 allowlist。 |
| root | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java` | OpenAPI task evidence | updated | `TaskEvidence.structuredOutput` 可从 Codex final JSON 的 `structured_output.*` 自动提升。 |
| SIM repo | `foggy-world-sim/docs/versions/v0.0.706/tms-real-rehearsal-m0` | SIM consumer evidence | update later | 回写 SIM-specific acceptance notes，不改写通用 contract。 |
| TMS repo | TMS upstream profile/docs | TMS consumer evidence | update later | 由 TMS owner 按通用 contract 补接入说明和 smoke evidence。 |

## Implementation Plan

### Stage 0: Contract Rebase

- [x] 将 SIM-only `directoryId` 必传口径改为 Navigator effective directory 口径。
- [x] 明确 CodexBizWorker 是补充路线，不是 LangBizWorker 透明替换。
- [x] 定义 SIM / TMS 共享验收关注点：tool, function, structured output, context, workspace, evidence。

### Stage 1: Self-Owned Smoke Upstream Provisioning

- [x] 使用有效 Navigator operator/admin key 执行 `codex-biz-smoke-approve.ps1`。
- [x] claim admin-key 并 provision isolated ClientApp / control key / runtime token。
- [x] 创建 `OPENAI_CODEX` model config 和 grant；当前可用 live-smoke 组合为 `CODEX_API_KEY` env + `https://codex2.qlfloor.com:8443/v1` proxy baseUrl。
- [x] 创建 smoke directory、Codex worker anchor、gray Agent 和 route/profile binding。
- [x] 记录 provisioning 输出到 gitignored `temp/codex-biz-smoke/`，把非敏感摘要回写到本 workitem。

### Stage 2: Worker Readiness And Local Smoke

- [x] 确认目标 Codex Worker 使用仓库外绝对 `CODEX_BIZ_HOME_ROOT`。
- [x] 跑 Worker `/health` readiness。
- [ ] 如需要，显式 opt-in 跑 actor A/B scoped home live query。
- [x] 验证 health 和 smoke 输出不泄露真实 root path、API key、auth 内容。

### Stage 3: Navigator End-To-End Smoke

- [x] 发起基础 BusinessAgent ask，验证 Navigator -> CodexBizWorker -> provider live route 可完成。
- [x] 发起 deterministic `submit_skill_result` task。
- [x] 验证 `/tasks`、`/messages`、`/evidence`、`/diagnostics` 中的 provider、workerBackend、contextId、effective directory 与 final marker。`TaskEvidence.structuredOutput` 已补 Codex final JSON `structured_output.*` 自动提升；OPEN_ARTIFACT 仍可用 final JSON / function result / tool-message evidence 交叉验证。
- [x] 执行低风险 BusinessFunction：schema -> invoke -> internal tool-message audit。
- [x] 验证 `business_runtime_context.task_scoped_token` 不进入 prompt / developer instructions / 普通日志；Worker scoped `config.toml` 只持久化 command/cwd/args/env var names/MCP approval whitelist。
- [x] 跑 `contextId` continuation 正例：只传 `contextId`。
- [x] 跑 `contextId` continuation 负例：传冲突 directory/provider/scoped-home 并确认 fail-fast。

### Stage 4: Consumer Gray Acceptance

- [x] SIM: 通用接入协议已按 actor-owned directory mapping 兼容，不要求改成 TMS profile 形态；待 SIM owner 在自身 repo 回写 consumer smoke。
- [x] TMS: 通用接入协议已按 TMS profile/route/client-app effective directory 兼容，不要求显式沿用 SIM actor `directoryId`；待 TMS owner 在自身 repo 回写 consumer smoke。
- [x] 两边记录要求已明确：请求入口、effective directory 来源、codex home 来源、BusinessFunction/tool smoke 结果、回退边界。

### Stage 5: Signoff Package

- [x] 完成 execution check-in。
- [x] 执行正式 `foggy-implementation-quality-gate`。
- [x] 执行正式 `foggy-test-coverage-audit`。
- [x] 执行正式 `foggy-acceptance-signoff`，生成 `acceptance/OPT-003-codex-biz-upstream-acceptance.md`。
- [x] 将交付结论整理为 SIM / TMS consumer owner 可验收的协议与证据清单。

## Delivery Protocol Adjustments

- `directoryId` 表示 Navigator workspace/effective directory，不是 SIM 专属必填字段。SIM 可由 actor-owned mapping 派生，TMS 可由 profile/route/client-app 派生；进入 Worker 前必须解析出 effective directory。
- `privateAccountId` / `codexHomeKey` 表示 Codex scoped home 隔离来源，不替代 `directoryId`。当前 self-owned smoke 使用 `privateAccountId=codex-biz-smoke-user`。
- Continuation 时上游不应重复传 `providerType` / `workerType` / `directoryId` / `privateAccountId` / `codexHomeKey` 来选择 Worker 或 Codex scoped-home；Navigator 会使用已绑定 `contextId` 的 session provider state。新建 context 的首轮请求仍需提供或派生 scoped-home。
- `allowedTools` 已作为 OpenAPI/SDK ask 顶层 runtime option 透传，并按 MCP tool 粒度生效：`business.functions.list` -> `list_business_functions`，`business.functions.schema` -> `get_business_function_schema`，`business.functions.invoke` -> `invoke_business_function`，`business.functions.*` / `business.*` -> 三个工具全部开放；不使用旧的 `navigator_business.*` 口径，业务函数 id 不等同 MCP tool grant。
- CodexBizWorker 的 navigator_business MCP bridge 使用 scoped `CODEX_HOME/config.toml` managed block 注入 no-secret server config；task token 只通过 MCP 子进程继承的 env var 值传递，不持久化。
- Windows/Codex MCP 需要 `cwd` 指向 `tools/codex-agent-worker`，stdio 使用 `Content-Length` framing；Codex CLI 0.142.3 还需要 server 级 `default_tools_approval_mode = "approve"` 与 `enabled_tools` 白名单，否则会出现 tools/list 可见但 tools/call 被客户端取消。
- `submit_skill_result` 的 OPEN_ARTIFACT 在 CodexBizWorker 当前验收形态下以 BusinessFunction invoke result、tool-message audit、final JSON marker 和 `TaskEvidence.structuredOutput` 交付；Evidence source 可能是 `task_state`、`message_metadata` 或 `message_content`。

## Consumer Handoff Runbook

Navigator self-owned acceptance is complete. The next execution owner should only add consumer-specific evidence or follow-up enhancements; do not reopen the completed Navigator smoke unless the route, Worker, or SDK contract changes.

1. SIM consumer smoke。
   - Use the SIM repo's actor-owned directory mapping as the source of the effective Navigator directory.
   - Keep `directoryId` as a resolved Navigator workspace concept, not a mandatory universal ask-body field.
   - Record route/profile, directory source, Codex home source, fallback behavior, task id, context id, and OPEN_ARTIFACT evidence in the SIM repo.
2. TMS consumer smoke。
   - Use the TMS profile/route/client-app mapping as the source of the effective Navigator directory.
   - Keep the same `allowedTools` protocol and `submit_skill_result` evidence shape validated here.
   - Record route/profile, directory source, Codex home source, fallback behavior, task id, context id, and OPEN_ARTIFACT evidence in the TMS repo.
3. Optional follow-up enhancements。
   - Confirm the `navigator-upstream-cli` 1.0.18 package is published to the consumer-visible release channel before relying on `self update`; this project-local wrapper already uses 1.0.18.
   - Add provider/worker live conflict negatives only if the explicit override protocol changes; current conflict behavior is protected by session-module regression tests.
4. Detailed handoff。
   - Use `docs/version-tracker/1.3.2-SNAPSHOT/handoff/OPT-003-consumer-handoff.md` for SIM / TMS smoke commands, wrapper gate and evidence checklist.

## Acceptance Criteria

- self-owned smoke upstream provisioning 完成，且不依赖 TMS 现有 profile。
- Codex Worker readiness 显示 scoped home configured / ready，且不泄露敏感路径或凭证。
- Navigator E2E smoke 能完成 `submit_skill_result`，并在 messages/evidence 中保留可验收的 structured output 与 tool call/result 线索。
- BusinessFunction schema/invoke 通过 Navigator WorkerGateway 执行，tool-message audit 可观测。
- `business_runtime_context.task_scoped_token` 只存在于结构化运行时和 MCP 子进程环境。
- `contextId` continuation 正例和冲突负例均通过。
- effective directory 解析结果可观测；SIM 和 TMS 可采用各自来源解析，不强制统一显式字段。
- SIM 和 TMS 接入说明均完成，明确各自的 route/profile、directory/codex home 来源和回退方式。
- 验收文档明确：CodexBizWorker 可交付显式灰度 / 内部调试 / 开发者通道；正式生产默认切换另开评审。

## Verification Plan

```powershell
powershell -ExecutionPolicy Bypass -File tools/navigator-upstream/scripts/codex-biz-smoke-approve.ps1
powershell -ExecutionPolicy Bypass -File tools/navigator-upstream/scripts/codex-biz-smoke-bootstrap.ps1
powershell -ExecutionPolicy Bypass -File tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3070
mvn -q -am -pl metadata-config-module,business-agent-module,navigator-open-sdk "-Dtest=LlmModelManagerImplTest,ClientAppOwnedModelConfigServiceTest,UpstreamAdminModelConfigServiceTest,UpstreamCliTest,BusinessAgentSessionServiceTest,BusinessAgentTaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -q -am -pl addons/claude-worker-agent "-Dtest=OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
npm --prefix tools/codex-agent-worker run typecheck
npm --prefix tools/codex-agent-worker test
```

Targeted regression commands may be added as Stage 3/4 exposes concrete code deltas. Current known baseline from OPT-001:

```powershell
npm --prefix tools/codex-agent-worker run typecheck
npm --prefix tools/codex-agent-worker test
mvn test -pl session-module -am "-Dtest=TaskDispatchFacadeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
mvn test -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest,CodexBusinessAgentWorkerTaskLauncherTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
git diff --check
```

## Progress Tracking

### Precondition Check

| Item | Status | Notes |
| --- | --- | --- |
| OPT-001 route readiness signed off | pass | Accepted on 2026-06-29. |
| OPT-002 actor home directory hardening ready | pass | Ready-for-signoff. |
| self-owned smoke profile exists | pass | `.navigator/codex-biz-smoke.env` exists and is gitignored per OPT-001 record. |
| smoke helper scripts exist | pass | `navi.ps1`, approve/bootstrap scripts, and Worker smoke script are present. |
| smoke profile/artifact paths gitignored | pass | `.navigator/*.env`, `.navigator/tenants/*.env`, and `temp/` are covered by gitignore. |
| admin-key request / approval | pass | Request `ubreq_72a92559-3f86-4b1a-9eb2-77a3bf721f8a`, code suffix `WH1A43OE`, approved at `2026-06-30T11:16:56.637421900`; tenant `navi-codex-biz-smoke-local`, upstreamSystemId `navi-codex-biz-smoke`. No admin/operator key recorded. |
| tenant smoke profile exists | pass | `.navigator/tenants/codex-biz-smoke-local.env` generated by bootstrap and gitignored. |
| Navigator Upstream CLI installed | pass | Repaired `navigator-upstream-cli 1.0.18` package reports buildId `1.0.18+6d1eb7431155.dirty`, packageSha `b94556726789124837cbf683f4acb69f75df89dfa5c0d7144dcd3dfc3a5084a2`; consumer release verification must compare packageSha/buildId, not only semantic version. |
| owner-smoke provisioning | pass | ClientApp `capp_62137cc6-584a-42db-8ffc-35508a98aa80`, agent `codex-biz-smoke-agent`, modelConfig `48212e4e-fd63-4ec6-8fe5-47089a19824c`, directory `20260630-143b`, worker `3ad8bb7b`. |
| `NAVI_LLM_API_KEY` for model config creation | pass with deviation | Not required for the completed smoke after model was created. Final live-smoke key source was process env `CODEX_API_KEY` via `--api-key-env CODEX_API_KEY`; no raw key recorded. |
| default local Codex Worker health | pass | `http://127.0.0.1:3070/health` reports status ok, worker `codex-worker-biz-gray`, `codex_login`, scoped home configured and ready. |

### Development Progress

- [x] Stage 0: Contract rebase recorded.
- [x] Stage 1.1: local profile/script/gitignore preflight recorded.
- [x] Stage 1: self-owned smoke upstream provisioned.
- [x] Stage 2.1: Worker health readiness passed against default local Worker.
- [x] Stage 2: Worker readiness and local smoke recorded for health / init-directory.
- [x] Stage 3.0: Navigator basic BusinessAgent live ask completed through CodexBizWorker.
- [x] Stage 3.1: deterministic `submit_skill_result` smoke completed.
- [x] Stage 3.2: BusinessFunction schema/invoke and tool-message audit completed.
- [x] Stage 3.3: `contextId` continuation positive / directory-conflict negative completed.
- [x] Stage 4: SIM / TMS consumer gray acceptance protocol handed off; consumer-side smoke evidence remains their follow-up.
- [x] Stage 5: quality, coverage and acceptance signoff completed.

### Testing Progress

| Case | Scope | Status | Evidence |
| --- | --- | --- | --- |
| route readiness baseline | OPT-001 | pass | Existing OPT-001 quality / coverage / acceptance docs. |
| Stage 1.1 preflight | local docs/scripts/profile hygiene | pass | Scripts exist; system profile has request code; smoke profile/artifact paths are gitignored. |
| Java targeted regression | metadata-config-module + business-agent-module + navigator-open-sdk | pass | `mvn -q -am -pl metadata-config-module,business-agent-module,navigator-open-sdk "-Dtest=LlmModelManagerImplTest,ClientAppOwnedModelConfigServiceTest,UpstreamAdminModelConfigServiceTest,UpstreamCliTest,BusinessAgentSessionServiceTest,BusinessAgentTaskServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`. |
| Java OpenAPI evidence regression | addons/claude-worker-agent | pass | `mvn -q -am -pl addons/claude-worker-agent "-Dtest=OpenApiControllerMessageMappingTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`; covers OPEN_ARTIFACT lift from final JSON `structured_output.*`. |
| Worker typecheck | tools/codex-agent-worker | pass | `npm run typecheck`. |
| Worker unit tests | tools/codex-agent-worker | pass | `npm test -- tests/navigator-business-mcp.test.ts tests/sdk-wrapper.test.ts`; script ran the full Worker suite, 97 tests passed. |
| isolated provisioning | Navigator CLI | pass | owner-smoke passed with ClientApp / Agent / ModelConfig / Directory / Worker resources listed in precondition table. |
| Worker health smoke | Codex Worker | pass | Health reports `status=ok`, `codex_login`, `codex_biz_home_root_configured=true`, `codex_biz_scoped_home_ready=true`. |
| Worker init-directory probe | Codex Worker | pass | Direct probe created `temp/codex-biz-smoke/.init-route-probe.md`; validates safe managed-directory file init route. |
| backend restart / health | Navigator current project | pass | `.\start-launcher.ps1` restarted current Navi service after OpenAPI evidence update; health is UP on `8112`, listening Java PID `34100`. |
| provider auth diagnostic: official base without request key | Navigator + Worker + OpenAI official endpoint | expected-fail | Task `20260630-718c`, providerTask `7ec71212-caac-4a85-8bdc-44da326389ac`; failed with provider 401 missing `api.responses.write`, confirming no leaked request key path. |
| provider auth diagnostic: proxy without key | Navigator + Worker + proxy endpoint | expected-fail | Task `20260630-c1a7`, providerTask `51d9756f-1761-4a1d-8f16-ab025385d1fe`; failed with provider 401 invalid API key, confirming proxy still needs a key. |
| basic BusinessAgent live ask | Navigator + CodexBizWorker + provider | pass | Task `20260630-c4c8`, contextId `bctx_20260630_ef_ef2799df98f7416bacb45135e8a79d4a`, provider/workerTask `2785924a-110c-412f-92cc-d62aa64d84b5`; marker `CODEX_BIZ_SMOKE_OK_20260630_CODEX_KEY` returned. |
| BusinessAgent context resource guard | business-agent-module | pass | `BusinessAgentSessionServiceTest` covers resource binding persistence, directory mismatch rejection, and legacy `skillId`-as-`agentId` compatibility; `BusinessAgentTaskServiceTest` covers fail-before-task-save when context resource validation rejects. |
| context continuation positive | Navigator + CodexBizWorker + provider | pass | Post-guard task `20260630-62ca`, same contextId `bctx_20260630_ef_ef2799df98f7416bacb45135e8a79d4a`, provider/workerTask `cb52bc9a-d24a-42a4-ab5a-8eec2496e89a`; request intentionally omitted directory/model/provider overrides and returned marker `CODEX_BIZ_CONTEXT_BINDING_AFTER_GUARD_OK_20260630`. Earlier context-only continuation task `20260630-8a88` also passed with marker `CODEX_BIZ_CONTEXT_CONTINUATION_OK_20260630`. |
| context continuation negative: valid directory conflict | Navigator route/session guard | pass | Created second ClientApp directory `20260630-cee1`, bound it as non-default workspace to `codex-biz-smoke-agent`, then reused the same context with `--directory-id 20260630-cee1`; CLI exited `2` with HTTP 400 `CONTEXT_WORKER_MISMATCH: directoryId 20260630-cee1 conflicts with context/session-bound directory 20260630-143b`. No task id was issued. |
| `submit_skill_result` E2E | Navigator + Worker | pass | Task `20260630-b499`, contextId `bctx_20260630_36_36787f40b092468e8183687a84ea0d01`, workerTask/providerTask `3c9ef3e8-80c6-4061-8e6c-231c6ae0c95c`, marker `codex-biz-smoke-20260630-134920`; final JSON reports `functionId=submit_skill_result`, `status=SUCCESS`, `structured_output.type=OPEN_ARTIFACT`. |
| BusinessFunction E2E | Navigator + WorkerGateway + Worker | pass | Same task: MCP `tools/call` list/schema/invoke all completed; WorkerGateway GET `/business-functions` 200, GET `/business-functions/submit_skill_result/schema` 200, POST `/business-functions/submit_skill_result/invoke` 200, POST `/tool-messages` 200. |
| 2026-06-30 rerun owner-smoke | Navigator CLI / local 8112 | pass | Tenant profile `.navigator/tenants/codex-biz-smoke-local.env`; effective directory `20260630-143b`, worker `3ad8bb7b`, workerBackend `OPENAI_CODEX`, provider route `codex-biz-worker` readiness OK. |
| 2026-06-30 rerun `submit_skill_result` | Navigator + CodexBizWorker + WorkerGateway | pass | Task `20260630-0a61`, contextId `bctx_20260630_1b_1b347d05b3da48e9a7101738940c620d`, provider/workerTask `fcceaf4e-193d-4381-895a-5f178793ad29`; final marker `codex-biz-smoke-rerun-20260630-174033`, `functionId=submit_skill_result`, `status=SUCCESS`, `structuredOutput.source=message_content`, `structured_output.type=OPEN_ARTIFACT`. |
| 2026-06-30 rerun context continuation positive | Navigator context binding | pass | Task `20260630-4f73`, same contextId `bctx_20260630_1b_1b347d05b3da48e9a7101738940c620d`; request omitted provider, directory and model overrides, retained `privateAccountId=codex-biz-smoke-user`, and resolved to `providerType=codex-biz-worker`, workerBackend `OPENAI_CODEX`, worker `3ad8bb7b`; marker `codex-biz-context-bound-rerun-20260630-174304`. |
| 2026-06-30 context-bound scoped-home replay | Direct CLI continuation | pass | First task `20260630-d39b`, contextId `bctx_20260630_96_960f31e76ab94c2f94c453d9b9fa6be8`, provider/workerTask `6cf33d91-ee41-4042-a9bf-2801984fe664` used explicit `privateAccountId`; strict continuation task `20260630-00d8` used a temp no-profile env with provider/directory/model/scoped-home values removed and only `contextId`, resolved to `providerType=codex-biz-worker`, workerBackend `OPENAI_CODEX`, provider/workerTask `64249a06-1317-4424-85ca-84bfb175c528`, and returned `OK2`. |
| 2026-06-30 provider conflict negative | Navigator route/session guard | pass | Same contextId plus explicit `--provider-type langgraph-biz-worker` failed fast with HTTP 400 `CONTEXT_WORKER_MISMATCH: providerType langgraph-biz-worker conflicts with context/session-bound provider codex-biz-worker`; no silent route switch. |
| 2026-06-30 scoped-home conflict negative | Navigator route/session guard | pass | Same contextId plus conflicting `--private-account-id tenant/world-sim/scenario-1/actor-conflict` failed fast with HTTP 400 `CONTEXT_WORKER_MISMATCH: privateAccountId ... conflicts with context/session-bound scoped home ...`; no silent Codex home switch. |
| MCP tool allowlist granularity | Codex Worker | pass | `navigator-business-mcp.test.ts` and `sdk-wrapper.test.ts` cover grant-to-tool mapping, filtered `tools/list`, blocked disallowed `tools/call`, and `enabled_tools` for `business.functions.invoke`. |
| `TaskEvidence.structuredOutput` OPEN_ARTIFACT lift | OpenAPI evidence | pass | `OpenApiControllerMessageMappingTest` covers visible final JSON content with flattened `structured_output.type`, `structured_output.artifact.*` and source `message_content`; live task `20260630-af4c` re-read after service restart reports `structuredOutput.available=true`, `source=message_content`, `value.type=OPEN_ARTIFACT`. |
| Codex MCP approval config | Worker scoped Codex home | pass | Managed scoped `config.toml` writes `default_tools_approval_mode = "approve"` and `enabled_tools = ["list_business_functions", "get_business_function_schema", "invoke_business_function"]`; no task token persisted. |
| task-scoped token hygiene | Worker + backend logs | pass | Worker log and MCP debug log record only env var names / task ids / gateway path/status; no task token, API key, auth content, or `CODEX_BIZ_HOME_ROOT` value appears in smoke evidence. Backend debug records token registration by task/session id only. |
| context continuation positive / directory-conflict negative | Navigator + Worker | pass | See dedicated rows above. Provider/worker conflict live cases remain optional unless their explicit override protocol changes. |
| SIM gray acceptance | Consumer | handed-off | Protocol allows SIM actor-owned directory mapping; SIM owner must run repo-specific smoke and record consumer evidence. |
| TMS gray acceptance | Consumer | handed-off | Protocol allows TMS profile/route/client-app effective directory mapping; TMS owner must run repo-specific smoke and record consumer evidence. |

### Experience Progress

- N/A. This work item covers backend / Worker / OpenAPI / upstream smoke and does not add or change UI pages, forms, lists, buttons, routes, or permission-visible UI.

### Implementation Self-Check

- scope conformance: pass for current staged scope; self-owned upstream smoke is complete and consumer-specific gray runs are handed off.
- non-goals preserved: pass; SIM / TMS production cutover and mandatory ask-body `directoryId` are explicitly out of scope.
- code/docs touched are listed: pass for current code and docs; later quality/coverage/acceptance docs remain expected outputs.
- tests and smoke evidence recorded: pass; Java / Worker regression, owner-smoke, Worker health, basic live ask, `submit_skill_result`, BusinessFunction schema/invoke/tool-message, live `TaskEvidence.structuredOutput` lift, and context continuation positive/directory-conflict negative passed.
- remaining risks documented: pass; consumer-side SIM/TMS smoke and consumer-visible wrapper release verification remain follow-ups.
- self-check conclusion: current implementation is ready for consumer validation and signed off with risks for the self-owned Navigator acceptance scope.

### Residual Risks / Follow-ups

- No current blocker for self-owned smoke provisioning, basic live ask, deterministic `submit_skill_result`, BusinessFunction schema/invoke, tool-message audit, or task-scoped token hygiene in this Navi workspace.
- `navigator-upstream-cli` project-local wrapper is updated to `1.0.18`; consumer projects still need to verify the published package by `packageSha256` / `buildId` before relying on `self update`.
- BusinessAgent session binding added nullable columns; local/default launcher uses additive schema update, but validate-only deployments must include the equivalent additive DDL before promotion.
- CodexBizWorker contexts created before the 2026-06-30 scoped-home replay fix may not have `codexHomeKey` / `privateAccountId` in session provider state; recreate those contexts or continue once with the same scoped-home to refresh the binding.
- Consumer gray acceptance is handed off to SIM and TMS owners; each must record its own route/profile, directory source, Codex home source, and fallback boundary.

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-06-30
- acceptance_record: docs/version-tracker/1.3.2-SNAPSHOT/acceptance/OPT-003-codex-biz-upstream-acceptance.md
- blocking_items: none
- follow_up_required: yes

## Execution Prompt

Use this prompt for the next consumer or follow-up execution agent:

```text
You are extending OPT-003: Codex Biz Upstream Acceptance after Navigator self-owned signoff.

Read first:
- docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-003-codex-biz-upstream-acceptance.md
- docs/version-tracker/1.3.2-SNAPSHOT/quality/OPT-003-implementation-quality.md
- docs/version-tracker/1.3.2-SNAPSHOT/coverage/OPT-003-coverage-audit.md
- docs/version-tracker/1.3.2-SNAPSHOT/acceptance/OPT-003-codex-biz-upstream-acceptance.md
- CLAUDE.md

Goal:
- Add SIM/TMS consumer-owned smoke evidence or implement an explicitly approved follow-up.
- Preserve the accepted protocol adjustments: effective directory is resolved by the upstream adapter, `allowedTools` is a top-level ask runtime option with MCP-tool granularity, and OPEN_ARTIFACT can be evidenced by `TaskEvidence.structuredOutput`, function result, tool-message audit, and final JSON marker.

Do not:
- Use TMS existing profile or tms-agent-v305 as the foundation smoke.
- Treat CodexBizWorker as the default enterprise production route.
- Put task_scoped_token, API keys, auth contents, or real CODEX_BIZ_HOME_ROOT paths into docs or logs.

Completion:
- Update consumer-specific evidence and residual risks in OPT-003 or the consumer repo.
- Run targeted tests for any code changes.
- Run git diff --check.
```

## Consumer Evidence Plan

Consumer follow-up signoff must include:

- Provisioning summary for isolated smoke upstream.
- Worker health/readiness summary.
- `submit_skill_result` task evidence: taskId, contextId, providerType, workerBackend, effective directory marker, structured output location.
- BusinessFunction evidence: function id/version, schema result summary, invoke result summary, WorkerGateway/tool-message audit marker.
- Context continuation evidence: positive continuation without provider override, negative conflict fail-fast.
- SIM/TMS consumer notes: directory source, codex home source, route/profile, fallback route.
- Test command summary and result.
- Explicit production boundary statement.
