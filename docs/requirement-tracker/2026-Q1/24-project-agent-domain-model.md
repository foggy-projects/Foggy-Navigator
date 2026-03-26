# 编程 Agent 领域模型对齐文档

## 目标

本文用于对齐 Foggy Navigator 中“编程 Agent”的领域语义，明确 `Agent / Task / Session / Worker / Directory / Worktree` 的边界。

这份文档不讨论具体代码实现细节，而是先确定系统应该怎样理解“编程 Agent”，为后续统一任务分发、统一事件流、统一前端协议提供基础。

---

## 一句话结论

在编程任务子域中：

- `Agent` 以项目为粒度定义
- `Task` 是 Agent 当前执行的具体工作
- `Worker` 是执行基础设施
- `Directory` 是项目代码上下文
- `Worktree` 是任务期临时工作区，用完即可丢弃
- `Session` 是用户与 Agent 围绕某项工作的会话状态

对调用者来说，只需要知道“把任务发给哪个 Agent”，不需要知道这个 Agent 背后跑在什么 Worker、什么目录、什么 worktree 上。

---

## 背景

当前系统中已经存在：

- A2A 抽象：`A2aAgent` / `A2aAgentProvider`
- 编程 Agent 实体：`CodingAgentEntity`
- 目录绑定关系：`AgentDirectoryBindingEntity`
- 统一任务分发入口：`TaskDispatchFacade`

相关代码位置：

- `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/A2aAgent.java`
- `navigator-spi/src/main/java/com/foggy/navigator/spi/agent/A2aAgentProvider.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/entity/CodingAgentEntity.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/entity/AgentDirectoryBindingEntity.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`

但此前对“编程 Agent 的主身份到底是什么”存在语义摇摆：

- 一种理解是按 `worker` 来看 Agent
- 一种理解是按 `directory` 来看 Agent
- 当前代码则更接近“Agent 独立存在，但又同时和 worker / directory 产生耦合”

如果这个概念不先统一，后续任务路由、前端协议、OpenAPI、A2A 封装都会持续不稳定。

---

## 核心定义

### 1. Agent

在编程任务子域中，`Agent` 是一个面向项目的稳定能力入口。

它表达的是：

- “负责 A 项目的 Agent”
- “负责维护 payment-service 项目的 Agent”
- “负责修复 A 项目某个 Bug 的 Agent 正在执行一个任务”

它不表达的是：

- 某台机器
- 某个临时 worktree
- 某个具体任务本身

### 定义原则

1. `Agent` 是对外可见的逻辑身份。
2. `Agent` 是前端、OpenAPI、A2A 调用方理解任务入口的唯一对象。
3. `Agent` 应该长期稳定，不能随一次具体任务执行而变化。
4. `Agent` 可以有角色，例如：
   - A 项目开发 Agent
   - A 项目 Bug 修复 Agent
   - A 项目 Review Agent
5. 未来可以继续扩展更高层级 Agent，例如：
   - 负责所有项目进度管理的 Agent
   - 负责跨项目发布协调的 Agent

也就是说：

- `Agent` 是“谁负责”
- `Task` 是“它现在在做什么”

---

### 2. Task

`Task` 是某个 Agent 在某一时刻执行的具体工作单元。

例如：

- 实现支付回调接口
- 修复登录模块空指针异常
- 在项目 A 上做一次代码审查
- 针对指定 issue 生成修复提交

### 任务与 Agent 的关系

- 一个 Agent 可以有很多 Task
- 一个 Task 只属于一个 Agent
- Task 生命周期短于 Agent 生命周期

因此自然语义应该是：

- “A 项目的 Agent 正在执行一个修复 XX Bug 的任务”

而不是：

- “这个修复 XX Bug 的任务本身就是一个 Agent”

---

### 3. Worker

`Worker` 是执行基础设施，不是对外主身份。

它可以表示：

- Claude Code 所在机器
- Codex Worker 所在执行节点
- 未来的远端开发容器、沙箱、执行集群

### Worker 的职责

- 提供运行时环境
- 挂载项目目录
- 执行 CLI / SDK / 工具调用
- 暴露 SSE / 状态 / 中止等能力

### Worker 的语义边界

对调用者而言，`Worker` 应该被隐藏在 Agent 背后。

前端和外部系统不应该因为一个任务在 `worker-1` 还是 `worker-2` 上执行而改变它对 Agent 的理解。

所以：

- `workerId` 是基础设施细节
- 不应成为前端任务创建的主路由键

---

### 4. Directory

`Directory` 是项目的代码上下文。

在编程任务子域里，它通常对应：

- 项目根目录
- 某个仓库 checkout
- 某个项目在某个环境中的工作目录

