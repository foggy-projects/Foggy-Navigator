---
type: optimization
version: 1.3.2-SNAPSHOT
ticket: OPT-003
severity: high
status: in-progress
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

- 已绑定 `contextId` 后，上游可只传 `contextId` 续接，不必再传 `providerType`、`workerId`、`directoryId` 或 `sessionId`。
- 如果上游仍显式传入这些字段，必须与 context 绑定一致。
- 冲突时 fail-fast，不允许静默切换到另一类 Worker。

### Codex Scoped Home

- `codexHomeKey` 是 Codex scoped home 隔离 key。
- `privateAccountId` / `private_account_id` 可作为兼容 alias 或派生来源。
- 默认应由 Navigator route/profile/upstream user/account 映射派生；只有同一 actor/account 需要多个 Codex home 时，上游才需要显式传 `codexHomeKey`。
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
| `tools/navigator-upstream` / `navigator-open-sdk` | self-owned smoke upstream provisioning、admin-key approve/claim、model grant、directory、agent、owner-smoke。 | yes | valid operator/admin key, `NAVI_LLM_API_KEY` |
| SIM upstream | 消费通用 contract，保留 actor-owned directory mapping，执行 SIM-specific regression。 | after Stage 3 | shared smoke pass |
| TMS upstream | 消费通用 contract，通过 TMS profile/route/client-app 解析 effective directory，执行 TMS-specific regression。 | after Stage 3 | shared smoke pass |

## Code Inventory

| Repo | Path | Role | Expected Change | Notes |
| --- | --- | --- | --- | --- |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-003-codex-biz-upstream-acceptance.md` | source-of-truth workitem | create | 本文件。 |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/README.md` | version index | update | 增加 OPT-003 入口和当前状态。 |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/quality/OPT-003-implementation-quality.md` | quality gate | create later | Stage 完成后执行正式质量门禁。 |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/coverage/OPT-003-coverage-audit.md` | coverage audit | create later | E2E evidence ready 后执行。 |
| root | `docs/version-tracker/1.3.2-SNAPSHOT/acceptance/OPT-003-codex-biz-upstream-acceptance.md` | acceptance record | create later | 对外交付签收记录。 |
| root | `tools/navigator-upstream/scripts/codex-biz-smoke-approve.ps1` | admin-key approval helper | update if needed | 已存在；需用有效 operator/admin key 运行。 |
| root | `tools/navigator-upstream/scripts/codex-biz-smoke-bootstrap.ps1` | isolated smoke provisioning | update if needed | 已存在；生成 smoke ClientApp / Agent / Directory / Worker / model grant。 |
| root | `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1` | Worker health/live query smoke | update if needed | 已存在；用于 local Worker readiness 和 actor A/B scoped home。 |
| root | `session-module` | context binding / continuation | read-only-analysis or update | 只有发现 OPT-001 覆盖不足时才补代码。 |
| root | `business-agent-module` | BusinessAgentTask / WorkerGateway / effective directory diagnostics | read-only-analysis or update | 重点验证 evidence/diagnostics 是否可验收。 |
| root | `addons/codex-worker-agent` | Codex Biz provider and launcher | read-only-analysis or update | 重点验证 runtime fields 和 tool message 形态。 |
| root | `tools/codex-agent-worker/src/business-mcp` | Navigator BusinessFunction MCP bridge | read-only-analysis or update | 重点验证 list/schema/invoke 和 token hygiene。 |
| SIM repo | `foggy-world-sim/docs/versions/v0.0.706/tms-real-rehearsal-m0` | SIM consumer evidence | update later | 回写 SIM-specific acceptance notes，不改写通用 contract。 |
| TMS repo | TMS upstream profile/docs | TMS consumer evidence | update later | 由 TMS owner 按通用 contract 补接入说明和 smoke evidence。 |

## Implementation Plan

### Stage 0: Contract Rebase

