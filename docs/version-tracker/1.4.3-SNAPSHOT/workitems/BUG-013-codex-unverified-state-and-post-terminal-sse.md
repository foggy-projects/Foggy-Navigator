---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-013-codex-unverified-state-and-post-terminal-sse
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: user
approved_at: 2026-07-22
open_questions: []
---

# Delivery Spec: BUG-013 Codex 待核验状态与终态后 SSE 异常

## Document Purpose

- intended_for: ultra-implementation
- purpose: 修复 Codex Worker 已正常完成时，被错误展示为“Codex 执行进程异常退出”并触发多余恢复的回归。
- canonical_path: docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-013-codex-unverified-state-and-post-terminal-sse.md

## Goal

- version_goal: 使 Codex Worker 的非终态诊断、终态事件与 Java SSE 收尾保持一致的用户语义。
- target_outcome: 待核验错误显示为可重新查询的状态卡；任务已完成后到达的 SSE 异常不再安排恢复；诊断只保留稳定安全码。

## Scope

- in_scope:
  - 将 `PROCESS_UNVERIFIED` 的 Worker warning / error 收敛为既有 `reconnect_pending` 可恢复状态。
  - Java Relay 在本地任务已终态时忽略后续 SSE 事件和流错误，并清理流跟踪。
  - 将未分类 Worker SDK 诊断的中文文案改为不臆测进程退出的安全描述。
  - 增加 Worker 与 Java Relay 的回归测试，并记录验证结果。
- affected_modules:
  - `tools/codex-agent-worker`
  - `addons/codex-worker-agent`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: 既有 Codex Worker SSE 事件协议与前端已支持的 `reconnect_pending` 状态卡。

## Non-Goals

- out_of_scope:
  - 不改 Worker 鉴权、任务 capability、进程管理、重试次数或前端卡片视觉设计。
  - 不对 dev-kvm-jdk17 执行 live 重启、升级或任务重放。
- do_not_touch: 用户已有的 `scripts/local-dev-stack.sh`、`tools/codex-agent-worker/stop.sh`、其测试及 BUG-011 工作项。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 非终态 `PROCESS_UNVERIFIED` 统一发出 `CODEX_RUNTIME_RESULT_UNKNOWN` + `reconnect_pending` | 前端已有可操作的“最终状态暂时无法确认”卡片，不能用失败气泡误导用户 | 不改变任务终态；保留稳定错误码，不透传原始诊断 |
| 本地终态优先于后续 SSE 序号与错误处理 | 已完成任务不应因收尾事件再次进入恢复 | 仅清理本任务流跟踪，不改远端任务状态 |
| 未分类 SDK 错误不再声称“执行进程异常退出” | 当前证据只说明未分类运行时诊断，不能证明子进程退出 | 保持中文安全文案与 stable code，禁止原文泄露 |

## Acceptance Criteria

- [ ] AC-1: `PROCESS_UNVERIFIED` warning/error 仅生成可重新查询的 `reconnect_pending` 状态，不生成“异常退出”式失败提示，任务保持非终态。
- [ ] AC-2: 本地任务已 `COMPLETED`/`FAILED`/`ABORTED` 后，后续 SSE event 或 SSE error 不发布矛盾结果、不安排恢复，并释放本地流跟踪。
- [ ] AC-3: 未分类 SDK 诊断展示为不臆测进程退出的安全中文文案，且不暴露原始诊断文本。

## Contract / Data / Security Constraints

- API or event contract: 复用既有 `STATE_SYNC`、`content=CODEX_RUNTIME_RESULT_UNKNOWN`、`subtype=reconnect_pending`、`reconnectable=true`；不增加外部 API。
- data and migration: 无数据库变更或迁移。
- compatibility and rollback: 旧前端仍接收普通 `STATE_SYNC`；回滚只需回退本提交。
- permissions and secrets: 禁止记录或发布 CLI 原始错误、命令行、路径、token 或凭据。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1 | major | Worker safe-diagnostic unit test + Java Relay event test | 通过的 focused test 输出 |
| AC-2 | major | Java Relay terminal event/error regression tests | 通过的 focused test 输出 |
| AC-3 | moderate | Worker diagnostic mapping test | 通过的 focused test 输出 |
| changed surface | moderate | affected Maven module and Worker test lane；`git diff --check` | 精确命令与结果 |

