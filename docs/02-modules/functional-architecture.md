# 功能架构说明

> 面向产品与研发协同的当前功能地图

## 1. 功能总图

```text
统一入口
  -> 登录 / 初始化配置
  -> 主导航

主业务能力
  -> Workers
  -> 会话
  -> 任务
  -> 跨项目

平台治理能力
  -> 设置
  -> 用户
  -> 监控

平台支撑能力
  -> SSE
  -> Agent 发现与分发
  -> Open API / SDK
```

## 2. 一级功能域拆解

### 2.1 Workers

目标：把远程 Worker、工作目录、文件、Git、终端和任务执行整合成一个操作台。

包含能力：

- Worker 注册、编辑、健康检查、进程管理
- 工作目录、项目目录、子目录、worktree 管理
- 目录级任务创建、回复、重连、回溯、同步
- 文件浏览、全文搜索、Git diff、Git history
- 终端、代码服务入口、附件与绘图辅助
- 目录授权 Agent 与 Agent Team 配置

### 2.2 会话

目标：把普通用户消息、Agent 回复、委派跳转统一进一个会话体验中。

包含能力：

- Session 创建、删除、切换
- 消息历史与实时流式回复
- Guide Cards
- Agent 委派与返回路由
- 会话绑定 Agent / provider / 模型配置
- 分享 Key 与公开提问

### 2.3 任务

目标：从平台视角统一查看 Agent Task 和 Worker Task 的运行情况。

包含能力：

- 任务列表、状态筛选、类型筛选、Agent 筛选
- 任务摘要查看
- 任务恢复、取消、重连、重同步
- 目录级或 Worker 级任务查询

### 2.4 跨项目

目标：用阶段化流程管理复杂任务，而不是只靠单轮对话。

包含能力：

- 创建多阶段任务
- 阶段绑定 Agent、目录、Prompt、worktree 分支
- 阶段 handoff 编辑与审核
- 任务启动、推进、取消
- 阶段会话回跳

### 2.5 设置

目标：管理平台运行所需的外部资源、模型、凭证和偏好。

包含能力：

- Git Provider 管理
- LLM 模型管理与连通性测试
- Agent 模型覆盖
- 用户记忆管理
- API 凭证管理
- Claude Worker 管理
- 任务助手配置

### 2.6 用户

目标：提供平台管理员视角的用户和凭证治理。

包含能力：

- 用户新增、编辑、删除
- 角色与状态管理
- API Key 创建、撤销、查看使用情况

### 2.7 监控与通知

目标：让平台具备最基础的运行可见性。

包含能力：

- 监控事件查询
- 错误统计
- 事件详情查看
- SSE 通知与助手通知

### 2.8 开放集成

目标：让平台能力可以被其他系统调用。

包含能力：

- Agent 发现与问答接口
- Claude Worker Open API
- Java SDK 封装

## 3. 功能边界判断

### 3.1 主业务能力

- Workers
- 会话
- 任务
- 跨项目

### 3.2 平台治理能力

- 设置
- 用户
- 监控

### 3.3 平台底座能力

- 统一任务分发
- A2A Agent 发现
- SSE
- Open API / SDK

## 4. 推荐阅读顺序

1. [系统架构概览](../00-system-overview.md)
2. [工作区与 Worker 中心](./worker-workspace-center.md)
3. [会话协作中心](./session-collaboration.md)
4. [任务治理中心](./task-governance.md)
5. [跨项目编排](./cross-project-orchestration.md)
6. [平台设置与资源治理](./platform-governance.md)
7. [用户与访问控制](./user-and-access-control.md)
8. [监控、通知与开放集成](./observability-notification-integration.md)
