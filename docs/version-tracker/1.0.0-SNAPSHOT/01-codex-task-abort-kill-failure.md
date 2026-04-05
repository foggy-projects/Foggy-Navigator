# 01 Codex Task Abort / Kill Failure

## Date

- 2026-04-02

## Type

- Bug
- Fixed
- Regression Guard

## Background

在 Codex Worker 任务执行过程中，前端执行“中止任务”后，任务状态可能先显示为已中止，但实际运行中的 `codex.exe` 没有被成功停止。

现场还出现过通过进程管理接口手动终止 PID 时，仅看到如下泛化错误：

```text
500 Internal Server Error from POST http://<worker-host>/api/v1/processes/<pid>/kill
```

这个问题的实质不是单一 kill 命令失败，而是平台统一取消链路和 Codex Worker 真实中止链路没有完全打通。

## Root Cause

### 1. 统一取消链路未真正中止远端 Codex 任务

旧行为下，统一 `cancelTask` 可能只更新本地任务状态，没有稳定触发 Codex Worker 远端中止。

结果是：

- 平台任务状态可显示为 `ABORTED`
- 但 Worker 里的真实 Codex 任务仍在执行
- 进一步表现为 `codex.exe` 残留

### 2. Worker 侧 Windows kill 策略不够强

旧实现对 Windows kill 的处理偏弱，失败后也没有充分暴露 `stdout`、`stderr`、退出码和重试信息。

### 3. Java 代理层丢失了 Worker 端错误细节

上游经常只能看到统一的 `500 Internal Server Error`，不利于区分：

- 进程已退出
- 非强制 kill 失败
- 需要杀进程树
- Worker 内部请求参数不正确

## Current Implementation Sync

当前代码已完成修复，文档需要以“已修复 + 回归保护”视角交付技术。

### 平台侧

- `session-module` 的统一取消仍从 `TaskDispatchFacade.cancelTask(...)` 进入
- Codex A2A 路径最终会进入 `CodexTaskService.abortTask(...)`
- `abortTask(...)` 现在统一负责：
  - 通知远端 Worker 中止任务
  - 清理本地流订阅
  - 将任务状态落为 `ABORTED`
  - 发布状态变更事件

### Worker 侧

- `tools/codex-agent-worker` 已增强 Windows kill 策略
- kill 失败时会保留多次尝试信息
- 若 kill 返回失败但进程已不存在，会回判为 `not_found` 而不是一律 500

### 错误透传

- Java 代理层已提取 Worker 返回的 JSON 错误体
- 上游不再只看到裸 `500 Internal Server Error from POST ...`

## Related Code Checklist

以下代码已与当前实现对齐，技术可以从这些位置复核：

### Session / Java Agent

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerInnerA2aAgent.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/spi/CodexWorkerFacadeImpl.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexTaskController.java`
- `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/controller/CodexWorkerController.java`

### Codex Worker

- `tools/codex-agent-worker/src/codex/processes.ts`
- `tools/codex-agent-worker/src/routes/processes.ts`
- `tools/codex-agent-worker/src/routes/tasks.ts`
- `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`

### Regression Tests

- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/service/CodexTaskServiceTest.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/spi/CodexWorkerFacadeImplTest.java`
- `addons/codex-worker-agent/src/test/java/com/foggy/navigator/codex/worker/adapter/CodexWorkerA2aAgentTest.java`
- `tools/codex-agent-worker/tests/processes.test.ts`

## Verification

### 已完成验证

已完成以下回归验证：

```bash
mvn -pl addons/codex-worker-agent -am "-Dtest=CodexTaskServiceTest,CodexWorkerFacadeImplTest,CodexWorkerA2aAgentTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
node --import tsx --test tests/processes.test.ts
```

验证目标包括：

- A2A 取消会进入 `abortTask(...)`
- `abortTask(...)` 会通知远端 Worker 并清理流
- Windows kill 失败时会保留详细 attempts 信息
- “进程其实已经退出”的场景不会误报为硬失败

### Frontend / Playwright Smoke

该缺陷的核心在后端与 Worker，但建议保留一个最小 UI 烟测：

1. 在 Codex 会话页启动一个长时间运行任务
2. 点击“中止任务”
3. 断言任务状态进入 `ABORTED`
4. 断言后续不再持续收到该任务的 streaming 更新
5. 若环境允许，配合 Worker 侧进程查询接口确认对应进程已退出

## Delivery Assessment

本项已经不是待分析缺陷，而是可直接交付技术参考的“缺陷闭环文档”。

适用用途：

- 回顾 root cause
- 复查修复点
- 作为后续中止链路重构的回归基线

不适用用途：

- 不应再按“Deferred 待修复缺陷”流转

## Status

本项已完成修复，并建议保留为后续 abort/cancel 统一重构的回归参考项。

## 验收签收

- 签收状态：✅ 已签收
- 签收日期：2026-04-05
- 签收方式：版本文档审计签收
- 签收依据：条目已明确“已完成修复”，并附回归验证记录。
- 关联台账：[12-acceptance-signoff.md](./12-acceptance-signoff.md)
