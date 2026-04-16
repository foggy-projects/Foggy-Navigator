# 上游 Agent 接入最终目标与首批开发任务拆分

## 文档作用

- doc_type: implementation-breakdown
- intended_for: root-controller | execution-agent | reviewer
- purpose: 在 1.0.3-SNAPSHOT 已完成需求、现状、接口草案和流程图的基础上，正式拍板首版目标口径，并拆出第一批可执行开发任务

## Version

- `1.0.3-SNAPSHOT`

## Status

- Draft
- 2026-04-15

## 上游输入文档

1. [01-upstream-agent-integration-requirement.md](./01-upstream-agent-integration-requirement.md)
2. [05-upstream-agent-integration-implementation-plan.md](./05-upstream-agent-integration-implementation-plan.md)
3. [06-upstream-agent-integration-api-contract-draft.md](./06-upstream-agent-integration-api-contract-draft.md)
4. [07-upstream-agent-integration-sequence-and-flow.md](./07-upstream-agent-integration-sequence-and-flow.md)

## 1. 最终目标清单

本迭代首版目标正式收口为以下 6 项：

1. 上游可通过 Open API 发起 Agent 任务，并获得 `taskId` 与 `contextId`
2. 上游可通过 Open API 轮询任务状态
3. 上游可通过 Open API 轮询任务执行中的新增消息
4. 上游可通过 Open API 获取会话上下文列表
5. 上游可通过 Open API 获取指定 `contextId` 下的消息列表
6. 提供一个可运行的 Java SDK 示例 Demo，验证真实接入链路

## 2. 已拍板的首版决策

### 2.1 对外主键口径

正式口径：

1. 对外会话主键只保留 `contextId`
2. 对外任务主键只保留 `taskId`
3. 内部 `sessionId` 不进入上游正式合同

内部约束：

1. 一个 `contextId` 只绑定一个内部 `sessionId`
2. 绑定建立后不允许变化
3. 如出现同一 `contextId` 试图绑定新 `sessionId`，视为异常链路

### 2.2 首版接口路径

首版正式路径收口为：

1. `POST /api/v1/open/agents/{agentId}/ask`
2. `GET /api/v1/open/agents/{agentId}/tasks/{taskId}`
3. `POST /api/v1/open/agents/{agentId}/tasks/{taskId}/cancel`
4. `GET /api/v1/open/agents/{agentId}/tasks/{taskId}/messages`
5. `GET /api/v1/open/agents/{agentId}/sessions`
6. `GET /api/v1/open/agents/{agentId}/sessions/{contextId}/messages`

### 2.3 增量轮询协议

首版正式建议拍板为：

- `taskId + cursor`

不采用 `sinceMessageId` 作为首版主协议，原因：

1. `cursor` 更适合统一会话列表、会话消息、任务消息三类分页语义
2. `cursor` 更便于后续调整底层存储和查询策略
3. 能避免把消息主键稳定性直接固化为外部兼容负担

### 2.4 Demo 首版形态

首版建议拍板为：

- 优先交付 Java SDK 示例 Demo

原因：

1. 当前版本目标是证明“上游可真实接入”，Java 示例更贴近上游集成场景
2. Java SDK 示例更适合直接覆盖 `ask -> poll task -> poll messages -> replay context`
3. 前端页面 Demo 可以作为增强项，但不应阻塞首版交付

### 2.5 兼容策略

当前结论：

1. 由于当前版本尚未正式交付上游使用，首版可以直接切换到最终合同
2. 对外正式接口不保留 `sessionId` 兼容字段
3. 不为旧的双主键口径再设计过渡协议

### 2.6 历史数据策略

首版范围收口为：

1. 首版正式保证“改造后产生的 Open API 会话数据”可被上游稳定访问
2. 对于历史上未建立 `contextId -> sessionId` 映射的内部旧数据，不纳入首版正式承诺范围
3. 如后续需要让上游回放存量旧数据，再单独增加映射回填或迁移方案

### 2.7 安全细化策略

当前结论：

1. 本轮先不展开细化上游安全矩阵
2. 实现阶段仍需保持现有认证、租户隔离和接口权限基线不被破坏
3. 更细的上游安全策略、角色矩阵和对外交付约束，后续单独定

## 3. 首版完成定义

以下条件全部满足后，`1.0.3-SNAPSHOT` 上游接入能力可视为首版完成：

1. Open API 已按最终合同开放 6 个正式接口
2. 外部返回对象中不再要求上游理解 `sessionId`
3. 同一 `taskId` 下多条消息可稳定轮询得到
4. 同一 `contextId` 下历史消息可稳定回放
5. Java SDK 已补齐核心调用封装
6. Java Demo 已可真实跑通完整链路
7. 文档、SDK、Demo 三者口径一致
8. 首批测试已执行并通过

## 4. 第一批开发任务拆分

### 4.1 Task A: Open API 合同落地

