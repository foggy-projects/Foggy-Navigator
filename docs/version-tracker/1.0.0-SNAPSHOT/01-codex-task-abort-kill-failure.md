# 01 Codex Task Abort / Kill Failure

## Date

- 2026-04-02

## Type

- Bug
- Deferred

## Background

在 Codex Worker 任务执行过程中，前端执行“中止任务”后，任务状态可能变为已中止，但实际运行中的 Codex CLI 进程没有被成功停止。

随后通过进程管理接口手动终止 PID 时，出现如下异常：

```text
500 Internal Server Error from POST http://192.168.31.119:3052/api/v1/processes/63680/kill
```

现场排查显示，出错的 Codex 运行在 `tools/codex-agent-worker` 下。

## Current Findings

### 1. 统一取消任务链路没有真正停止远端 Codex Worker 任务

当前统一 `cancelTask` 路径只将本地任务状态置为 `ABORTED`，没有复用 Codex 专用 abort 链路去通知 Worker 终止运行中的任务。

结果是：

- 平台任务状态可显示为“已中止”
- 但 `codex.exe` 仍可能继续运行

### 2. Worker 侧 Windows kill 实现较弱

`tools/codex-agent-worker` 的 Windows 进程终止当前仅执行：

```text
taskkill /PID <pid>
```

存在以下问题：

- 默认不是强制终止
- 不包含 `/T`，不会处理进程树
- `taskkill` 非 0 返回会直接转成 HTTP 500

### 3. 现有日志无法还原真实 kill 失败原因

已检查 `tools/codex-agent-worker/logs` 下日志：

- `worker.log` 只有启动信息
- `worker-error.log` 没有 kill 失败细节
- `logs/events/*.jsonl` 是任务事件流，不记录 `POST /api/v1/processes/:pid/kill` 的完整请求/响应和 `taskkill` stderr

因此当前只能确认 kill 调用失败，不能从现有日志中恢复真实系统错误原因。

## Impact

- 用户在 UI 上点击中止后，可能误以为任务已完全停止
- 实际 CLI 进程继续运行，会持续占用资源
- 后续通过手动 kill 处理时，错误信息不透明，排障成本高

## Scope

涉及模块：

- `packages/navigator-frontend`
- `session-module`
- `addons/codex-worker-agent`
- `tools/codex-agent-worker`

## Proposed Follow-up

1. 统一 `cancelTask` 路径复用 Codex 专用 abort 逻辑，确保停止请求真正传递到 Worker
2. Windows kill 失败后自动重试强制模式，至少覆盖 `/F`，必要时包含 `/T`
3. `tools/codex-agent-worker` 在 kill 失败时记录 `stdout`、`stderr`、退出码和 PID
4. Java 代理层保留并透传 Worker 返回的错误体，避免上层只看到泛化的 500

## Status

本项先归档到 `1.0.0-SNAPSHOT` 版本下，暂不立即处理，后续安排时间再修复。
