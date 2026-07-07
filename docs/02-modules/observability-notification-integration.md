# 通知与开放集成（监控暂停）

## 1. 功能定位

该功能域当前负责消息通知链路以及对外开放接口；RabbitMQ 监控事件链路已暂停，不纳入 `launcher` 或默认部署。

对应前端入口：

- `监控`（当前暂停）

对应后端模块：

- `session-module`
- `addons/task-assistant`
- `addons/claude-worker-agent`
- `navigator-open-sdk`

## 2. 功能范围

### 2.1 监控事件（当前暂停）

- `monitoring-module` 源码保留
- RabbitMQ 消费、事件持久化、监控事件 API 当前不纳入主线运行

### 2.2 SSE 通知

- 统一消息流
- 任务状态更新
- 用户通知
- 助手通知
- 订阅管理

### 2.3 任务助手通知

- 监听任务事件
- 聚合事件
- 调用助手生成通知
- 支持测试通知和摘要类能力

### 2.4 Open API

当前 Claude Worker addon 已提供面向外部系统的接口能力，包括：

- Worker 注册与查询
- 目录初始化与管理
- 员工 Provision
- Agent 查询与问答
- 任务查询与取消
- Worker 进程治理

### 2.5 SDK

`navigator-open-sdk` 已提供 Java SDK，包括：

- `AgentApi`
- `DirectoryApi`
- `EmployeeApi`
- `WorkerApi`

## 3. 设计特点

### 3.1 监控能力当前暂停

后续如果恢复监控，需要重新接入 `monitoring-module`、RabbitMQ 和前端监控入口。

### 3.2 SSE 是平台内部实时总线

前端多个页面都依赖统一 SSE：

- 会话
- 通知
- 任务状态
- 助手通知

### 3.3 Open API 是平台外扩的重要锚点

从当前接口面看，平台已经不只是内部 UI，而是开始具备被外部系统调用的能力。

## 4. 典型使用场景

### 场景 1：排查异常

管理员通过监控页查看错误事件和堆栈信息。

### 场景 2：接收任务通知

用户在前端接收统一 SSE 通知，获知任务开始、完成、失败或助手建议。

### 场景 3：外部系统接入

外部系统通过 Open API 或 Java SDK 管理 Worker、目录和 Agent 调用。