- [x] 将 SIM-only `directoryId` 必传口径改为 Navigator effective directory 口径。
- [x] 明确 CodexBizWorker 是补充路线，不是 LangBizWorker 透明替换。
- [x] 定义 SIM / TMS 共享验收关注点：tool, function, structured output, context, workspace, evidence。

### Stage 1: Self-Owned Smoke Upstream Provisioning

- [ ] 使用有效 Navigator operator/admin key 执行 `codex-biz-smoke-approve.ps1`。
- [ ] claim admin-key 并 provision isolated ClientApp / control key / runtime token。
- [ ] 设置 `NAVI_LLM_API_KEY`，创建 `OPENAI_CODEX` model config 和 grant。
- [ ] 创建 smoke directory、Codex worker anchor、gray Agent 和 route/profile binding。
- [ ] 记录 provisioning 输出到 gitignored `temp/codex-biz-smoke/`，把非敏感摘要回写到本 workitem。

### Stage 2: Worker Readiness And Local Smoke

- [ ] 确认目标 Codex Worker 使用仓库外绝对 `CODEX_BIZ_HOME_ROOT`。
- [ ] 跑 `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1` health readiness。
- [ ] 如需要，显式 opt-in 跑 actor A/B scoped home live query。
- [ ] 验证 health 和 smoke 输出不泄露真实 root path、API key、auth 内容。

### Stage 3: Navigator End-To-End Smoke

- [ ] 发起 deterministic `submit_skill_result` task。
- [ ] 验证 `/tasks`、`/messages`、`/evidence`、`/diagnostics` 中的 provider、workerBackend、contextId、effective directory、structured output。
- [ ] 执行低风险 BusinessFunction：schema -> invoke -> internal tool-message audit。
- [ ] 验证 `business_runtime_context.task_scoped_token` 不进入 prompt / developer instructions / 普通日志。
- [ ] 跑 `contextId` continuation 正例：只传 `contextId`。
- [ ] 跑 `contextId` continuation 负例：传冲突 provider/worker/directory 并确认 fail-fast。

### Stage 4: Consumer Gray Acceptance

- [ ] SIM: 以 actor-owned directory mapping 方式接入，不要求改成 TMS profile 形态。
- [ ] TMS: 以 TMS 自身 profile/route/client-app 方式解析 effective directory，不要求显式沿用 SIM actor `directoryId`。
- [ ] 两边各记录：请求入口、effective directory 来源、codex home 来源、BusinessFunction/tool smoke 结果、回退边界。

### Stage 5: Signoff Package

- [ ] 完成 execution check-in。
- [ ] 执行正式 `foggy-implementation-quality-gate`。
- [ ] 执行正式 `foggy-test-coverage-audit`。
- [ ] 执行正式 `foggy-acceptance-signoff`，生成 `acceptance/OPT-003-codex-biz-upstream-acceptance.md`。
- [ ] 将交付结论同步给 SIM / TMS consumer owners。

## Next Execution Runbook

下一步先完成 Stage 1 / Stage 2 的基础闭环，避免直接拿 SIM 或 TMS 生产 profile 做首轮验证。

1. Stage 1.1: profile 与脚本前置检查。
   - 确认 `tools/navigator-upstream/navi.ps1` 可执行，`tools/navigator-upstream/scripts/codex-biz-smoke-approve.ps1` 和 `tools/navigator-upstream/scripts/codex-biz-smoke-bootstrap.ps1` 存在。
   - 确认 `.navigator/codex-biz-smoke.env`、`.navigator/tenants/codex-biz-smoke-local.env`、`temp/codex-biz-smoke/` 已被 gitignore 覆盖；不得把 profile、key、token 或 generated runtime artifact 纳入版本管理。
   - 确认 `.navigator/codex-biz-smoke.env` 中已有 `NAVI_ADMIN_KEY_REQUEST_CODE`，或由 operator 手工传入 `-RequestCode`。
