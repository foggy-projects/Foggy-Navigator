---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-029
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: standard
bug_source: user-report
approved_by: project-owner-direct-runtime-bug-report
approved_at: 2026-07-28
open_questions: []
---

# Delivery Spec: Claude never-registered cancellation convergence

## Goal

修复 Claude 任务在 Worker 停机窗口内未成功注册、取消派发也未确认后，Java 永久保留
`CANCEL_REQUESTED / PROCESS_UNVERIFIED`，导致会话持续显示进行中的问题。

## Scope

- `TaskStateReconciler` 对“Worker 可达但任务不存在”的独立证据计数。
- `ClaudeTaskService` 对零 provider 进度、已明确取消任务的事务化收口。
- termination operation、`session_tasks`、Session interaction state 和终态事件同步。
- Java 回归测试与现场只读诊断。

不修改 Claude Worker Python 协议、模型配置、9443 网关或数据库结构；不重新发布
Claude Worker。

## Confirmed Decisions

1. 进程列表缺失本身仍不是终态证据。
2. 仅 HTTP 404 计入 provider-task absence；网络错误、429 或任意非 404 会清零该计数。
3. 必须连续取得三次 404，且任务为 `CANCEL_REQUESTED`。
4. 事务内再次确认无 `workerTaskId`、正数 ACK、`lastAliveAt`、result、checkpoint、
   token/cost/duration/turn 等 provider 进度。
5. 满足全部条件后将 Navigator task 收口为 `ABORTED`，并把 termination operation
   记录为 `ABORTED / OBSERVED`；这里的 `OBSERVED` 表示已观察到目标不存在，不声称
   Worker 执行过进程 signal。
6. 任一条件不满足时继续保持 `PROCESS_UNVERIFIED`，不得推断 provider 终态。

## Acceptance Criteria

- [x] Worker 可达、进程不存在、连续三次 status 404、零进度且已取消时，task、
  `session_tasks` 和 Session 收口为 `ABORTED / AWAITING_REPLY`。
- [x] active termination operation 同步关闭，attention/failure 清空并记录 observed time。
- [x] RUNNING 任务即使连续 404 也不自动终结。
- [x] 已有 ACK 等任一 provider 进度时不自动终结。
- [x] 普通 transport failure 会清零明确 absence 计数。
- [x] Claude addon 全量测试通过；受影响 reactor 的既有无关失败被明确记录。

## Bug Context

- environment: `dev-kvm-jdk17-2` Java，物理 Worker
  `/home/sa/.claude-worker:3033`。
- task: `20260728-3d3e`。
- timeline:
  - Java 在 Worker 升级停机期间先持久化 RUNNING task；
  - 首次 stream connect 和取消 dispatch 均为 transport unconfirmed；
  - Worker 恢复后进程列表为空，status 持续明确返回 404；
  - Java 旧调解逻辑仍只写 `PROCESS_UNVERIFIED`，不会释放 Session。
- live evidence:
  - Worker `0.1.12`、3033 可达、active process/task 均为零；
  - Worker event store 无该 task；
  - `claude_tasks` 与 `session_tasks` 均为 `CANCEL_REQUESTED`；
  - `worker_task_id`、provider task ID、ACK、alive、result、checkpoint 均为空；
  - termination operation 为 `RUNNING / UNCONFIRMED`；
  - Session 为 `ACTIVE / PROCESSING`。

## Implementation Result

- implementation_summary:
  - 新增独立 `workerTaskAbsentMissCount`，只累计 Worker status 404；
  - 三次 404 后调用事务化 `reconcileAbsentUntrackedCancellation`，在行锁下重验全部
    零进度条件；
  - 收口 task、统一 task 投影、Session、termination audit 和终态事件，并停止残留
    stream recovery；
  - 保留 RUNNING、已有进度及非 404 场景的 fail-closed 行为。
- changed_paths:
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/TaskStateReconciler.java`
  - `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
  - 对应两个 Claude service test。
  - `session-module/src/main/java/com/foggy/navigator/session/service/TerminationOperationService.java`
  - 对应 termination operation test。
  - 本 work item 与版本索引。
- tests_and_results:
  - failure-first：focused tests 在实现前因缺少
    `reconcileAbsentUntrackedCancellation` 编译失败。
  - `mvn -pl session-module -Dtest=TerminationOperationServiceTest test`：
    13 tests，0 failure/error。
  - `mvn -pl addons/claude-worker-agent
    -Dtest=TaskStateReconcilerTest,ClaudeTaskServiceAbortGuardTest test`：
    30 tests，0 failure/error。
  - `mvn -pl addons/claude-worker-agent test`：
    432 tests，0 failure/error。
  - `mvn test -pl addons/claude-worker-agent -am`：
    common 127、agent-framework 215、user-auth 173、session 440 均通过；
    business-agent 既有 `BusinessTaskScopedTokenLifecycleJpaTest` Spring context
    缺失 `RuntimeRequestAuditService` Bean，710 tests 中 9 errors，Claude addon 因
    reactor fail-fast 被跳过；随后独立 addon 全量 432 tests 通过。
- live_recovery:
  - 未直接改现场 task 表。直接 SQL 会绕过终态事件与 task-token tombstone；
    部署本 Java 后由调解事务自动收口。
- residual_risks:
  - Java 部署重启后明确 404 计数从零开始，默认调解间隔 60 秒，预计最多约三轮收口；
  - 用户已在请求中暴露 Bearer Token，应立即轮换。
- readiness: READY_FOR_SIGNOFF
