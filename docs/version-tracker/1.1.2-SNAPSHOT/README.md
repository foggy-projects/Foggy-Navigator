# 1.1.2-SNAPSHOT

本目录用于跟踪 `1.1.2-SNAPSHOT` 阶段围绕 LangGraph Biz Worker、Skill Runtime 与自然语言业务能力编排的后续设计事项。

## 文档作用

- doc_type: version-index
- intended_for: platform-owner | worker-owner | execution-agent | reviewer
- purpose: 定义 `1.1.2-SNAPSHOT` 的设计条目索引，避免与 `1.3.0-SNAPSHOT` Gemini Worker 版本线混淆

## 当前重点方向

1. LangGraph Biz Worker 自然语言触发 Skill 的标准契约
2. Root-level LLM planner 与标准工具边界
3. Skill 与业务工具的职责隔离
4. 业务脚本编排、审批确认码与脚本资产沉淀
5. 基于 Compose Script P2.6 暂停恢复原语设计 Navigator 侧业务动作审批适配

## 条目列表

- [01-langgraph-biz-skill-tool-contract.md](./01-langgraph-biz-skill-tool-contract.md) - 架构规划 / Skill、标准工具、自然语言触发与业务工具边界
- [02-business-script-engine-and-function-manifest.md](./02-business-script-engine-and-function-manifest.md) - 架构规划 / 业务脚本引擎、Business Function Manifest、审批确认码与脚本复用
- [03-compose-script-business-action-adapter-requirement.md](./03-compose-script-business-action-adapter-requirement.md) - 集成需求 / 基于 Compose Script P2.6 的业务动作审批适配、事件桥接与脚本复用