2. Stage 1.2: Navigator operator approval。
   - 在只属于运维 / 管理员的 shell 中设置 `NAVI_OPERATOR_API_KEY`，不打印变量值。
   - 运行 `tools/navigator-upstream/scripts/codex-biz-smoke-approve.ps1`，默认授权 tenant `navi-codex-biz-smoke-local`、namespace `navi-codex-biz-smoke` 和 smoke 所需 scopes。
   - 只记录 request code、tenant、namespace、approval 状态和时间；不记录 operator key 或返回的 admin key。
3. Stage 1.3: self-owned smoke provisioning。
   - 在执行 shell 中设置 `NAVI_LLM_API_KEY`，不打印变量值。
   - 确认目标 Codex Worker `/health` 可访问，默认 bootstrap 端口为 `http://127.0.0.1:3070/health`。
   - 运行 `tools/navigator-upstream/scripts/codex-biz-smoke-bootstrap.ps1`，完成 admin-key claim、ClientApp ensure、runtime/control key、runtime token、worker-host apply、`OPENAI_CODEX` model create、directory client-init、agent sync、grant 和 owner-smoke。
   - 仅把非敏感摘要回写到本文 Progress Tracking：ClientApp id 是否存在、worker id 是否存在、directory id 是否存在、model config id 是否存在、owner-smoke pass/fail。
4. Stage 2.1: Worker readiness。
   - 确认 Worker 启动时使用仓库外绝对 `CODEX_BIZ_HOME_ROOT`。
   - 运行 `tools/codex-agent-worker/scripts/codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3070`，先只验 `/health` 的 `codex_biz_home_root_configured` 和 `codex_biz_scoped_home_ready`。
5. Stage 2.2: optional live query。
   - 只有在 Codex auth 和模型链路准备好后，显式增加 `-RunLiveQueries`。
   - actor A/B 与 resume 输出只记录 task/session/model/event_count 等非敏感字段；如果输出包含本机路径、auth 内容、API key 或 token，先修脚本脱敏再进入 Stage 3。

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
| admin-key request code available | pass | `NAVI_ADMIN_KEY_REQUEST_CODE` key is present in `.navigator/codex-biz-smoke.env`; value not recorded. |
| tenant smoke profile exists | pending | `.navigator/tenants/codex-biz-smoke-local.env` is expected to be generated by bootstrap. |
| Navigator Upstream CLI installed | pass | `navi.ps1 version` reports `navigator-upstream-cli 1.0.16`. |
| admin-key request status | blocked | `admin-key status` reports `PENDING`; approval has not completed. |
| valid operator/admin approval | blocked | `NAVI_OPERATOR_API_KEY` not present in Process/User/Machine env and not stored in checked smoke profiles. Required to approve pending request. |
| `NAVI_LLM_API_KEY` for model config creation | blocked | Not present in Process/User/Machine env or known local profiles. Required by current `navigator-upstream model create`. |
| default local Codex Worker health | pass | `http://127.0.0.1:3070/health` reports status ok, `codex_login`, scoped home configured and ready. |

### Development Progress

- [x] Stage 0: Contract rebase recorded.
- [x] Stage 1.1: local profile/script/gitignore preflight recorded.
- [ ] Stage 1: self-owned smoke upstream provisioned.
- [x] Stage 2.1: Worker health readiness passed against default local Worker.
- [ ] Stage 2: Worker readiness and local smoke recorded.
- [ ] Stage 3: Navigator E2E smoke completed.
- [ ] Stage 4: SIM / TMS consumer gray acceptance completed.
- [ ] Stage 5: quality, coverage and acceptance signoff completed.

### Testing Progress

