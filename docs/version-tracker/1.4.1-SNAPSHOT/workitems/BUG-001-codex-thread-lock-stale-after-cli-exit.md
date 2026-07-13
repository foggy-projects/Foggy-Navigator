---
type: bug
bug_source: user-report
version: 1.4.1-SNAPSHOT
ticket: BUG-001
severity: major
status: ready-for-verification
reproduction_status: confirmed
test_strategy: unit-test
automation_decision: required
owner: codex-agent-worker
---

# Codex CLI 退出后 Thread 锁未自动回收

## 文档作用

- doc_type: bug
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 跟踪 Codex CLI 被用户主动或意外关闭后，Worker 任务状态与 Thread reservation 未及时回收的问题。

## Background

Codex SDK Worker 为 resumed thread 提供 Worker 内部互斥，避免同一个 Codex thread 被并发继续。用户报告在 Codex CLI 进程已经全部关闭后，再次继续会话仍收到 `CODEX_THREAD_ACTIVE`。

锁的权威归 Codex Worker 管理。Java 侧保留现有 Session 数据库行锁和任务状态检查，但不新增 JVM 本地锁，也不负责释放 Worker 内部 reservation。

## Reproduction

1. 使用已有 `session_id` 启动 Codex resume 任务。
2. 在任务仍运行时，通过 Worker 进程管理接口或操作系统直接结束对应 Codex CLI 进程。
3. 确认进程列表中已不存在该 PID。
4. 再次使用相同 `session_id` 继续。
5. Worker 仍可能以 `reservation` 或 `task_registry` 为来源返回 `CODEX_THREAD_ACTIVE`。

## Expected vs Actual

- Expected：Worker 持续核对 reservation、任务注册表与真实 Codex CLI 进程；确认进程已经退出后，将对应任务收敛为终态并安全释放 Thread reservation。
- Actual：进程关闭接口只结束 PID；reservation 只在 `runQuery()` Promise 完成时释放。如果 SDK 事件流未及时结束，内存任务和 reservation 会继续阻止 resume。

## Impact Scope

- `tools/codex-agent-worker` 的 resumed thread 并发保护与进程管理。
- 用户主动点击关闭进程、通过任务管理器结束进程、Codex CLI 崩溃或被系统终止的场景。
- Java 侧会收到稳定的 409，但无法自行判断或释放 Worker 内部陈旧锁。
- 不影响 `codex-app-server-worker` 的独立 Thread/Turn 管理。

## Test Strategy

- 单元测试：守护器必须在真实 PID 连续缺失并超过安全窗口后 abort 对应任务并释放 reservation。
- 单元测试：真实 PID 仍存活、处于启动保护期或进程扫描失败时不得释放 reservation。
- 路由测试：通过进程管理接口主动 kill 已绑定任务时，必须同步终止 Worker 任务并进入统一清理流程。
- 回归测试：同一 thread 在真实任务仍运行时继续稳定返回 `CODEX_THREAD_ACTIVE`。

## Code Inventory

- `tools/codex-agent-worker/src/codex/thread-reservations.ts`
- `tools/codex-agent-worker/src/codex/thread-process-watchdog.ts`
- `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
- `tools/codex-agent-worker/src/routes/processes.ts`
- `tools/codex-agent-worker/src/index.ts`
- `tools/codex-agent-worker/tests/thread-process-watchdog.test.ts`
- `tools/codex-agent-worker/tests/thread-reservations.test.ts`
- `tools/codex-agent-worker/tests/processes-route.test.ts`

## Fix Checklist

- [x] 提供按 taskId 统一释放 Thread reservation 的 Worker 内部能力。
- [x] 主动 abort 任务时进入统一 reservation 清理流程。
- [x] 主动 kill Codex CLI 时关联并终止对应 Worker 任务。
- [x] 增加周期守护器，交叉核对运行任务、reservation 与真实进程。
- [x] 守护器采用连续缺失安全窗口，不按任务总时长盲目解锁；空闲时不执行系统进程扫描。
- [x] 进程扫描失败时 fail closed，不改变任务和锁状态。
- [x] Worker 关闭时停止守护器。
- [x] 补齐自动化回归并完成 Worker 全量测试、类型检查和构建。

## Constraints / Non-goals

- 不在 Java 侧新增 JVM 锁或新的 Thread reservation 权威状态。
- 不使用固定任务 TTL 强制释放仍有真实进程的长任务。
- 不允许守护器在进程扫描异常时推断进程已经退出。
- 本次不修改 Codex app-server Worker 的独立执行模型。

## Verification

- `npm test`：通过，159 tests，158 pass，1 个 Windows-only 测试在 Linux 环境跳过，0 fail。
- `npm run typecheck`：通过。
- `npm run build`：通过。
- `git diff --check`：通过；仅报告既有 CRLF 文件 `src/routes/processes.ts` 的行尾转换提示。
- 安全回归已覆盖：真实进程持续存活时不释放锁；reservation 已释放但旧 PID 仍存活时，新 resume 仍由 `process_scan` 阻止；连续缺失超过安全窗口后才 abort 并释放；扫描失败时不改变状态。

## Progress Tracking

### Development

- status: complete
- current: Worker-owned 统一守护、任务终止与 reservation 清理入口均已实现；Java 侧未新增锁。

### Testing

- status: complete
- automation: required
- evidence: Worker 全量测试、类型检查和构建均通过。

### Experience

- status: N/A
- reason: 纯 Worker 生命周期修复，无新增或修改 UI 交互。

### Execution Check-in

- completed work: 增加按 taskId 释放 reservation；`abortTask` 统一清理；进程 kill 路由关联 Worker task；新增统一进程守护器及可配置扫描/缺失窗口；Worker 启停接入守护器。
- touched code paths: `thread-reservations.ts`, `thread-process-watchdog.ts`, `sdk-wrapper.ts`, `routes/processes.ts`, `config.ts`, `index.ts`, `.env.example` 及对应测试。
- self-check conclusion: 真实进程仍是最终安全边界；内存 reservation 提前释放不会绕过进程扫描；扫描异常 fail closed；未发现阻塞性实现问题。
- test status: passed
- remaining risks: Linux 环境未执行 Windows PowerShell 进程枚举的运行时用例；默认配置下外部进程退出到回收存在约 10–15 秒确认延迟，这是避免瞬时扫描误判的设计取舍。
- acceptance readiness: ready-for-coverage-audit，尚未进行正式验收或发布。

## References

- 用户报告：Codex CLI 进程已经关闭，但继续相同会话仍返回 `CODEX_THREAD_ACTIVE`。
- 首次引入 resumed thread 互斥：commit `426ab007`。
- [BUG-001 Implementation Quality Gate](../quality/BUG-001-codex-thread-lock-stale-fix-quality-review.md)
