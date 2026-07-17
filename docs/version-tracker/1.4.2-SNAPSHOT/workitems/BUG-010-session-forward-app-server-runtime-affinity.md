---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-010
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: repository owner
approved_at: 2026-07-16
open_questions: []
---

# Delivery Spec: Session Forward 新会话 App Server Runtime Affinity

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定“转发 assistant 消息到新会话时，Codex app-server Provider 被误绑定到 legacy SDK runtime”缺陷的目标、边界、验收与证据要求。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-010-session-forward-app-server-runtime-affinity.md`

## Goal

- version_goal: 保持 Session、Task 与 Provider runtime affinity 一致，避免协作转发路径绕过正常的 app-server runtime 选择。
- target_outcome: `POST /api/v1/session-relations/forward` 以 `NEW_SESSION` 转发到 Codex app-server 模型时，首个任务按普通新任务规则选择 READY app-server runtime，成功创建目标任务与转发关系，不再返回 `CODEX_PROVIDER_RUNTIME_MISMATCH`。

## Scope

- in_scope: NEW_SESSION 转发目标会话的首次 Worker/runtime affinity 建立时序；转发与 Codex app-server 的自动化回归；本 work item 的实现和验证证据。
- affected_modules: `navigator-spi` 的内部调度标记、`session-module`、`addons/codex-worker-agent` 的既有运行时契约、`launcher` 的真实进程 E2E 基座。
- external_dependencies: 仅复用仓内 Codex app-server Worker、mock Responses API 与 H2 隔离测试环境；不调用上游业务项目。

## Non-Goals

- out_of_scope: source assistant message 异步落库可见性；通用异常 HTTP 状态与 RX 错误映射；EXISTING_SESSION resume 语义；runtime registry 调度策略；生产部署、数据修复或远端服务重启。
- do_not_touch: 当前工作区其他未提交改动；其他 Worker Provider；兄弟仓库；真实用户凭据和运行中任务。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| NEW_SESSION 的首个 Provider 任务负责建立 immutable runtime affinity | 新会话尚无历史 task/runtime，预写 Worker 投影会被旧会话兼容逻辑解释为 legacy SDK affinity | 不改变已有任务会话的 affinity 恢复与 fail-closed 行为 |
| 转发路径必须与普通 app-server 新任务使用相同 runtime registry 选择语义 | 同一 Worker、目录、模型在普通 UI 创建时已能正确选择 APP_SERVER | 不新增独立路由或兼容分支，不绕过模型 grant、Worker ownership 或目录校验 |
| 先以真实公共 API + 真实 app-server Worker E2E 固化回归，再实施最小修复 | 缺陷跨 Session、Provider 和 runtime registry 边界，单一 mock 不能充分证明端到端行为 | E2E 使用隔离 H2、repo-local Worker 与 mock LLM，不访问生产数据 |
| 失败转发必须继续保持事务原子性 | 现状已验证失败请求不会留下孤儿会话或 relation | 不引入补偿数据、schema 或迁移 |

## Acceptance Criteria

- [x] AC-1: 自动化测试在修复前稳定复现 NEW_SESSION forward 返回 `CODEX_PROVIDER_RUNTIME_MISMATCH` 或等价 500。
- [x] AC-2: 修复后同一公共 API 请求成功返回目标 Session、Task 和 forward relation，目标任务使用 `codex-app-server-worker` 与 `APP_SERVER` runtime affinity。
- [x] AC-3: 目标会话在首个 Provider 任务选择前不被误标为 legacy SDK affinity；任务接受后 Session 的 Worker、Directory、Provider/runtime 投影完整。
- [x] AC-4: EXISTING_SESSION、普通任务创建和真实 legacy SDK 会话的既有 affinity 行为不退化。
- [x] AC-5: 失败路径仍事务回滚，不遗留无任务的目标会话或转发关系。
- [x] AC-6: 相关 unit/integration 测试和真实进程 E2E 实际运行通过，命令、结果与证据路径写回本文件。

## Contract / Data / Security Constraints

- API or event contract: 不改变 `/api/v1/session-relations/forward` request/response 字段、targetMode 或 Provider event 契约。
- data and migration: 无 schema、数据迁移或生产数据修复。
- compatibility and rollback: 已有 Session 的持久 runtime affinity 继续权威；回滚代码即可恢复旧行为，无数据回滚。
- permissions and secrets: E2E 仅使用隔离测试账号和临时 token；日志、文档和提交不得包含用户提供的 Bearer Token、服务器密码或真实 API key。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/2/3 | critical | 真实 Navigator HTTP + app-server Worker + mock Responses API E2E | 修复前失败摘要、修复后命令 exit code、Surefire 与 artifact 路径 |
| AC-3/4/5 | major | Session forward service regression and affected module tests | 首次提交时序、已有会话行为、事务/关系写入断言 |
| delivery hygiene | major | affected Maven dependency chain and `git diff --check` | 精确命令、测试数、未运行原因和残余风险 |

## Bug Context

- bug_source: user-report
- severity: major
- environment: `dev-kvm-jdk17-2.foggysource.com`，Navigator commit `f9e32a74e4ab61b9b2c3d429c2c33ca5bae0113f`，Codex app-server Worker，`NEW_SESSION` forward。
- current_behavior: 转发先创建目标 Session 并写入 `currentWorkerId`；首个 app-server Task 将该无 runtime state 的会话解释为 `legacy-sdk:<workerId>`，随后以 `CODEX_PROVIDER_RUNTIME_MISMATCH` 失败并返回 HTTP 500。
- expected_behavior: 新会话首个 Task 从 READY app-server runtime 中建立 affinity，转发成功；已有会话继续恢复其既有 affinity。
- reproduction_steps: 准备已持久化 assistant message、Codex app-server 模型配置、Worker 与 Directory；调用 `/api/v1/session-relations/forward`，targetMode=`NEW_SESSION`；观察 500 与 runtime mismatch。
- reproduction_status: confirmed on remote runtime and reproduced by automated E2E before the fix; the same E2E passes after the fix.
- existing_evidence: 2026-07-16 15:49:02 UTC 后端日志记录 `Provider codex-app-server-worker cannot execute on runtime SDK_EXEC`；同一 Worker/Directory/model config 约 12 秒后普通 UI app-server Task 成功；失败事务未留下 Session/relation。
- existing_tests: `SessionForwardServiceTest` 已增加首次 affinity 建立时序断言；`CodexTaskServiceTest` 已覆盖 pristine 预分配 Session、非 pristine 拒绝和普通 continuation fail-closed；repo-local `CodexAppServerNavigatorE2ETest`/runner 已覆盖公共 forward API。
- regression_protection: required.
- waiver_reason_and_risk: none.

## Risks and Open Questions

- known_risks: `currentWorkerId` 同时承担 UI 投影与 legacy affinity 兼容信号；实现通过只允许 JVM 内部对象身份标记触发 pristine Session 的首次 affinity 初始化，普通已有会话继续 fail closed。现有 E2E 文件和 runner 原本处于未提交工作树中，本次仅扩展 forward 场景并保留 BUG-007 场景。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md` 和相关 Session/Codex runtime 实现与测试。
- 先建立能失败的 E2E/回归测试，再实施最小修复；在 scope 内自主决定具体文件和实现结构。
- 不得用跳过 affinity 校验、强制 SDK/app-server 类型转换或放宽 ownership/model grant 的方式通过测试。
- 如需改变已有 Session affinity、API 契约、数据模型、安全边界或扩大到 source message race，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证并记录精确结果；完成后填写 `Implementation Result`，将状态改为 `READY_FOR_SIGNOFF`，不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: NEW_SESSION forward 不再预写 `currentWorkerId`；其首个直接 Provider 调度携带不可由 HTTP/JSON 伪造的 JVM 内部标记。Codex app-server 仅在锁定校验目标 Session 为同 tenant、同 Provider 且无 Worker/task/provider state 的 pristine 预分配 Session 后，按普通新任务规则选择 READY runtime 并原子建立 affinity。普通已有 Session、legacy SDK 恢复和 app-server 缺失 affinity 的 fail-closed 行为不变。
- changed_paths:
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/AgentTaskSubmitRequest.java`
  - `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/InternalTaskDispatchMarkers.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/SessionForwardService.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchRequest.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/TaskOperationRouter.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/model/command/CodexTaskCreateCommand.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/SessionForwardServiceTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `launcher/src/test/java/com/foggy/navigator/launcher/CodexAppServerNavigatorE2ETest.java`
  - `tools/codex-app-server-worker/scripts/run-navigator-e2e.sh`