| Case | Scope | Status | Evidence |
| --- | --- | --- | --- |
| route readiness baseline | OPT-001 | pass | Existing OPT-001 quality / coverage / acceptance docs. |
| Stage 1.1 preflight | local docs/scripts/profile hygiene | pass | Scripts exist; system profile has request code; smoke profile/artifact paths are gitignored. |
| isolated provisioning | Navigator CLI | not-run | Waiting for valid operator/admin key and `NAVI_LLM_API_KEY`. |
| Worker health smoke | Codex Worker | pass | `codex-biz-smoke.ps1 -BaseUrl http://127.0.0.1:3070` passed health readiness; live queries not run. |
| `submit_skill_result` E2E | Navigator + Worker | not-run | Stage 3. |
| BusinessFunction E2E | Navigator + WorkerGateway + Worker | not-run | Stage 3. |
| context continuation positive / negative | Navigator + Worker | not-run | Stage 3. |
| SIM gray acceptance | Consumer | not-run | Stage 4. |
| TMS gray acceptance | Consumer | not-run | Stage 4. |

### Experience Progress

- N/A. This work item covers backend / Worker / OpenAPI / upstream smoke and does not add or change UI pages, forms, lists, buttons, routes, or permission-visible UI.

### Implementation Self-Check

- scope conformance: pass for planning scope; execution remains staged.
- non-goals preserved: pass; SIM / TMS production cutover and mandatory ask-body `directoryId` are explicitly out of scope.
- code/docs touched are listed: pass for README and this workitem; later quality/coverage/acceptance docs remain expected outputs.
- tests and smoke evidence recorded: partial; Stage 1.1 preflight and Worker health passed, provisioning/E2E smoke not run.
- remaining risks documented: pass; credential and E2E blockers remain listed.
- self-check conclusion: plan is ready for Stage 1.2 once `NAVI_OPERATOR_API_KEY` and `NAVI_LLM_API_KEY` are available; Worker health does not block bootstrap.

### Blockers

- `NAVI_OPERATOR_API_KEY` is required to approve the self-owned smoke upstream request; current request status is `PENDING`.
- `NAVI_LLM_API_KEY` is required by current model config creation flow even when local Codex Worker uses `codex_login`.
- Full migration equivalence remains blocked until `submit_skill_result` and BusinessFunction E2E smoke pass.

## Execution Prompt

Use this prompt for the next execution agent after the required credentials are available:

```text
You are executing OPT-003: Codex Biz Upstream Acceptance.

Read first:
- docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-003-codex-biz-upstream-acceptance.md
- docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-001-codex-biz-route-readiness.md
- docs/version-tracker/1.3.2-SNAPSHOT/workitems/OPT-002-langgraph-biz-actor-home-readiness.md
- CLAUDE.md

Goal:
- Provision the self-owned Codex Biz smoke upstream.
- Run Worker readiness, Navigator E2E submit_skill_result, BusinessFunction, and context continuation smoke.
- Record non-sensitive evidence and update OPT-003 progress.

Do not:
- Use TMS existing profile or tms-agent-v305 as the foundation smoke.
- Treat CodexBizWorker as the default enterprise production route.
- Put task_scoped_token, API keys, auth contents, or real CODEX_BIZ_HOME_ROOT paths into docs or logs.

Completion:
- Update Development Progress, Testing Progress, blockers, and self-check in OPT-003.
- Run targeted tests for any code changes.
- Run git diff --check.
- If Stage 3 or Stage 4 completes, proceed to implementation quality gate and coverage audit before acceptance signoff.
```

## Acceptance Evidence Plan

Final signoff must include:

- Provisioning summary for isolated smoke upstream.
- Worker health/readiness summary.
- `submit_skill_result` task evidence: taskId, contextId, providerType, workerBackend, effective directory marker, structured output location.
- BusinessFunction evidence: function id/version, schema result summary, invoke result summary, WorkerGateway/tool-message audit marker.
- Context continuation evidence: positive continuation without provider override, negative conflict fail-fast.
- SIM/TMS consumer notes: directory source, codex home source, route/profile, fallback route.
- Test command summary and result.
- Explicit production boundary statement.
