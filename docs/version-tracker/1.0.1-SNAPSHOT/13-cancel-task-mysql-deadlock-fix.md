# 13 - 任务取消 MySQL 死锁修复

- **状态**: 已修复
- **日期**: 2026-04-06
- **关联**: #12-codex-completed-task-cancel-a2a-resolution-failure, #13-unified-task-cancel (1.0.0-SNAPSHOT)

## 问题

`POST /api/v1/tasks/{taskId}/cancel` 返回 500 Internal Server Error。

### 根因

Cancel 线程与 SSE reactor 线程**同一毫秒内**并发 UPDATE 同一条 `claude_tasks` 行 → **MySQL 死锁**。

```
Cancel 线程                                 Reactor 线程
    │                                            │
    ├─ client.abortTask() ──────────────────────►│ Worker 立即回复 SSE error
    │                                            ├─ failTask() @Transactional
    ├─ abortStream()                             │   UPDATE claude_tasks → FAILED
    │  (reactor 已在 relayEvent 中)              │   UPDATE session_tasks
    ├─ txTemplate                                │
    │   UPDATE claude_tasks → ABORTED            │
    │   UPDATE session_tasks                     │
    └───── MySQL Deadlock ◄─────────────────────┘
```

## 修复方案：`abortRequested` 字段协调

引入 `ClaudeTaskEntity.abortRequested` 布尔字段，作为 cancel 线程与 reactor 线程的协调信号。

### 修复后时序

```
Cancel 线程                                 Reactor 线程
    │                                            │
    ├─ 1. UPDATE abort_requested=true (COMMIT)   │
    │                                            │
    ├─ 2. client.abortTask() ──────────────────►│ Worker 回复 SSE error
    │                                            ├─ failTask()
    ├─ 3. abortStream()                          │   → 读取 abort_requested=true
    │                                            │   → SKIP（不更新 DB）
    ├─ 4. txTemplate                             │
    │   status → ABORTED                         │
    │   abort_requested → false (COMMIT)         └─ done（无 DB 冲突）
    └─ done
```

### 设计要点

1. **`abortRequested` 是辅助字段而非新状态** — 对 UI/状态机零侵入，大部分情况下只存在一瞬间
2. **持久化到 DB** — Worker 离线时标记留存，Reconciler 可据此在 Worker 重连后重试 abort
3. **轻量 UPDATE** — 使用 `@Modifying @Query` 直接 UPDATE 单字段，不加载实体，最小化锁范围
4. **双重防护** — `failTask()` 同时检查 `abortRequested` 和 terminal-state guard
5. **Controller 兜底** — catch `PessimisticLockingFailureException`，重查状态幂等返回
6. **通用模式** — 此方案可复用到 Codex 等其他 Provider

### Worker 失败场景处理

| 场景 | abort_requested | 最终状态 | 说明 |
|------|----------------|---------|------|
| Worker 正常响应 | true → false | ABORTED | 正常流程 |
| Worker 报告任务不存在 | true → false | ABORTED | 任务已结束 |
| Worker 离线/超时 | **true（留存）** | 保持 RUNNING | Reconciler 可据此重试 |
| 极端时序窗口（标记后 Worker 已自行失败） | true → false | 保持 FAILED | terminal-state guard 保护 |

## 修改文件

| 文件 | 改动 |
|------|------|
| `ClaudeTaskEntity.java` | +abortRequested Boolean 字段 |
| `ClaudeTaskRepository.java` | +updateAbortRequestedByTaskId() 轻量 UPDATE |
| `ClaudeTaskService.java` | +TERMINAL_STATES 常量; doAbortWorkerTask() 4 步流程; failTask() +abortRequested/terminal guard |
| `TaskController.java` | cancelTask() catch PessimisticLockingFailureException 兜底 |

## 验证

- [x] 编译通过（BUILD SUCCESS，14 模块）
- [x] 单元测试全部通过
- [ ] 手动验证：创建 Claude Code 任务 → 立即取消 → 不再 500

### 新增测试

| 测试文件 | 测试方法 | 覆盖场景 |
|---------|---------|---------|
| `ClaudeTaskServiceAbortGuardTest` | `failTask_skipsWhenAbortRequested` | failTask 遇 abortRequested=true 跳过 |
| | `failTask_skipsWhenAlreadyAborted` | failTask 遇已 ABORTED 跳过 |
| | `failTask_skipsWhenAlreadyCompleted` | failTask 遇已 COMPLETED 跳过 |
| | `failTask_proceedsNormally_whenNoGuardTriggered` | 正常 FAILED 流程不受影响 |
| | `failTask_proceedsNormally_whenAbortRequestedIsNull` | 旧任务（null 字段）兼容 |
| | `doAbortWorkerTask_setsAbortRequestedBeforeWorkerNotification` | 标记→通知→清流→ABORTED 完整流程 |
| | `doAbortWorkerTask_skipsStatusUpdate_whenAlreadyTerminal` | reactor 已抢先 FAILED，不覆盖 |
| | `doAbortWorkerTask_handlesWorkerOffline` | Worker 离线不阻塞本地状态更新 |
| `TaskControllerTest` | `cancelTask_deadlockFallback_terminalState` | 死锁后重查→已终态→幂等返回 |
| | `cancelTask_deadlockFallback_nonTerminal` | 死锁后重查→仍非终态→返回失败 |

## 后续

- Worker 离线时 `abortRequested=true` 留存，用户可手动重试取消（Reconciler **不**自动重试）
- Codex Provider 可复用相同模式