- tests_and_results:
  - red unit: `mvn -pl session-module -am -Dtest=SessionForwardServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` 在修复前 1/7 失败，证明目标 Session 在 submit 前被预写 Worker。
  - red E2E: `bash tools/codex-app-server-worker/scripts/run-navigator-e2e.sh`，artifact `temp/test-artifacts/bug007-navigator-e2e/20260716T161832Z/`；新增 forward 场景返回 500，错误为 `CODEX_PROVIDER_RUNTIME_MISMATCH`。
  - intermediate red E2E: artifact `temp/test-artifacts/bug007-navigator-e2e/20260716T162623Z/`；仅移除 Worker 预写后返回 `CODEX_RUNTIME_AFFINITY_MISSING`，证明需要受控的首次 affinity 初始化语义。
  - targeted green: `mvn -pl session-module,addons/codex-worker-agent -am -Dtest=SessionForwardServiceTest,CodexTaskServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`，7 + 88 tests，0 failure/error，BUILD SUCCESS，完成于 2026-07-16 16:38:48 UTC。
  - real-process E2E green: `bash tools/codex-app-server-worker/scripts/run-navigator-e2e.sh`，2 tests、0 failure/error，BUILD SUCCESS；artifact `temp/test-artifacts/bug007-navigator-e2e/20260716T163856Z/`，完成于 2026-07-16 16:41:29 UTC。
  - affected dependency chain: `mvn -pl session-module,addons/codex-worker-agent -am test`，8 reactor modules，1816 tests、0 failure/error/skip，BUILD SUCCESS，完成于 2026-07-16 16:43:44 UTC。
  - hygiene: `git diff --check` exit 0；仅报告三个既有工作树文件的 CRLF/LF 转换警告，无 whitespace error。
