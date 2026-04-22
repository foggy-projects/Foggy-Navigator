# Worker 总控工作流 v1 实现计划

## 文档作用

- doc_type: implementation-plan
- intended_for: root-controller | execution-agent | reviewer | signoff-owner
- purpose: 将 1.2.0-SNAPSHOT 的“worker 总控工作流”拆成可执行阶段、技能接入节点、质量门和验收路径，作为后续开发与验收的直接上游

## Version

- `1.2.0-SNAPSHOT`

## 上游输入文档

本计划以下列文档作为上游输入：

1. [01-worker-governance-supervisor-mode-requirement.md](./01-worker-governance-supervisor-mode-requirement.md)
2. [02-worker-governance-supervisor-mode-module-responsibility.md](./02-worker-governance-supervisor-mode-module-responsibility.md)
3. [03-worker-governance-supervisor-mode-code-inventory.md](./03-worker-governance-supervisor-mode-code-inventory.md)

本计划不重复需求背景，只负责收口首版执行路线。

## 1. 规划判断

### 1.1 首版工作流判断

`1.2.0-SNAPSHOT` 不应一开始就追求“很多 agent 自主讨论并共同编码”。首版更可落地的路线是：

1. 单一 `root-controller` 作为入口与状态机持有者
2. `implementer` 负责实现
3. `reviewer/challenger` 负责非对称质疑和回看
4. `tester/auditor` 负责证据与测试门
5. `signoff-owner` 负责正式签收

### 1.2 技能接入判断

首版应把现有治理型 skills 视为“流程节点的专用能力”，而不是 worker 默认长期常驻技能：

1. `workspace-governance-handbook` 作为 workspace 基线检查
2. `foggy-versioned-doc-tracking` 作为 record / progress / execution-checkin 的默认落盘能力
3. `plan-evaluator` 作为计划评估与风险识别节点
4. `foggy-plan-execution-docs` 作为方案确认后的文档生成节点
5. `foggy-bug-regression-workflow` 作为 BUG 入口与验收失败回流入口
6. `foggy-implementation-quality-gate` 作为实现自检与正式质量门
7. `foggy-test-coverage-audit` 作为覆盖审计节点
8. `foggy-acceptance-signoff` 作为正式签收节点

### 1.3 审计模式判断

首版明确约定：

1. `pre-acceptance-check` = `foggy-test-coverage-audit` 的验收前模式
2. `post-acceptance-regression-review` = `foggy-test-coverage-audit` 的签收后复盘模式

不建议把它们单独做成新 skill 名称，否则会让总控流程与现有 skill 体系脱节。

## 2. 首版总流程

建议首版采用下面这条固定链路：

1. `INTAKE`
2. `RECORD`
3. `PLAN_EVALUATION`
4. `PLAN_APPROVED`
5. `EXECUTING`
6. `SELF_CHECK`
7. `QUALITY_GATE`
8. `TESTING`
9. `COVERAGE_AUDIT`
10. `ACCEPTANCE_SIGNOFF`
11. `POST_ACCEPTANCE_REVIEW`
12. `DONE`

BUG 流的特殊回路：

1. intake 判定为 BUG 时，先调用 `foggy-bug-regression-workflow`
2. 验收发现 BUG 时，`foggy-acceptance-signoff` 保留结论，再回流到 BUG work item
3. 回归完成后重新回到 `COVERAGE_AUDIT -> ACCEPTANCE_SIGNOFF`

## 3. 技能接入矩阵

| stage | primary skill | 目的 | 产物 |
| --- | --- | --- | --- |
| workspace bootstrap | `workspace-governance-handbook` | 确认 root / 子目录 handbooks 是否支撑总控模式 | handbook audit / merge 建议 |
| intake record | `foggy-versioned-doc-tracking` | 将事项落到正确版本目录 | requirement / workitem / progress skeleton |
| requirement or plan review | `plan-evaluator` | 评估规划合理性、风险、过度设计 | 评审意见 |
| execution docs generation | `foggy-plan-execution-docs` | 把已确认规划转成 root 执行文档包 | requirement / ownership / code inventory / implementation plan |
| bug intake | `foggy-bug-regression-workflow` | 建立 BUG 条目、复现判断、测试决策 | BUG work item |
| execution checkin | `foggy-versioned-doc-tracking` | 回写开发、测试、体验、风险 | progress / execution-checkin |
| quality gate | `foggy-implementation-quality-gate` | 先做自检，再决定是否可进入覆盖审计 | self-check / implementation quality report |
| pre-acceptance audit | `foggy-test-coverage-audit` | 审查测试证据覆盖是否足够 | coverage audit |
| formal signoff | `foggy-acceptance-signoff` | 写正式验收记录并给出结论 | acceptance record |
| post signoff review | `foggy-test-coverage-audit` | 复盘长期回归保护是否足够 | regression review |

