---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: FEAT-003
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner-user-confirmed
approved_at: 2026-07-25
approval_addendum_at: 2026-07-26
langgraph_extension_approved_at: 2026-07-27
clean_release_alignment_approved_at: 2026-07-27
open_questions: []
---

# Delivery Spec: Runtime Task Completion Readiness

## Document Purpose

- intended_for: ultra-implementation / SIM runtime operator / independent-signoff
- purpose: 固定 runtime-only 调用方对既有任务执行完成就绪性审计的目标、证据语义、安全边界、现场部署和新 ASK 验收合同。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/FEAT-003-runtime-task-completion-readiness.md`

## Goal

- version_goal: 在 1.4.3 已有 runtime task audit 与 termination reconciliation 基础上，补齐 Worker/provider 进程存活、注册残留和真实完成证据的只读区分能力。
- target_outcome: 提供 `navi upstream runtime task-completion-readiness`，使用既有 runtime profile 查询 durable task facts、Worker/provider observed facts、completion evidence 和 reconciliation assessment，不读取模型内容且查询本身零 mutation、零 token、零执行 dispatch。
- critical_outcomes:
  - 不以 Navigator active registration、task token `ACTIVE`、超时或 final message 代替 provider process 和 provider terminal facts。
  - Worker/provider 不可查询或进程归属不明确时返回 `UNKNOWN`，不得猜测。
  - 只有 provider terminal success 或完整可信 durable final result 才能形成 completion candidate；只有可恢复、身份绑定且 durable 的证据才能声明 completion reconciliation supported。
  - API、SDK、CLI 和 Worker audit 均只返回脱敏布尔值、枚举、计数、时间和 digest。
  - readiness 不执行 terminate、reconcile、retry、recovery、redispatch、finalize 或任何资源变更。
- success_is_sufficient_when: focused/affected tests、Worker durable evidence fixtures、clean server/CLI/Worker artifacts、现场进程归属检查、必要数量的新 SIM test-owned ASK、查询前后零副作用证据和敏感信息扫描均通过。

## Approved LangGraph Extension (2026-07-27)

- approval_context: TMS 的真实主路径继续使用 exact `langgraph-biz-worker`，不切换为 Codex SDK Worker 规避 capability 缺口。
- target_outcome: 为 LangGraph Biz Worker 补齐 provider-specific authoritative terminal/completion evidence，使 runtime completion-readiness 能区分可信 `FAILED/CANCELLED` 终态与带 durable result 的可信 `COMPLETED`，并保留底层 Worker observation error code。
- critical_outcomes:
  - LangGraph 使用独立 `LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1`，不得伪装或复用 `CODEX_COMPLETION_RECEIPT_V2`。
  - `terminalEvidenceAuthoritative` 与 `completionEvidenceAuthoritative` 分离：可信 `FAILED/CANCELLED` 可前者为 `true`、后者必须为 `false`；只有可信 `COMPLETED` 且 durable result digest 匹配时后者才为 `true`。
  - Python Worker route 只读取 content-free receipt metadata，并沿用 Worker Bearer 鉴权；不得读取 prompt、response、legacy content-bearing event、workspace 或结果正文。
  - Navigator 在正常终态持久化路径计算并保存 result digest metadata；readiness 不重新读取正文计算 digest。
  - LangGraph `FAILED` 必须投影稳定、脱敏 ErrorDiagnostic code；terminal task 的 `TASK_ALREADY_TERMINAL` 不得再覆盖并丢失底层 provider observation code。
- success_is_sufficient_when: Worker route/auth/receipt tests、LangGraph addon provider/client/error projection tests、共享 readiness assessment tests 和 affected-module tests 通过；exact Worker provenance/health/route/auth 能力完成无 ASK 预检。本实施阶段不创建 TMS ASK。

## Approved Clean Release and TMS Alignment (2026-07-27)

- approval_context: owner 已批准阶段 1–3：回填脱敏现场证据、让 TMS helper/BFF 严格识别 Codex 与 LangGraph 两种 authoritative profile，并形成不夹带无关改动的 clean commit/build provenance。
- scope_boundary: 本阶段允许修改 Navigator 当前仓库和 sibling TMS validator/test/work item；不部署、不重启、不修改 provisioning/runtime binding，也不再创建 ASK。
- exact_profiles:
  - Codex: `completionSignalSource=PROVIDER_TERMINAL_EVENT` 且 `assessmentSource=DURABLE_TASK+CODEX_COMPLETION_RECEIPT_V2+WORKER_PROCESS_SNAPSHOT`。
  - LangGraph: `completionSignalSource=LANGGRAPH_BIZ_RESULT_EVENT` 且 `assessmentSource=DURABLE_TASK+LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1+WORKER_READ_ONLY_OBSERVATION`。
- fail_closed_rule: TMS 必须校验 signal/source 成对匹配，并投影 `terminalEvidenceAuthoritative`、terminal signal facts 和 `providerObservationErrorCode`；跨 profile 拼接、未知 profile 或缺失权威字段均不得成为 authoritative completion。
- clean_release_rule: clean commit/artifact 只包含本 work item 的 LangGraph/readiness 变更和必要文档，不得夹带现有前端、Claude file route 或其他用户改动。

## Scope

- in_scope:
  - runtime long-term credential self-scope completion-readiness API、SDK、CLI 和稳定脱敏错误。
  - Navigator durable task facts 与 Worker/provider observed facts 的分层聚合。
  - 复用 Codex SDK Worker 现有 task status/process inventory，增加只读取 content-free receipt 的 completion evidence audit。
  - Codex SDK Worker 为新任务持久化 content-free、fsync completion receipt，并确保被 receipt 引用的 final result 先 durable；digest 在正常完成链已经持有结果时生成，不由 readiness 重新读取正文计算。
  - LangGraph Biz Worker 为新终态写入 content-free、fsync provider receipt；Navigator LangGraph 正常终态持久化路径同步保存 result/structured-output digest metadata，并在 readiness 中与 Worker receipt 做 exact task/Worker/provider/dispatch/status/digest 关联。
  - LangGraph Worker authenticated content-free completion-readiness route、health capability declaration 和 Server provider implementation。
  - provider-neutral authoritative terminal evidence、底层 provider observation error code 保留和 LangGraph FAILED stable ErrorDiagnostic projection。
  - completion candidate、authoritativeness、recoverability、reconciliation support 和 recommended action 的 fail-closed assessment。
  - route authorization catalog、CLI feature manifest/help、自动化回归、clean package、部署和 runtime-only live smoke。
  - 对归属当前 Foggy Navigator 工作区且绑定目标 Physical Worker 的 Worker 进行必要升级/重启；在确认进程 command line、workspace 和 task identity 后，可终止其残留 Codex CLI。
  - 使用 SIM 既有 runtime profile 创建必要数量的新 test-owned ASK，覆盖自然完成、受控 provider process absence 和 dispatch 前环境拒绝场景；不得对旧 task 使用 retry/resume/recovery。
- affected_modules:
  - `navigator-spi`
  - `addons/codex-worker-agent`
  - `addons/claude-worker-agent`
  - `navigator-common`
  - `navigator-open-sdk`
  - `user-auth-module`
  - `tools/codex-agent-worker`
  - `addons/langgraph-biz-worker`
  - `tools/langgraph-biz-worker`
  - `tools/navigator-upstream-cli`
  - `launcher` packaging/runtime
- external_dependencies: 本机 Navigator 8112、MySQL durable state、Physical Worker `ddc45293` 对应的 Codex SDK Worker、SIM gitignored runtime profile。

## Non-Goals

- out_of_scope:
  - completion reconciliation mutation、自动 finalize 或把非终态任务写为 `COMPLETED`。
  - 修改现有 terminate/reconcile 的 `ABORTED/CANCELLED` 语义。
  - retry、resume、recovery、redispatch 或重新调用既有任务的模型。
  - Claude、Gemini 或 Codex app-server provider 的完成证据实现；本交付中继续显式返回 `UNSUPPORTED/UNKNOWN`。
  - 本实施阶段创建 TMS ASK、修改 TMS binding/provisioning 或用 Codex/SIM binding 替代 TMS 的 LangGraph binding；TMS clean ASK 仅在本侧 gate 满足后由后续明确授权执行。
  - TMS、真实业务数据、BusinessFunction、prompt/response/message body、workspace 文件或原始 Worker HTTP body 的读取或输出。
  - typed-management、system-admin、control、platform authority、Worker Gateway external 或 production enablement。
- do_not_touch:
  - 不对历史任务 `20260725-6a2e` 调用 terminate、task-reconcile、future completion reconcile、retry、resume、recovery、redispatch 或 finalize。
  - Worker/CLI 运维可能使该历史任务的底层进程退出，但不得把退出、Worker 重启或 task registry 404 当作其完成证据。
  - 不修改 FEAT-001、FEAT-002 及当前 worktree 中无关的用户改动。
  - 不修改 sibling workspace；SIM 只作为既有 runtime profile 的调用方。
- non_blocking_or_waivable_items:
  - 可豁免：若现场无法稳定捕获短暂的 `providerProcessPresent=true` 窗口，可用隔离集成测试证明该分支，并在 live 记录中披露。
  - 不可豁免：credential lane、任务/Worker ownership、content redaction、UNKNOWN fail-closed、query zero-side-effect 和禁止伪造 COMPLETED。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 新建独立 FEAT-003 | termination readiness 不能证明完成 | 不向 FEAT-001/002 混入完成语义 |
| 新增独立 GET endpoint/CLI command | 现有 task-audit 与 termination-readiness 字段合同不足 | 不创建语义重复的 mutation 命令 |
| durable、Worker observed、assessment 三层分离 | 防止注册状态反向伪造 provider facts | 每个 assessment 必须声明 source 和 assessedAt |
| 复用 Worker task status/process inventory | 当前接口已能提供 registry 与真实进程快照 | Navigator 输出不得暴露 PID、command、thread、path |
| Worker completion audit 只读独立 content-free receipt | 现有 EventBroadcast 初始化/load 可能创建目录、改变 replay state，旧 event JSONL 含模型正文 | audit 不得打开 content-bearing event log，不得 mkdir、append、ack、refresh 或改变 replay state |
| 不回扫或回填 legacy V1 result event | 读取旧 result event 会接触模型正文，且旧写入不是完整 self-describing fsync receipt | 无 content-free receipt 的历史任务返回 `UNKNOWN`；不得为兼容生成事后证据 |
| V2 receipt 在 durable final result 后 fsync | digest 必须绑定可恢复结果和 provider terminal success | receipt 不含正文，仅含身份、状态、时间、版本和 digest |
| readiness GET 调用 Worker 只读观察不算 worker command dispatch | 它不启动、控制或改变 provider task | `workerCommandDispatched=false`，同时可返回 `workerReachable=true` |
| 允许重启 exact owned Worker、终止 exact owned Codex CLI | owner 已明确允许放弃旧观察对象并改用新 ASK | 操作前必须用 command line、workspace、Worker/task identity 确认归属；端口不构成证明 |
| live smoke 使用新 SIM ASK | 避免依赖历史假死任务形成不可重复验收 | 可按 2026-07-26 用户授权执行必要数量的 test-owned ASK；无 BusinessFunction、不得读取业务/workspace 内容 |
| completion mutation 后续独立立项 | readiness 必须保持绝对只读 | 当前交付只报告 `COMPLETION_RECONCILIATION_AVAILABLE`，不执行 |
| TMS 继续使用 LangGraph Biz Worker | 这是 TMS 的真实主运行路径 | 不通过切换 Codex SDK Worker 掩盖 LangGraph capability 缺口 |
| LangGraph receipt 使用独立 V1 schema | provider evidence 语义与 Codex SDK 生命周期不同 | schema 固定为 `LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1`；禁止标记为 Codex receipt |
| terminal authority 与 completion authority 分离 | `FAILED/CANCELLED` 可被权威证明，但不是成功完成 | 新增 additive `terminalEvidenceAuthoritative`；保留既有 `completionEvidenceAuthoritative` 严格成功语义 |
| Worker receipt 与 Navigator durable result metadata 联合验证 | Worker 可证明 provider terminal event，Navigator 可证明已持久化可恢复结果 | digest 在正常终态路径计算；readiness 只读取 metadata，不读取结果正文 |
| 保留底层 observation error code | terminal branch 的 `TASK_ALREADY_TERMINAL` 只表达 recommended-action 原因 | additive `providerObservationErrorCode` 独立返回，稳定且脱敏 |
| LangGraph FAILED 创建 ErrorDiagnostic | durable task 不能只保留自由文本或 null code | 使用现有 ErrorDiagnostic schema/retention/redaction，不新增 credential/content 字段 |

## Public Response Contract

### Task Durable Facts

- `taskId`
- `terminal`
- `status`
- `sanitizedErrorCode`
- `createdAt`
- `completedAt`
- `taskTokenStatus`
- `activeTaskRegistrationPresent`
- `dispatchCount`
- `retryCount`
- `recoveryCount`
- `physicalWorkerId`
- `modelConfigId`
- `modelVariant`

### Worker / Provider Observed Facts

- `workerReachable`
- `workerObservedAt`
- `workerTaskKnown`
- `workerTaskState`
- `providerProcessPresent`
- `providerProcessState`
- `providerActiveTaskPresent`
- `providerTaskTerminal`
- `providerTerminalStatus`
- `lastHeartbeatAt`
- `lastProgressAt`
- `processExitedAt`

Unknown or unsupported facts must be `null` or `UNKNOWN`. Navigator active registration is never a source for provider process presence.

### Completion Evidence Facts

- `finalOutputPresent`
- `finalOutputDurable`
- `finalOutputDigest`
- `finalOutputRecordedAt`
- `structuredOutputPresent`
- `structuredOutputDigest`
- `completionSignalPresent`
- `completionSignalSource`
- `completionSignalRecordedAt`
- `resultRecoverable`

No response, log or evidence artifact may contain prompt, response, message content, raw result, raw HTTP body, workspace path or model output.

### Reconciliation Assessment

- `staleRegistrationSuspected`
- `workerProcessAbsent`
- `completionCandidate`
- `completionEvidenceAuthoritative`
- `terminalEvidenceAuthoritative`
- `completionReconciliationSupported`
- `terminationReconciliationSupported`
- `reconcileRequired`
- `reconcileReason`
- `recommendedAction`
- `assessmentReason`
- `assessmentSource`
- `providerObservationErrorCode`
- `assessedAt`

`recommendedAction` is restricted to:

- `CONTINUE_OBSERVING`
- `OPERATOR_REVIEW`
- `TERMINATE_AND_RECONCILE`
- `COMPLETION_RECONCILIATION_AVAILABLE`
- `NO_ACTION_ALREADY_TERMINAL`
- `BLOCKED_INSUFFICIENT_EVIDENCE`

### Audit Side Effects

Every successful or fail-closed readiness response must explicitly report:

- `accessTokenIssued=false`
- `runtimeTokenIssued=false`
- `taskTokenIssued=false`
- `taskCreated=false`
- `contextCreated=false`
- `sessionCreated=false`
- `workerCommandDispatched=false`
- `modelDispatched=false`
- `businessFunctionDispatched=false`
- `retryTriggered=false`
- `recoveryTriggered=false`
- `terminationTriggered=false`
- `reconciliationTriggered=false`
- `provisioningResourceChanged=false`

## Assessment Rules

1. Timeout or stale heartbeat may set `staleRegistrationSuspected=true`, but cannot set `completionCandidate=true`.
2. Confirmed provider process absence without authoritative final result cannot produce `COMPLETED`; recommend `TERMINATE_AND_RECONCILE` or `OPERATOR_REVIEW`.
3. `completionCandidate=true` requires explicit provider terminal success or a complete trusted durable final result and must include source, recorded time and digest.
4. Final message, natural-language answer, workspace file or Navigator active registration alone is non-authoritative.
5. Worker/provider state that cannot be queried remains `UNKNOWN`; token `ACTIVE`, timeout and active registration cannot fill the gap.
6. `completionEvidenceAuthoritative=true` requires an identity-bound durable receipt/result pair or equivalent Navigator durable evidence; readiness must not read raw result content to establish it.
7. `completionReconciliationSupported=true` additionally requires `resultRecoverable=true`, exact task/Worker/provider/dispatch binding and no conflicting terminal evidence.
8. Readiness never performs reconciliation, even when `recommendedAction=COMPLETION_RECONCILIATION_AVAILABLE`.
9. `terminalEvidenceAuthoritative=true` requires exact identity/dispatch binding, a supported provider receipt schema, a provider terminal event and a stable terminal status; it does not imply successful completion.
10. LangGraph `completionEvidenceAuthoritative=true` additionally requires terminal `COMPLETED`, completion signal, matching Worker/Navigator result digest metadata, durable Navigator result persistence and recoverability.
11. `assessmentReason` may remain `TASK_ALREADY_TERMINAL`, but `providerObservationErrorCode` must preserve the safe underlying unsupported/unreachable/identity/evidence observation code when one exists.

## Acceptance Criteria

- [x] AC-1 `GET /api/v1/open/runtime/task-completion-readiness` and the matching CLI command use only the existing runtime long-term profile, exact task ownership and expected Physical Worker equality guard.
- [x] AC-2 response implements every field in the public contract, preserves durable/observed/assessment separation and returns `UNKNOWN` rather than inferred provider facts.
- [x] AC-3 Codex SDK process observation distinguishes exact process present, exact process absent, ambiguous orphan and Worker unreachable without using active registration as process proof.
- [x] AC-4 completion evidence audit opens only content-free receipt/index material, returns only presence/type/time/digest and recognizes V2 receipt/result metadata, conflicting evidence and corrupt/incomplete receipt.
- [x] AC-5 historical tasks without a pre-existing content-free receipt return `UNKNOWN`; V2 support requires durable result-before-receipt ordering and a digest generated during provider completion, not an audit-time read of raw output.
- [x] AC-6 assessment follows every rule above and restricts `recommendedAction` to the approved enum.
- [x] AC-7 every readiness response returns all fourteen audit side effects as `false`; interaction and before/after persistence checks prove no token issuance, task/resource creation, execution dispatch, retry/recovery, termination or reconciliation.
- [x] AC-8 API、CLI、Worker response、logs and committed evidence contain no credential/profile/header、prompt、response、message body、raw event/result、workspace path、PID or process command.
- [x] AC-9 unsupported provider/runtime returns stable `UNSUPPORTED/UNKNOWN` and never falls back to another Worker, provider, process or credential lane.
- [x] AC-10 Worker and Navigator artifacts have clean provenance; route manifest/auth tests, Worker tests, affected Maven tests, CLI package/help/feature manifest and launcher health all pass.
- [x] AC-11 live smoke uses bounded new test-owned SIM ASK tasks: one naturally completes, one may have its exact owned Codex CLI terminated after an initial observation, and environment-invalid requests fail terminally before provider execution. No BusinessFunction or business/workspace content is used.
- [x] AC-12 live evidence proves natural completion is not confused with stale registration, forced process absence without authoritative result is not reported as completed, and readiness queries do not change task/token/registration/counter state.
- [x] AC-13 Worker/CLI restart and termination records include only sanitized ownership, version, time and outcome; port alone is never accepted as process ownership.
- [x] AC-14 no terminate/reconcile/retry/recovery/finalize operation is invoked against historical task `20260725-6a2e`.
- [x] AC-15 LangGraph Worker exposes an authenticated content-free `GET /api/v1/tasks/{taskId}/completion-readiness` route and advertises its exact route/schema/auth capability through health metadata.
- [x] AC-16 `LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1` is atomically persisted with file/directory durability and safe permissions; it contains only identity、dispatch、terminal、time、presence and digest metadata, never model or workspace content.
- [x] AC-17 LangGraph normal terminal handling records terminal receipt metadata for `COMPLETED/FAILED/CANCELLED`; conflicting/corrupt/mismatched evidence fails closed and cannot become authoritative.
- [x] AC-18 LangGraph `COMPLETED` stores Navigator result/structured-output digest metadata during the existing durable completion transaction and readiness proves exact Worker receipt/Navigator metadata equality without reading result正文。
- [x] AC-19 runtime readiness supports exact providerType `langgraph-biz-worker`; exact identity、provider task、dispatch count、terminal status/schema and digest mismatch remain non-authoritative with a stable observation code.
- [x] AC-20 response additively exposes `terminalEvidenceAuthoritative` and `providerObservationErrorCode`; `FAILED/CANCELLED` never set completion candidate or completion authority, while a fully matched durable `COMPLETED` may set both completion values true.
- [x] AC-21 LangGraph failure paths persist a stable ErrorDiagnostic code before/with terminal projection; raw provider error text is only accepted through the existing bounded sanitizer and is not used as the stable code.
- [x] AC-22 terminal assessment keeps `TASK_ALREADY_TERMINAL` action semantics while preserving the underlying safe provider observation code in its dedicated field.
- [x] AC-23 focused Worker、LangGraph addon、shared readiness and affected-module tests pass; exact Worker route/auth/health/provenance are verified without creating a TMS ASK or changing task/Agent/runtime binding.
- [x] AC-24 经 owner 后续明确授权的一次新 TMS LangGraph ASK 已自然完成；两次稳定 readiness 快照证明 durable `COMPLETED`、Worker/provider terminal `COMPLETED`、合法 SHA-256、可恢复结果、V1 identity-bound authority 和十四项 side effects 全部为 false，且证据不含 task ID、正文、路径、PID 或命令行。
- [x] AC-25 TMS helper/BFF 对 Codex 与 LangGraph exact profile 均通过正向测试，并对跨 profile、未知 profile、缺失 terminal authority/terminal signal 的响应 fail closed。
- [x] AC-26 Navigator 和 TMS 分别形成 scoped clean commit；Navigator clean Server/Worker artifacts 记录 `gitDirty=false` provenance 与 SHA-256，但本阶段不部署或重启。

## Contract / Data / Security Constraints

- API or event contract: additive runtime-scoped GET endpoint and CLI command; strict taskId/expectedWorker validation; stable sanitized enums/codes; no content-bearing output.
- Worker evidence contract:
  - readiness never opens or parses legacy content-bearing result events and never backfills historical completion receipts;
  - V2 computes the digest while the final result is already present in the normal completion path, persists the result durably, then persists a content-free completion receipt;
  - receipt binds task ID, Physical Worker, provider task/attempt where available, dispatch count, terminal status/source, evidence version, recorded time and result digest;
  - later conflicting terminal evidence invalidates authoritative assessment.
  - LangGraph V1 receipt proves provider terminal event metadata; Navigator provider-scoped task state proves its own durable result metadata, and the Server requires their exact digest/status/identity match for completion authority.
- data and migration: readiness itself performs no persistence. The LangGraph extension may add provider-scoped keys to existing `SessionTask.taskStateJson`; no new table/column or historical backfill is approved. If that is insufficient, set `NEEDS_REPLAN` rather than silently adding a migration.
- compatibility and rollback: API/CLI/Worker changes are additive. Rollback may make new evidence unavailable but must not reinterpret V1 evidence as authoritative or change existing task terminal state.
- permissions and secrets: runtime caller uses only ClientApp runtime key/secret; Navigator-to-Worker reads use the existing exact Worker credential/identity path; no admin/control/platform/management credential.

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/2/6 | must-pass | critical | service/controller/SDK/CLI contract tests | FEAT-001 runtime auth/audit base | exact test commands/results and response fixtures |
| AC-3/9 | must-pass | critical | process identity matrix and Worker unreachable/unsupported tests | existing Codex process snapshot guards | present/absent/ambiguous/unknown results |
| AC-4/5 | must-pass | critical | Worker receipt fixtures, no-content-read guard, digest and ordering tests | existing durable termination event-store tests | absent/V2/conflict/corrupt receipt results |
| AC-7/8 | must-pass | critical | interaction verification, persistence before/after, output/log/secret scan | existing audit side-effect tests | all fourteen false and zero-write proof |
| AC-10 | must-pass | major | Worker `test/typecheck/build`, affected Maven tests, CLI package, launcher clean package/start | current module build scripts | provenance, hashes, health and feature manifest |
| AC-11/12/13/14 | must-pass | critical | bounded live SIM smoke and exact process ownership verification | existing runtime profile and Worker binding | sanitized task IDs, times, enums, digests, counters and operation record |
| AC-15/16/17 | must-pass | critical | Python route/auth/receipt store unit and integration tests | Codex content-free receipt safety principles, not its schema | route/auth/capability fixtures; atomicity/permissions/conflict results |
| AC-18/19/20/22 | must-pass | critical | LangGraph provider/client plus shared readiness service tests | existing RuntimeTaskCompletionReadiness SPI/service | exact match/mismatch/FAILED/COMPLETED/terminal override matrices |
| AC-21 | must-pass | critical | failure-first ErrorDiagnostic projection tests | existing ErrorDiagnostic persistence/sanitizer | stable code and no raw-content leakage assertions |
| AC-23 | must-pass | major | Worker pytest/ruff, affected Maven modules, exact owned Worker no-ASK readiness preflight | existing clean Codex/API/CLI evidence where unchanged | exact commands/results and sanitized health/route/provenance record |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation:
  - focused Java tests, route/auth contract tests, CLI parsing/output tests and sensitive-output scans;
  - `npm test`, `npm run typecheck`, `npm run build` in `tools/codex-agent-worker`;
  - focused `pytest`/`ruff` in `tools/langgraph-biz-worker` and focused LangGraph/readiness Maven tests;
  - expected duration per run `<5m`.
- medium_validation:
  - affected Maven modules with dependencies;
  - `mvn clean package -pl launcher -am -DskipTests`;
  - `bash tools/navigator-upstream-cli/dist/package.sh`;
  - Worker/launcher deploy、health/provenance and authenticated no-ASK route/readiness preflight；
  - expected duration per run `5-30m`.
- expensive_validation: none planned.
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none; this delivery does not require synthetic authority, production, TMS or full historical replay.
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: LangGraph implementation、一次新 TMS clean ASK、TMS provider-aware alignment 和 scoped clean commit/artifact 已批准；部署、重启、provisioning/runtime binding 变更和无关 full-chain 未批准
- decision_if_not_approved: not applicable to the approved bounded live smoke; unrelated full-chain remains omitted
- expensive_validation_trigger: if focused/affected evidence cannot prove durable result-before-receipt ordering or zero-side-effect behavior, set `NEEDS_REPLAN` before expanding validation.
- maximum_expensive_attempts: 0 without new approval
- reusable_evidence: FEAT-001 auth/read-only audit and FEAT-002 process/Worker identity foundations, only where affected code and artifact identity have not changed.
- stop_when_evidence_is_sufficient: LangGraph AC-15..23 and unchanged prior must-pass evidence succeed, exact Worker route/auth/health/provenance gate is green, all readiness side effects remain false and sensitive scan is clean.
- validation_not_required: frontend/Playwright、当前阶段 TMS ASK、BusinessFunction mutation、production/Gateway external、其他 provider、completion reconciliation mutation or full root reactor.

## Waiver Policy

- waivable_items: live capture of a short process-present window only, when the equivalent isolated integration test passes and the limitation is recorded.
- authorized_role: project owner
- non_waivable_guards: exact ownership, runtime-only credential, no content exposure, UNKNOWN fail-closed, durable evidence ordering/digest, no forged completion, readiness zero mutation and historical task mutation prohibition.
- required_risk_record: any omitted environment check, unsupported field, live timing limitation, deployment drift or evidence ambiguity must be recorded in this work item and final signoff.

## Risks and Open Questions

- known_risks:
  - current worktree contains unrelated modified/untracked files; implementation must not overwrite or reformat them.
  - Worker restart removes in-memory task registry and may end the historical provider process; restart/task 404 is not terminal evidence.
  - legacy result and lifecycle events were not written as a content-free durable receipt; readiness must return `UNKNOWN` instead of reading or backfilling them.
  - launcher restart may run normal background recovery for unrelated tasks; live evidence must separate pre-existing/background actions from readiness query side effects.
  - natural model completion may be too fast to capture a live process-present sample; the waiver above applies only to that observation, not completion evidence semantics.
- open_questions: none

## Future Completion Reconciliation Contract

This delivery does not implement mutation. A later independently approved work item may add `task-completion-reconcile` only when:

- request contains exact `taskId`, equal `confirmTaskId`, expected Physical Worker, expected dispatch count, provider task/attempt identity, evidence version and evidence digest;
- server re-reads and verifies the authoritative evidence instead of trusting client-provided completion claims;
- task status/version, registration and evidence identity are checked with CAS;
- one transaction preserves the immutable original completion evidence/result, writes the real provider terminal status, revokes the task token and removes active registration;
- identical replay is idempotent and conflicting replay fails closed;
- it never creates task/context/session, issues token, redispatches, retries, recovers or invokes model/BusinessFunction.

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和 `navigator-runtime-provisioning`。
- 在 scope 内自主决定具体文件、类和实现结构，不把本合同扩展成多 provider 或 completion mutation。
- 进程停止/重启前必须确认 command line、workspace、Worker/task identity；只处理当前 Foggy Navigator 所属实例。
- 如需新增 Navigator schema、改变 V1/V2 authority 规则、扩大 credential/Provider/production 边界或执行历史任务 mutation，设置 `NEEDS_REPLAN` 并停止扩展。
- LangGraph 扩展只允许 provider-scoped `taskStateJson` metadata；如必须新增表/列、读取正文进行 audit-time digest 或把 LangGraph receipt 伪装成 Codex schema，设置 `NEEDS_REPLAN`。
- 运行与改动面匹配的验证并记录精确命令、结果、证据路径和未运行原因。
- 当前 LangGraph 实施阶段不创建 TMS ASK；只允许 exact Worker health、route/auth 和既有 task readiness 的只读预检。后续 clean TMS ASK 必须在 AC-23 gate 后单独执行。
- 达到 evidence sufficiency 后停止，不运行未批准的大型 authority/replay/full-chain。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Codex Baseline Implementation Result (2026-07-26)

- implementation_summary:
  - 新增 runtime-only `GET /api/v1/open/runtime/task-completion-readiness`、SDK 和
    CLI 1.0.34 命令，聚合 durable facts、Worker/provider observed facts、
    content-free completion evidence 和 fail-closed assessment。
  - Codex SDK Worker 1.0.25（Codex SDK/CLI 0.145.0）在 provider terminal success
    路径先原子持久化可恢复
    result，再持久化 `CODEX_COMPLETION_RECEIPT_V2`；只读 route 不读取 legacy event、
    result 正文、workspace、prompt 或 response。
  - Worker 在创建 provider task、SSE 和 Codex SDK 调用之前校验 delegated cwd；
    不存在或非目录时仅返回 `CODEX_WORKING_DIRECTORY_UNAVAILABLE`，避免环境错误留下
    Navigator `RUNNING` 假死任务。
  - Open API 在提交结果已经是 `COMPLETED/FAILED/CANCELED` 时立即撤销刚绑定的 task
    token，避免 dispatch 前快速终态留下 `ACTIVE` token；task audit 仅允许受控前缀、
    全大写稳定错误码作为 `sanitizedErrorCode` fallback，拒绝提升自由文本错误。
  - 对提交后异步到达的确定性 cwd 拒绝，Codex terminal marker 标记为不可恢复；
    Open API 正确处理 terminal-marker 与 task-token binding 的竞态并幂等撤销 token。
    其他 `FAILED` 状态仍保留既有可恢复语义。
  - readiness 对 receipt schema、task/Worker/provider identity、dispatch count、
    terminal success、signal time、SHA-256 digest 和 recoverability 做联合验证；未知
    或 unsupported 字段保持 `null/UNKNOWN`。
- changed_paths:
  - `tools/codex-agent-worker`: receipt store、task progress/process observation、
    readiness route、tests、version/package metadata。
  - `navigator-spi`、`addons/codex-worker-agent`: provider-neutral SPI、Worker client、
    identity/dispatch validation 和 SDK-only provider implementation。
  - `addons/claude-worker-agent`: runtime DTO/service/controller 和十四项
    zero-side-effect contract。
  - `navigator-common`、`user-auth-module`: route catalog/manifest 和 runtime
    credential lane authorization。
  - `navigator-open-sdk`、`tools/navigator-upstream-cli`: API、CLI command/help/tests、
    feature manifest 和 1.0.34 provenance。
- tests_and_results:
  - Codex SDK Worker: 247 tests，246 passed、1 Windows-only skipped、0 failed；
    typecheck/build passed。
  - immediate-terminal token revoke 与 sanitized error fallback 聚焦 Maven 测试：
    69 tests passed；terminal-marker/token-binding 竞态与确定性 cwd 拒绝语义补充测试：
    206 tests passed。
  - clean clone focused reactor: authorization 11、WebMvc 2、Claude completion/runtime
    74、Codex provider/client 175 tests passed；CLI 1.0.34 contract 153 tests passed。
  - Worker 1.0.25 full release smoke、CLI 1.0.34 package/remote install smoke 和
    14-module launcher package passed；clean launcher JAR SHA-256:
    `0a6a824f085b42e4bfd1649b96f254a3ad6da790033341e99b0eb6f544407611`；
    embedded commit `9a4bbd7a08a5398661d91bd45c7bfadd0c6581b3`、
    `git.dirty=false`。
  - Worker 1.0.25 Linux artifact SHA-256:
    `b55fd8595e498845f974308010a3b7e4c5e9701e2b1f1353616d6267b811a4d8`。
  - CLI 1.0.34 Linux/Windows artifact SHA-256:
    `666d321e237a25457ea65f6aeb37edcbde9fbf48bc7c116a8f4b3258f47d7405` /
    `69d176986586cd0ed1a77816965e9631e4f7fa5b2a1cc57c1ae714c1263c9055`；
    independent remote downloads matched `latest.json`。
  - affected multi-module run 在 710 tests 后因既有
    `BusinessTaskScopedTokenLifecycleJpaTest` JPA slice 缺少
    `RuntimeRequestAuditService` 出现 9 errors；FEAT-003 未修改该测试/监听器，所有
    FEAT-003 聚焦 Java surface 均通过。
- manual_or_experience_evidence:
  - implementation commit
    `d5a9e97fb677cb77c22ee7101abef71e19891618` 和 CLI provenance contract
    patch `9a4bbd7a08a5398661d91bd45c7bfadd0c6581b3` 均已推送到 `origin/main`。
  - 8112 已运行 clean launcher，health `UP`，embedded commit
    `9a4bbd7a08a5398661d91bd45c7bfadd0c6581b3`、`git.dirty=false`；
    2026-07-26 最终 listener PID 为 `184140`。
  - 3151 listener 经 Windows relay、`Ubuntu-24.04` listener PID cwd、安装目录
    `VERSION`、`.env` port 和 health 交叉确认属于
    `/home/navigator/.codex-worker`；未触碰当前 WSL 的
    `/home/sa/.codex-worker` 或 `/home/sa/.claude-worker`。
  - 使用目标安装目录自带 `update-worker.sh` 和本地 Linux artifact，将 exact bound
    Worker 逐步原地升级到 1.0.25；配置被保留并由 installer 规范化 termination
    identity/ledger。最终 health `ok/ready`、`active_tasks=0`、Codex SDK
    `0.145.0` compatible、termination ready。
  - 历史任务 `20260725-6a2e` readiness 现返回 Worker reachable、
    `workerTaskKnown=false`、provider process `ABSENT`、
    `staleRegistrationSuspected=true`；completion evidence 仍全部
    `null/UNKNOWN`，`completionCandidate=false`，推荐
    `TERMINATE_AND_RECONCILE`。十四项 readiness side effects 全部为 false。
  - 多条新 SIM test-owned ASK 均为单轮、显式 empty tool/function。1.0.23/1.0.24
    现场诊断确认 provider CLI 在 `thread.started` 前退出；只读关联只记录 cwd digest
    `e25e164f1d7c8451b487bf5874f165ad29f16ecf2ebb06b53921391fa17ea479`
    和 `exists=false/isDirectory=false`，未记录或输出实际路径。
  - 目标 `Ubuntu-24.04` 中 exact 3131 Claude role 经目录、端口和用户归属核验后恢复；
    delegated cwd 仍不存在，证明 Worker role 可达或 `active_tasks` 不能替代 workspace
    实际存在事实。
  - Worker 1.0.25 部署后的新 ASK `20260726-9bf3` 在约 18ms 内终态
    `FAILED`，固定错误码 `CODEX_WORKING_DIRECTORY_UNAVAILABLE`，durable
    `dispatchCount=0`、active registration absent、retry/recovery `0/0`。
    Worker 未创建 provider task/process；没有 BusinessFunction 调用。
  - official Worker 1.0.25 和 CLI 1.0.34 已从 independent clean clone 发布；
    Worker/CLI remote metadata 均为 `gitDirty=false`，archive digest 与独立下载一致。
    CLI 1.0.33 是中间发布，因 provenance contract test 仍保留 1.0.32 预期而被
    1.0.34 supersede；1.0.34 的 153 项 CLI contract tests 全部通过。
  - 对该已终态失败任务执行的 task-audit、completion-readiness 和
    termination-readiness 均为只读，十四项 query side effects 全部为 false。
    completion-readiness 返回 provider/evidence `UNKNOWN/null`、
    `completionCandidate=false`、`recommendedAction=NO_ACTION_ALREADY_TERMINAL`，
    未把快速失败解释成完成。
  - 第一轮部署后 ASK `20260726-3461` 已正确投影固定错误码，但暴露
    terminal-marker 先于 task-token binding 到达时 token 仍为 `ACTIVE` 的竞态。
    最小修复将确定性 cwd 拒绝标记为不可恢复，Open API 对 terminal binding
    exception 做幂等闭环；补充 206 项聚焦测试后再次构建并部署 8112。
  - 最终 live ASK `20260726-a7b8` 返回 terminal `FAILED`、固定错误码
    `CODEX_WORKING_DIRECTORY_UNAVAILABLE`、task token `REVOKED`、active
    registration absent、dispatch/retry/recovery `0/0/0`。task-audit 和
    completion-readiness 的十四项查询副作用全部为 false，BusinessFunction 未分派。
  - 两次授权重启后的历史任务 `20260725-6a2e` 仍为
    `RUNNING/ACTIVE`、registration present、dispatch/retry/recovery `1/0/0`；
    未发生 retry、recovery、termination 或 reconciliation。
  - operator-supplied provider profile 经脱敏 shape 校验并限制为 `0600` 后，仅将
    API key/base URL 写入 exact 3151 Worker；未修改 Navigator modelConfig、Agent、
    Directory、grant 或 binding。Worker 重启后 health 为 `api_key` auth mode，
    `ready=true`、`active_tasks=0`。
  - 新 SIM ASK `20260726-6700` 使用现有 `codex-luna:high` alias、单轮、显式 empty
    tool/function scope，在约六秒内自然 `COMPLETED`；task token `REVOKED`、
    registration absent、dispatch/retry/recovery `1/0/0`，BusinessFunction 未分派。
  - completion-readiness 返回 Worker task/provider terminal `COMPLETED`、
    provider process `ABSENT`、durable/recoverable final output、authoritative
    `PROVIDER_TERMINAL_EVENT` 和稳定 SHA-256 digest；
    `completionCandidate=true`、`completionEvidenceAuthoritative=true`、
    `recommendedAction=NO_ACTION_ALREADY_TERMINAL`。两次 readiness 查询的 durable、
    Worker terminal、evidence 和 assessment 语义稳定，十四项查询副作用全部为
    false；两次 task-audit 仅 `observedAt` 不同。
  - durable evidence:
    `docs/version-tracker/1.4.3-SNAPSHOT/evidence/FEAT-003-implementation-evidence.md`。
- deviations:
  - exact bound Worker 1.0.25 部署、process-absence live proof 和环境拒绝快速终态
    proof 已完成。用户显式授权后，目标 WSL 上按已有绑定创建了 exact delegated cwd，
    未修改 Directory 或 binding；后续两次 ASK 均越过 cwd 校验并创建 provider task，
    但当时的 provider auth lane 以 `CODEX_AUTH_REQUIRED` 终态失败。operator 后续提供
    可用 provider profile 后，同一 modelConfig/alias 的自然成功 ASK 已完成。
  - 2026-07-26 用户已授权多次新 ASK；未对任何旧任务执行 retry/resume/recovery。
  - clean publish 和 8112 clean deployment 已完成；该项不再构成偏差。
- residual_risks:
  - implementation acceptance 已满足；独立 signoff 尚未执行，因此不得标记
    `ACCEPTED`。
  - immediate/async fast-terminal token revoke 与 sanitized error fallback 已部署到
    8112，并由 `20260726-a7b8` 的 durable audit 证明；本项不再是现场差异。
  - 旧 test-owned tasks 中仍有非终态/待 reconcile 样本；本次没有对其执行
    terminate/reconcile。新任务 `20260726-9bf3` 已自然收敛为 `FAILED`，不需要
    closure mutation。
  - task-audit 的原任务执行事实记录 `runtimeDispatched/modelDispatched=true`，
    表示 Navigator 已进入 Worker dispatch lane；Worker 现场证据表明 Codex SDK
    provider task/process 未创建。消费者必须区分原任务事实与 readiness 查询自身
    `modelDispatched=false`。
- reused_evidence:
  - FEAT-001 runtime exact-self read-only credential/audit foundation。
  - FEAT-002 task/Worker identity、termination readiness 和 zero-redispatch foundation。
- omitted_validation_and_reason:
  - independent signoff 未执行：implementation evidence 已满足 AC-11/12，等待独立
    `foggy-delivery-signoff`。
- readiness: READY_FOR_SIGNOFF

## LangGraph Extension Implementation Result (2026-07-27)

- implementation_summary:
  - LangGraph Biz Worker 0.2.1 新增受 Bearer 鉴权保护、content-free 的
    `/api/v1/tasks/{taskId}/completion-readiness`，health 明确声明 route、schema、
    auth、identity 和 content-free capability。
  - 新增 `LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1` 原子 receipt store；目录/文件分别使用
    `0700/0600`，写入使用 flush/fsync/atomic replace，冲突、损坏、身份或 dispatch
    不匹配均 fail closed。
  - Worker 在 terminal SSE 前持久化 receipt；receipt 持久化失败使用稳定
    `LANGGRAPH_COMPLETION_RECEIPT_PERSISTENCE_FAILED`，且不得在无 receipt 时继续投影
    provider terminal event。
  - Server 新增 exact `langgraph-biz-worker` readiness provider，联合校验 task、
    Physical Worker、provider task、dispatch、terminal status、schema 和 digest；
    `terminalEvidenceAuthoritative` 与成功 completion authority 分离。
  - LangGraph 正常终态路径持久化 content-free result/structured-output digest metadata；
    failure path 在 terminal projection 前写入稳定 ErrorDiagnostic。terminal task 仍保留
    `TASK_ALREADY_TERMINAL` action 语义，同时通过 `providerObservationErrorCode` 暴露安全的
    底层 observation code。
  - Server 到 Worker 的 endpoint Bearer 与 Biz provider credential 使用独立 lane；
    legacy endpoint registration 仅在 same-worker/same-endpoint 时提供专用 Bearer，不能
    替换 governed Biz identity 或路由。
- changed_paths:
  - `tools/langgraph-biz-worker`: completion receipt store、readiness route、health
    capability、terminal dispatch metadata、stable persistence failure、0.2.1 version 和测试。
  - `addons/langgraph-biz-worker`: Worker client/health DTO、exact endpoint auth、terminal
    relay/ErrorDiagnostic、durable digest metadata、readiness provider 和测试。
  - `navigator-spi`: provider-neutral terminal signal、terminal error 和 observation fields。
  - `addons/claude-worker-agent`: additive response DTO、terminal/completion assessment 和
    provider observation error preservation。
  - `addons/codex-worker-agent`: 将既有 Codex completed-only receipt 映射为 additive
    terminal signal，保持原 Codex authority 语义。
  - 本 canonical work item：contract、acceptance、validation、deployment 和 residual risk。
- tests_and_results:
  - `PYTHONPATH=src .venv/bin/python -m pytest -q tests`：796 passed、0 failed。
  - `.venv/bin/ruff check src tests/test_completion_readiness.py tests/test_health.py tests/test_query.py`：PASS。
  - `mvn -q -pl addons/langgraph-biz-worker test`：181 tests、0 failures/errors/skips。
  - `mvn -q -pl addons/claude-worker-agent test`：426 tests、0 failures/errors/skips。
  - `mvn -q -pl addons/codex-worker-agent test`：489 tests、0 failures/errors/skips。
  - 聚焦 LangGraph client/provider/relay/task/service 测试通过；`mvn package -pl launcher
    -am -DskipTests` 通过，授权重启后的 8112 health 为 `UP`。
  - `git diff --check` 通过，仅有既有/受影响文件 line-ending 提示，无 whitespace error。
  - 较早的 affected reactor `-am test` 在进入目标模块前，因既有
    `BusinessTaskScopedTokenLifecycleJpaTest` JPA slice 缺少
    `RuntimeRequestAuditService` 出现 9 个 Spring context errors；本扩展未修改该 fixture，
    三个受影响 addon 的独立全量测试均通过。
- manual_or_experience_evidence:
  - 启动前以 listener command/cwd 交叉验证 8112 Server 和 3061 exact Worker 均属于当前
    Foggy Navigator 工作区；端口未被单独当作归属证据。
  - 经后续明确授权，exact platform Biz identity credential 已轮换；Server→Worker
    endpoint Bearer 使用独立 credential lane，same-worker endpoint registration 与
    governed identity 地址对齐；未修改 task、Agent、Directory、modelConfig、grant 或
    runtime binding。
  - exact dirty canary Worker 0.2.0 已部署并健康，health 广告 readiness supported、auth required、
    identity configured 和 content-free；未鉴权 route 被拒绝，鉴权的未知 task 返回安全
    content-free observation。
  - 新 Server 部署后，对既有目标 task 的只读 readiness 返回 Worker reachable、
    `workerTaskKnown=false`、`providerObservationErrorCode=WORKER_TASK_NOT_FOUND`、
    `assessmentSource=DURABLE_TASK+WORKER_READ_ONLY_OBSERVATION`。这证明底层 observation
    code 不再被 `TASK_ALREADY_TERMINAL` 覆盖，同时历史 task 不会被回扫或伪造 receipt。
  - 同一 readiness 返回 `terminalEvidenceAuthoritative=false`、
    `completionEvidenceAuthoritative=false`，两键均存在且为 boolean；十四项
    `auditSideEffects` 全部为 false。未创建 TMS ASK，未执行任何 task mutation。
  - secure handoff 目录/文件权限为 `0700/0600` 且被 Git ignore；三个核心关联字段均为
    非空 string。文件含批准的辅助授权元数据，不满足最初“仅三个字段”的严格 schema，
    按用户明确的 schema-violation override 继续，未读取或输出任何标识值。
- deviations:
  - 本轮在用户后续“全部授权”后执行了 exact Worker credential/endpoint registration
    配置、Worker 部署和 Server 重启；这些动作超出最初只读诊断边界，但未改变 task 或
    runtime binding，并在执行前完成 exact ownership 校验。
  - 经 owner 后续明确授权，TMS clean ASK 已执行并自然完成；未对该任务执行 retry、
    resume、recovery、redispatch、terminate、reconcile 或 finalize。
- residual_risks:
  - 当前 LangGraph Worker credential 仅注入正在运行的进程，未写入 tracked file 或
    本地明文 profile。当前进程保持运行时 clean ASK gate 为绿色；若 Worker 重启，必须先
    通过安全 secret store/profile 重新注入两条独立 credential lane。
  - 当前实际运行的 Server/Worker 仍是先前 dirty canary；本阶段形成的 clean Server 和
    Worker 0.2.1 artifacts 尚未部署。部署、重启和持久化 credential 修复需要单独授权。
  - 历史目标 task 在旧 Worker 生命周期中没有 V1 receipt，按合同永久保持非 authoritative；
    这不阻塞新 clean ASK 生成新的 authoritative evidence。
- reused_evidence: Codex baseline API/CLI/zero-side-effect/read-only credential evidence remains valid where shared code and artifact identity are unchanged.
- omitted_validation_and_reason: no frontend/Playwright、BusinessFunction、task mutation、production/Gateway external or completion reconciliation validation was required.
- readiness: READY_FOR_SIGNOFF

## Clean Release and TMS Alignment Result (2026-07-27)

- implementation_summary:
  - Server authoritative assessment 已从“schema/source 各自允许集合”收紧为 exact profile：
    Codex V2 只接受 `PROVIDER_TERMINAL_EVENT`，LangGraph V1 completed 只接受
    `LANGGRAPH_BIZ_RESULT_EVENT`，LangGraph V1 failed/cancelled 只接受
    `LANGGRAPH_BIZ_ERROR_EVENT`。
  - Worker receipt reader 新增 schema/status/signal/digest/error-code coherence 校验；被篡改的
    cross-provider signal、非法 digest 或非稳定错误码全部作为 invalid receipt fail closed。
  - TMS helper/BFF 严格接受 Codex 与 LangGraph 两个成对 profile，投影 terminal signal、
    `terminalEvidenceAuthoritative` 和 `providerObservationErrorCode`，并拒绝跨 profile 拼接。
  - LangGraph Biz Worker clean release version 提升为 `0.2.1`，与已运行的 dirty canary
    `0.2.0` 明确区分。
- sanitized_live_evidence:
  - 一次新 TMS LangGraph ASK 自然进入 durable `COMPLETED`；task token revoked、active
    registration absent、dispatch/retry/recovery 为 `1/0/0`。
  - 两份稳定 readiness 快照均显示 Worker reachable/task known、provider terminal
    `COMPLETED`、final output present/durable、合法 SHA-256、result recoverable、
    terminal/completion authority 为 true。
  - completion/terminal signal 均为 `LANGGRAPH_BIZ_RESULT_EVENT`，assessment source 为
    `DURABLE_TASK+LANGGRAPH_BIZ_COMPLETION_RECEIPT_V1+WORKER_READ_ONLY_OBSERVATION`；
    十四项 audit side effects 全部为 false。
  - 本记录不保存 task ID、upstream user ID、Physical Worker ID、prompt/response、原始
    HTTP body、workspace path、PID 或进程命令。
- validation:
  - TMS Node helper self-test：PASS；Node smoke：2/2；BFF focused：39/39；BFF affected：
    257/257。
  - Navigator Worker pytest：796/796；ruff：PASS；Claude/LangGraph/Codex addon：
    426/181/489，全部零失败/错误。
  - Navigator affected reactor 测试的依赖阶段复现既有
    `BusinessTaskScopedTokenLifecycleJpaTest` 缺少 `RuntimeRequestAuditService` fixture
    bean，导致 9 个 context errors；本 work item 未修改该模块，`-DskipTests` 完整依赖
    install 和三个目标 addon 全量测试均通过。
- release_boundary:
  - scoped clean commits 与 clean artifacts 已形成并记录 SHA-256；未 push、未上传、未部署、
    未重启，也未修改 provisioning/runtime binding。
  - 下一步需要单独授权后，才可持久化两条独立 Worker credential lane、部署 clean
    Server/Worker 0.2.1，并执行一次 post-deploy read-only smoke；无需再创建 ASK 即可先对
    clean provenance、health、route/auth 和既有新任务做只读核验。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: project owner completion-readiness request and live-smoke authorization dated 2026-07-25
- architecture / glossary: `AGENTS.md`; `docs/02-modules/task-governance.md`
- related work items: `FEAT-001-runtime-binding-task-read-only-audit.md`; `FEAT-002-runtime-standard-task-termination-reconciliation.md`; `BUG-013-codex-unverified-state-and-post-terminal-sse.md`; `BUG-014-codex-sdk-termination-identity-and-reconciliation.md`
