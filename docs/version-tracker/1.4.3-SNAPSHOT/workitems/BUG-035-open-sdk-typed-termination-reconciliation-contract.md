---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-035
status: ACCEPTED
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner-user-confirmed
approved_at: 2026-07-30
open_questions: []
---

# Delivery Spec: Open SDK Typed Termination and Request Reconciliation Contract

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 Navigator 服务端与 Open SDK 的 typed termination/readiness/request-ID reconciliation 公共契约、兼容边界和验证义务。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-035-open-sdk-typed-termination-reconciliation-contract.md`

## Goal

- version_goal: 让 Java 调用方通过编译期类型安全地执行 termination readiness、termination request，并在响应丢失后按原 client request ID 查询权威状态。
- target_outcome: `navigator-open-sdk` 提供与 Navigator 正式响应一致的 typed Form/DTO/enum 和明确 typed 方法；调用方不读取或猜测 `Map<String,Object>` 字段。
- critical_outcomes:
  - readiness 明确 durable selected Physical Worker、expected Worker 匹配、能力可用性、Task 当前状态、canonical terminal 与 stable reason code。
  - termination 明确区分 `ACCEPTED`、`REJECTED`、`ALREADY_TERMINAL`；`ACCEPTED` 不等于 terminal。
  - request-ID reconciliation 使用原 taskId 与 `X-Navigator-Client-Request-Id`，区分 not-found、in-progress、accepted、rejected、terminal、ambiguous，并保持只读。
  - receipt 开启时，相同 client request ID 不产生第二次有效 termination dispatch；response loss 后不以新 request ID 盲目重发。
  - termination receipt 持久化可独立配置；关闭时不阻断单次 termination，但必须显式暴露 reconciliation/idempotency 降级。
  - termination receipt 默认保留 7 天，过期物理清理由可配置 cron 在低峰时段执行，其他 runtime audit 的默认 retention 不变。
- success_is_sufficient_when: focused/affected tests 通过，新 snapshot binary/sources 安装到本机 Maven 仓库，`javap` 显示 typed 签名，制品哈希可复核。

## Scope

- in_scope:
  - Open SDK typed request/result/enum、typed `BusinessAgentApi` 方法、旧 Map 方法兼容与 deprecation。
  - Navigator Open API readiness/terminate/reconcile 正式 JSON 字段与稳定状态映射。
  - 在现有 `/task-reconcile` 上增加原 termination request-ID 的只读查询模式。
  - SDK snapshot 版本升级、sources JAR、本机 Maven install、focused/affected tests。
  - termination receipt 独立开关、7 天默认 retention、cron cleanup 及对应 typed capability 字段。
- affected_modules:
  - `navigator-open-sdk`
  - `addons/claude-worker-agent`
  - `business-agent-module`
  - `tools/navigator-chat-observer-bff`（仅依赖版本对齐与编译兼容）
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: none；验证全部使用本地 unit/integration fixture。

## Non-Goals

- out_of_scope:
  - live Navigator、Worker、provider、TMS 或 SIM 联调。
  - Worker/model/Directory/provisioning/runtime resource 变更。
  - SDK publish、push、tag、release。
  - completion-readiness 或其他仍为 Map 的 SDK 契约重构。
- do_not_touch:
  - `/home/sa/workspace/foggy-world-sim`
  - `/home/sa/workspace/tms-x3`
  - 任何 Worker 安装目录、进程、凭据或运行配置。
- non_blocking_or_waivable_items: live/full-chain 验证不在本次授权范围；不影响本地 typed contract 交付。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| typed DTO 字段由 Navigator 服务端正式产生 | 禁止 SDK 从旧 Map 猜测语义 | SDK 只反序列化正式字段 |
| `ACCEPTED` 只代表 termination intent 被接受 | Worker ACK 不是 Task terminal evidence | 另用 `canonicalTerminal` 表达终态事实 |
| typed reconciliation 是严格只读 request-ID query | response loss 需要权威查询且不得触发 repair/dispatch | 原 client request ID 继续放在既有 header |
| 保留旧 Map API 并 deprecated | 降低已有二进制/源码调用方破坏 | typed API 使用清晰的新方法名 |
| 保留旧 projection-repair payload 分支 | 仓库内 CLI 仍使用既有 evidence-gated repair 语义 | typed SDK 不暴露该 mutation 分支；服务端按 legacy 字段显式区分 |
| enum 对未来未知值稳定映射为 `UNKNOWN` | 避免新服务端状态使旧 SDK 反序列化失败 | null/unknown 不被误判为 accepted/terminal |
| snapshot 版本升级到新的唯一版本 | 不复用 `1.0.36-SNAPSHOT` 发布不同内容 | observer BFF 依赖同步升级 |
| `navigator.runtime-audit.termination-receipt-enabled` 独立控制 termination receipt，默认 `true` | 全局关闭 runtime audit 会破坏其他 ask/safe-smoke 审计；开关必须只影响本契约 | disabled 时仍允许单次 termination，但 Navigator 不承诺 receipt-backed 幂等或 request-ID reconciliation |
| disabled 状态必须通过 typed response fail-visible | 不能让调用方误以为 response-loss recovery 仍可用 | reconcile 返回 `AMBIGUOUS` + `TERMINATION_REQUEST_RECEIPT_DISABLED`，禁止自动重发 |
| termination receipt 默认 retention 改为 7 天 | 降低正常排查和 response-loss 查询因 24 小时窗口过短而失效的概率，同时避免放大其他 runtime audit 存储 | 通过 `navigator.runtime-audit.termination-receipt-retention` 覆盖；通用 audit retention 保持 24 小时 |
| cleanup 改为 cron，默认每日 `02:00` | 避免固定每 5 分钟和写路径触发清理 | `navigator.runtime-audit.cleanup-cron` 可覆盖；使用 Spring 六段 cron |
| follow-up SDK 使用新的 `1.0.38-SNAPSHOT` | typed result 新增 receipt/reconciliation availability 字段，不复用旧 snapshot 内容 | observer BFF 依赖同步升级；旧字段和方法不移除 |

## Acceptance Criteria

- [x] AC-1 `BusinessAgentApi` 公开 readiness、terminate、request reconciliation 的 typed 签名，`javap` 可见；调用方无需 Map。
- [x] AC-2 readiness 覆盖 capability supported/unavailable、expected Worker matched/mismatched、current status、canonical terminal 和 reason code。
- [x] AC-3 terminate Form 覆盖 taskId、expectedPhysicalWorkerId、reason、dryRun、confirmTaskId；结果覆盖 request ID、outcome、current status、canonical terminal、reason code。
- [x] AC-4 `ACCEPTED` fixture 的 `canonicalTerminal=false`；rejected 与 already-terminal 有独立 outcome。
- [x] AC-5 typed reconciliation 按原 request ID 只读返回 `NOT_FOUND|IN_PROGRESS|ACCEPTED|REJECTED|TERMINAL|AMBIGUOUS|UNKNOWN`，不创建 audit/operation、不 dispatch、不 repair。
- [x] AC-6 相同 request ID 同 task/operation 返回幂等结果且不二次调用 provider；operation/task mismatch fail closed。
- [x] AC-7 null、未知 enum/status 和 unsupported provider 均有稳定 UNKNOWN/unavailable/reason 行为，JSON 序列化/反序列化通过。
- [x] AC-8 旧 Map 方法与 legacy reconcile payload 仍可编译运行，并标记 deprecated。
- [x] AC-9 新 SDK snapshot binary 与 sources 安装到本机 Maven 仓库，binary SHA-256 和 sources 存在性可复核。
- [x] AC-10 receipt 默认开启；关闭时不写 termination request audit，单次 termination 仍返回真实 outcome，且 response 明确 `receiptPersisted=false`、`requestReconciliationAvailable=false`。
- [x] AC-11 receipt 关闭时 typed reconcile 严格只读返回 `AMBIGUOUS`/`TERMINATION_REQUEST_RECEIPT_DISABLED`，不查 receipt、不调用 provider、不允许自动重发。
- [x] AC-12 termination receipt retention 默认 `7d`、其他 runtime audit 默认 retention 保持 `24h`；物理清理不再固定每 5 分钟或由写路径触发，改为默认每日 `02:00`、可由 Spring cron 配置。
- [x] AC-13 SDK typed DTO 暴露 receipt/reconciliation availability，新 snapshot binary/sources 本机安装并通过 JSON、`javap` 与兼容测试。

## Contract / Data / Security Constraints

- API or event contract: endpoint path 与 credential headers 不变；typed reconciliation 只读取 runtime request audit 与当前 owned task facts。
- data and migration: 无数据库 schema/migration；复用短期 sanitized runtime request audit。
- configuration:
  - `navigator.runtime-audit.termination-receipt-enabled=true`
  - `navigator.runtime-audit.termination-receipt-retention=7d`
  - `navigator.runtime-audit.retention=24h`
  - `navigator.runtime-audit.cleanup-cron=0 0 2 * * *`
- compatibility and rollback: 旧 Map 方法不移除；legacy projection repair 仅由旧 payload 字段触发；回滚到旧 SDK 不影响服务端原能力。
- permissions and secrets: 保持 ClientApp runtime credential exact-self 与 upstream user/task ownership；响应不得包含 token、credential、payload、path 或 stack。

## Caller Protocol and Stable Semantics

- receipt 开启时，同一 `X-Navigator-Client-Request-Id`、同一 task/operation 的
  termination 重复请求是幂等重放；服务端返回既有 request outcome/current task
  facts，不进行第二次有效 provider termination dispatch。相同 ID 绑定到不同
  task/operation 时 fail closed。
- receipt 关闭时，不保存 ID 绑定，Navigator 不提供上述幂等保证；即使 header
  相同，重复 HTTP 请求也视为新的 one-shot attempt。因此 response loss 后禁止自动
  重发，调用方只能查询 canonical task state 或转人工处置。
- typed `task-reconcile` body 只包含原 `taskId`，header 携带原 termination client request ID；该模式严格只读，不创建 reconciliation audit、不调用 provider、不执行 projection repair。携带旧 `expectedDispatchCount`/Worker/confirm/dryRun 字段时才进入 deprecated legacy repair 分支。
- response loss 后可以立即用原 taskId + 原 request ID 查询：
  - `NOT_FOUND`：仅当原 HTTP attempt 已确认不再 in-flight，且首次请求至今仍处于配置的 idempotency retention window 内，才允许以**同一个** request ID 重放同一 termination；禁止自动换新 ID。
  - `IN_PROGRESS|ACCEPTED`：继续查询 task/reconciliation；禁止重新发送 termination。
  - `TERMINAL`：以 `canonicalTerminal=true` 和 current status 为终态事实；不再发送 termination。
  - `REJECTED`：不得自动重发；修正前置条件后需要新的显式 operator decision。
  - `AMBIGUOUS|UNKNOWN`：fail closed，只查询/人工处理；禁止以新 ID 猜测重发。
- receipt 开启时 reconciliation result 返回
  `sameClientRequestIdReplaySafe=true`、`newClientRequestIdAllowed=false`；只有
  `NOT_FOUND` 返回 `terminationReplayRecommended=true`。这里的
  replay-safe/recommended 只允许按上一条的“原请求结束且仍在 retention window
  内”前置条件解释，不是无条件重发授权。receipt 关闭时前两项分别为 `false` 和
  `false`，`terminationReplayRecommended=false`，并返回
  `requestReconciliationAvailable=false`。
- 未来未知 enum 值在 SDK 映射为 `UNKNOWN`；null/缺失 current status 与 reason 映射为字符串 `UNKNOWN`；未知 canonical terminal 保持 `null`，绝不转成 `false` 或 terminal。
- unsupported provider/capability 返回 typed `UNAVAILABLE`/stable reason；Worker mismatch 返回 selected durable Worker、`MISMATCHED` 和 `EXPECTED_PHYSICAL_WORKER_MISMATCH`，不把 expected Worker 当路由输入。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/2/3/4/7 | must-pass | major | service + SDK unit/HTTP fixture + Jackson tests | existing closure tests | exact Maven commands/results |
| AC-5/6 | must-pass | critical | request-audit/service idempotency and reconciliation state tests | existing request audit base | no-provider/no-mutation assertions |
| AC-8 | must-pass | major | legacy method fixture + affected module compile/tests | existing CLI tests | compile/test result |
| AC-9 | must-pass | major | Maven clean install, sources check, `javap`, SHA-256 | none | local artifact paths/hash/output |
| AC-10/11 | must-pass | critical | closure service disabled-mode unit tests + no-audit/no-provider assertions | existing typed closure fixtures | exact outcomes/reason/availability fields |
| AC-12 | must-pass | major | properties default + scheduled annotation + cleanup tests | existing audit cleanup fixture | exact default duration/cron and deletion evidence |
| AC-13 | must-pass | major | SDK HTTP/Jackson tests, affected build, clean install, sources and `javap` | existing typed SDK fixture | new unique GAV/hash/signatures |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation: focused Java tests、JSON round-trip、API signature inspection，单次 `<5m`。
- medium_validation: affected Maven module tests/install，单次 `5-30m`。
- expensive_validation: none planned。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: not-requested
- decision_if_not_approved: proceed-with-focused-and-affected-validation
- expensive_validation_trigger: none
- maximum_expensive_attempts: 0
- reusable_evidence: 当前 closure/audit/CLI unit tests，仅在相关源码未变化时复用。
- stop_when_evidence_is_sufficient: focused/affected tests pass，artifact install/sources/hash/javap 证据齐全，dirty diff 仅含本事项。
- validation_not_required: live Navigator/Worker/provider、frontend/Playwright、SIM/TMS、full authority/replay。

## Waiver Policy

- waivable_items: none of AC-1 through AC-9。
- authorized_role: project owner。
- non_waivable_guards: no second effective termination dispatch、typed accepted 不冒充 terminal、reconciliation read-only、credential/task ownership、no sibling workspace mutation。
- required_risk_record: 未运行的环境验证与任何历史 legacy 分支残余风险必须显式披露。

## Bug Context

- bug_source: user-report
- severity: major
- environment: local Foggy Navigator main workspace；Open SDK `1.0.36-SNAPSHOT`。
- current_behavior: 三个 SDK 方法返回 `Map<String,Object>`；无 termination/reconciliation typed model；现有 reconcile 仅表达 projection repair。
- expected_behavior: SDK 与服务端共享稳定 typed wire contract，并支持原 request-ID 的只读 authoritative reconciliation。
- reproduction_steps: 对 `navigator-open-sdk-1.0.36-SNAPSHOT.jar` 执行 `javap` 查看 `BusinessAgentApi`，三个目标方法均为 Map 签名。
- reproduction_status: confirmed
- existing_evidence: 用户提供旧 binary SHA-256 `86a7825dceac2c61d8ccdd720c9ea9f7dc10640d86b805ffa4e6bd566fbfe7f5`；当前源码与该签名一致。
- existing_tests: closure service、runtime audit、Open SDK CLI/HTTP fixture tests，但没有 typed public contract coverage。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - 同一路径保留 legacy projection-repair branch 会形成临时双模式；必须由 payload 字段明确分流并在 typed SDK 中隐藏 mutation branch。
  - receipt 开关关闭后，单次 termination 仍可用，但 Navigator 侧 request-ID reconciliation 和 receipt-backed 幂等不可用；调用方必须依赖 canonical task state 或人工处置，禁止 response loss 后自动重发。
  - runtime request audit 默认保留 7 天（可配置）。超过保留期后的 `NOT_FOUND` 无法证明原请求从未被接受，调用方必须停止自动 termination replay，并依赖 canonical task state 或人工处置；任何情况下都不能自动换新 ID。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关源码与 `form-design` 规范。
- 在 scope 内自主决定具体类和局部实现。
- 如需改变 endpoint、credential lane、legacy compatibility 或数据库 schema，设置 `NEEDS_REPLAN` 并停止扩展。
- 先补足稳定回归测试，再完成实现与版本安装验证。
- 不运行 live/full-chain，不修改 sibling workspace 或运行资源。
- 完成后填写 `Implementation Result` 并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - `navigator-open-sdk` 新增正式 typed readiness/terminate/reconciliation Form、DTO 与 unknown-safe enum；`BusinessAgentApi` 新增 typed 方法并保留三个 deprecated Map 方法。
  - Navigator 服务端正式产生 typed wire 字段；termination acceptance 与 canonical terminal 分离，post-dispatch task audit 暂时不可用时仍保存最小 request receipt。
  - 原 request-ID reconciliation 使用 taskId-only body，严格只读读取 request receipt 与 canonical task facts；旧字段 payload 继续进入 evidence-gated legacy projection repair。
  - request audit 支持同 task/operation/upstream user 的串行和并发同-ID幂等重放；重复请求不二次调用 provider，不同 scope fail closed。
  - SDK snapshot 从 `1.0.36-SNAPSHOT` 升至唯一内容版本 `1.0.37-SNAPSHOT`，observer BFF 依赖同步对齐。
- changed_paths:
  - `navigator-open-sdk/{pom.xml,src/main/java,src/test/java,src/main/resources}`
  - `addons/claude-worker-agent/src/{main,test}`
  - `business-agent-module/src/{main,test}`
  - `navigator-common/src/{main,test}` 与冻结 route manifest evidence
  - `tools/navigator-chat-observer-bff/pom.xml`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- tests_and_results:
  - failure-first: `mvn -pl navigator-open-sdk -Dtest=RuntimeTaskTypedContractTest test` 在实现前因 typed classes/methods 不存在而编译失败。
  - focused final: `mvn -q -pl addons/claude-worker-agent -am -Dtest=RuntimeTaskTypedContractServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，`10/10` passed。
  - affected final: `mvn -q -pl navigator-common,business-agent-module,addons/claude-worker-agent,navigator-open-sdk,tools/navigator-chat-observer-bff -am test`，exit `0`；最终 surefire reports 汇总 `2347` tests、`0` failures、`0` errors、`3` skipped。
  - artifact install: `mvn -q -pl navigator-open-sdk clean install`，exit `0`；SDK `203` tests、`0` failures/errors/skips。
  - static: `git diff --check` passed；canonical route manifest source/evidence byte-identical，`462` entries，SHA-256 `bb423a4705780bbf9e88cbe0b3b67d64e830d85a8e415640fee8a786e8d71e9e`。