### Directory 的职责

- 提供代码上下文
- 提供 `cwd`
- 提供 git 信息
- 提供 agent teams / 项目配置 / 权限配置等与项目相关的上下文

### Directory 与 Agent 的关系

在“项目级 Agent”模型里，Directory 更适合作为 Agent 的项目锚点，而不是独立对外身份。

也就是说：

- 项目级 Agent 通常有一个主目录
- 该主目录定义了这个 Agent 所属的项目上下文
- 额外目录、子目录、扩展目录可以作为内部授权范围

所以从产品语义上看，最自然的理解是：

- “A 项目 Agent”锚定到 A 项目的主目录

而不是：

- “用户直接面对目录 ID 在调用”

---

### 5. Worktree

`Worktree` 是任务期临时工作区。

它的特征是：

- 用于隔离某个具体任务的代码修改
- 生命周期通常跟任务强相关
- 可以创建，也可以执行完后删除

### Worktree 的定位

`Worktree` 不应该成为对外主身份，也不应该成为稳定 Agent。

原因很简单：

- worktree 天然是临时的
- 一个项目在一段时间内可能有多个 worktree
- 如果把 worktree 直接升格成 Agent，Agent 数量会失控，前端心智也会恶化

所以：

- `Agent` 锚定项目
- `Worktree` 只是该 Agent 在执行某个 Task 时选择的具体工作区

这也是当前对 worktree 最合理的定位：

> worktree 用完就丢弃，它属于任务执行上下文，不属于对外稳定身份。

---

### 6. Session

`Session` 是用户与 Agent 围绕某项工作的对话状态。

它记录的是：

- 用户上下文
- 消息历史
- 当前任务状态
- 是否等待权限回复
- 是否等待下一步输入

### Session 的绑定对象

`Session` 应该绑定 `Agent`，而不是绑定 `Worker` 或临时 `Worktree`。

原因：

- 用户在和“项目 Agent”持续协作
- 不是在和某台机器持续协作
- 也不是在和某个临时目录持续协作

因此，统一分发层后续应坚持：

- `sessionId` 绑定 `agentId`
- 目录、worktree、worker 由后台在该 Agent 内部恢复或选择

---

## 关系模型

```text
Project Agent
    ├── owns many Tasks
    ├── is anchored to one primary Project Directory
    ├── may authorize additional Directories
    ├── executes on one or more Workers
    └── may create ephemeral Worktrees per Task

Task
    ├── belongs to one Agent
    ├── runs in one execution context at a time
    └── may use one temporary Worktree

Session
    ├── binds to one Agent
    └── tracks conversation and task state

Worker
    └── provides runtime only
```

---

## A2A 抽象的真正含义

`A2aAgent` 的意义，不是简单统一接口名，而是统一“对外可见的责任边界”。

对于调用者：

- 它不关心 Agent 背后是 Claude 还是 Codex
- 不关心 Agent 在哪个 Worker 上运行
- 不关心 Agent 是在主目录还是某个 worktree 上执行
- 不关心 Agent 内部是单实例执行还是多步骤编排

它只关心：

- 我把任务发给哪个 Agent
- 这个 Agent 当前任务状态是什么
- 我收到哪些事件
- 这个 Agent 最终给我什么结果

所以 `A2aAgent` 应该作为稳定的“Agent 端口”存在，屏蔽背后的执行细节。

这也是为什么：

- Controller 不应该依赖 `ClaudeWorkerFacade`
- Controller 不应该依赖 `CodexWorkerFacade`
- Controller 不应该知道 worker / directory / worktree 的内部恢复逻辑

Controller 只应该依赖：

- `TaskDispatchFacade`
- `A2aAgentProvider` / `UnifiedAgentResolver` 间接解析出来的 Agent 能力

---

## 产品语义建议

为了让前端、OpenAPI、A2A 三条链路统一，建议明确采用以下产品语义。

### 1. 前端面对的是 Agent，不是 Worker

任务创建、恢复、取消、继续会话时，前端应该选择 Agent。

前端不应把 `workerId` 当成主路由键。

### 2. 编程 Agent 以项目为粒度定义

前端展示的对象应是：

- A 项目 Agent
- payment-service Agent
- A 项目维护 Agent

而不是：

- worker-123
- /repo/payment-service-wt-fix-login

### 3. 目录是执行上下文，不是前端主身份

目录可以继续存在于前端模型里，但它表达的是：

- 当前项目根目录
- 当前可操作的代码空间
- 当前任务选择的执行目录

不是任务入口对象本身。

### 4. worktree 完全属于任务执行细节

worktree 可在任务执行过程中由后台自动创建、选择、回收。