## 4. 分阶段计划

### Phase 1: 工作流基线与文档收口

- depends_on: 无
- owner: root docs

目标：

1. 确定总控状态机
2. 确定治理型 skills 的固定接入点
3. 形成版本基线文档

交付物：

1. requirement
2. module responsibility
3. code inventory
4. implementation plan

验收标准：

1. 可以明确回答每个 skill 在流程中的位置
2. 可以明确回答总控和执行 agent 的分工
3. 不再把 `pre-acceptance-check` / `post-acceptance-regression-review` 误认成独立 skill

### Phase 2: intake 与 work item 流程化

- depends_on: Phase 1
- owner: root-controller + docs + runtime

目标：

1. 把用户输入统一归类为 requirement / bug / clarification-needed
2. 建立 record 和 progress skeleton
3. requirement 与 bug 分别进入正确流程

交付物：

1. intake 分类规则
2. `foggy-versioned-doc-tracking` 接入点
3. `foggy-bug-regression-workflow` 接入点

验收标准：

1. 新输入不再直接跳到编码
2. 每个事项都能回指版本内主文档
3. BUG 与 requirement 路径不再混淆

### Phase 3: 非对称实现与挑战式复核

- depends_on: Phase 2
- owner: `tools/langgraph-biz-worker/` + `addons/langgraph-biz-worker/`

目标：

1. 建立 `implementer` 与 `reviewer/challenger` 的非对称执行流
2. 完成编码后的自检与 execution-checkin
3. 避免多个 coder 改同一责任区

交付物：

1. implementer workflow
2. reviewer/challenger workflow
3. progress / execution-checkin writeback 规则

验收标准：

1. implementer 不负责宣布正式通过
2. reviewer/challenger 能明确输出风险、漏项和回改建议
3. execution-checkin 能记录开发、测试、体验、风险

### Phase 4: 质量门、测试与验收链路

- depends_on: Phase 3
- owner: root-controller + tester/auditor + signoff-owner

目标：

1. 自检后进入正式质量门
2. 测试完成后进入覆盖审计
3. 审计通过后进入正式验收

交付物：

1. `SELF_CHECK` 基线
2. `QUALITY_GATE` 触发规则
3. `pre-acceptance-check`
4. `foggy-acceptance-signoff`

验收标准：

1. 没有 coverage audit 的事项不能直接签收
2. 没有正式 signoff 的事项不能标记为闭环完成
3. 验收记录能回指 requirement / plan / progress / evidence

### Phase 5: 验收失败回流与签收后回归复盘

- depends_on: Phase 4
- owner: root-controller + signoff-owner + auditor

目标：

1. 验收发现 BUG 时自动回流
2. 签收后对高风险功能做长期回归保护复盘
3. 形成最终总结报告

交付物：

1. `REOPENED_AS_BUG` 回流规则
2. `post-acceptance-regression-review`
3. 最终总结报告模板或规则

验收标准：

1. 验收失败的问题不会只停留在聊天上下文
2. 回归复盘能指出长期测试薄弱点
3. 总控能输出完整闭环报告

## 5. 关键设计决策

### 5.1 首版为什么不做平级多 coder

原因：

1. 多个 coder 同时修改同一范围，责任和回归边界最容易失控
2. 当前治理 skill 体系已经天然适合“总控 + 审查 + 验收”链路
3. 先把非对称角色跑通，比先做复杂协商更可落地

### 5.2 首版为什么不复制现有 skills

原因：

1. 现有 skills 已有明确边界和文档契约
2. 仓内复制会制造平行规范
3. 先按节点调用，成本最低，也最容易保持一致

### 5.3 首版为什么保留人工闸门

原因：

1. 计划批准和正式签收属于高价值判断
2. 当前阶段更需要“可追责的决定”，而不是完全自治
3. 高风险变更仍需要人类确认版本范围与风险承担

## 6. 质量门与验收链路

编码完成后必须按顺序通过：

1. implementer 自检
2. `foggy-versioned-doc-tracking` 执行 `execution-checkin`
3. 正式质量门
4. 测试运行与证据收集
5. `foggy-test-coverage-audit` with `audit_mode = pre-acceptance-check`
6. `foggy-acceptance-signoff`
7. 如需要，`foggy-test-coverage-audit` with `audit_mode = post-acceptance-regression-review`

## 7. 完成定义

本计划完成后的执行标准是：

1. 后续开发 agent 可按本文分阶段开工
2. 每个阶段都有清晰 owner、输入、输出和准入准出条件
3. 现有治理型 skills 被组织成固定工作流，而不是继续散落在手动口头操作里
