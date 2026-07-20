---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-006
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: Project Owner
approved_at: 2026-07-20
open_questions: []
---

# Delivery Spec: Codex 历史任务终止重试与诊断收敛

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定历史 Codex CLI 任务安全终态对账和 App Server 终止检查可操作诊断的目标、边界与验收要求。
- canonical_path: docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-006-codex-historical-termination-reconciliation.md

## Goal

- version_goal: 补齐 BUG-004/BUG-005 在 Worker 重启、历史任务注册丢失和 runtime 不可用场景下的治理闭环。
- target_outcome: 对历史 CLI `CANCEL_REQUESTED` 任务再次中止时，只有在 Worker task 与对应 CLI 进程均被严格证明不存在后才安全释放平台运行态；App Server inspection 返回可定位的安全 runtime 错误码。

## Scope

- in_scope:
  - Codex SDK/CLI 历史 `CANCEL_REQUESTED` 任务的重复取消前安全对账。
  - App Server `termination-inspection` 的 runtime affinity/readiness 业务异常映射。
  - 对上述行为的失败优先自动化回归。
- affected_modules: `addons/codex-worker-agent`；版本工作项文档。
- external_dependencies: Codex Worker task status、CLI process snapshot；App Server runtime registry。

## Non-Goals

- out_of_scope: 直接改现场数据库、把无终态证据的任务标记为 `ABORTED`、跨 runtime/instance 重路由、修改 Worker 协议、发布或重启现场服务。
- do_not_touch: 前端交互、数据库 schema、其他工作区、真实凭据、BUG-004/BUG-005 历史证据。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 仅对 SDK/CLI 且已处于 `CANCEL_REQUESTED` 的重复取消执行历史任务对账 | 正常首次取消必须继续走签名 capability 和 Worker 中止链路 | App Server 继续使用专用 inspection/retry 合同 |
| Worker task 404 后必须再读取结构完整的进程快照，并同时排除 workerTaskId 与 codexThreadId | Worker 重启造成注册丢失不能单独证明原生进程退出 | 快照缺失、格式异常、查询失败或存在不可归属 orphan 时 fail closed |
| 双重缺席证据成立时沿用 `CODEX_STALE_TASK_REPAIRED`，收敛为可恢复 `FAILED` | 没有 Worker 终态证据，不得伪造 `ABORTED`；但平台运行态需要释放 | 同步 SessionTask、必要的 Session 投影和状态事件 |
| inspection 对 `CodexRuntimeUnavailableException` 返回其既有安全 code | `CODEX_TERMINATION_INSPECTION_UNAVAILABLE` 隐藏了 affinity/readiness 根因 | 不返回异常详情、URL、instance、token 或其他敏感字段；未知异常仍返回通用码 |

## Acceptance Criteria

- [x] AC-1: SDK/CLI `CANCEL_REQUESTED` 任务在 Worker task 404 且严格进程快照证明 task/thread 缺席时，重复取消将任务收敛为 `FAILED` + `CODEX_STALE_TASK_REPAIRED`，不创建新的终止 operation、不调用 Worker abort。
- [x] AC-2: Worker task 仍存在、状态查询非 404、进程查询失败、快照不完整或仍含目标 task/thread 时，不执行 stale repair，保留原有安全取消行为。
- [x] AC-3: App Server termination inspection 遇到 runtime affinity/readiness 业务异常时返回该异常的安全 code；Worker 拒绝和未知异常语义保持兼容。
- [x] AC-4: 自动化回归实际运行通过，且未改 API path、数据库 schema、前端合同或 Worker 协议。

## Contract / Data / Security Constraints

- API or event contract: `/api/v1/tasks/{taskId}/cancel` 与 `/termination-inspection` path、方法和成功响应不变；仅细化现有 A600 message code。
- data and migration: 无 schema 或批量数据迁移；历史任务只在用户再次发起取消且双重证据成立时按单任务更新。
- compatibility and rollback: 回滚后恢复原重复取消与通用 inspection 错误行为；首次取消和 App Server retry 不受影响。
- permissions and secrets: 保留现有用户、租户、Worker ownership 校验；日志、响应、测试和文档不得记录真实 JWT/token。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-2 | critical | `CodexTaskServiceTest` 双重证据成功与 fail-closed 回归 | failure-first 与修复后 Maven 命令、测试结果 |
| AC-3 | major | `CodexTaskExtensionControllerTest` runtime code 与未知异常映射 | 精确断言与 Maven 结果 |
| AC-4 | major | 相关测试集、模块 reactor、`git diff --check` | 命令、数量、结果、changed paths |

## Bug Context

