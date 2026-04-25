# Worker 总控工作流 v1 需求

## 文档作用

- doc_type: requirement
- intended_for: root-controller | execution-agent | reviewer | signoff-owner
- purpose: 将“用户与总控 agent 沟通后，统一落成 BUG / 需求，并经过计划、开发、质量检查、测试、验收与报告”的治理闭环正式落盘，作为 1.2.0-SNAPSHOT 的上游基线

## Version

- `1.2.0-SNAPSHOT`

## Priority

- P0

## Status

- Draft
- 2026-04-17 初版记录

## Background

当前工作区已经具备几类关键治理能力，但它们大多仍以“人和 LLM 手动对话触发”的方式使用：

- `workspace-governance-handbook` 负责 root / 子目录协作边界
- `foggy-plan-execution-docs` 负责把已确认方案转成执行文档包
- `foggy-versioned-doc-tracking` 负责把需求、BUG、进度落到正确版本目录
- `plan-evaluator` 负责评估规划或验收文档
- `foggy-bug-regression-workflow` 负责 BUG 收口、测试决策与修复清单
- `foggy-implementation-quality-gate` 负责实现自检与正式质量门
- `foggy-test-coverage-audit` 负责测试证据覆盖审计
- `foggy-acceptance-signoff` 负责正式验收与签收

这些能力单独看已经可用，但仍缺少一条稳定的“总控工作流”，回答下面这些问题：

1. 用户提出问题后，什么时候落成需求，什么时候落成 BUG
2. 什么时候进入计划，什么时候先澄清，什么时候先做 BUG 复现
3. 一个或两个 agent 如何在实现与质疑之间分工，而不是互相覆盖修改
4. 哪些技能是总控在流程节点显式调用，哪些不应作为 worker 默认常驻技能
5. 什么时候可以进入验收，什么时候应该回退为 BUG 再次闭环

## Problem Statement

如果继续依赖“人工临场决定要不要调哪个 skill、什么时候写版本文档、什么时候做验收”，会持续出现这些问题：

1. 相同类型的需求 / BUG 在不同会话中走不同流程，难以沉淀
2. 规划、进度、测试、验收和证据容易脱节
3. 多 agent 协作时责任边界不清，容易让两个 coder 改同一范围
4. 验收发现的问题难以自动回流到 BUG 工作项
5. 总控无法基于统一状态机输出稳定报告

## Target Outcome

`1.2.0-SNAPSHOT` 首版目标不是做“完全自治的 agent 组织”，而是交付一条可落地的总控工作流：

1. 用户输入先进入总控 intake
2. 总控将事项归类为 `requirement`、`bug`、`clarification-needed` 或 `acceptance-found bug`
3. 已确认的需求进入规划评审，再生成执行文档包
4. 已确认的 BUG 进入复现 / 测试决策 / 修复清单流程
5. 编码阶段默认采用非对称角色：
   - `implementer`
   - `reviewer/challenger`
6. 编码完成后必须先做实现自检，再决定是否进入正式质量门
7. 测试证据通过 `pre-acceptance-check` 后，才允许正式 `foggy-acceptance-signoff`
8. 验收后可通过 `post-acceptance-regression-review` 复盘长期回归保护
9. 如果验收发现缺陷，自动回流成 BUG work item，而不是只停留在聊天记录里

## Scope

### In Scope

- 总控工作流状态机
- 总控与执行 agent 的角色模型
- 现有治理类 skills 的接入顺序
- 需求 / BUG / 验收发现问题的统一落盘规则
- 质量检查、覆盖审计、验收和回归复盘的阶段关系
- `single-root-delivery` 场景下的版本文档基线

### Out of Scope

- 首版就实现任意数量 agent 的动态博弈和共识系统
- 首版就把所有治理 skill 复制进仓库内重写一套
- 首版就完全取消人工审批
- 首版就覆盖生产发布、发布回滚或外部流水线治理

## Functional Requirements

### 1. Intake 归类

总控在接收到用户输入后，必须先做 intake 归类，而不是直接进入编码。

首版至少支持以下分类：

1. `requirement`
2. `bug`
3. `clarification-needed`
4. `acceptance-found bug`

分类规则：

- 新功能、流程优化、治理优化，优先进入 `requirement`
- 现有行为异常、回归、验收失败，优先进入 `bug`
- 语义不清、范围不清、成功标准不清，进入 `clarification-needed`
- 验收阶段发现的新问题，进入 `acceptance-found bug`

### 2. 固定状态机

总控工作流首版采用固定状态机，不允许任意跳步。

建议主状态：

