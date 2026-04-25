# 独立总控服务与项目目录级 Agent 双层架构设计

## 文档作用

- doc_type: requirement + architecture-design
- intended_for: root-controller | architect | reviewer | signoff-owner
- purpose: 论证“独立总控服务 + 项目目录级 agent”双层架构的必要性，明确与当前 A2A 平台的边界、最小协议、MVP 范围与演进路径，供立项评估使用

## Version

- `1.2.0-SNAPSHOT`

## Status

- Draft
- 2026-04-17

## 1. 背景

当前实际运行形态已经呈现出一套清晰的目录级分层：

1. 一个项目级 agent 作为当前工作区入口
2. 若干子模块 agent 按目录边界负责本模块实现
3. 总控通过项目树、模块路径和目录发现来下沉任务

这套模式在单项目内已经工作良好，说明：

1. “根目录总控 + 子目录落实”在执行层面是成立的
2. 子模块 ownership 已经天然存在
3. 当前问题不再是“有没有总控能力”，而是“总控应该留在项目内部，还是抽到项目外部做独立控制面”

与此同时，用户给出的最新前提也已经发生变化：

1. 当前项目对外提供的 `A2aAgent` 访问能力已经比较稳定
2. 当前项目本身运行较稳定
3. 总控层希望高频迭代，且避免与当前项目深度耦合

这意味着总控层已经开始具备独立立项的基础。

## 2. 设计结论

本设计建议采用双层结构：

1. **独立总控服务**
   - 负责治理控制面
   - 负责流程状态机、角色编排、验收与回流
2. **项目目录级 agent**
   - 负责项目内部执行面
   - 负责目录发现、模块任务下沉、代码与文档操作、结果回收

换句话说：

- 当前稳定项目保留为 **A2A 执行底座 / data plane**
- 新总控服务建设为 **治理控制面 / control plane**

## 3. 为什么不是直接把总控继续塞进当前项目

### 3.1 当前项目的职责已经偏稳定底座

当前项目已经承担：

1. A2A 能力暴露
2. agent 注册与执行
3. task / session / SSE / 事件投影
4. worker 生命周期

这些都是低频大边界、强调稳定性的底座能力。

如果继续把总控服务直接内嵌到当前项目，会出现：

1. 底座版本和治理版本被迫同频
2. 高频实验逻辑不断侵入稳定平台
3. 目录型执行逻辑、治理状态机和平台底座开始混杂

### 3.2 当前目录级 agent 更适合作为“项目执行代理”

从现有使用方式看，项目级 agent 的强项是：

1. 识别当前项目结构
2. 知道子模块边界
3. 可以就地读写代码和文档
4. 可以把任务下沉到目录级 agent

这说明它更适合扮演：

- `project execution agent`

而不是：

- 最终的跨项目总控服务

### 3.3 独立总控服务更适合承载高频治理演进

总控层后续大概率要高频演进这些内容：

1. intake 分类
2. workflow 状态机
3. implementer / reviewer / tester / signoff 编排
4. BUG 回流规则
5. 覆盖审计、验收、复盘规则
6. 报告模型

这些都明显比底座更“策略化”，适合独立版本节奏。

## 4. 双层架构定义

### 4.1 L1: 独立总控服务

职责：

1. 用户入口统一 intake
2. 归类 requirement / bug / clarification-needed / acceptance-found bug
3. 持有主 workflow state
4. 编排角色：
   - implementer
   - reviewer/challenger
   - tester/auditor
   - signoff-owner
5. 决定何时调用治理 skills
6. 生成阶段报告和最终报告
7. 管理回流、重开和验收结论

不负责：

1. 直接读当前项目数据库
2. 直接依赖当前项目 Java domain model
3. 直接入侵当前项目 session/task service 代码
4. 自己承担项目内所有代码操作

### 4.2 L2: 项目目录级 agent

职责：

1. 识别项目结构
2. 列出子模块 agent
3. 将任务下沉到具体模块
4. 执行代码修改、测试、文档写回
5. 汇总模块执行结果
6. 将结构化结果回传给总控服务

不负责：

1. 持有全局 workflow state
2. 自己定义正式验收规则
3. 替总控做版本级治理决策

### 4.3 L3: 子模块 agent

职责：

1. 本模块实现
2. 本模块测试
3. 本模块 progress 回写
4. 本模块证据产出

不负责：

1. 跨模块总协调
2. 最终签收
3. 版本级报告

## 5. 运行关系

建议主调用链如下：

```text
User
  -> Independent Supervisor Service
  -> Project Execution Agent (project directory agent)
  -> Module Agent(s)
  -> Project Execution Agent
  -> Independent Supervisor Service
  -> Audit / Signoff / Report
```

如果是 BUG 回流链路：

```text
Acceptance Signoff
  -> Independent Supervisor Service marks rejected / blocked
  -> create bug work item
  -> Project Execution Agent dispatches fix task
  -> Coverage Audit
  -> Acceptance Signoff
```

## 6. 当前项目与新总控服务的边界

### 6.1 当前项目继续负责

1. `A2aAgent` 暴露
2. `invoke/query/cancel`
3. agent registry
4. task / session / event / SSE
5. worker lifecycle
6. 项目内项目级 agent 与子模块 agent 执行

