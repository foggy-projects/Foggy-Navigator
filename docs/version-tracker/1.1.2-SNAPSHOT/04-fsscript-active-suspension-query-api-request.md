# FSScript Python Runtime 活动 Suspension 查询 API 需求

## 文档作用

- doc_type: cross-project-follow-up
- intended_for: fsscript-team | langgraph-biz-worker-owner | navigator-platform-owner
- purpose: 向 FSScript 团队提出 Python runtime 活动 suspension 查询能力需求，支撑 LangGraph Biz Worker 将阻塞式 pause primitive 桥接为审批事件
- source_context: `1.1.2-SNAPSHOT` LangGraph Biz Worker 业务脚本编排与审批适配
- status: satisfied

## 当前结果

FSScript Python runtime 已满足本需求：

1. `SuspensionManager(on_suspended=...)`
2. `list_active_suspensions()`
3. `get_active_suspension(script_run_id)`
4. 返回 `SuspensionResult` 快照，不暴露 `_WaitSlot`、线程、Event、Timer 或 manager 内部锁。
5. callback 在锁外执行并做异常隔离。

语义补充：当前查询 API 只返回 `pause_and_wait(...)` 创建的活动 wait-slot suspension，不返回兼容路径 `request_suspension(...)` 创建的非阻塞 suspension。该语义满足 LangGraph Biz Worker 首版需求，因为 Worker 的业务动作审批通过 `compose_pause(...)` 进入 `pause_and_wait(...)`。

## 背景

LangGraph Biz Worker 计划在 Python 进程内集成 FSScript / Compose Script runtime，并通过业务对象门面暴露受控业务能力，例如：

```javascript
const order = orderBiz.get_order({ order_id: "ORD-1001" });
const draft = orderBiz.close_apply_draft({
  order_id: order.id,
  reason: "delivery_failed"
});

return orderBiz.close_apply_submit({
  application_id: draft.application_id
});
```

其中 `orderBiz.close_apply_submit(...)` 是可信对象门面方法，会在内部调用 `compose_pause(...)`，等待 Navigator Java 平台完成用户确认码审批后再继续执行。

当前 FSScript Python runtime 已经提供：

1. `run_script(..., suspension_manager=...)`
2. `SuspensionManager`
3. `compose_pause(...)`
4. `PauseRequest`
5. `SuspensionResult`
6. `ResumeCommand`
7. `RejectCommand`

这些能力足以完成同进程内存态 pause / resume PoC。

## 当前缺口

`compose_pause(...)` 是阻塞式 primitive。脚本线程进入暂停后，Worker 需要及时拿到活动 suspension 信息，并转成：

1. Worker SSE / task event
2. Java 平台审批记录
3. 前端确认码审批弹窗

当前 runtime 可以通过内部 `ScriptRunContext.suspension` 或包装 `SuspensionManager.register_run(...)` 间接获取 suspension，但这会让 Worker 依赖 runtime 内部字段和状态结构。

为了让 Worker 侧集成稳定，建议 FSScript Python runtime 提供正式公开 API。

## 需求目标

请 FSScript Python runtime 补充活动 suspension 查询能力，使宿主进程可以在不访问私有字段的情况下：

1. 查询当前所有活动 suspension。
2. 按 `script_run_id` 查询活动 suspension。
3. 可选：注册 suspension 创建回调。
4. 保持返回值 JSON-safe，不暴露线程、Event、Timer、handler、数据库连接等 host object。
5. 与现有 `ResumeCommand` / `RejectCommand` 兼容。

## 推荐 API

### 最小必需 API

```python
class SuspensionManager:
    def list_active_suspensions(self) -> list[SuspensionResult]:
        ...

    def get_active_suspension(
        self,
        script_run_id: str,
    ) -> SuspensionResult | None:
        ...
```

语义：

1. 只返回 state 为 `SUSPENDED` 且 `suspension is not None` 的 run。
2. 返回 `SuspensionResult` 的快照列表。
3. 不返回 `_WaitSlot`、threading event、timer、manager 内部锁或 run context 引用。
4. 与 `resume(...)` / `reject(...)` 并发调用时线程安全。
5. 如果查询时 suspension 已被恢复、拒绝或超时，可以返回空列表或 `None`。

### 可选增强 API

```python
class SuspensionManager:
    def set_on_suspended(
        self,
        callback: Callable[[SuspensionResult], None],
    ) -> None:
        ...
```

或在构造函数中注入：

```python
manager = SuspensionManager(
    on_suspended=lambda result: publish_event(result)
)
```

语义：

1. 在 `pause_and_wait(...)` 完成 state transition 并创建 `SuspensionResult` 后触发。
2. 回调异常不能破坏 suspension 状态机。
3. 回调不得持有 manager 内部锁执行长耗时逻辑；必要时复制快照后锁外调用。
4. 回调参数仍为 JSON-safe 的 `SuspensionResult`。

## Worker 侧使用方式

Worker 预期流程：

```python
manager = SuspensionManager()

future = executor.submit(
    run_script,
    script,
    ctx,
    semantic_service=semantic_service,
    capability_registry=registry,
    capability_policy=policy,
    suspension_manager=manager,
)

# 后台 watcher 或 callback 发现 suspension
for suspension in manager.list_active_suspensions():
    publish_approval_required(
        task_id=task_id,
        script_run_id=suspension.script_run_id,
        suspend_id=suspension.suspend_id,
        reason=suspension.reason,
        summary=suspension.summary,
        timeout_at=suspension.timeout_at,
    )

# Java 审批通过后
manager.resume(ResumeCommand(
    script_run_id=script_run_id,
    suspend_id=suspend_id,
    payload={
        "approved": True,
        "approval_id": approval_id,
        "approved_by": user_id,
    },
))
```

## 非目标

本需求不要求 FSScript 团队实现：

1. 审批系统。
2. 用户确认码生成或校验。
3. 审批 UI。
4. HTTP / MCP resume endpoint。
5. durable resume。
6. 跨进程 Worker 恢复。
7. 业务动作风险策略。

这些由 Navigator Java 平台、LangGraph Biz Worker 和上游业务系统负责。

## 验收标准

1. `SuspensionManager.list_active_suspensions()` 可返回所有当前活动 suspension。
2. `SuspensionManager.get_active_suspension(script_run_id)` 可按 run id 查询活动 suspension。
3. 返回对象不暴露 host object 或内部等待结构。
4. API 与 `resume(...)` / `reject(...)` 并发使用时线程安全。
5. 单元测试覆盖：无 suspension、单个 suspension、多个 suspension、resume 后不再返回、reject 后不再返回、timeout 后不再返回。
6. 如实现 callback，需测试回调触发时机、异常隔离和锁外执行。

## Navigator 侧接受口径

如果 FSScript 团队短期只能交付查询 API，Navigator / Worker 可以先用 watcher 轮询实现审批事件桥接。

如果 FSScript 团队同时交付 `on_suspended` hook，Worker 可优先使用 callback，查询 API 作为恢复、巡检和兜底能力。