- owner: `addons/claude-worker-agent`
- 目标: 将 `06` 草案中的 6 个首版接口落实到控制器和 DTO

子任务：

1. 调整 `ask/getTask/cancel` 返回结构，只保留对外 `contextId`
2. 新增 `GET /agents/{agentId}/tasks/{taskId}/messages`
3. 新增 `GET /agents/{agentId}/sessions`
4. 新增 `GET /agents/{agentId}/sessions/{contextId}/messages`
5. 统一错误码口径，支持 `CONTEXT_NOT_FOUND`

完成标准：

1. 接口路径、字段、错误语义与 `06` 一致
2. 对外 DTO 不暴露内部 `sessionId`
3. 不为旧外部合同保留过渡返回结构

### 4.2 Task B: contextId 读模型与映射约束落地

- owner: `session-module`
- 目标: 建立面向 Open API 的 `contextId` 查询能力，并把内部 `contextId -> sessionId` 一对一约束收口

子任务：

1. 梳理 `contextId` 到内部 `sessionId` 的读取入口
2. 提供基于 `contextId` 的消息查询读模型
3. 提供会话上下文列表聚合读模型
4. 明确映射异常时的失败语义

完成标准：

1. 上游所有会话域查询都能只基于 `contextId`
2. 内部不会出现静默改绑 `contextId -> sessionId`
3. 对无映射历史数据有明确处理策略，不做隐式兜底

### 4.3 Task C: taskId 增量消息轮询落地

- owner: `session-module` + `addons/claude-worker-agent`
- 目标: 提供稳定的 `taskId + cursor` 增量消息轮询能力

子任务：

1. 明确消息查询游标结构
2. 查询时只返回目标 `taskId` 的新增消息
3. 对返回对象显式补齐 `taskId/contextId`
4. 明确空结果、末尾游标和终态任务的响应语义

完成标准：

1. 同一游标重复调用结果稳定
2. 不会重复消费已返回消息
3. 多条消息在同一任务内可按时间升序返回
4. 空结果和终态任务响应语义与合同一致

### 4.4 Task D: Java SDK 补齐

- owner: `navigator-open-sdk`
- 目标: 为首版接口提供最小可用 SDK 封装

子任务：

1. 补齐会话上下文列表查询 API
2. 补齐会话消息查询 API
3. 补齐任务增量消息轮询 API
4. 提供基于 `cursor` 的简单轮询助手

完成标准：

1. Java 调用方不需要手写底层 HTTP 路径和分页细节
2. SDK 调用口径与 Open API 文档一致

### 4.5 Task E: Java 示例 Demo

- owner: 示例工程或 `navigator-open-sdk` 配套 example
- 目标: 交付一个最小可运行的 Java 示例程序

示例主流程：

1. 配置 API Key 和目标 `agentId`
2. 调用 `ask`
3. 轮询 `getTask`
4. 轮询 `getTaskMessages`
5. 任务完成后按 `contextId` 拉取完整消息
6. 在控制台输出完整回放结果

完成标准：

1. Demo 不依赖内部调试脚本
2. Demo 使用公开 SDK 或公开 Open API 完成全部流程

## 5. 首批测试拆分

### 5.1 后端测试

至少覆盖：

1. `ask` 返回 `taskId/contextId` 的合同测试
2. `GET /tasks/{taskId}/messages` 增量轮询测试
3. `GET /sessions` 列表测试
4. `GET /sessions/{contextId}/messages` 历史消息测试
5. `contextId -> sessionId` 一对一映射约束测试

### 5.2 SDK 测试

至少覆盖：

1. SDK 请求路径和 DTO 映射测试
2. `cursor` 连续轮询测试
3. Demo 主流程冒烟验证

## 6. 交付门槛

本批开发任务完成时，至少满足：

1. 相关测试已执行并通过
2. 版本文档已按实际实现结果回写
3. 如出现合同偏差，必须先更新文档再进入验收讨论

## 7. 执行顺序建议

建议按以下顺序推进：

1. 先完成 Task A 和 Task B
2. 再完成 Task C
3. 然后并行推进 Task D 和 Task E
4. 最后统一补测试和文档收口

原因：

1. `contextId` 读模型和 Open API 合同是后续 SDK、Demo 的基础
2. 增量轮询能力不稳定时，SDK 和 Demo 都会返工

## 8. 暂不纳入首批开发任务的内容

以下内容不建议放入第一批：

1. 前端页面 Demo
2. SSE 对外开放
3. Worker 内部调试字段大范围开放
4. 消息存储模型大规模重构
5. 上游安全矩阵细化设计

## 9. 下一步建议

本拆分文档确认后，下一步建议直接进入执行准备：

1. 生成 `09-upstream-agent-integration-execution-prompt.md`
2. 按 Task A/B/C/D/E 拆给执行 agent
3. 开始代码实现和测试回写
