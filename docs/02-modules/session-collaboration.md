# Session 协作底座

## 1. 功能定位

Session 是 Worker 任务和开放集成共享的协作底座，负责把“用户消息、Agent 回复、上下文绑定、委派跳转、实时推送”统一起来。历史 CrossProject 记录可能仍引用既有 Session 或 deep-link，但当前不会创建或推进阶段，也不会据此生成新会话。PC 顶部旧独立 `/chat` 已退出主导航，不再把 Session 描述为单一产品入口。

对应前端入口：

- Workers 内任务会话
- `/c/:id` 深链兼容入口

对应后端模块：

- `session-module`
- `agent-framework`
- 各 Provider Addon

## 2. 功能范围

### 2.1 Session 生命周期

- 创建 Session
- 查询 Session 列表
- 删除 Session
- 读取历史消息
- 读取最新消息

### 2.2 消息交互

- 发送用户消息
- 接收 Agent 流式回复
- 维护会话内消息顺序
- 显示会话状态与连接状态

### 2.3 会话路由与委派

- 按 session/目录/worker/provider/modelConfig 绑定解析目标 Agent
- 支持 Agent 发起委派
- 支持跳转到子会话
- 支持从子会话返回父会话

### 2.4 会话配置治理

- 绑定认证配置
- 归档 / 取消归档
- Hold / Unhold
- 批量绑定认证

### 2.5 扩展能力

- Guide Cards
- 分享 Key
- 公开问答
- Agent 发现与同步问答

## 3. 关键设计点

### 3.1 会话是平台级主对象

当前多个能力都围绕 Session 组织：

- 普通对话
- Worker 任务绑定
- 历史 CrossProject 记录中的既有 Session / deep-link
- 分享与公开问答
- 会话配置状态

### 3.2 会话与 Agent/Provider 是绑定关系

绑定后会固定：

- 逻辑 Agent
- providerType
- 可选模型配置

这使得任务恢复、重连、同步有统一约束。

### 3.3 SSE 是会话体验的核心支撑

统一 SSE 不只承载消息，还承载：

- 任务状态
- 通知
- 助手通知
- 订阅关系

## 4. 典型使用场景

### 场景 1：Worker 任务会话

1. 在 Workers 中选择 Worker、目录和 Provider
2. 创建任务并建立或绑定 Session
3. Provider/Worker 返回流式消息和任务状态
4. 继续回复、恢复、取消或转到其他受控执行上下文

### 场景 2：Agent 委派

1. 当前会话中发起委派
2. 前端自动刷新会话列表
3. 跳转到子会话
4. 子会话完成后回到原会话

### 场景 3：会话治理

1. 查看会话列表
2. 归档或挂起指定会话
3. 绑定认证配置

## 5. 与其他功能域的关系

- `任务治理中心` 的任务结果通常落在某个 Session 上
- 历史 CrossProject 记录可能保留既有 Session / deep-link；退役只读边界不会创建或推进阶段，也不会生成新会话
- `通知、基础观测与开放集成` 通过 SSE 和 Agent 发现接口为会话提供支撑
