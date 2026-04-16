# 上游 Agent 接入现状调研

## 文档作用

- doc_type: analysis
- intended_for: execution-agent | reviewer | product-owner
- purpose: 记录 1.0.3-SNAPSHOT 立项时平台现有能力、接口边界、taskId 串联现状和关键缺口，作为后续规划输入

## Version

- `1.0.3-SNAPSHOT`

## Date

- 2026-04-15

## Status

- Investigated

## 1. 结论摘要

当前平台已经具备“上游可发起任务并轮询最终结果”的基础，但还没有形成“上游可完整浏览会话、读取会话消息、按任务轮询增量消息”的正式开放合同。

可以明确确认的结论如下：

1. 平台内部已经有会话列表、会话消息、分页读取能力
2. 平台对外 Open API 已支持 `ask + getTask + cancel + listActiveTasks`
3. 当前对外轮询仍主要返回任务状态与终态结果，不是增量消息流
4. Worker 在同一任务中持续产生的多条消息，平台侧已经通过统一 `taskId` 串起来
5. Worker 内部任务 ID 与平台任务 ID 之间已有映射关系
6. 当前消息表中 `taskId` 主要存在于消息 metadata，而不是独立结构化列

## 2. 已确认的现有能力

### 2.1 对外 Open API

当前第三方主入口在：

- [OpenApiController.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java)

当前已具备：

- 发起任务
- 查询单任务状态
- 取消任务
- 查询活跃任务

现状判断：

- 已具备“第三方可调用 Agent”的最小异步调用能力
- 尚未具备“第三方正式读取会话列表 / 会话消息 / 任务内增量消息”的完整合同

### 2.2 平台内部会话能力

当前内部会话控制器在：

- [SessionController.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java)

当前已具备：

- `GET /api/v1/sessions`
- `GET /api/v1/sessions/{id}`
- `GET /api/v1/sessions/{id}/messages`
- `GET /api/v1/sessions/{id}/messages/latest`
- `POST /api/v1/sessions/{id}/messages`

现状判断：

- 平台内部已经有成熟的会话和消息读取接口
- 这些能力尚未按“对上游开放合同”的口径整理
- 当前会话列表还会主动过滤 `claude-worker` 会话，因此不能直接视为上游接入现成合同

### 2.3 平台内部实时推送能力

内部统一 SSE 入口在：

- [UnifiedSseController.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/controller/UnifiedSseController.java)

现状判断：

- 平台内部具备实时推送通道
- 但当前上游正式对接主路径还不是 SSE 合同，而是 Open API 轮询

### 2.4 SDK 与示例基础

已有对外 Java SDK：

- [NavigatorClient.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/NavigatorClient.java)
- [AgentApi.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/AgentApi.java)

已有前端侧 SSE / Chat 封装：

- [README.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-chat/README.md)

现状判断：

- SDK 和示例基础已有一部分
- 但仍偏向现有能力封装，还不是围绕“上游业务接入首版”设计

## 3. 任务轮询现状

### 3.1 当前对外轮询能拿到什么

当前任务轮询 DTO 在：

- [OpenApiTaskDTO.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/dto/OpenApiTaskDTO.java)

当前主要字段包括：

- `taskId`
- `agentId`
- `status`
- `contextId`
- `result`
- `errorMessage`
- `durationMs`
- `costUsd`
- `createdAt`

结论：

- 当前轮询主要是任务状态视图
- 终态可取到 `result`
- 不能表达任务过程中的多条新增消息

### 3.2 当前内部统一任务能力

统一任务入口在：

- [TaskController.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java)

统一任务表在：

- [SessionTaskEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionTaskEntity.java)

已确认：

- 平台统一任务层已经保存 `taskId/sessionId/providerTaskId/status/resultText/lastAckedSeq`
- 内部已经有面向任务的统一抽象，为后续做“按 taskId 拉消息”提供了落点

## 4. 多消息与 taskId 串联现状

### 4.1 Worker 同一任务中的多条消息是否会带同一个 taskId