## Bug Context

- bug_source: user-report
- severity: major
- environment: 本机 `~/.codex-worker` SDK Worker 与远程 `dev-kvm-jdk17.foggysource.com` Java 服务；报告会话 `8d7bea28-4c56-45da-8aa9-2393aa98a67d`。
- current_behavior: 2026-07-22 21:44:39（Asia/Shanghai）收到非终态状态同步后显示“Codex 执行进程异常退出”；随后 21:44:58 任务实际完成，但 Relay 又记录 SSE `IllegalStateException` 并安排恢复。
- expected_behavior: 待核验状态应提示可查询、不得宣称进程退出；实际完成后不得再启动恢复。
- reproduction_steps: Worker 收到非终态 `error` / `lifecycle_attention(PROCESS_UNVERIFIED)`，随后收到 `result` 并在终态后产生 SSE 收尾错误。
- reproduction_status: confirmed
- existing_evidence: 远程 Relay 日志显示 `STATE_SYNC`、`SESSION_END`、任务完成以及其后的 SSE stream error；本机 Worker health 正常。
- existing_tests: `sdk-wrapper.test.ts` 与 `CodexStreamRelayTest` 覆盖非终态 error、warning、终态事件和恢复流程。
- regression_protection: required
- waiver_reason_and_risk: 无；live 环境验证需在部署本提交后另行执行。

## Risks and Open Questions

- known_risks: 本次只修复事件语义和本地收尾；不能证明未部署版本的远端环境已即时消除现场消息。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - Worker 未分类 SDK 诊断改为“Codex 运行时返回未分类错误”，不再无证据地断言执行进程退出。
  - Relay 识别 Worker 已发送的 `attention_status=PROCESS_UNVERIFIED`；该 lifecycle warning 与非终态 error 都只发布既有 `CODEX_RUNTIME_RESULT_UNKNOWN` / `reconnect_pending` 可查询状态。
  - Relay 对本地已终态任务在 SSE event 与 SSE error 两个入口均立即清理流跟踪、停止后续处理和恢复调度；安全日志只记录 task、状态、稳定错误码/序号/异常类型。
- changed_paths:
  - `agent-framework/src/main/java/com/foggy/navigator/agent/framework/protocol/WorkerEvent.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexStreamRelayTest.java`
  - `tools/codex-agent-worker/src/diagnostics.ts`
  - `tools/codex-agent-worker/tests/sdk-wrapper.test.ts`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-013-codex-unverified-state-and-post-terminal-sse.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/README.md`
- tests_and_results:
  - `npm test -- --test-name-pattern='thread item error is emitted as a non-terminal warning|SDK stream error'`（在 `tools/codex-agent-worker`）— test runner 执行全量套件：223 passed，0 failed，1 Windows-only skipped。
  - `npm run typecheck`（在 `tools/codex-agent-worker`）— passed。
  - `mvn -pl addons/codex-worker-agent -am -Dtest=CodexStreamRelayTest -Dsurefire.failIfNoSpecifiedTests=false test` — passed；`CodexStreamRelayTest` 46 passed，0 failures/errors/skips。
  - `mvn -pl addons/codex-worker-agent -am test` — 8-module affected reactor passed；现有 MySQL environment-dependent tests 保持 3 skips。
  - `git diff --check` — passed。
- manual_or_experience_evidence: 已由 Java 回归测试断言 `reconnect_pending` payload，前端已有该 payload 的状态卡测试；未对 `dev-kvm-jdk17` 执行 live 部署或重放，需在部署本提交后另行验证现场会话。
- deviations: none
- residual_risks:
  - 当前远程 Java 服务仍需部署本提交才会修复现场会话；本次不包含部署授权。
  - 终态后到达的 Worker event 不再 ACK，是刻意的本地收尾策略；任务已处于终态，后续恢复也被清理。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 2026-07-22 用户报告与会话截图。
- architecture / glossary: Codex Worker SSE、`PROCESS_UNVERIFIED`、`reconnect_pending`。
- related work items: `BUG-004-codex-cancel-execution-and-retry-confirmation.md`、`BUG-011-codex-worker-verified-listener-force-stop.md`。
