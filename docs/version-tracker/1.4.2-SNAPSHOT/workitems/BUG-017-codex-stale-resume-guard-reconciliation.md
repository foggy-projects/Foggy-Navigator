---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-017
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
approved_by: repository owner
approved_at: 2026-07-17
open_questions: []
---

# Delivery Spec: Codex Resume 陈旧运行态自动对账

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定“CLI 已不存在但 Java 控制面仍以活跃任务阻止 resume”的修复边界、事务语义与验证义务。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-017-codex-stale-resume-guard-reconciliation.md`

## Goal

- version_goal: 保持 Navigator 持久任务状态与绑定 Codex SDK Worker 的实际任务/进程状态一致，同时延续 GOV-004 的不确定状态 fail-closed 原则。
- target_outcome: resume 命中陈旧活跃任务时，只有 Worker 的任务状态和新鲜进程快照共同确认旧任务真实不存在，才将残留状态修复为可恢复 `FAILED`；本次请求返回明确的“状态已修复，请重新尝试”，下一次 resume 可正常继续。

## Scope

- in_scope: `codex-worker` 与同一 SDK_EXEC 执行面的 `codex-biz-worker` resume 活跃任务门禁；绑定 Worker 状态/进程对账；`codex_tasks`、`session_tasks`、Session 交互态同步；事务提交语义；自动化回归。
- affected_modules: `addons/codex-worker-agent`；版本工作项索引。
- external_dependencies: 复用现有 SDK Worker `GET /api/v1/tasks/{taskId}/status` 与 `GET /api/v1/processes`，不新增 Worker API。

## Non-Goals

- out_of_scope: Codex app-server runtime 的 lane/process 对账；自动取消、kill 或释放仍被 Worker 持有的任务；修复 Worker 内部仍存在的 stale task registry；生产部署、远端服务重启或历史数据批量清理。
- do_not_touch: GOV-004、BUG-007 及 app-server Worker 的现有用户脏改；其他 Provider；兄弟仓库；真实凭据和正在运行的 Worker。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 单次任务状态 404 不足以自动清理 | SDK Worker 的 status 只查询内存 registry，Worker 重启或 registry 丢失也会 404 | 必须继续取得同一绑定 Worker 的成功、结构完整的新鲜进程快照 |
| 只有 404 + 无 task/thread 匹配进程 + 无不可归属孤儿进程才视为 verified absence | 进程端点执行 OS 级 Codex CLI 扫描；未知孤儿进程可能仍是旧任务 | 进程查询超时、5xx、畸形响应、身份不符或任何歧义都保持原活跃门禁 |
| 修复终态使用可恢复 `FAILED`，不使用 `ABORTED` | 对账证明逻辑任务/CLI 已不在 Worker，不代表发生授权取消 | 不写 termination observed，不伪造显式取消或 Provider terminal event |
| 修复与用户提示在同一 resume 事务内完成，并对专用异常禁用回滚 | 需要 session 行锁下原子更新两套任务投影，同时确保抛错后修复不回滚 | 其他运行时异常继续按默认规则回滚；本次请求不创建新任务 |

## Acceptance Criteria

- [ ] AC-1: resume 在 session 行锁后取得具体活跃 `CodexTaskEntity`，不再只用布尔 `exists` 判断。
- [ ] AC-2: 活跃任务有 `workerTaskId`，绑定 SDK Worker status 明确返回 404，且完整进程快照无对应 `foggy_task_id`、无对应 `codex_thread_id`、无不可归属孤儿 Codex CLI 时，旧任务转为 `FAILED`。
- [ ] AC-3: 修复同步写入 `codex_tasks`、`session_tasks` 和 Session 交互态，发布 recoverable status change，但不标记 termination operation 已观察终态。
- [ ] AC-4: 本次 resume 返回稳定错误 `CODEX_STALE_TASK_REPAIRED`，明确说明旧任务已不在绑定 Worker、残留状态已修复、请重新尝试；同一次请求不创建新任务。
- [ ] AC-5: Worker 返回任务仍 active、status 非 404、进程仍匹配、存在未知孤儿进程、响应畸形、超时/5xx/401/403 或无 `workerTaskId` 时，旧状态不变并继续返回原“正在运行”门禁。
- [ ] AC-6: 专用修复异常不会回滚已完成的对账；其他异常保持默认 rollback 行为。
- [ ] AC-7: 定向测试和受影响 Maven dependency chain 实际运行通过，精确命令与结果写回本文件。

## Contract / Data / Security Constraints

- API or event contract: 不改变 `/api/v1/tasks/resume` 请求/响应结构；沿用现有 `RX.failB`，仅增加稳定、可操作的错误消息。
- data and migration: 无 schema 或 migration；只修复命中的单个陈旧任务及其统一投影。
- compatibility and rollback: 回滚代码即可恢复旧门禁；已修复为 `FAILED` 的记录保持可恢复语义，不做反向数据变更。
- permissions and secrets: 只使用任务已绑定 Worker/runtime 和服务端持久标识；不得信任浏览器传入的 provider task/thread 身份，不记录 Token、API key 或完整进程命令。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| verified absence | critical | 404 + 空/无匹配进程成功修复；匹配/孤儿/畸形/查询失败均 fail closed | `CodexTaskServiceTest` 红绿矩阵 |
| transaction and projection | critical | 专用异常 no-rollback；两套任务表与 Session 交互态一致 | 事务属性断言及持久化/event 断言 |
| compatibility | major | 正常 resume、active guard、SDK Worker client 既有测试 | addon dependency-chain Maven 结果 |
| delivery hygiene | major | `git diff --check`、精确暂存、commit/push | commit 与 upstream 证据 |

## Bug Context

- bug_source: user-report
- severity: major
- current_behavior: `/api/v1/tasks/resume` 只依据数据库中相同 thread/worker/user/provider 的活跃状态直接返回“该会话正在运行任务”，即使对应 CLI 和 Worker 任务已经不存在也不会对账。
- expected_behavior: 对明确 verified absence 的残留活跃状态做一次原子修复，并要求用户重试；任何不确定或仍活跃的情况继续阻止 resume。
- reproduction_status: confirmed by code path and user runtime report.
- regression_protection: required.

## Risks and Open Questions

- known_risks:
  - 若 Worker task registry 仍保留 stale active，status 会返回 200，本切片按设计不会清理；该形态需要 Worker 内部受约束的 ownership reconcile，不能由 Java 猜测。
  - 新任务型 CLI 若已脱离 registry 且命令行无法暴露 thread，会表现为不可归属 orphan；本切片保持阻塞以避免误清理。
  - 本地自动化不能替代目标环境部署后的真实 Worker/CLI 复验。
- open_questions: none

## Ultra Execution Contract

- 先补能稳定失败的 resume 对账回归，再实施最小修复。
- 不得把 404、超时、扫描失败或未知孤儿进程单独解释为任务终态；不得自动发送 abort/kill。
- 在 scope 内自主决定 repository 查询、helper 和异常类型；不得扩展到 app-server Worker 或改动 GOV-004 的显式终止契约。
- 完成后记录 changed paths、精确验证命令、结果、偏差和残余风险，将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: pending
- changed_paths: pending
- tests_and_results: pending
- manual_or_experience_evidence: pending
- deviations: pending
- residual_risks: pending
- readiness: ULTRA_EXECUTING