- manual_or_experience_evidence:
  - GAV: `com.foggy.navigator:navigator-open-sdk:1.0.37-SNAPSHOT`。
  - binary: `/home/sa/.m2/repository/com/foggy/navigator/navigator-open-sdk/1.0.37-SNAPSHOT/navigator-open-sdk-1.0.37-SNAPSHOT.jar`；SHA-256 `49878d0e156e0f40804bef2db4fa70f75bd6b680e088b941b73235c09eb71ebc`。
  - sources: `/home/sa/.m2/repository/com/foggy/navigator/navigator-open-sdk/1.0.37-SNAPSHOT/navigator-open-sdk-1.0.37-SNAPSHOT-sources.jar`；已生成，SHA-256 `7b658a1230bb18f8b7d9c04e3fa200db394a7d87dacb8244b7a03c0bede8bdac`。
  - `javap` 已确认 `getRuntimeTerminationReadiness(...) -> RuntimeTerminationReadinessDTO`、`terminateRuntimeTask(..., RuntimeTaskTerminateForm) -> RuntimeTaskTerminationDTO`、`reconcileRuntimeTaskTermination(..., RuntimeTaskReconcileForm) -> RuntimeTaskReconciliationDTO`，并确认各必需 typed getter。
- deviations: none
- residual_risks:
  - deprecated Map 与 legacy repair 双模式仍保留；其退出需要独立兼容窗口和调用方迁移。
  - baseline `1.0.37-SNAPSHOT` 的 request receipt 默认 24 小时；本 follow-up
    已将 termination receipt 独立延长到 7 天。保留期外的 NOT_FOUND 仍不构成未接收
    证明，禁止自动重发。
