# Worker 总控工作流 v1 模块职责划分

## 文档作用

- doc_type: module-responsibility
- intended_for: root-controller | execution-agent | reviewer
- purpose: 明确 1.2.0-SNAPSHOT 中“总控工作流”在 root docs、runtime、后端、前端和治理 skill 之间的职责边界，避免把治理技能、执行技能和运行时实现混在一起

## Version

- `1.2.0-SNAPSHOT`

## Status

- Draft
- 2026-04-17

## 1. 文档适用范围

本文件服务于 `1.2.0-SNAPSHOT` 目标：

1. 把当前手动调用的治理 skills 收敛成一条可复用工作流
2. 明确总控、实现、审计、验收之间的边界
3. 给后续 runtime / backend / docs / UI 的实施拆分提供 ownership 基线

本文件不负责锁定最终类名和包路径，只负责回答：

1. 哪部分由 root-controller 负责
2. 哪些模块只是流程依赖，不需要改造成“总控大脑”
3. 哪些技能应该在流程节点被调用，而不是复制实现

## 2. Root 控制职责

当前 root 控制和版本基线位于：

- [01-worker-governance-supervisor-mode-requirement.md](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/docs/version-tracker/1.2.0-SNAPSHOT/01-worker-governance-supervisor-mode-requirement.md)

root 层负责：

1. intake 分类
2. 主状态机迁移
3. 决定本轮走 requirement 还是 bug 流程
4. 决定什么时候调用 `plan-evaluator`
5. 决定什么时候生成 root execution docs
6. 决定什么时候进入覆盖审计和正式验收
7. 汇总最终报告

root 层不负责：

1. 直接承包所有代码实现
2. 代替 reviewer 宣布实现无风险
3. 在没有证据的情况下直接给出验收通过
4. 把现有治理 skills 全部复制成仓库内新实现

## 3. 模块职责划分

### 3.1 `docs/version-tracker/1.2.0-SNAPSHOT`

负责：

1. 记录版本内总控流程基线
2. 承载 requirement / module responsibility / code inventory / implementation plan
3. 作为后续 progress、coverage、acceptance 的上游来源

建议承担：

1. root 级文档链路
2. 工作流阶段说明
3. skill 接入矩阵

不负责：

1. 运行时状态推进
2. 任务执行本体

### 3.2 `tools/langgraph-biz-worker/`

负责：

1. worker 侧 runtime 与 graph 执行
2. 总控 graph、执行 graph、审查 graph 的路由承载
3. worker 侧技能调用、执行日志和阶段结果回写
4. 将 skill 调用结果组织成结构化状态与报告片段

建议承担：

1. supervisor workflow manifests 或 graph routing
2. implementer / reviewer 非对称执行流
3. 自检、审计、签收前的阶段性结果对象

不建议承担：

1. 把 `C:/Users/oldse/.claude/skills/` 下的治理 skill 复制一份到仓库内
2. 在 runtime 内硬编码所有版本文档路径

### 3.3 `addons/langgraph-biz-worker/`

负责：

1. Java 侧任务入口、任务持久化、状态同步与事件 relay
2. worker 总控流程对平台任务模型的承载
3. 审批 / 恢复 / 阶段推进相关接口
4. 结构化结果与平台统一任务视图的映射

建议承担：

1. workflow task type 或 stage metadata 承载
2. 审批状态、验收状态与任务状态对齐
3. 与 `session-module` 的事件联动

不建议承担：

1. 复杂治理规则正文
2. 文档模板生成逻辑

### 3.4 `session-module/`

负责：

1. 会话、任务、事件投影和 SSE 推送
2. 为总控模式提供任务阶段可观测性
3. 为验收和回归复盘保留事件追踪基础

建议承担：

1. 工作流阶段事件投影
2. 与任务 / 会话关联的报告索引

不建议承担：

1. 直接承载治理 workflow 规则本体

### 3.5 `metadata-config-module/`

负责：

1. 配置类元数据管理
2. 后续如果需要，将工作流模板、角色模板、skill allowlist 等配置持久化

建议承担：

1. 可配置工作流模板的元数据承载
2. skill 名称到工作流节点的配置映射

不建议承担：

1. 文档产物生成
2. 任务阶段状态机执行

### 3.6 `packages/navigator-frontend/`

负责：

1. 如有需要，展示总控流程阶段、审计结论和验收结果
2. 展示 work item、覆盖审计和验收状态

首版可选承担：

1. 总控任务详情视图
2. 阶段进度与阻断项可视化

不建议在首版承担：

1. 完整的复杂 BPM 设计器

## 4. 治理 skill 职责划分

### 4.1 `workspace-governance-handbook`

负责：

1. 审查 root / 子目录 handbooks 是否支撑“根目录总控 + 子目录落实”
2. 明确哪些目录需要独立 agent 工作约束

定位：

- workspace 基线 skill
- 不作为每个 work item 的必跑节点

### 4.2 `foggy-plan-execution-docs`

负责：

1. 在规划已确认后生成 root execution docs
2. 统一 requirement、ownership、code inventory、implementation plan 这类根文档

定位：

- `requirement -> plan-approved` 阶段的文档生成节点

### 4.3 `foggy-versioned-doc-tracking`

负责：

1. 版本落盘
2. progress writeback
3. execution-checkin

定位：

- 全流程基础落盘 skill
- 应作为总控默认文档链路，而不是临场补救

### 4.4 `plan-evaluator`

负责：

1. 对 requirement、规划、验收文档做评估
2. 识别过度设计、证据缺口和不合理跳步

定位：

- `PLAN_EVALUATION`
- 验收文档二次复核

### 4.5 `foggy-bug-regression-workflow`

负责：

1. BUG work item 建立
2. 复现判断
3. 测试策略与修复清单

定位：

- BUG 主流程入口
- 验收失败后的回流入口

### 4.6 `foggy-test-coverage-audit`

负责：

1. `pre-acceptance-check`
2. `post-acceptance-regression-review`

定位：

- 验收前测试证据盘点
- 验收后长期回归复盘

补充：

- 这两个名字在首版中是 `audit_mode`，不单独视为 skill

### 4.7 `foggy-implementation-quality-gate`

负责：

1. `lightweight-self-check`
2. `pre-coverage-audit`
3. `post-fix-quality-review`

定位：

- 编码完成后的自检与正式质量门
- 覆盖审计之前的实现质量收口

### 4.8 `foggy-acceptance-signoff`

负责：

1. 正式签收
2. 写 acceptance record
3. 给出 `accepted`、`accepted-with-risks`、`rejected`、`blocked`

定位：

- 正式验收节点

## 5. 当前可直接开工的部分

1. root 文档包落盘
2. 总控状态机与阶段定义
3. skill 接入矩阵
4. runtime / backend 的代码触点盘点

## 6. 当前不建议直接编码的部分

1. 一开始就做任意数量 agent 的协商型编排
2. 一开始就把所有治理 skill 仓内复制重写
3. 一开始就做 UI 侧完整流程设计器
4. 在没有确定状态机和文档链路前直接写执行逻辑

## 7. 完成定义

模块职责文档完成的标准是：

1. 后续执行 agent 能知道哪些规则属于 root-controller，哪些属于 runtime，哪些属于文档 skill
2. 不会把“治理技能”和“执行 runtime”误合并为一个大模块
3. 后续 code inventory 与 implementation plan 可以基于本文继续收口
