---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-030
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: elevated
approved_by: project-owner
approved_at: 2026-07-28
open_questions: []
---

# Delivery Spec: Claude task-owner force cancellation

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定任务所有者二次强制中止的权限、运行时终止和终态证据边界。
- canonical_path:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-030-claude-owner-force-cancel.md`

## Goal

- version_goal: 消除 Claude 已有 Worker 历史事件但 CLI 已消失时永久停留
  `CANCEL_REQUESTED / PROCESS_UNVERIFIED` 的状态。
- target_outcome: 用户再次中止自己的任务时可明确选择强制中止；平台按 taskId
  解析精确运行目标并在可信 Worker 终止证据后收口。
- critical_outcomes:
  - 所有用户只能强制中止自己发起的任务。
  - 浏览器不得选择或伪造 Worker、PID、进程启动身份或 provider task ID。
  - 仍在运行的精确任务进程被终止；无进程但有持久 Worker 历史的任务产生可信强制终态。
  - Worker 不可达或身份不匹配时不得伪造 `ABORTED`。
- success_is_sufficient_when: focused 权限、Worker contract、Java 状态投影和前端二次
  确认回归均通过，受影响模块验证完成且无未披露偏差。

## Scope

- in_scope:
  - Claude `CANCEL_REQUESTED` 任务的 task-owner force cancellation。
  - 二次中止弹窗中默认关闭的“强制中止”复选框。
  - 签名、一次性、绑定 task/physical Worker 的 owner force termination operation。
  - Worker 精确任务进程终止、无进程持久任务的强制终态事件和 Java 投影收口。
  - Worker termination readiness 可观测性及旧安装缺少 stable Worker ID 的明确诊断。
- affected_modules:
  - `packages/navigator-frontend`
  - `session-module`、`navigator-spi`
  - `addons/claude-worker-agent`
  - `tools/claude-agent-worker`
- external_dependencies: 本机 Physical Claude Worker 及其持久 termination receipt/event store。

## Non-Goals

- out_of_scope:
  - 改变 Codex、Gemini 或 LangGraph 的取消协议。
  - 允许用户按任意 PID 终止进程。
  - 自动回滚 Claude 已执行的文件或外部副作用。
  - Worker 不可达时仅修改数据库制造终态。
- do_not_touch: 9443 模型网关、模型映射、LLM 凭据、其他工作区 Worker。
- non_blocking_or_waivable_items: 真实长任务 destructive E2E 可由 owner 延后到部署环境验收。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 所有任务所有者可强制中止自己的任务 | project owner 明确批准 | 服务端以持久任务 owner 为唯一授权源 |
| 浏览器只提交 taskId 和 force | 防止 PID/Worker 目标注入 | 普通 cancel 请求保持兼容 |
| 强制中止使用独立签名 operation | 与普通 cancel、管理员 PID kill 分离审计 | capability 必须绑定 physical Worker 和 task |
| Worker 产生终态证据后 Java 才写 ABORTED | 避免 UI/DB 已结束但 CLI 继续运行 | 网络不确定时继续 fail closed |
| 二次中止才显示复选框且默认关闭 | 降低误操作和输出丢失风险 | 首次中止行为不变 |
| 管理员任意 PID 入口保持原权限 | owner 权限只扩大到自己的 taskId | 不弱化现有 MANUAL_PID_KILL 边界 |

## Acceptance Criteria

- [x] AC-1: task owner 对自己的 `CANCEL_REQUESTED` Claude 任务提交 force 后可完成精确终止。
- [x] AC-2: 其他用户、其他租户、非 Claude 任务和非待取消任务在任何 Worker 调用前被拒绝。
- [x] AC-3: 请求不能通过 body 重定向 workerId、provider task ID、PID 或进程身份。
- [x] AC-4: 有精确绑定 CLI 时 Worker 终止任务进程并返回与 operation/task/Worker 关联的终态证据。
- [x] AC-5: CLI 已消失但 Worker event store 仍为未终态时，Worker 记录强制 ABORTED 事件并关闭持久状态。
- [x] AC-6: Worker 不可达、stable Worker ID 缺失、receipt ledger 不可用或身份不匹配时保持非终态并返回安全错误码。
- [x] AC-7: Java 同步 task、`session_tasks`、Session、termination operation、终态事件和 task-token lifecycle。
- [x] AC-8: 前端仅在 Claude `CANCEL_REQUESTED` 的二次中止弹窗展示默认未选中的复选框，并刷新权威状态。
- [x] AC-9: 普通取消和现有管理员 PID kill 行为保持兼容。

## Contract / Data / Security Constraints

- API or event contract: 统一 cancel 请求增加可选 `force`；Worker 增加 owner-force task
  termination contract，响应必须包含 operation/task/Worker 关联和 observed terminal evidence。
- data and migration: 不新增业务表；termination operation kind 可加枚举值但沿用现有表。
- compatibility and rollback: `force` 缺省 false；旧前端和 SDK 不受影响。回滚代码后新操作记录只作审计历史。
- permissions and secrets: 不记录 capability、签名、Worker token 或请求凭据；所有 ownership、
  tenant、provider、Worker 和 process binding 校验在服务端完成。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/2/3 | must-pass | authorization | Java controller/service tests | existing ownership tests | exact test output |
| AC-4/5/6 | must-pass | destructive runtime | Python integration/contract tests | existing termination fixtures | exact pytest output |
| AC-7 | must-pass | lifecycle/data | Java service tests | BUG-029 projection tests | exact Maven output |
| AC-8 | must-pass | user control | frontend integration test and build | existing abort dialog tests | test/build output |
| AC-9 | must-pass | compatibility | affected module regression | current main baseline | exact command/result |
| live destructive E2E | waivable | environment | deployment smoke with owned disposable task | none | recorded result or waiver |

## Validation Budget and Evidence Sufficiency

- assurance_level: elevated
- lightweight_validation: focused Python, Java and frontend tests，单次预期 `<5m`。
- medium_validation: Claude Worker full pytest、Claude addon affected Maven tests、frontend build，
  单次预期 `5-30m`。
- expensive_validation: none approved。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: not-requested
- decision_if_not_approved: proceed-with-focused-and-affected-validation
- expensive_validation_trigger: only if focused/affected evidence exposes cross-module uncertainty。
- maximum_expensive_attempts: 0 without new owner approval。
- reusable_evidence: BUG-029 task/session projection and termination operation tests。
- stop_when_evidence_is_sufficient: must-pass focused tests、Worker full suite、frontend build 和
  affected Java lane 已给出一致结果，已知无关基线失败单独披露。
- validation_not_required: root-wide Maven、authority/replay/rehearsal、其他 provider E2E。

## Waiver Policy

- waivable_items: 部署环境真实 destructive E2E。
- authorized_role: project owner。
- non_waivable_guards: ownership、tenant、task/Worker/process binding、signed operation、
  no-terminal-without-evidence、secret redaction。
- required_risk_record: 未执行 live E2E 时记录目标环境和剩余操作风险。

## Bug Context

- bug_source: user-report
- severity: major
- environment: `dev-kvm-jdk17` Java main `23462289`，本机
  `/home/sa/.claude-worker:3033` 0.1.12。
- current_behavior:
  - task `20260728-0e1a` 有 1142 ACK 与 checkpoint，Worker event store 返回
    `ACTIVE_TASK_EXECUTION / PROCESS_UNVERIFIED`，但精确 CLI 已不存在；
  - 普通取消三次均因 Worker 未配置 stable Navigator Worker ID 返回 503；
  - 重连只得到已消费历史并立即结束，任务和 Session 永久保持进行中。
- expected_behavior: 所有者明确 force 后由 Worker 按 taskId 终止精确进程或确认无进程并写入
  强制终态，Java 收口全部投影。
- reproduction_steps:
  1. 令已产生 Worker 事件的 Claude CLI 在无 terminal event 时消失。
  2. 对任务调用普通 cancel。
  3. 观察任务长期停留 `CANCEL_REQUESTED / PROCESS_UNVERIFIED`。
- reproduction_status: confirmed
- existing_evidence: Worker `/status`、process snapshot、Java 日志、四张相关数据库表只读结果。
- existing_tests: BUG-029、manual PID termination 和 Worker termination operation tests。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - 强制中止可能丢失末尾模型输出，且不会回滚已完成的工具或文件副作用。
  - 老 Worker 安装必须补充正确 stable Worker ID；不得从端口或显示名称猜测。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 可稳定复现的授权、无进程持久任务和网络不确定场景必须先形成失败回归。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 未经用户明确批准，不得运行大型 authority/replay/full-chain。
- 完成后填写 `Implementation Result` 并设置 `READY_FOR_SIGNOFF`，不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 统一取消接口增加可选 `force`，但继续从已授权任务投影解析 owner、provider 和
    physical Worker；请求体不能提交 PID 或重定向信息。
  - Claude provider 为 task owner 创建独立 `OWNER_FORCE_CANCEL` operation，Worker
    只按签名 operation 和 taskId 解析精确绑定的 Claude CLI；仅在回读到持久
    `OWNER_FORCE_CANCEL / ABORTED` 终态后 Java 才收口任务与 Session 投影。
  - 无绑定 CLI 但有持久事件历史时，Worker 写入强制终态并关闭 event store；Worker
    不可达、身份/回执不一致或退出未确认时保持 `CANCEL_REQUESTED`。
  - Claude 二次中止弹窗增加默认未勾选的“强制中止”，其他 provider 的重试行为不变；
    Worker 版本提升至 `0.1.13`，health 增加 termination readiness 字段。
- changed_paths:
  - `session-module`、`navigator-spi`：typed cancel form、force 路由、operation kind 和回归。
  - `addons/claude-worker-agent`：签名 force dispatch、owner/receipt 校验、状态收口和回归。
  - `tools/claude-agent-worker`：task-bound force-abort、持久终态、readiness 和 Python 回归。
  - `packages/navigator-frontend`：二次确认 checkbox、API 参数、安全错误说明和 UI 回归。
  - `docs/version-tracker/1.4.3-SNAPSHOT`：canonical work item 与索引。
- tests_and_results:
  - `tools/claude-agent-worker/.venv/bin/python -m pytest -q`：
    `552 passed, 11 skipped`。
  - `mvn -pl session-module,addons/claude-worker-agent -am -DskipTests compile`：
    8 个 reactor module `BUILD SUCCESS`。
  - `mvn -pl session-module,addons/claude-worker-agent -am
    -Dtest=TaskControllerTest,TaskDispatchFacadeTest,TerminationOperationServiceTest,ClaudeTaskServiceAbortGuardTest
    -Dsurefire.failIfNoSpecifiedTests=false test`：session 125、Claude addon 20，
    共 145 个用例通过。
  - `bash scripts/build-frontend.sh`：TypeScript、workspace tests、Navigator production
    build 和 Mobile H5 build 全部通过；workspace tests 为 mobile 59、foggy-chat 115、
    widget 31、Navigator frontend 291。
  - 额外运行 `mvn test -pl addons/claude-worker-agent -am`：本次经过的
    common/SPI/framework/auth/session 均成功；随后在未改动的
    `business-agent-module` 基线测试
    `BusinessTaskScopedTokenLifecycleJpaTest` 因测试上下文缺少
    `RuntimeRequestAuditService` 出现 9 个 errors，Claude addon 因 reactor
    fail-fast 被跳过。该额外 root-wide lane 不属于本 work item 必需验证，失败与本次
    changed paths 无依赖关系，已保留原始 surefire evidence。
- manual_or_experience_evidence:
  - 变更前只读核验目标任务已有 1142 ACK/checkpoint、Worker 持久状态为
    `ACTIVE_TASK_EXECUTION / PROCESS_UNVERIFIED`、精确 CLI 已不存在；普通 cancel
    因目标 Worker 未配置 stable Navigator Worker ID 返回 503。
  - 未对目标任务改库、未重启 `/home/sa/.claude-worker`，也未运行真实 destructive
    E2E；按本 spec waiver 延后至部署后的 disposable owned task 验收。
- deviations: none
- residual_risks:
  - 部署时必须同时更新 Java/前端与 Claude Worker `0.1.13`，并为目标安装配置正确的
    `AGENT_WORKER_NAVIGATOR_WORKER_ID`；否则 signed termination 继续 fail closed。
  - 强制中止不会回滚已完成的工具、文件或外部系统副作用，且可能丢失末尾模型输出。
  - 部署环境真实 process kill 尚未执行；自动化已覆盖精确 PID 被杀、无关 PID 保留、
    无 PID 持久任务收口和错误路径不伪造终态。
- reused_evidence: BUG-029 task/session 投影收口、既有 signed termination replay
  ledger、manual PID binding 和 task-token terminal lifecycle 契约。
- omitted_validation_and_reason:
  - live destructive E2E：按 project owner 允许的 waiver 延后到新 Worker 部署后执行。
  - root-wide Maven：不属于批准的 required lane；额外尝试已被上述独立基线测试缺陷阻断。
- readiness: READY_FOR_SIGNOFF

## References

- related work items:
  - `BUG-029-claude-never-registered-cancel-convergence.md`
  - `docs/version-tracker/1.4.2-SNAPSHOT/runbooks/GOV-004-cli-non-termination-and-lifecycle-observability-runbook.md`