- reused_evidence: 既有 CLI/closure/request-audit tests 作为兼容基线，并在最终 affected reactor 中全部实际重跑。
- omitted_validation_and_reason: 按批准 scope 未调用 live Navigator、Worker/provider、SIM 或 TMS，未进行 provisioning/runtime resource 变更。
- readiness: `READY_FOR_SIGNOFF`；实现会话未自行标记 `ACCEPTED`。

## Follow-up Implementation Result (2026-07-30)

- implementation_summary:
  - 新增独立 `navigator.runtime-audit.termination-receipt-enabled`，默认开启。关闭时
    non-dry-run termination 跳过 request receipt 注册/更新，但仍调用真实 provider
    并返回 `ACCEPTED|REJECTED|ALREADY_TERMINAL`；typed result 显式返回
    `terminationRequestReceiptEnabled=false`、
    `terminationRequestReceiptPersisted=false` 和
    `requestReconciliationAvailable=false`。
  - receipt 关闭时 typed request-ID reconciliation 不读取 receipt、不调用
    provider、不创建 audit，返回 `AMBIGUOUS`、termination outcome `UNKNOWN`、
    stable reason `TERMINATION_REQUEST_RECEIPT_DISABLED`，且 replay flags 全部
    fail closed。相同 request ID 的重复 termination 不再有 Navigator 幂等保证。
  - termination receipt 使用独立默认 retention `7d`；ask/safe-smoke 等其他 runtime
    audit 保持原 `24h`。移除普通写路径的 opportunistic cleanup，改为 Spring 六段
    cron，默认 Navigator JVM 时区每日 `02:00`，每次最多
    `cleanup-batch-size * cleanup-max-batches` 条。
  - SDK DTO 增加 receipt/reconciliation availability typed 字段；缺失或 null
    capability 按 `false` fail closed。snapshot 升至唯一版本
    `1.0.38-SNAPSHOT`，observer BFF 同步对齐。
  - affected launcher coverage 暴露既有 safe-smoke ingress 未进入冻结 route
    manifest；补充该静态 catalog/evidence 行并更新冻结计数/哈希，不改变 endpoint、
    鉴权或运行行为。
