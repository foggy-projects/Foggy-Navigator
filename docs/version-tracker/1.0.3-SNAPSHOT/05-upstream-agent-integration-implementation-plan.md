# 上游 Agent 接入实现计划

## 文档作用

- doc_type: implementation-plan
- intended_for: root-controller | execution-agent | reviewer
- purpose: 将 1.0.3-SNAPSHOT 上游接入首版拆解成可执行的阶段计划、模块分工、交付物和验收路径

## Version

- `1.0.3-SNAPSHOT`

## 上游输入文档

本计划以下列文档作为上游输入：

1. [01-upstream-agent-integration-requirement.md](./01-upstream-agent-integration-requirement.md)
2. [02-upstream-agent-integration-current-state-analysis.md](./02-upstream-agent-integration-current-state-analysis.md)
3. [03-upstream-agent-integration-module-responsibility.md](./03-upstream-agent-integration-module-responsibility.md)
4. [04-upstream-agent-integration-code-inventory.md](./04-upstream-agent-integration-code-inventory.md)

本计划不重复现状调研，只负责收口首版执行路线。

## 1. 规划判断

### 1.1 首版目标判断

`1.0.3-SNAPSHOT` 不宜一次性追求“开放全部内部能力”，首版应聚焦以下最小业务闭环：

1. 上游可发起任务
2. 上游可读取会话列表
3. 上游可读取会话消息
4. 上游可轮询任务状态
5. 上游可轮询任务进行中的新增消息
6. 上游有一份可运行的接入 Demo

### 1.2 首版接口策略判断

建议首版策略：

1. 继续以现有 Open API 作为外部主入口
2. 在现有 Open API 基础上补齐会话域与增量消息能力
3. 不把内部 Session API 直接原样暴露给上游
4. SSE 继续作为内部和后续增强能力，首版上游正式合同以轮询为主

理由：

1. 当前上游已有最小 Open API 入口，延展成本最低
2. 直接开放内部 Session API 容易把内部语义原样泄漏给上游
3. 轮询更适合首版示例和业务集成落地

### 1.3 首版消息轮询策略判断

建议首版优先采用：

- `taskId + cursor` 或 `taskId + sinceMessageId`

不建议首版直接以 Worker 原始事件流作为外部合同。

原因：

1. 上游更关心“新增消息”而不是 Worker 内部事件语义
2. 直接暴露原始事件容易把内部事件类型固化成外部兼容负担
3. 以消息对象为核心更容易与会话消息列表能力对齐

## 2. 首版拟定 API 能力包

建议首版对上游正式开放以下能力包：

### 2.1 会话域

1. 会话列表
2. 会话消息列表

### 2.2 任务域

1. 发起任务
2. 任务状态查询
3. 任务取消
4. 任务进行中新增消息轮询

### 2.3 文档与 SDK

1. 对接文档
2. Java SDK 最小补齐
3. 接入 Demo

## 3. 阶段计划

### Phase 1: 对外合同收口

- depends_on: 无
- owner: `addons/claude-worker-agent` + `session-module` + docs

目标：

1. 决定首版对外 API 形态
2. 决定会话对象、消息对象、任务对象最小字段集
3. 决定进行中消息轮询协议

交付物：

1. 对外接口初稿
2. 字段合同说明
3. `taskId/contextId/sessionId/providerTaskId` 串联规则说明

验收标准：

1. 能清晰回答“上游调用哪些接口完成完整接入”
2. 能清晰回答“进行中多消息如何轮询”
3. 能清晰回答“消息与 taskId 如何关联”
4. 能清晰回答“为什么外部只保留 contextId，而不暴露 sessionId”

### Phase 2: 后端读模型与 Open API 扩展

- depends_on: Phase 1
- owner: `session-module` + `addons/claude-worker-agent`

目标：

1. 补齐会话列表对外读模型
2. 补齐会话消息对外读模型
3. 补齐任务进行中新增消息轮询

