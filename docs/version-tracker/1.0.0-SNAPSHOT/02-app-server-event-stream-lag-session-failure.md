# 02 App Server Event Stream Lag Causes Session Failure

## Date

- 2026-04-02

## Type

- Bug
- Deferred

## Background

在任务执行过程中，事件流中会连续出现如下错误事件：

```text
in-process app-server event stream lagged; dropped 5 events
```

现场现象不是只丢部分事件，而是会进一步触发会话失败。

用户手动执行重新同步后，连接又可以恢复，说明会话和任务本身未必已经彻底失效，更像是事件流传输或消费链路出现了短时失步。

## Observed Symptoms

- 短时间内连续出现多条 `in-process app-server event stream lagged; dropped N events`
- `N` 可能是 1、3、5、7、21、24 等不同值
- 出现多条此类事件后，会话进入失败态
- 手动重新同步后，可重新连上并继续查看状态

## Current Assessment

这个问题更像是事件流桥接层或前后端消费链路的稳定性问题，而不是单纯的模型执行失败。

从现象看，可能涉及：

- in-process app-server 到当前会话视图之间的事件转发积压
- SSE 消费端处理不及时，导致事件被丢弃
- 丢事件后的状态恢复机制不足，直接把会话判定为失败
- 实际任务仍在运行，但前端或会话层未能正确恢复流状态

## Impact

- 用户会看到会话失败，但底层任务可能仍可恢复
- 会增加误判和重复操作
- 用户需要手动重新同步才能恢复可见状态，体验较差

## Scope

可能涉及模块：

- `tools/codex-agent-worker`
- `addons/codex-worker-agent`
- `session-module`
- `packages/navigator-frontend`

## Proposed Follow-up

1. 梳理 `in-process app-server event stream lagged` 的触发源和事件丢弃条件
2. 区分“事件流丢包”与“会话失败”两个状态，避免直接把短时流异常升级为失败
3. 为前端增加自动重连或自动重新同步能力，而不是完全依赖手动操作
4. 为事件流链路补充更明确的监控与日志，至少能定位 dropped events 发生在哪一层

## Status

本项记录到 `1.0.0-SNAPSHOT` 版本目录，后续统一排期处理。
