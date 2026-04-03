# 02 App Server Event Stream Lag Causes Session Failure

## Date

- 2026-04-02

## Type

- Bug
- Investigation
- Deferred

## Background

在任务执行过程中，事件流中会连续出现如下错误事件：

```text
in-process app-server event stream lagged; dropped N events
```

现场现象不是只丢部分事件，而是会进一步触发会话失败。用户手动执行重新同步后，连接又可以恢复，说明底层任务本身未必已经终止，更像是事件流传输、桥接或消费链路出现了短时失步。

## Observed Symptoms

- 短时间内连续出现多条 `in-process app-server event stream lagged; dropped N events`
- `N` 可能是 1、3、5、7、21、24 等不同值
- 出现多条此类事件后，会话进入失败态或前端视图进入异常态
- 手动重新同步后，可重新连上并继续查看状态

## Current Implementation Sync

当前仓库内尚未找到该日志文本的直接发出位置，因此这份文档目前更适合作为“调查单”，不适合作为已定位完成的修复单。

### 已确认事实

- 在当前仓库代码搜索范围内，未直接检索到 `in-process app-server event stream lagged` 这条日志
- 说明该错误更可能来自：
  - 外部 app-server / runtime
  - 上游依赖
  - 不在当前仓库搜索范围内的运行层

### 当前仓库内最相关的链路

- `tools/codex-agent-worker` 的 Worker 事件转发
- `addons/codex-worker-agent` 的流桥接和状态同步
- `session-module` 的会话消息持久化与 SSE 推送
- `packages/navigator-frontend` 的统一事件消费、重同步和 UI 状态收敛

## Related Code Checklist

虽然日志源未锁定，但技术分析可以优先从以下代码开始：

### Worker / Agent Relay

- `tools/codex-agent-worker/src/routes/tasks.ts`
- `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexStreamRelay.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`

### Session / SSE

- `session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/UnifiedSseController.java`
- `session-module/src/main/java/com/foggy/navigator/session/event/SessionEventListener.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/JpaSessionManager.java`

### Frontend Consumption

- `packages/navigator-frontend/src/composables/useTaskPane.ts`
- `packages/navigator-frontend/src/composables/useClaudeWorker.ts`
- `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
- `packages/foggy-chat/src/components/ChatPanel.vue`

## Investigation Focus

建议技术先围绕以下问题排查：

### 1. dropped events 发生在哪一层

- Worker 内部事件源到 HTTP/SSE 输出
- Java relay 到 session message 持久化
- Session SSE 到浏览器
- 浏览器消费后到 UI 状态机

### 2. “事件流短时失步”为什么会升级为“会话失败”

要明确以下状态是否被错误耦合：

- 流中断
- 流落后
- 会话展示失败
- 任务执行失败

### 3. 重新同步为何能恢复

如果重同步可恢复，通常意味着：

- 任务真实状态仍可查询
- 历史消息仍可回补
- 失败更像前端或 relay 层状态收敛不充分

## Suggested Validation

### 调查阶段验收

只有在以下问题被回答后，才适合进入编码修复：

- 能定位该日志的真实发出源
- 能复现 dropped events 到 session failure 的完整链路
- 能明确“任务失败”和“流异常”当前在哪一层被混淆
- 能给出恢复策略应该落在 Worker、Java relay 还是前端

### 技术验证建议

- 增加链路级 tracing 或 request / taskId / sessionId 贯穿日志
- 对 lag 发生时的 session 状态、task 状态、SSE 连接状态做对照采样
- 比对“未重同步前”和“重同步后”的消息总数、最后一条事件、任务状态

### Frontend / Playwright Validation

Playwright 更适合作为复现和回归辅助，而不是定位根因的唯一工具。建议场景：

1. 打开一个长任务会话并保持页面在线
2. 人为制造网络抖动、页面挂起或高频事件场景
3. 观察前端是否进入失败态
4. 触发重同步
5. 断言历史消息、任务状态和继续观察能力是否恢复

## Delivery Assessment

本项当前可以交付技术，但应作为“调查单”而不是“直接开发单”。

结论如下：

- 可以交付：用于指导技术先做代码分析、定位日志源、补 tracing
- 不建议直接交付为修复单：因为当前还没有锁定真实触发源和责任层

## Status

本项目前保持 Deferred，但文档口径应明确为 Investigation。完成日志源定位和责任层判定后，再升级为正式修复项。