会，当前已经串起来。

关键逻辑在：

- [WorkerStreamRelay.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelay.java)

已确认：

- Worker 事件转平台消息时，统一通过 `AgentMessageBuilder.create(...).taskId(taskId)` 打入平台侧 `taskId`
- 同一任务内的 `assistant_text`、`tool_use`、`tool_result`、`result`、`error` 等事件都会沿用同一个平台 `taskId`

结论：

- 平台侧已经具备“同一任务多条消息可归并”的实现基础

### 4.2 Worker 内部 taskId 是否也被记录

会，当前已有映射关系。

关键位置：

- [ClaudeTaskEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/entity/ClaudeTaskEntity.java)
- [SessionTaskEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionTaskEntity.java)
- [WorkerStreamRelay.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelay.java)

已确认：

- 平台任务实体有平台 `taskId`
- Claude Worker 任务实体有 `workerTaskId`
- 统一任务表有 `providerTaskId`
- 运行时还维护了平台 `taskId -> workerTaskId` 的映射

结论：

- 平台侧已经具备调试和追踪所需的 task 映射基础
- 但对上游是否显式暴露、以什么字段暴露，仍需收口

## 5. 消息落库现状

消息持久化监听器在：

- [SessionEventListener.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/session-module/src/main/java/com/foggy/navigator/session/event/SessionEventListener.java)

消息实体在：

- [SessionMessageEntity.java](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionMessageEntity.java)

已确认：

- 非 `TEXT_CHUNK`、非 `HEARTBEAT` 的消息会被持久化
- 持久化时会把 payload 拷入 metadata
- 如果 payload 内有 `taskId`，消息 metadata 中就会保留 `taskId`

结论：

- 当前消息与 `taskId` 的关联是存在的
- 但当前是 JSON metadata 级别承载，不是结构化列

这带来的影响：

1. 能支持现有功能和现状调试
2. 但如果要做正式高频轮询接口，后续需要评估查询效率和协议稳定性

## 6. 当前缺口

### 6.1 上游会话列表合同缺口

当前内部有会话列表，但不是面向上游正式开放的合同，且现有实现会过滤 `claude-worker` 会话。

### 6.2 上游会话消息合同缺口

当前内部有消息读取，但没有明确整理为第三方正式使用的 API 契约、字段说明和分页约定。

### 6.3 上游进行中消息轮询缺口

这是当前最核心的缺口。

当前对外轮询接口：

- 适合拿任务状态
- 不适合拿任务执行过程中的新增多消息

### 6.4 文档边界缺口

当前存在以下能力，但边界混合：

- Open API
- 内部 Session API
- 内部统一任务 API
- SSE
- Worker 内部 API

这些能力缺少一份统一面向上游的边界说明。

### 6.5 SDK 缺口

当前 Java SDK 更偏 `ask/getTask/pollUntilDone`，还没有围绕“会话列表 + 消息列表 + 增量消息轮询”形成完整封装。

## 7. 对 1.0.3-SNAPSHOT 的直接启发

结合现状，后续规划至少要回答以下问题：

1. 上游正式合同是基于现有 Open API 扩展，还是单独增加上游会话 API
2. 进行中消息轮询是直接轮询消息对象，还是先轮询任务事件投影
3. `taskId` 在消息层是否继续仅保留在 metadata，还是升级为结构化字段
4. Java SDK 是否同步补充会话与消息能力
5. Demo 选择前端示例、后端示例还是组合示例

## 8. 建议作为后续规划输入的最小目标

后续实现规划建议至少覆盖以下最小交付：

1. 会话列表 API
2. 会话消息列表 API
3. 任务增量消息轮询 API
4. `taskId/sessionId/contextId/providerTaskId` 串联规则文档
5. Java SDK 最小补齐
6. 可运行接入 Demo

## 9. Related Docs

- [01-upstream-agent-integration-requirement.md](./01-upstream-agent-integration-requirement.md)
- [observability-notification-integration.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/02-modules/observability-notification-integration.md)