交付物：

1. Open API 新增或扩展接口
2. 对外 DTO
3. 任务增量消息查询读模型

验收标准：

1. 可通过正式接口获取会话列表
2. 可通过正式接口获取会话消息
3. 可通过正式接口按 `taskId` 获取新增消息
4. 多条消息在同一任务内可正确归并

### Phase 3: SDK 补齐

- depends_on: Phase 2
- owner: `navigator-open-sdk`

目标：

1. Java SDK 补齐会话和增量消息能力
2. 给 Demo 和外部接入提供稳定封装

交付物：

1. SDK 新增 API 封装
2. 最小示例代码

验收标准：

1. 上游可只依赖 SDK 完成核心接入流程
2. SDK 不需要调用方自行拼接底层 HTTP 细节

### Phase 4: Demo 落地

- depends_on: Phase 2
- owner: 示例工程或 `packages/foggy-chat` 相关承载层

目标：

1. 交付一个可运行的接入 Demo
2. 验证首版合同具备真实业务可用性

交付物：

1. Demo 工程或 Demo 页面
2. Demo 使用说明

验收标准：

1. Demo 可展示会话列表
2. Demo 可展示会话消息
3. Demo 可创建任务
4. Demo 可轮询状态
5. Demo 可轮询进行中新增消息

### Phase 5: 文档收口与验收

- depends_on: Phase 2 + Phase 3 + Phase 4
- owner: docs + reviewer

目标：

1. 形成正式对接文档
2. 形成版本内验收基线

交付物：

1. 上游对接手册
2. 版本进度与验收记录

验收标准：

1. 外部接入不依赖口头补充即可理解主流程
2. Demo 与 SDK、API 文档保持一致

## 4. 关键设计决策建议

### 4.1 会话接口出口

建议：

1. 会话域能力也纳入对外 Open API 体系
2. 不直接暴露内部 `SessionController` 原始返回结构
3. 对外会话主键统一使用 `contextId`
4. 内部 `sessionId` 仅作为实现细节和调试追踪字段

### 4.2 进行中消息协议

建议：

1. 以“消息对象列表”作为对上游合同
2. 对外显式返回 `taskId`
3. 会话相关消息对象显式返回 `contextId`
4. 支持 `cursor` 或 `sinceMessageId`

### 4.3 taskId 显式化

建议：

1. 首版先保证对外消息 DTO 显式返回 `taskId`
2. 对外任务 DTO 只返回 `taskId + contextId`
3. 是否升级到数据库结构化列，放在实现阶段评估

### 4.4 Demo 形态

建议优先级：

1. 优先选择最容易体现完整业务链路的形态
2. 如果 Java SDK 优先落地，则可优先配 Java 示例
3. 如果希望更直观展示消息轮询过程，则可增加最小前端页面

## 5. 测试与验证要求

### 5.1 后端

至少补齐：

1. Open API Controller 测试
2. 会话 / 消息读模型测试
3. 任务增量消息轮询测试
4. `taskId` 串联回归测试

### 5.2 SDK

至少补齐：

1. SDK 请求封装测试
2. 典型调用链示例验证

### 5.3 Demo

至少验证：

1. 创建任务成功
2. 轮询状态成功
3. 能拿到任务进行中的新增消息
4. 能回放会话与消息

## 6. 当前不纳入首版的内容

以下能力不建议压进 `1.0.3-SNAPSHOT` 首版：

1. 对外 WebSocket
2. 全量 Worker 内部事件开放
3. 文件浏览、目录执行等高权限外部能力
4. 消息存储模型的大规模重构

## 7. 计划产出后的下一动作

本计划确认后，建议按下面顺序继续推进：

1. 先补一份接口合同草案
2. 再决定 Demo 形态
3. 然后拆成后端、SDK、Demo 三条执行线
4. 执行时补 `06-upstream-agent-integration-progress.md` 记录实际进度