- changed_paths:
  - `business-agent-module/src/{main,test}`
  - `addons/claude-worker-agent/src/{main,test}`
  - `navigator-open-sdk/{pom.xml,src/main,src/test}`
  - `launcher/{.env.example,src/main/resources/application.yml,src/test}`
  - `navigator-common/src/{main,test}` 与冻结 route manifest evidence
  - `tools/navigator-chat-observer-bff/pom.xml`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- tests_and_results:
  - failure-first:
    `mvn -q -pl business-agent-module,addons/claude-worker-agent,navigator-open-sdk -am -Dtest=RuntimeRequestAuditServiceTest,RuntimeTaskTypedContractServiceTest,RuntimeTaskTypedContractTest -Dsurefire.failIfNoSpecifiedTests=false test`
    在实现前因缺少 `isTerminationReceiptEnabled()` 编译失败。
  - focused contract:
    `mvn -q -pl navigator-common,business-agent-module,addons/claude-worker-agent,navigator-open-sdk -am -Dtest=AuthorizationContractTest,RuntimeRequestAuditServiceTest,RuntimeTaskTypedContractServiceTest,RuntimeTaskTypedContractTest -Dsurefire.failIfNoSpecifiedTests=false test`
    exit `0`。
  - affected compatibility:
    `mvn -q -pl launcher,tools/navigator-chat-observer-bff -am test`
    最终 exit `0`；第一次运行准确暴露并修正两处旧 no-interaction 断言，后续运行
    暴露并补齐既有 safe-smoke manifest 缺口。launcher 结束时仍打印既有
    Surefire fork 退出超时警告，但 Maven 结果成功。
  - route/config:
    `mvn -q -pl navigator-common -Dtest=AuthorizationContractTest,AuthorizationRequiredSectionCatalogRegressionTest test`、
    `mvn -q -pl launcher -am -Dtest=AuthorizationRouteManifestCoverageTest -Dsurefire.failIfNoSpecifiedTests=false test`、
    `mvn -q -pl launcher -am -Dtest=RuntimeTimeBasisConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`
    均 exit `0`。
  - artifact install:
    `mvn -q -pl navigator-open-sdk clean install` exit `0`；SDK `203` tests、
    `0` failures/errors/skips。
  - static: `git diff --check` passed；canonical route manifest source/evidence
    byte-identical，`463` entries（`464` lines），SHA-256
    `aa33e1361f2240eaad80ce51387fb4861bc67604f31450e979435856d50d5b95`。