- manual_or_experience_evidence: 远端原始故障在 2026-07-16 15:49:02 UTC 精确记录 `Provider codex-app-server-worker cannot execute on runtime SDK_EXEC`，且失败事务未留下 orphan Session/relation。修复后隔离 E2E 的 target Session `06c58ad7-30e8-42d3-8560-c7ae3802b9ea`、task `20260717-1844` 完成，持久 affinity 为 `APP_SERVER / appserver-24516623999440bf887a221fbd7416fb@1`。完整证据见 `../evidence/BUG-010-session-forward-app-server-runtime-affinity.md`。
- deviations: none
- residual_risks: 尚未把修复部署到 `dev-kvm-jdk17-2.foggysource.com` 做远端复验；用户先前看到的“消息不存在”属于 source assistant message 可见性时序，本 work item 未复现也未扩展修复；通用 500/RX 错误映射和 EXISTING_SESSION 语义仍在非目标内；E2E 基座文件与 runner 是既有未提交工作树资产，本次验证结果不能替代其独立 BUG-007 签收。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: user report on 2026-07-16
- architecture / glossary: `docs/02-modules/session-module.md`; `docs/terminology-glossary.md`
- related work items: `BUG-007-app-server-single-instance-containment.md`
- evidence: `docs/version-tracker/1.4.2-SNAPSHOT/evidence/BUG-010-session-forward-app-server-runtime-affinity.md`
