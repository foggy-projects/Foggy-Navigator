# 上游 Agent 接入代码触点清单

## 文档作用

- doc_type: code-inventory
- intended_for: execution-agent | reviewer
- purpose: 为 1.0.3-SNAPSHOT 上游接入首版提供代码触点清单，帮助后续开发快速定位责任模块与建议变更范围

## Version

- `1.0.3-SNAPSHOT`

## Status

- Draft
- 2026-04-15

## 1. 使用说明

本清单不是最终改动列表，而是当前已识别的主要触点。

`expected change` 说明：

- `update`: 高概率需要修改
- `create`: 高概率需要新增
- `read-only-analysis`: 当前以调研为主
- `do-not-touch`: 当前版本不建议直接修改

## 2. Code Inventory

| repo/module | path | role | expected change | notes |
| --- | --- | --- | --- | --- |
| workspace/docs | `docs/version-tracker/1.0.3-SNAPSHOT/01-upstream-agent-integration-requirement.md` | 版本主需求 | update | 后续规划确认后可回写状态和范围 |
| workspace/docs | `docs/version-tracker/1.0.3-SNAPSHOT/02-upstream-agent-integration-current-state-analysis.md` | 现状基线 | update | 实现过程中可补调研结论 |
| workspace/docs | `docs/version-tracker/1.0.3-SNAPSHOT/03-upstream-agent-integration-module-responsibility.md` | 模块职责 | update | 随规划收敛可能更新 |
| workspace/docs | `docs/version-tracker/1.0.3-SNAPSHOT/05-upstream-agent-integration-implementation-plan.md` | 实现计划 | create | 本轮规划文档 |
| workspace/docs | `docs/version-tracker/1.0.3-SNAPSHOT/06-upstream-agent-integration-progress.md` | 进度模板 | create | 后续执行时建议补齐 |
| `addons/claude-worker-agent` | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java` | 第三方 Open API 入口 | update | 高概率需要扩展会话 / 增量消息合同 |
| `addons/claude-worker-agent` | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/dto/OpenApiTaskDTO.java` | 任务轮询 DTO | update | 高概率需要扩展进行中消息能力或关联字段 |
| `addons/claude-worker-agent` | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/model/entity/ClaudeTaskEntity.java` | Claude Worker 任务持久化 | read-only-analysis | 先确认是否需要补充查询字段或索引 |
| `addons/claude-worker-agent` | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/WorkerStreamRelay.java` | Worker 流转消息与 taskId 串联 | update | 若进行中消息合同基于 task event/cursor，可能需要补投影或附加字段 |
| `session-module` | `session-module/src/main/java/com/foggy/navigator/session/controller/SessionController.java` | 会话 / 消息内部入口 | update | 可能抽取或扩展为对上游正式合同 |
| `session-module` | `session-module/src/main/java/com/foggy/navigator/session/controller/TaskController.java` | 统一任务入口 | read-only-analysis | 先看是否复用读模型，不直接作为对外入口 |
| `session-module` | `session-module/src/main/java/com/foggy/navigator/session/controller/SharedTaskController.java` | 共享任务/会话查询参考 | read-only-analysis | 可参考共享访问边界设计，不直接复用为上游正式接口 |
| `session-module` | `session-module/src/main/java/com/foggy/navigator/session/controller/UnifiedSseController.java` | 内部 SSE 能力 | read-only-analysis | 本版本主要用于边界对照，不默认改造 |
| `session-module` | `session-module/src/main/java/com/foggy/navigator/session/event/SessionEventListener.java` | 消息持久化入口 | update | 如果要统一对外消息模型，可能需要补 taskId/type 显式投影 |
| `navigator-common` | `navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionTaskEntity.java` | 统一任务表 | read-only-analysis | 已有 `providerTaskId/lastAckedSeq`，可作为轮询设计依据 |
| `navigator-common` | `navigator-common/src/main/java/com/foggy/navigator/common/entity/SessionMessageEntity.java` | 会话消息表 | read-only-analysis | 评估是否继续使用 metadata 承载 taskId |
| `navigator-open-sdk` | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/NavigatorClient.java` | SDK 根入口 | update | 新增 API 聚合时可能需要调整 |
| `navigator-open-sdk` | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/AgentApi.java` | 现有 Agent SDK | update | 高概率需要补会话和增量消息封装 |
| `navigator-open-sdk` | `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/SessionApi.java` | 对外会话 SDK | create | 如采用独立会话合同，建议新增 |
| `packages/foggy-chat` | `packages/foggy-chat/README.md` | 现有前端接入说明 | read-only-analysis | 可作为 Demo 的能力参考 |
| `packages/foggy-chat` | `packages/foggy-chat/src/` | 前端 SSE/chat 能力 | read-only-analysis | 若 Demo 选前端方式，可评估复用 |
| `tools/claude-agent-worker` | `tools/claude-agent-worker/SETUP.md` | Worker 内部接口说明 | read-only-analysis | 用于校对 taskId / subscribe / session 能力边界 |
| docs | `docs/02-modules/observability-notification-integration.md` | 现有 Open API/SDK 文档 | update | 后续若对外合同调整，需补模块文档同步 |

## 3. 当前默认不建议改动的区域

以下区域当前版本默认不建议直接扩改：

1. Worker Python 内部执行模型本体
2. 统一 SSE 通道协议本体
3. 与上游目标无关的内部 UI 会话展示逻辑

## 4. 备注

如果后续实现规划决定：

- 继续以 `OpenApiController` 为唯一对外入口

则重点改动会集中在：

1. `addons/claude-worker-agent`
2. `session-module`
3. `navigator-open-sdk`

如果后续实现规划决定：

- 为会话域新增独立对外 API

则还需要在 `session-module` 中新增专门的对外 Controller / DTO / Service 输出层。
