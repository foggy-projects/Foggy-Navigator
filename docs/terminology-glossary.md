# 术语表

> 用于统一当前文档中的产品术语、实现术语和代码映射关系。

## 1. 使用原则

后续写文档时，建议遵循下面三条规则：

1. 先用产品术语描述用户可理解的能力面。
2. 需要落到实现时，再补充对应模块名或代码名。
3. 同一篇文档里尽量固定用一组术语，不要在“产品词”和“代码词”之间来回切换。

## 2. 核心术语

### 2.1 Worker

推荐定义：
远程执行资源或远程编程节点，通常承载目录、文件、Git、终端、任务执行等上下文。

当前典型实现：

- `addons/claude-worker-agent`
- `addons/codex-worker-agent`

推荐用法：

- “Worker 工作台”
- “远程 Worker”
- “Worker 目录上下文”

不推荐混用：

- 不要把 Worker 直接等同于 Session
- 不要把 Worker 直接等同于某个具体 Agent

### 2.2 通用 Agent / 项目 Agent

推荐定义：
Navigator 中可被 A2A 路由发现和调用的稳定 Agent 实体。它可以是绑定项目/目录的编程 Agent，也可以是上游 ClientApp 注册的业务运行 Agent。

当前常见表现：

- 代码中的 `CodingAgentEntity`
- 前端里某些与目录或项目绑定的 Agent 记录
- 上游集成中的业务 Agent，例如 `tms-x3-agent-v305`

推荐用法：

- “通用 Agent”
- “项目 Agent”
- “业务 Agent”
- “目录绑定的 Agent”

说明：

`CodingAgentEntity` 是历史类名；当前更准确的实现语义是“通用 Agent 注册行”。具体 Agent 类型通过 `agentType` 与 `agentProfile` JSON 区分。

### 2.3 Coding Agent

推荐定义：
历史上偏向“编程执行 Agent”的统称；当前仓库里主要保留为历史代码命名，不再对应独立编程执行 addon。

推荐用法：

- 仅在引用现有实体名或兼容字段名时使用

不推荐混用：

- 不要在产品总览里把所有执行型 Agent 都统称为 Coding Agent
- 不要默认把 Coding Agent 等同于某个具体执行引擎

### 2.4 A2A Agent

推荐定义：
通过 A2A 协议发现、解析和调用的统一 Agent 接入对象。

当前作用：

- 作为统一任务分发中的一类路由目标
- 作为外部或异构执行能力的统一接入层

推荐用法：

- “A2A Agent 接入”
- “A2A 路由”
- “A2A Provider”

说明：

A2A Agent 是接入方式，不是单独的一级产品功能域。

### 2.5 Tutor

推荐定义：
当前默认的引导型 Agent，负责识别意图、解释、路由和把用户导向正确上下文。

当前对应模块：

- `tutor-agent`

推荐用法：

- “Tutor”
- “Tutor Agent”
- “引导型 Agent”

不推荐混用：

- 不要把 Tutor 写成主要执行引擎
- 不要把 Tutor 写成 Worker 的别名

### 2.6 会话

推荐定义：
用户与 Agent 持续交互的对话上下文，承载消息、路由、委派、分享和部分任务关联关系。

当前对应模块：

- `session-module`

推荐用法：

- “会话中心”
- “会话上下文”
- “父子会话跳转”

说明：

会话强调的是交互连续性，不等同于执行环境。

### 2.7 Session

推荐定义：
`会话` 的英文或代码术语。

推荐用法：

- 在代码说明、接口说明、字段说明里使用 `Session`
- 在产品说明里优先使用“会话”

### 2.8 任务

推荐定义：
平台视角下可查询、可恢复、可取消、可治理的执行对象。

当前特点：

- 既可能来自会话触发
- 也可能来自 Worker 目录触发
- 也可能来自跨项目阶段编排

推荐用法：

- “任务治理”
- “任务分发”
- “任务恢复/取消/重连”

说明：

任务是平台治理对象，不等同于单条消息。

### 2.9 跨项目任务

推荐定义：
由多个阶段组成、可跨目录或跨仓库推进的复合任务。

推荐用法：

- “跨项目任务”
- “阶段任务”
- “多阶段编排”

### 2.10 编程执行入口

推荐定义：
承接编程类任务的 Worker addon、远程执行端和 A2A 路由组合。

当前典型对象：

- `addons/claude-worker-agent`
- `addons/codex-worker-agent`
- `addons/gemini-worker-agent`

说明：

在产品说明里，优先写“编程执行入口”或“Worker 执行能力”；只有在引用具体历史实体名时才写 `CodingAgentEntity`。

## 3. 推荐写法对照

| 场景 | 推荐写法 | 不推荐写法 |
|------|------|------|
| 产品总览 | Worker、会话、任务、跨项目任务 | 一律写成 Agent / Coding Agent |
| 模块说明 | `session-module`、`tutor-agent`、Worker addon | 只写抽象词，不落代码映射 |
| 前端能力面 | Workers、会话、任务、设置、监控 | 后端模块名直接代替前端导航 |
| 实现说明 | Session、TaskDispatchFacade、A2A Provider | 把产品词和代码词混在同一句里反复切换 |

## 4. 推荐引用顺序

如果一篇文档既涉及产品能力也涉及代码实现，建议按这个顺序写：

1. 先定义产品词  
   例如“会话”“任务”“Worker”
2. 再给出实现映射  
   例如 `session-module`、`TaskDispatchFacade`
3. 最后补充特殊模块词  
   例如 Worker addon、A2A Provider

## 5. 相关文档

- [系统架构概览](./00-system-overview.md)
- [功能架构说明](./02-modules/functional-architecture.md)
- [文档状态清单](./documentation-status.md)