- bug_source: user-report
- severity: major
- environment: `dev-kvm-jdk17.foggysource.com`；App Server 任务 `20260719-638a`；Codex CLI 任务 `20260719-a843`。
- current_behavior: App Server inspection 返回通用 `CODEX_TERMINATION_INSPECTION_UNAVAILABLE`；历史 CLI 任务保持 `CANCEL_REQUESTED`，再次调用 cancel 后仍不收敛。
- expected_behavior: runtime 业务不可用原因可安全定位；已被严格证明不存在原生任务/进程的历史 CLI 任务释放平台运行态。
- reproduction_steps:
  1. 对历史 App Server `CANCEL_REQUESTED` 任务调用 `termination-inspection`，触发绑定 runtime 不可用。
  2. 对 Worker 已重启且任务注册丢失的历史 CLI `CANCEL_REQUESTED` 任务调用 `cancel`。
  3. 观察旧实现将 inspection 统一包装为 unavailable，并在 Worker 404 后保持 termination unconfirmed。
- reproduction_status: partial
- existing_evidence: 用户现场响应与截图；源码确认 inspection 通用 catch；源码确认 Worker abort 404 不足以证明原生进程退出；已有 resume stale repair 使用严格双重证据。
- existing_tests: resume stale repair 已覆盖 404、进程快照和 fail-closed；重复 cancel 未复用该对账；inspection 仅覆盖成功路径。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - Git push 不会部署或重启目标 Java 后端，现场验证必须在新制品部署后进行。
  - `FAILED` 表示平台通过缺席证据修复 stale guard，不等价于收到 Worker `ABORTED` 终态。
  - 用户在问题描述中暴露的 Bearer JWT 必须在环境侧轮换，仓库不得保存。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 对可稳定复现的缺陷先建立失败回归，再修复并运行通过。
- 如需改变目标、范围、兼容、安全边界或数据库结构，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 重复取消 SDK/CLI `CANCEL_REQUESTED` 任务前复用严格的 Worker task + CLI process 双重缺席证明；证明成立后在事务内加锁重读，仅对身份未变且仍为 `CANCEL_REQUESTED` 的任务收敛为 `FAILED` + `CODEX_STALE_TASK_REPAIRED`。
  - 远程缺席探测期间若其他流程已写入终态，加锁重读后直接保留该终态，避免 stale repair 覆盖新结果。
  - `termination-inspection` 对 `CodexRuntimeUnavailableException` 返回原有安全业务码，未知异常仍使用 `CODEX_TERMINATION_INSPECTION_UNAVAILABLE`。
- changed_paths:
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexTaskExtensionController.java`
  - `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/controller/CodexTaskExtensionControllerTest.java`
  - `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-006-codex-historical-termination-reconciliation.md`
- tests_and_results:
  - failure-first: `mvn -pl addons/codex-worker-agent -am -Dtest=CodexTaskServiceTest#repeatedCancelRepairsVerifiedAbsentSdkTaskWithoutMintingTerminationOperation,CodexTaskExtensionControllerTest#terminationInspectionReturnsSafeRuntimeUnavailableCode -Dsurefire.failIfNoSpecifiedTests=false test` — 修复前 2 项中 1 failure + 1 error，分别证明 inspection 丢失 runtime code 与重复 cancel 未进入 stale reconciliation。
  - concurrency failure-first: `CodexTaskServiceTest#repeatedCancelDoesNotOverwriteTerminalStateCommittedDuringAbsenceProbe` — 加锁重读前失败，证明旧修复路径可能覆盖探测期间的终态。
  - targeted regression: 4 项通过，0 failures/errors/skips。
  - focused classes: `mvn -pl addons/codex-worker-agent -am -Dtest=CodexTaskServiceTest,CodexTaskExtensionControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` — 164 项通过。
  - full reactor: `mvn -pl addons/codex-worker-agent -am test` — 8 个 reactor 模块全部 `SUCCESS`；Codex Worker Agent 470 项通过；全 reactor 合计 2085 项，0 failures/errors，3 项环境型 MySQL 集成测试跳过。
  - `git diff --check` 通过。
- manual_or_experience_evidence: 未执行目标环境 live smoke；目标 Java 后端尚未基于本变更重建/重启，且当前执行环境无法通过该域名回连。
- deviations: none
- residual_risks:
  - 目标环境必须重建并重启 Navigator Java 后端后，现场 curl 才能验证新行为。
  - 历史 CLI 任务只有在 Worker status 404 且结构完整的进程快照同时排除 workerTaskId/codexThreadId 时才会自动收敛；证据不足时保持活跃是有意的 fail-closed 策略。
  - 问题描述中暴露的 Bearer JWT 必须立即在环境侧轮换。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户于 2026-07-20 报告部署更新后仍有历史 App Server/CLI 任务无法完成终止治理。
- architecture / glossary: SDK_EXEC、APP_SERVER、runtime affinity、termination operation、stale repair。
- related work items: `BUG-004-codex-cancel-execution-and-retry-confirmation.md`、`BUG-005-codex-runtime-worker-token-rotation.md`。