前端只在需要浏览具体代码上下文时才感知它，不需要把它当成稳定 Agent。

---

## 对现有模型的影响

### 1. `CodingAgentEntity` 的语义要收口到“项目 Agent”

当前 `CodingAgentEntity` 已经是独立 Agent 实体，这是对的。

但后续它的语义需要更明确：

- `workerId`：执行宿主
- `defaultDirectoryId`：项目主目录
- `name/description/projectSummary`：项目 Agent 的对外描述

也就是说，`defaultDirectoryId` 更应该被理解为：

- “该项目 Agent 的主项目目录”

而不是：

- “很多目录中的一个默认值”

### 2. `AgentDirectoryBindingEntity` 应降级为授权/扩展关系

当前 `coding_agent_directories` 是多对多关系。

在目标模型里，这张表依然可以保留，但语义应调整为：

- 额外授权目录
- 辅助上下文目录
- 可选扩展目录

而不是：

- 普通任务分发时的主身份来源

普通任务分发的主身份来源应始终是：

- `agentId`

### 3. `TaskDispatchFacade` 后续应只认 `agentId`

当前为了兼容旧前端，`TaskDispatchFacade` 还存在 fallback 查找逻辑。

这类 fallback 只能作为迁移期兼容策略，不能作为长期主语义。

长期目标应是：

- 外部调用只传 `agentId`
- 后台内部自行恢复 `directoryId / workerId / worktree`

---

## 推荐的长期接口语义

### 1. 统一任务接口

创建任务时，推荐长期只暴露：

- `agentId`
- `prompt`
- 可选 `taskIntent`
- 可选 `modelConfigId`
- 可选 `contextId`

根据具体能力，也可以保留可选 `directoryId`，但它不应再承担“找 Agent”的职责。

### 原则

- `agentId` 负责路由
- `directoryId` 负责上下文覆盖
- `workerId` 不进入前端主协议

### 2. 统一目录/项目展示接口

如果前端仍需要展示目录树或项目列表，建议返回：

- `directoryId`
- `projectName`
- `agentId`
- `agentName`
- `isPrimaryDirectory`

这样前端可以在“目录视图”和“Agent 视图”之间切换，但提交任务时仍然统一提交 `agentId`。

### 3. 统一会话绑定

会话绑定对象应是：

- `agentId`

而不是：

- `workerId`
- `directoryId`
- `worktreeId`

---

## 为什么这个模型更自然

因为对真实使用者来说，更自然的表达始终是项目视角：

- 负责 A 项目的 Agent
- 负责 A 项目登录模块的 Agent
- 负责 A 项目某个 Bug 修复的当前任务
- 负责所有项目进度管理的上层 Agent

这套语义有三个好处：

1. 对前端心智简单。
2. 对 A2A 抽象自然。
3. 对后续多 Agent 扩展稳定。

如果把主身份放在 `worker` 上，会把基础设施暴露给上层。  
如果把主身份放在 `worktree` 上，会把临时执行上下文暴露给上层。  
如果把主身份放在“项目级 Agent”上，则既稳定又符合用户直觉。

---

## 本文对后续重构的约束

后续做统一任务分发、统一事件、统一消息流时，应遵守以下约束：

1. `A2aAgent` 代表项目级或业务级 Agent，不代表 Worker。
2. 编程任务默认按项目级 Agent 路由，不按 Worker 路由。
3. 目录是项目上下文，worktree 是临时执行上下文。
4. Session 绑定 Agent，不绑定 Worker。
5. 外部调用不关心 Agent 背后执行细节。
6. `workerId` 逐步退出前端主任务协议。

---

## 需要后续确认的点

### 1. 一个项目是否允许多个项目级 Agent

例如：

- A 项目开发 Agent
- A 项目 Review Agent
- A 项目 Release Agent

如果允许，就需要明确：

- 这些 Agent 是否共享同一个主目录
- 它们的命名、角色、卡片如何表达

### 2. 主目录与附加目录的边界

是否规定：

- 一个编程 Agent 必须有且只有一个主目录
- 其他目录只能作为授权附加目录

我倾向于这样规定。

### 3. 任务是否允许显式指定上下文目录

长期建议是：

- 默认由 Agent 自动选择
- 只有在特殊场景下，外部才显式覆盖目录或 worktree

---

## 当前结论

本轮领域语义对齐后的结论是：

- 编程 Agent 以项目为粒度定义
- Worker 不是 Agent
- Worktree 不是 Agent
- Directory 也不应直接成为对外主身份
- 对调用者而言，唯一需要面对的是 Agent

这应作为后续统一任务分发和 A2A 封装的前置原则。