1. `INTAKE`
2. `NEEDS_CLARIFICATION`
3. `RECORDED`
4. `PLAN_EVALUATION`
5. `PLAN_APPROVED`
6. `EXECUTING`
7. `SELF_CHECK`
8. `QUALITY_GATE`
9. `TESTING`
10. `COVERAGE_AUDIT`
11. `ACCEPTANCE_SIGNOFF`
12. `POST_ACCEPTANCE_REVIEW`
13. `DONE`
14. `REOPENED_AS_BUG`
15. `REJECTED`

状态迁移要求：

- `requirement` 默认走 `RECORDED -> PLAN_EVALUATION -> PLAN_APPROVED`
- `bug` 默认走 `RECORDED -> EXECUTING`，但前面必须先完成 BUG work item 与测试决策
- `ACCEPTANCE_SIGNOFF` 失败时，必须落为 `REOPENED_AS_BUG`

### 3. 非对称 agent 角色

首版不采用多个 coder 平级自由协作，默认角色为：

1. `root-controller`
2. `implementer`
3. `reviewer/challenger`
4. `tester/auditor`
5. `signoff-owner`

角色边界：

- `root-controller` 负责状态迁移、技能调用、文档链路和最终报告
- `implementer` 负责实现，不宣布自己“验收通过”
- `reviewer/challenger` 负责找风险、找漏项、挑战实现，不与 implementer 共享同一修改责任区
- `tester/auditor` 以测试结果和证据为准，不以自然语言自报为准
- `signoff-owner` 负责正式接受或拒绝，不负责展开修复细节

### 4. 治理类 skill 工作流化

下列 skills 应纳入总控流程，而不是继续完全依赖临场手动调用：

1. `workspace-governance-handbook`
2. `foggy-plan-execution-docs`
3. `foggy-versioned-doc-tracking`
4. `plan-evaluator`
5. `foggy-bug-regression-workflow`
6. `foggy-test-coverage-audit`
7. `foggy-implementation-quality-gate`
8. `foggy-acceptance-signoff`

接入规则：

- `workspace-governance-handbook`：用于 workspace 基线检查、根目录总控 / 子目录职责边界确认，不要求每个 work item 都重复执行
- `foggy-versioned-doc-tracking`：作为 `record`、`progress-update`、`execution-checkin` 的默认落盘入口
- `plan-evaluator`：用于 requirement / root-plan / acceptance-doc 的评审与风险识别
- `foggy-plan-execution-docs`：在方案已确认后生成 root 执行文档包
- `foggy-bug-regression-workflow`：用于 intake 判定为 BUG，或验收发现缺陷后的回流
- `foggy-implementation-quality-gate`：用于 `lightweight-self-check` 和正式 `pre-coverage-audit`
- `foggy-test-coverage-audit`：用于 `pre-acceptance-check` 与 `post-acceptance-regression-review`
- `foggy-acceptance-signoff`：用于 feature 或 version 正式签收

补充说明：

- `pre-acceptance-check` 和 `post-acceptance-regression-review` 在首版中作为 `foggy-test-coverage-audit` 的 `audit_mode` 处理，不单独建 skill

### 5. 固定文档链路

每个 work item 至少需要能追溯到：

1. requirement 或 BUG 主文档
2. implementation plan 或 fix checklist
3. progress / execution checkin
4. test / evidence / coverage audit
5. acceptance record

要求：

- 文档必须优先落在 `docs/version-tracker/<version>/`
- 不允许把关键结论只留在聊天上下文里
- 验收发现 BUG 时，必须写 BUG work item 路径

### 6. 人工闸门

首版保留以下默认人工闸门：

1. `PLAN_APPROVED`
2. `ACCEPTANCE_SIGNOFF`

以下情况应追加人工确认：

1. 跨模块或跨 repo 变更
2. 高风险 BUG
3. 涉及公共契约、共享接口、共享文档规范
4. `plan-evaluator` 给出明显不通过结论

### 7. 完成定义

总控工作流的一次闭环完成，至少满足：

1. 分类准确并已落盘
2. 对应规划或 BUG 清单已建立
3. 实现已完成并写回 progress
4. 测试结果和证据已记录
5. 已完成覆盖审计
6. 已完成正式验收或明确拒收
7. 若有验收缺陷，已回流为 BUG work item

## Acceptance Criteria

本需求文档达成后的验收标准是：

1. 后续执行 agent 能基于本文明确知道总控工作流阶段和角色分工
2. 手动调用的治理 skill 能被映射到固定流程节点，而不是继续散落在口头约定里
3. `bug -> 修复 -> 覆盖审计 -> 验收 -> 回归复盘` 闭环清晰
4. `requirement -> 规划评估 -> 执行文档 -> 开发 -> 验收` 闭环清晰
5. 能明确解释为什么首版不采用多个平级 coder 同改同一范围
