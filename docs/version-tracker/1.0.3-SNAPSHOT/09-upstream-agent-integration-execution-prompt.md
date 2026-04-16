# 上游 Agent 接入执行提示

## 文档作用

- doc_type: execution-prompt
- intended_for: execution-agent
- purpose: 将 1.0.3-SNAPSHOT 上游接入首版的目标、边界、执行顺序和交付要求转换为可直接开工的执行提示

## Version

- `1.0.3-SNAPSHOT`

## Status

- Draft
- 2026-04-15

## 开工前必须先读

按以下顺序阅读：

1. [01-upstream-agent-integration-requirement.md](./01-upstream-agent-integration-requirement.md)
2. [06-upstream-agent-integration-api-contract-draft.md](./06-upstream-agent-integration-api-contract-draft.md)
3. [07-upstream-agent-integration-sequence-and-flow.md](./07-upstream-agent-integration-sequence-and-flow.md)
4. [08-upstream-agent-integration-final-target-and-task-breakdown.md](./08-upstream-agent-integration-final-target-and-task-breakdown.md)
5. [04-upstream-agent-integration-code-inventory.md](./04-upstream-agent-integration-code-inventory.md)

## 你需要遵守的核心口径

1. 对外只保留 `contextId` 和 `taskId`
2. 内部 `sessionId` 不进入上游正式合同
3. 首版正式接口路径以 `/api/v1/open/agents/{agentId}/...` 为准
4. 任务进行中的增量消息轮询首版以 `taskId + cursor` 为准
5. 首版 Java Demo 优先，不要求先做前端页面 Demo

## 你需要做的事

### Step 1: 落地 Open API 合同

目标：

- 让 Open API 对外返回结构和 `06` 文档一致

最少完成：

1. 调整 `ask/getTask/cancel` 对外结构
2. 新增任务增量消息接口
3. 新增会话上下文列表接口
4. 新增按 `contextId` 读取会话消息接口
5. 统一错误语义到最终合同

### Step 2: 落地 contextId 读模型

目标：

- 上游所有会话查询都只基于 `contextId`

最少完成：

1. 建立 `contextId -> sessionId` 的稳定读取路径
2. 对无映射历史数据给出明确失败或排除策略
3. 确保不会静默发生 `contextId -> sessionId` 改绑

### Step 3: 落地 taskId + cursor 增量轮询

目标：

- 上游能按 `taskId` 稳定拿到任务执行中的多条新增消息

最少完成：

1. 设计并实现 `cursor` 轮询协议
2. 只返回目标 `taskId` 的新增消息
3. 对外显式返回 `taskId/contextId`
4. 明确空结果、终态任务、末尾游标的语义

### Step 4: 落地 Java SDK

目标：

- 上游 Java 调用方可不手拼 HTTP 完成核心接入流程

最少完成：

1. 补齐会话上下文列表 SDK
2. 补齐会话消息 SDK
3. 补齐任务增量消息轮询 SDK
4. 提供基础轮询辅助方法

### Step 5: 落地 Java Demo

目标：

- 证明首版合同具备真实业务可用性

最少完成：

1. 示例程序可配置 API Key 和 `agentId`
2. 可调用 `ask`
3. 可轮询任务状态
4. 可轮询任务新增消息
5. 可按 `contextId` 拉取完整消息回放

## 你不需要做的事

1. 不要为旧外部合同保留 `sessionId` 兼容返回结构
2. 不要先做前端页面 Demo
3. 不要在首版中把 SSE 作为正式上游合同开放
4. 不要大规模重构消息存储模型
5. 不要在本轮扩展上游安全矩阵设计

## 代码边界提醒

重点代码触点优先从这些位置开始：

1. `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`
2. `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java`
3. `session-module/src/main/java/com/foggy/navigator/session/service/AgentContextStoreImpl.java`
4. `session-module/src/main/java/com/foggy/navigator/session/event/SessionEventListener.java`
5. `navigator-open-sdk/src/main/java/com/foggy/navigator/sdk/api/AgentApi.java`

如需新增 DTO、读模型或 SDK 封装，优先遵循当前模块已有结构，不要在规划阶段外自行扩展新的大层级。

## 实现时必须额外确认的点

如果在实现中发现以下情况，不要自行猜：

1. 历史数据中存在无 `contextId` 映射但又需要对外开放
2. 某个接口现有调用方已经依赖 `sessionId`
3. `cursor` 无法稳定映射到底层查询语义
4. `taskId` 与消息归属出现跨任务混淆

出现上述任一情况，应先回写版本文档或向上确认，而不是直接拍脑袋兼容。

## 测试要求

至少补齐并运行：

1. Open API 合同测试
2. `taskId + cursor` 增量轮询测试
3. `contextId` 历史消息查询测试
4. `contextId -> sessionId` 一对一映射约束测试
5. Java SDK 基础映射和轮询测试
6. Java Demo 主流程冒烟测试

## 完成后必须做的事

1. 回写版本文档，说明实际实现与规划是否一致
2. 记录测试执行结果
3. 如果合同有改动，先更新 `06` 和 `07` 再汇报
4. 明确还有哪些风险留到后续版本

## 完成定义

只有同时满足以下条件，才可视为本次执行完成：

1. 代码实现完成
2. 相关测试已执行并通过
3. 版本文档已回写
4. 文档、实现、SDK、Demo 口径一致
