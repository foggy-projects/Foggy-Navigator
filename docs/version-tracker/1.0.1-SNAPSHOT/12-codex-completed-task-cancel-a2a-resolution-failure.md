# 12 Codex 已完成任务取消时报 `no A2A agent found` 分析

## Date

- 2026-04-05

## Type

- Bug
- Analysis

## Symptom

调用统一取消接口：

- `POST /api/v1/tasks/{taskId}/cancel`

返回：

```json
{
  "code": 600,
  "exCode": "B600",
  "msg": "Cannot cancel task 20260405-a1d0: no A2A agent found for agentId=codex-worker"
}
```

但从实际业务状态看，该任务已经完成，本次取消本应表现为：

- 直接返回成功 / no-op
- 或至少落到 provider 侧判断 terminal state 后安全返回

而不是在入口处报解析失败。

## Conclusion

这不是单点故障，而是两个设计问题叠加后的结果：

1. `Codex` 直连任务在缺少真实逻辑 Agent 时，会把 `agentId` 回退写成 provider 常量 `codex-worker`
2. 统一取消接口又把这个字段当成“必须能解析成 A2A agent 的逻辑 agentId”来使用

因此，只要某个任务的 `agentId=codex-worker`，`/api/v1/tasks/{taskId}/cancel` 就可能在真正接触任务状态之前先失败。

“任务其实已经完成”只是这次暴露问题的场景，不是唯一场景。对于仍在运行中的这类 Codex 直连任务，同样存在无法取消的风险。

## Trigger Chain

### 1. 前端取消入口默认不传 `agentId`

`packages/navigator-frontend` 当前调用统一取消接口时，默认只传 `taskId`：

- `packages/navigator-frontend/src/composables/useClaudeWorker.ts`
- `packages/navigator-frontend/src/api/unifiedTask.ts`

也就是说，后端通常会走“先查任务，再从任务记录里拿 `agentId`”这条路径。

### 2. TaskController 会从任务记录回填 `agentId`

`session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java`

取消时如果请求体没传 `agentId`：

- 先 `getTask(taskId)`
- 然后取 `task.getAgentId()`
- 再调用 `taskDispatchFacade.cancelTask(taskId, agentId, context)`

所以任务投影里保存的 `agentId` 值会直接决定取消路由。

### 3. 统一取消要求 `agentId` 必须能解析成 A2A agent

`session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`

当前 `cancelTask(...)` 已经是 fail-fast 语义：

- 先 `agentResolver.resolveAgent(agentId, context)`
- 解析不到就直接抛出
- 不再 fallback 到 `TaskQueryProvider.cancelTask(...)`

抛出的正是这次线上看到的错误：

- `Cannot cancel task ...: no A2A agent found for agentId=codex-worker`

### 4. `codex-worker` 不是一个可解析的 Agent 实体 ID

`session-module/src/main/java/com/foggy/navigator/session/registry/UnifiedAgentResolver.java`

`UnifiedAgentResolver` 只会委托各个 `A2aAgentProvider` 去解析真实 agent。

而 `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerAgentProvider.java` 的解析规则是：

- `agentId` 精确匹配
- 或 `name` 匹配

它不会把 provider 常量 `codex-worker` 解释成某个具体 `CodingAgentEntity`。

因此：

- `resolveAgent("codex-worker", context)` 返回空

这是符合当前实现的，不是解析器偶发失效。

### 5. Codex 直连任务会把 `agentId` 回退写成 `codex-worker`

根因在：

- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`

Codex 任务同步统一任务投影时会写入：

- `sessionTask.setAgentId(agentId)`

这里的 `agentId` 来自 `resolveLogicalAgentId(...)`。当下面两个值都缺失时：

- 请求没有显式 `agentId`
- 现有 session 也没有已绑定的逻辑 `agentId`

它会直接回退到：

- `AGENT_ID = "codex-worker"`

于是统一任务投影和 session 投影里就会出现：

- `agentId = codex-worker`
- `providerType = codex-worker`

这两个语义被写成了同一个值，但它们本来不是一个概念。

## Why This Case Shows Up On A Completed Task

从 provider 侧行为看，已完成任务本来是可以安全 no-op 的。

例如：

- `CodexTaskService.abortTask(...)` 对 `COMPLETED / FAILED / ABORTED` 有 terminal-state guard

也就是说，如果请求真的走到了 Codex 的任务服务层：

- 已完成任务不会再报错
- 最多只会记录 warning 然后返回

这次之所以仍然报错，是因为请求根本还没走到 provider 层，就在统一取消入口的 A2A 解析阶段提前失败了。

因此，“任务已经完成”与“取消时报 no A2A agent found”并不矛盾：

- 完成态是 provider 内部语义
- 当前失败发生在 provider 之前

## Likely Business Scenario

这类问题最容易出现在下面这个组合：

1. 用户从 Workers 页创建 Codex 任务
2. 前端因为 `modelConfigId` 指向 Codex backend，且与所选逻辑 Agent 不一致，故意不传 `agentId`
3. 后端按 direct provider route 创建 Codex 任务
4. 任务投影因缺少真实逻辑 Agent，最终把 `agentId` 写成 `codex-worker`
5. 后续用户点击“中止”或直接调用 `/api/v1/tasks/{taskId}/cancel`
6. 后端再把 `codex-worker` 当作逻辑 Agent 去解析，最终失败

其中第 2 步在前端已有明确注释，属于有意行为，不是偶发漏参。

## Existing Design Contract Already Says This Is Wrong

`docs/requirement-tracker/2026-Q1/26-worker-execution-context-routing-design.md` 已明确约束：

- 不允许再将 `agentId` 强制写成 `claude-worker` / `codex-worker`

本次问题说明这条约束在 Codex 直连任务持久化链路上仍未完全落地。

## Impact

直接影响：

- 某些 Codex 直连任务无法通过统一取消接口取消
- 即使任务已经完成，用户仍会得到 600 业务错误

更深层影响：

- `agentId` 与 `providerType` 语义继续混淆
- 统一 `/tasks/{id}/cancel` 无法稳定覆盖 direct provider route
- 后续 `reconnect / resync / rewind / delete` 一类按任务投影路由的能力，存在同类语义污染风险

## Fix Directions

推荐按优先级分两层修。

### P1. 先修统一取消的幂等性和兜底路由

目标：

- 已完成任务调用 `/api/v1/tasks/{taskId}/cancel` 不再报错

建议：

1. `TaskController.cancelTask(...)` 先读取任务状态
2. 若任务已处于 `COMPLETED / FAILED / ABORTED`，直接返回成功或 no-op
3. 若 `agentId` 为空、或明显等于 `providerType` 常量，则按任务所属 provider 路由到 `TaskQueryProvider.cancelTask(...)` / provider-specific no-op，而不是强行走 A2A 解析

这样可以先止血，避免用户在 terminal state 上看到误导性错误。

### P2. 再修持久化语义

目标：

- `agentId` 永远只表示真实逻辑 Agent
- `providerType` 永远只表示执行 backend

建议：

1. `CodexTaskService.resolveLogicalAgentId(...)` 不再把 `codex-worker` 当成逻辑 agentId 回填
2. 没有真实逻辑 Agent 时，统一任务投影里的 `agentId` 保持 `null`
3. direct provider route 的后续操作应主要依赖 `providerType`，而不是伪造一个 provider 常量去充当逻辑 Agent

这才符合需求 26 的原始设计。

## Suggested Regression Tests

建议至少补下面几组测试：

1. Codex direct task 无显式 `agentId`，任务投影不得再写入 `agentId=codex-worker`
2. 已完成的 Codex 任务调用统一 `/tasks/{taskId}/cancel`，应返回成功/no-op
3. 运行中的 Codex direct task 调用统一 `/tasks/{taskId}/cancel`，应能正确落到 provider 侧中止
4. 当任务投影 `agentId` 为空、`providerType=codex-worker` 时，取消路由仍应可工作

## Files Involved

- `session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/main/java/com/foggy/navigator/session/registry/UnifiedAgentResolver.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerAgentProvider.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
- `packages/navigator-frontend/src/api/unifiedTask.ts`
- `packages/navigator-frontend/src/composables/useClaudeWorker.ts`
- `packages/navigator-frontend/src/views/ClaudeWorkerView.vue`