### 6.2 新总控服务负责

1. workflow state
2. role state
3. review decision
4. signoff decision
5. bug reopen decision
6. report model
7. work item orchestration

### 6.3 明确禁止共享

1. 当前项目内部 DB schema
2. 当前项目内部 Java domain model
3. 当前项目内部 session/task service 代码

## 7. 最小协议

独立总控服务与项目目录级 agent 之间，首版建议只依赖最小协议。

### 7.1 结构发现协议

目标：

1. 列出项目级 agent 所能管理的模块
2. 返回模块标识、路径和职责摘要

建议最小返回：

- `projectAgentId`
- `moduleAgentId`
- `moduleName`
- `modulePath`
- `moduleRole`
- `capabilities`

### 7.2 执行委派协议

目标：

1. 将任务分配给指定项目级 agent 或模块 agent
2. 明确本轮角色、目标、输入文档和完成标准

建议最小输入：

- `workItemId`
- `workflowStage`
- `role`
- `targetAgentId`
- `context`
- `requiredArtifacts`
- `completionCriteria`

### 7.3 状态回传协议

目标：

1. 让项目级 agent 回报当前阶段状态
2. 让总控据此决定是否进入下一阶段

建议最小返回：

- `workItemId`
- `agentId`
- `stage`
- `status`
- `blockingItems`
- `summary`
- `artifactRefs`
- `readyForNextStage`

### 7.4 文档回写协议

目标：

1. requirement / bug / progress / acceptance 材料可被稳定写回目标项目
2. 关键治理结论不只留在总控服务本地

建议最小能力：

- `createWorkItemDoc`
- `updateProgressDoc`
- `recordExecutionCheckin`
- `attachEvidenceRef`
- `recordAcceptanceRef`

### 7.5 证据收集协议

目标：

1. 总控能拉取测试、质量门、覆盖审计和验收材料
2. 项目级 agent 负责收集路径和摘要

建议最小返回：

- `qualityGateRef`
- `testEvidenceRefs`
- `coverageAuditRef`
- `acceptanceRef`
- `manualEvidenceRefs`

## 8. 文档链路方案

这是拆分时最容易隐性耦合的部分，必须提前约束。

### 8.1 推荐方案

首版仍以“目标项目仓库中的版本文档”作为事实源：

1. work item 主文档写回目标项目
2. progress / execution-checkin 写回目标项目
3. coverage / acceptance 写回目标项目
4. 总控服务只维护工作流状态和摘要缓存，不把自己当唯一事实源

### 8.2 不建议方案

不建议首版让总控服务自己维护一套独立 work item 库，再靠人工同步回项目仓。

原因：

1. 文档事实源会分裂
2. 验收和回归路径容易断
3. 最终仍然要回写项目仓，等于重复建模

## 9. 与前述总控工作流文档的关系

本设计文档不是替换前面 `01-04` 文档，而是给出一个新的部署与边界视角：

1. `01-04` 解决“总控工作流本身怎么设计”
2. `05` 解决“总控工作流应该部署在哪一层、如何和项目目录级 agent 协作”

两者关系是：

- `01-04` = workflow 设计
- `05` = service boundary 设计

## 10. MVP 范围

首版建议只做最小闭环。

### 10.1 In Scope

1. 一个独立总控服务
2. 对接一个项目级 agent
3. 支持 requirement / bug 两条主路径
4. 支持模块发现
5. 支持任务下沉与状态回传
6. 支持 progress / acceptance 回写
7. 支持质量门、覆盖审计、验收和回流的主链路

### 10.2 Out of Scope

1. 多项目并行编排
2. 通用 BPM 设计器
3. 完整权限中心
4. 完整 UI 控制台
5. 首版就支持任意外部项目接入

## 11. 风险与应对

### 11.1 风险：项目代理协议不够稳定

应对：

1. 首版只定义最小协议
2. 不把当前内部模型直接暴露出去

### 11.2 风险：文档回写成为隐性耦合点

应对：

1. 明确 work item 事实源仍在目标项目
2. 文档回写能力做成项目代理能力，不让总控直接摸文件系统

### 11.3 风险：独立服务过早平台化

应对：

1. 先只服务一个项目级 agent
2. 不提前抽象多租户、多团队、多项目复杂模型

### 11.4 风险：总控与执行代理边界漂移

应对：

1. 总控只做治理决策
2. 项目代理只做项目内执行与文档落盘
3. 子模块 agent 只做模块内实现

## 12. 立项建议

如果以“独立总控服务”立项，建议立项名称明确体现双层关系，例如：

1. `Supervisor Control Plane`
2. `A2A Governance Supervisor`
3. `Project Execution Agent + Supervisor Service`

建议立项结论：

1. **可以独立立项**
2. **但应定位为基于稳定 A2A 契约的治理控制面**
3. **不应定位为共享当前项目内部模型的半独立子系统**

## 13. 验收标准

本设计文档用于立项评估时，应至少回答清楚：

1. 为什么当前适合拆成独立总控服务
2. 为什么现有项目目录级 agent 不该废弃，而应转为项目执行代理
3. 新服务与当前项目之间共享什么、不共享什么
4. 两层之间的最小协议是什么
5. MVP 第一阶段做什么、不做什么
