---
doc_type: lifecycle-decision
version: 1.4.4-SNAPSHOT
status: MAINTENANCE_ONLY
decided_at: 2026-08-10
effective_baseline: 1e7d6317fa8432026cc2c4f673ab67a00ee2a6ed
new_feature_intake: closed
successor: Foggy Agent Platform
---

# Foggy Navigator 1.4.4-SNAPSHOT 封版维护

## 决策

Foggy Navigator 自 2026-08-10 起进入 `MAINTENANCE_ONLY`。当前内网部署继续提供服务，
但本仓库停止产品功能演进；Foggy Agent Platform 是后续工作台、Host、Worker、Assistant、
协作与开放接入能力的唯一新产品主线。

有效代码基线为 `main@1e7d6317fa8432026cc2c4f673ab67a00ee2a6ed`。该提交撤回了 Navigator
中的 FAP 产品实验，保留旧版自身能力，适合作为新旧产品边界。本维护版本不声称 1.4.2、
1.4.3 的历史 workitem 已全部验收；它们原有的 `ACCEPTED`、`READY_FOR_SIGNOFF`、`REJECTED`、
`NEEDS_REPLAN` 等状态均按原记录保留，并统一退出自动实施队列。

当前没有清晰、干净的正式语义版本线，因此本次只提交封版治理记录，不创建 release 或 Git tag。

## 允许的维护

- 凭据泄露、鉴权绕过等严重安全缺陷；
- 可复现的 P0/P1 工作丢失、数据损坏或不可恢复问题；
- 阻断现有内网部署核心使用的 P0/P1 兼容缺陷；
- 为保持当前部署可启动所必需的依赖、证书、凭据轮换或构建修复；
- 对上述维护直接相关的最小代码、文档、focused tests 和风险相称验证。

每项维护都必须说明为什么不能在 FAP 解决，并保持既有公共契约、数据事实和部署拓扑不变。

## 明确停止

- 新页面、普通 UX 优化、新 Worker、新 Agent、新业务系统接入；
- Session、Task、A2A、权限体系、CrossProject 或平台治理的继续演进；
- 为“与 FAP 对齐”建立双写、同步、兼容桥或历史数据迁移；
- 恢复已经退役的 Monitoring、CrossProject mutation、Echo Agent、metadata-query 等切片；
- 对既有会话、任务、用户、凭据或运行记录执行清洗、回填、重放或删除。

## 运行与数据边界

封版不等于停服。现有 80 端口旧版入口、当前 Worker 和既有内部用户可以继续使用。
不得因为封版自动停止进程、撤销凭据、删除数据库、迁移会话或改变网络入口。

近期没有再次观察到历史上的工作丢失现象。当前可以怀疑上游 OpenAI 服务异常，但没有足够证据
确认根因；在缺少新的可复现证据前，不在 Navigator 建立缺陷或继续投入修复。

## 新需求处理

收到超出维护范围的请求时：

1. 在 Navigator 中只做只读现状调查；
2. 判断 FAP 是否已有等价能力；
3. 没有等价能力时，在 FAP 按其 authority 和协议边界重新设计，不复制 Navigator 内部模型；
4. 只有用户明确解除本封版决策，才允许 Navigator 恢复功能开发。

## 迁移价值评估

这里的“迁移”只迁产品语义、运维经验和回归用例。FAP 不复制 Navigator 的 Vue package、
route、CSS、生命周期 authority 或数据模型，也不建立对 Navigator 的运行依赖。

### P0/P1：建议补入新版

| 能力 | 处理方式 | 理由 |
|---|---|---|
| Worker 发布与更新失败用例 | 迁移为 FAP 发布器、Host updater 和 Supervisor 的回归资产 | 复用有效 UID HOME 回退、PowerShell 合法空输出、精确进程身份、失败锁存恢复、摘要校验和回滚经验，不迁旧 updater 实现 |
| Git diff 与 history | 在 FAP Host/Workbench 补齐 owner-scoped、只读、有界查询 | Navigator 已证明 changed files、单文件 diff、commit history/detail 有日常价值；FAP 当前只有 branch 与 CLEAN/DIRTY summary |

### P2/P3：有真实需求后在新版重做

| 能力 | 处理方式 | 前置条件 |
|---|---|---|
| Git worktree 管理 | 按 FAP Host/Workspace authority 重新设计 | 先确认团队存在稳定使用量，不恢复 Navigator Directory/Worker 模型 |
| 跨 Workspace 任务总览 | 仅做 Runtime/Worker canonical facts 的只读投影和深链 | 不迁旧 lifecycle、recovery authority 或第二事实源 |
| Claude/Gemini Worker | 按 FAP Worker Protocol 独立实现 | 出现明确 provider 需求后再立项 |
| 移动端与嵌入式聊天组件 | 按 Public Assistant/Workbench 公共契约重做 | 出现真实移动或嵌入场景后再投入 |

### 不迁移

- Navigator Session/Task/A2A lifecycle、once-effect、activation、reconciliation 数据模型；
- 已退役 CrossProjectTask、RabbitMQ Monitoring、Tutor/Echo/Code Review/metadata-query；
- 复杂 RBAC、ClientApp/upstream IAM 及历史兼容层；
- 旧 Open SDK、Observer BFF、CLI、Workbench UI/CSS/组件；
- Guide Cards、集中 Memory、Task Assistant 配置和 code-server。

文件搜索/预览、终端、会话归档删除、公开分享、附件、文件链接、Composer Skill/文件建议和任务完成通知
在 FAP 已有对应能力，不再作为迁移事项。

## 验证与残余风险

本版本只改变仓库治理和文档入口，不修改运行代码、配置、服务或历史数据，因此不运行构建与测试。
现有部署仍可能包含已知缺陷；只有满足“允许的维护”条件时才在本仓库处理。
