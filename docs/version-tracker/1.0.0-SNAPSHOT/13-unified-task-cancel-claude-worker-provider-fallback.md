---
type: bug
bug_source: user-report
version: 1.0.0-SNAPSHOT
ticket: BUG-013
severity: critical
status: signed-off
reproduction_status: confirmed
test_strategy: integration-test
automation_decision: required
owner: session-module
---

# 13 Unified Task Cancel Claude Worker Provider Fallback

## Date

- 2026-04-06

## Background

用户在 2026-04-06 报告：调用统一取消接口

- `POST /api/v1/tasks/{taskId}/cancel`

中止运行中的 Claude 任务时，返回：

```json
{
  "code": 600,
  "exCode": "B600",
  "msg": "Cannot cancel task 20260406-951d: no A2A agent found for agentId=claude-worker"
}
```

用户说明该问题不是 Codex 特例，Claude 任务也会出现。

## Reproduction

### Environment

- API endpoint: `http://dev-kvm-jdk17.foggysource.com/api/v1/tasks/{taskId}`
- entry: `POST /api/v1/tasks/{taskId}/cancel`
- auth: UI Bearer Token

### Steps

1. 创建一个通过统一任务入口运行的 Claude Worker 任务。
2. 确认任务状态为 `RUNNING`。
3. 调用统一取消接口，且请求体未显式传真实逻辑 Agent，或任务投影中的 `agentId` 已被写成 provider 常量 `claude-worker`。
4. 观察接口返回。

### Observed Result

- 接口在进入 Provider 中止逻辑前失败。
- 返回 `no A2A agent found for agentId=claude-worker`。

## Expected vs Actual

### Expected

- 对运行中的 Claude 任务，应成功路由到 `claude-worker` 对应的 Provider 取消链路。
- 对终态任务，应幂等返回，不报错。

### Actual

- `TaskController` 虽然已做 terminal-state guard，但 `TaskDispatchFacade.cancelTask(...)` 对非终态任务仍强制把 `agentId` 当作真实 A2A agentId 解析。
- 当 `agentId=claude-worker` 时，`ClaudeWorkerAgentProvider` 无法解析到具体管理 Agent，导致请求在 Facade 层提前失败。

## Impact Scope

直接影响：

- 运行中的 Claude 任务可能无法通过统一 `/api/v1/tasks/{taskId}/cancel` 取消。
- 用户收到误导性业务错误，误以为 Claude Worker 本身不可取消。

潜在影响：

- 任何 direct provider route 或逻辑 Agent 缺失的任务，只要任务投影把 `agentId` 保存成 provider 常量，都可能触发同类问题。
- `codex-worker` 存在相同风险模式。

## Test Strategy

- 主策略：`integration-test`
- 本次先补 `session-module` 单元回归，锁定统一取消入口的路由逻辑。
- 版本回归时再通过真实接口确认 Claude 运行态任务可被正常取消。

### Automation Decision

- `required`

原因：

- 统一取消属于核心任务治理链路。
- 该问题已在不同 provider 上重复出现过同类模式。
- 可通过稳定单测覆盖。

## Code Inventory

- `session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/adapter/ClaudeWorkerAgentProvider.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/controller/TaskControllerTest.java`

## Root Cause

根因是统一取消链路对 `logicalAgentId` 和 `providerType` 的语义处理不完整：

1. `TaskController.cancelTask(...)` 先查任务，并能对终态做 no-op。
2. 但进入 `TaskDispatchFacade.cancelTask(...)` 后，非终态任务仍优先尝试将 `agentId` 解析为真实 A2A agent。
3. 当任务投影中的 `agentId` 为 `claude-worker` 时，这其实是 provider 常量，不是可解析的管理 Agent 实体 ID。
4. `ClaudeWorkerAgentProvider.resolveAgent(...)` 只解析真实 `CodingAgentEntity`，因此返回空。
5. 请求未落到 `ClaudeTaskService.cancelTask(...)` / `abortTask(...)`，而是在 Facade 层直接报错。

## Fix Checklist

- [x] 明确问题来源为 `user-report`，并确认可稳定复现。
- [x] 确认 `TaskController` 已有 terminal-state guard，问题在 Facade 非终态路由。
- [x] 修改 `TaskDispatchFacade.cancelTask(...)`：
  - 先读取任务投影
  - 对终态继续 no-op
  - 当 `agentId` 为空，或等于 `providerType` 常量时，直接走 `TaskQueryProvider.cancelTask(...)`
  - 当 A2A 解析失败但任务已有明确 `providerType` 时，回退到 provider cancel
- [x] 补充回归测试：
  - `agentId=claude-worker` 时应走 provider route
  - 逻辑 Agent 缺失但 `providerType=claude-worker` 时应走 provider route
- [x] 使用真实环境再次回归统一取消接口

## Verification

### Automated

已执行：

```bash
mvn -pl session-module -am "-Dtest=TaskDispatchFacadeTest,TaskControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：

- `TaskControllerTest` 通过
- `TaskDispatchFacadeTest` 通过
- 总计 `42` 个测试通过，`0` failures，`0` errors

### Manual

已验证（2026-04-07）：

1. 在开发环境启动一个 Claude 运行态任务。
2. 调用 `POST /api/v1/tasks/{taskId}/cancel`。
3. 确认接口返回成功或 no-op，而不是 `no A2A agent found`。
4. 确认任务最终状态进入 `ABORTED` 或保持终态幂等。

手工验收结论：

- 真实环境下统一取消接口已能正确路由到 Claude Worker Provider 取消链路
- 未再复现 `no A2A agent found for agentId=claude-worker`
- 本项可按真实验收通过处理

## 验收签收

- 签收状态：✅ 已签收
- 签收日期：2026-04-07
- 签收方式：版本文档审计签收
- 签收依据：自动化回归已通过，且用户已完成真实环境取消验收，确认运行态 Claude 任务可正常取消。
- 关联台账：[12-acceptance-signoff.md](./12-acceptance-signoff.md)

## References

- `docs/version-tracker/1.0.1-SNAPSHOT/12-codex-completed-task-cancel-a2a-resolution-failure.md`
- `docs/version-tracker/1.0.0-SNAPSHOT/03-abort-task-entry-flow-analysis.md`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/TaskDispatchFacadeTest.java`
