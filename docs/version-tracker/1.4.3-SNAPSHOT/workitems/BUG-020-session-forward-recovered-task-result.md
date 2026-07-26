---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-020
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: standard
bug_source: user-report
approved_by: project-owner-explicit-implementation-request
approved_at: 2026-07-26
open_questions: []
---

# Delivery Spec: Recovered task result session forwarding

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 修复任务终态恢复消息可以展示但无法转发的问题，并用持久、可审计的真实消息 ID 建立转发关系。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-020-session-forward-recovered-task-result.md`

## Goal

- version_goal: 恢复会话“转发为新会话/已有会话”对已完成任务结果的稳定支持。
- target_outcome: 前端恢复出的临时 `task-result-*` 消息可通过受约束的 `sourceTaskId` 被后端核验、持久化并正常转发，后续刷新与关系查询均引用真实消息。
- critical_outcomes:
  - 已持久化 assistant 消息的现有转发路径和请求兼容性保持不变。
  - 仅当源消息不存在且提供了同一源会话内、当前用户可访问的已完成任务时，允许恢复任务结果。
  - 恢复结果先形成幂等、持久的 assistant final message，再创建转发关系和目标任务。
  - 错误请求不 dispatch、不创建 relation，前端只显示一次可理解的失败信息。
- success_is_sufficient_when: focused backend/frontend regression tests、受影响后端模块测试和完整前端构建通过。

## Scope

- in_scope:
  - 转发请求增加可选 `sourceTaskId`。
  - 后端对 recovered task result 的 ownership、session、status、resultText 校验。
  - 缺失源消息的确定性持久化、既有真实结果消息复用和 relation canonical source message ID。
  - 前端仅为 `recoveredFromTask` 消息透传 `sourceTaskId`。
  - 转发 API 错误提示去重和服务端消息保留。
  - 前后端回归测试。
- affected_modules:
  - `session-module`
  - `packages/navigator-frontend`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: none。

## Non-Goals

- out_of_scope:
  - 普通 streaming delta、tool result 或其他未持久化消息类型的通用转发。
  - Worker/provider 路由、目标会话选择和 agent runtime affinity 语义变更。
  - 数据库 schema/migration。
  - live dev 环境部署、服务重启或发布。
  - 同级 TMS、SIM、Foggy Data MCP 仓库修改。
- do_not_touch:
  - 现有 dirty worktree 和 `.navigator/` 本机配置。
  - 用户提供的 Authorization token；不得写入代码、测试、文档或日志。
- non_blocking_or_waivable_items:
  - dev-kvm live 浏览器复核可在部署后补做，不替代本次自动化验收。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 请求增加可选 `sourceTaskId`，不改变 `sourceMessageId` 必填调用习惯 | 前端 synthetic ID 本身不是数据库主键，需要稳定的任务身份作为恢复依据 | 现有调用方无需修改；仅 source message 查无记录时才走 fallback |
| fallback 必须调用 task ownership 校验并绑定到源 session | 防止通过 taskId 越权读取或转发其他会话结果 | unauthorized、session mismatch、非 COMPLETED、空 result 均 fail closed |
| 优先复用同 task、同内容的 assistant 消息 | 避免重复持久化历史上已存在但前端未加载的最终消息 | 不复用 role/content 不一致的记录 |
| 缺失时使用确定性 UUID 持久化 recovered result | 重试幂等，ID 满足现有 64 字符列约束 | metadata 明确 `TEXT_COMPLETE`、taskId、recoveredFromTask |
| relation 和 API response 使用持久消息 ID | 后续刷新、关系查询和再次转发均可解析真实 source message | 不保留 synthetic `task-result-*` 作为关系事实 |
| API 层抑制全局重复 toast，由调用方展示一次具体错误 | 当前 interceptor 与 view catch 会重复提示 | 401 登录过期行为保持全局处理 |

## Acceptance Criteria

- [x] AC-1: 已存在的 assistant `sourceMessageId` 继续按原路径转发，不要求 `sourceTaskId`。
- [x] AC-2: synthetic source message 查无记录时，合法 completed source task 的 result 被持久化并成功转发。
- [x] AC-3: 已有同 task、同内容 assistant 结果时直接复用；重试不会新增重复消息。
- [x] AC-4: relation 和 `SessionForwardCreateResponse.sourceMessageId` 均为真实持久消息 ID，而不是 `task-result-*`。
- [x] AC-5: unowned task、source session mismatch、非 completed task 或空 result 均拒绝，且不 dispatch、不保存 relation。
- [x] AC-6: 前端仅对 `raw.recoveredFromTask === true` 的消息提交 `sourceTaskId`，普通消息请求不变。
- [x] AC-7: 转发 HTTP 错误只触发一次用户提示，并优先保留后端 `msg/message`。
- [x] AC-8: focused frontend/backend tests、affected backend test run、完整 frontend build 和 `git diff --check` 通过。

## Contract / Data / Security Constraints

- API or event contract: `POST /api/v1/session-relations/forward` 仅新增 optional `sourceTaskId`；响应结构不变。
- data and migration: 不新增表或列；恢复结果使用既有 `session_messages`。
- compatibility and rollback: 旧客户端和真实消息路径完全兼容；回滚代码不会破坏既有数据，但 recovered result 转发会重新失败。
- permissions and secrets: task 必须通过 `SessionTaskResourceAccessService` ownership 校验并与 source session 精确匹配；不得记录用户 token。
- transaction semantics: recovered message、target dispatch 和 relation 保持现有事务边界；失败时不得留下孤立 relation。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Required Evidence |
|---|---|---|---|---|
| recovered result materialization | must-pass | critical | `SessionForwardServiceTest` | persisted message fields、canonical response/relation ID |
| reuse/idempotency | must-pass | major | `SessionForwardServiceTest` | no duplicate `addMessage` |
| ownership/session/status/result guards | must-pass | critical | `SessionForwardServiceTest` | rejection and zero dispatch/relation |
| frontend recovered-task passthrough | must-pass | major | focused Vitest | payload includes task ID only for recovered result |
| frontend error UX | must-pass | major | API/composable focused Vitest | single caller-owned error path |
| affected builds | must-pass | major | Maven affected test + full frontend build | command results |

## Validation Budget and Evidence Sufficiency

- assurance_level: standard
- lightweight_validation: focused Vitest、focused JUnit、source diff 和 `git diff --check`，单次 `<5m`。
- medium_validation: `session-module` affected Maven test run 与 `bash scripts/build-frontend.sh`，单次 `5-30m`。
- expensive_validation: none。
- large_authority_or_replay_policy: prohibited-unless-user-approved。
- full_chain_recommendation_trigger: schema、auth boundary 或 provider routing 如需改变则 `NEEDS_REPLAN`。
- user_approval_status: not-requested。
- decision_if_not_approved: proceed-with-focused-and-affected-validation。
- maximum_expensive_attempts: 0。
- stop_when_evidence_is_sufficient: AC-1 至 AC-8 均有自动化或静态证据，未出现 contract expansion。
- validation_not_required: live dev deployment、Worker restart、跨项目验证。

## Waiver Policy

- waivable_items: 部署后的 dev-kvm 浏览器复核。
- authorized_role: independent signoff owner。
- non_waivable_guards: ownership/session binding、completed/result validation、canonical persisted ID、existing path compatibility、frontend full build。
- required_risk_record: live 环境未部署时明确记录验证边界。

## Bug Context

- bug_source: user-report
- severity: major user-facing workflow blocker
- environment: `dev-kvm-jdk17.foggysource.com`，2026-07-26。
- current_behavior: `useTaskPane` 为缺失的 completed task result 构造 `task-result-<taskId>` 临时消息；转发 API 把该 synthetic ID 当作 `session_messages.id` 查询并返回 `Source message not found`。
- expected_behavior: 用户可转发可见的 completed task result；后端以授权任务事实恢复真实消息并建立可追溯关系。
- reproduction_steps:
  1. 打开 completed task 且最终 assistant message 未进入当前消息列表的会话。
  2. 前端显示 recovered result 并点击“转发为新会话”。
  3. 请求携带 `sourceMessageId=task-result-*`，后端返回 400。
- reproduction_status: confirmed by request/response and source inspection。
- existing_evidence:
  - 用户请求返回 `code=600`、`exCode=B600`、`Source message not found: task-result-20260726-05b6`。
  - commit `bca4ec87` 修复真实 SSE message ID 持久化，但 commit `652ad0c7` 新增 recovered synthetic task result 后未补转发语义。
- existing_tests: `useTaskPaneNativeSubtasks.test.ts` 仅验证 recovered result 可显示，未覆盖转发。
- regression_protection: required。
- waiver_reason_and_risk: N/A。

## Risks and Open Questions

- known_risks:
  - 历史任务可能已有真实 assistant result，但当前 pane 未加载；实现必须先复用而不是重复写入。
  - synthetic message content 与 task result 不一致时不得用前端内容覆盖 authoritative task result。
  - 当前 dev 域从本地环境不可达，live 验证需部署后补做。
- open_questions: none。

## Ultra Execution Contract

- 先读取本文件和根 `AGENTS.md`，仅修改 in-scope paths。
- 保留所有现有 dirty worktree，不读取或修改 `.navigator/` 内容。
- 后端仅在 source message 不存在且 `sourceTaskId` 存在时启用 recovered fallback。
- 如需 schema、provider routing、目标会话语义或跨项目改动，设置 `NEEDS_REPLAN` 并停止扩展。
- 完成后填写 `Implementation Result` 并设置 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - `SessionForwardCreateRequest` 新增 optional `sourceTaskId`；已持久化 source message 路径保持原样。
  - source message 查无记录时，后端通过 `SessionTaskResourceAccessService.requireOwnedTask` 校验 task ownership，再精确校验 source session、`COMPLETED` 和非空 `resultText`。
  - 后端优先复用同 session/task/assistant/content 的真实消息；缺失时使用 `sessionId + taskId` 派生的确定性 UUID 写入 `session_messages`，metadata 标记为 recovered `TEXT_COMPLETE`。
  - relation 和 response 均使用 `SessionManager.addMessage` 返回并可重新查询的持久消息 ID；跨会话真实 message ID 不允许降级到 task fallback。
  - 前端只从 `raw.recoveredFromTask === true` 的消息提取 task ID；普通持久消息 payload 不包含 `sourceTaskId`。
  - forward API 请求抑制全局普通错误 toast，由现有 view catch 显示一次，并保留后端 `msg/message`。
- changed_paths:
  - `session-module/src/main/java/com/foggy/navigator/session/dto/SessionForwardCreateRequest.java`
  - `session-module/src/main/java/com/foggy/navigator/session/repository/SessionMessageRepository.java`
  - `session-module/src/main/java/com/foggy/navigator/session/service/SessionForwardService.java`
  - `session-module/src/test/java/com/foggy/navigator/session/service/SessionForwardServiceTest.java`
  - `packages/navigator-frontend/src/api/unifiedTask.ts`
  - `packages/navigator-frontend/src/composables/useClaudeWorker.ts`
  - `packages/navigator-frontend/src/composables/useForwardSession.ts`
  - `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
  - `packages/navigator-frontend/src/__tests__/useForwardSession.test.ts`
  - `packages/navigator-frontend/src/__tests__/unifiedTaskForward.test.ts`
  - `docs/version-tracker/1.4.3-SNAPSHOT/README.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-020-session-forward-recovered-task-result.md`