- artifact_evidence:
  - GAV: `com.foggy.navigator:navigator-open-sdk:1.0.38-SNAPSHOT`。
  - binary:
    `/home/sa/.m2/repository/com/foggy/navigator/navigator-open-sdk/1.0.38-SNAPSHOT/navigator-open-sdk-1.0.38-SNAPSHOT.jar`；
    SHA-256 `8002becdb324ef19b5125b45a691a0c8200b9e039bc79ca4de8d896c4f9d3441`。
  - sources:
    `/home/sa/.m2/repository/com/foggy/navigator/navigator-open-sdk/1.0.38-SNAPSHOT/navigator-open-sdk-1.0.38-SNAPSHOT-sources.jar`；
    已生成，SHA-256
    `0f660a2022e10693e283d4ab14da1c69c5bc14c212d3370c34c308ff9508901d`。
  - `javap` 确认三个正式 typed API 签名仍存在，并确认 readiness/termination/
    reconciliation 的 receipt availability getters。
- deviations:
  - 无功能契约 deviation。affected test 发现并补齐 safe-smoke 的既有静态 route
    catalog omission；该修正不改变运行授权或 endpoint。
- residual_risks:
  - receipt disabled 是显式降级模式：单次 termination 可用，但 Navigator 不能阻止
    相同 request ID 的第二次 HTTP attempt 再次调用 provider，也不能证明丢失响应的
    原请求结果；调用方必须禁止自动重发。
  - receipt 过期后的 `NOT_FOUND` 仍不能证明原请求未被接受；只能查询 canonical
    task state 或人工处置。
  - 默认每日最多清理 `200 * 100 = 20,000` 条 expired audit；高流量部署需按容量
    调整 cron、batch size 或 max batches，并关注数据库事务时长。
  - cron 使用 JVM 时区；非 `Asia/Shanghai` 部署必须按本地低峰时间显式配置。
- omitted_validation_and_reason:
  - 按批准 scope 未调用 live Navigator、Worker/provider、SIM 或 TMS，未进行
    provisioning/runtime resource 变更，也未 push/tag/release。
- readiness: `READY_FOR_SIGNOFF`；实现会话未自行标记 `ACCEPTED`。

## References

- requirement / issue: 当前用户请求（2026-07-29）
- architecture / glossary: `docs/02-modules/observability-notification-integration.md`
- related work items: `FEAT-002-runtime-standard-task-termination-reconciliation.md`

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted
- signed_off_by: Codex release reviewer (same-thread evidence audit)
- signed_off_at: 2026-07-30
- acceptance_record: `docs/version-tracker/1.4.3-SNAPSHOT/evidence/BUG-035-delivery-signoff-2026-07-30.md`
- blocking_items: none
- follow_up_required: no
