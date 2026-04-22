# 1.2.0-SNAPSHOT

下个版本跟踪目录，用于存放 `1.2.0-SNAPSHOT` 阶段的总控工作流、治理链路和执行规范。

本版本当前重点方向：

1. Worker 总控工作流 v1
2. 需求 / BUG / 验收发现问题的统一收口
3. 治理类 skills 从手动调用整理为固定流程

## 首版约束

1. **首版采用单一总控 + 少量专职 agent**：不做平级多 agent 自由协商，不做“多个 coder 同改同一块代码”的默认模式。
2. **治理 skills 先作为流程节点，不作为长期常驻 prompt**：`workspace-governance-handbook`、`foggy-plan-execution-docs`、`foggy-versioned-doc-tracking`、`plan-evaluator`、`foggy-bug-regression-workflow`、`foggy-implementation-quality-gate`、`foggy-test-coverage-audit`、`foggy-acceptance-signoff` 先被总控按阶段调用，不复制成仓库内一套平行 skill。
3. **保留关键人工闸门**：首版默认保留计划批准、正式验收结论两个关键人工确认点；高风险 BUG 或跨模块变更可追加人工确认。
4. **`pre-acceptance-check` / `post-acceptance-regression-review` 视为审计模式**：它们属于 `foggy-test-coverage-audit` 的两种工作模式，不单独建模为新 skill。

## 条目列表

- [01-worker-governance-supervisor-mode-requirement.md](./01-worker-governance-supervisor-mode-requirement.md) - 需求基线 / 总控工作流目标、角色、状态机、技能接入点
- [02-worker-governance-supervisor-mode-module-responsibility.md](./02-worker-governance-supervisor-mode-module-responsibility.md) - 模块职责 / root-controller、runtime、文档链路和治理 skill 分工
- [03-worker-governance-supervisor-mode-code-inventory.md](./03-worker-governance-supervisor-mode-code-inventory.md) - 代码触点 / 首版建议触点、只读分析区和禁止误放区域
- [04-worker-governance-supervisor-mode-implementation-plan.md](./04-worker-governance-supervisor-mode-implementation-plan.md) - 实现计划 / 分阶段落地、质量门、验收链路和回归复盘
- [05-independent-supervisor-service-and-project-directory-agent-design.md](./05-independent-supervisor-service-and-project-directory-agent-design.md) - 架构设计 / 独立总控服务与项目目录级 agent 双层模型、边界、最小协议和 MVP