- tests_and_results:
  - `pnpm --filter @foggy/navigator-frontend exec vitest run src/__tests__/useForwardSession.test.ts src/__tests__/unifiedTaskForward.test.ts src/__tests__/useTaskPaneNativeSubtasks.test.ts --reporter=dot`: PASS，3 files / 27 tests。
  - `mvn -pl session-module -am -Dtest=SessionForwardServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`: PASS，12 tests，0 failures/errors/skips；reactor 6/6 modules success。
  - `bash scripts/build-frontend.sh`: PASS；frontend/mobile typecheck、workspace tests、Navigator frontend 276/276、Foggy Chat 115/115、Mobile 59/59、Widget 31/31，以及 Web/Mobile builds 均成功。
  - `git diff --check`: PASS；仅报告既有 Java working-copy CRLF normalization warning，无 whitespace error。
  - 首次未带 `-am` 的 session-module 命令在测试前因本地已安装 reactor 依赖滞后而 compile 失败；按项目模块验证规则改用上述 `-am` 命令后通过。
- manual_or_experience_evidence:
  - 用户提供的失败请求使用 synthetic `sourceMessageId=task-result-20260726-05b6`，与恢复消息生成规则和后端旧查询路径精确对应。
  - source review 确认 `bca4ec87` 的真实 SSE message ID 修复仍在主线，当前缺口来自后续 recovered task result 的 synthetic ID 未纳入转发契约。
- deviations: none；验证命令只修正为包含 reactor dependencies，未改变批准范围。
- residual_risks:
  - 本次未部署到 `dev-kvm-jdk17`，因此尚未执行部署后的真实浏览器/API smoke；本地环境此前访问该 dev 域超时。
  - UI 已通过 `forwardSubmitting` 防重复提交，确定性 ID 和既有 `addMessage` 语义覆盖顺序重试；两个并发 HTTP 请求首次恢复同一结果的数据库竞争未单独做并发测试，属于现有 forward endpoint 通用幂等边界。
  - 前端完整基线保留既有测试中的 Vue stub/chunk-size warnings，均未导致失败且与本 BUG 无关。
- reused_evidence: `bca4ec87`、`652ad0c7` 历史提交和既有 `useTaskPaneNativeSubtasks.test.ts` recovered result display regression。
- omitted_validation_and_reason: live dev-kvm deployment/browser smoke 不在批准 scope，需目标环境部署本提交后补做。
- readiness: READY_FOR_SIGNOFF

## References

- root cause commits: `bca4ec87`, `652ad0c7`
- affected UI recovery test: `packages/navigator-frontend/src/__tests__/useTaskPaneNativeSubtasks.test.ts`
