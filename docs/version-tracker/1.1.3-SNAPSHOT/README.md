# 1.1.3-SNAPSHOT

本目录用于跟踪 `1.1.3-SNAPSHOT` 阶段围绕上游业务系统接入、Navigator Java 服务业务函数注册、LangGraph Biz Worker 执行网关与 Skill 能力暴露的设计事项。

## 文档作用

- doc_type: version-index
- intended_for: platform-owner | upstream-business-owner | java-service-owner | worker-owner | skill-owner | execution-agent | reviewer
- purpose: 定义上游业务系统如何通过 Navigator Java 服务接入 Worker，并将 REST API 转换为受控内部业务函数与 LLM 可理解的 Skill 能力说明

## 当前重点方向

1. 明确交互方向：上游业务系统只与 Navigator Java 服务交互，Java 服务再与 LangGraph Biz Worker 交互。
2. 将上游 REST API 转换为 Navigator 内部 Business Function Registry，而不是把 REST/curl 直接暴露给 LLM。
3. 收敛 Business Function Manifest 首版字段、LLM 可见字段与 Java-only 字段边界。
4. 设计 Java Worker Gateway 内部 API：函数列表、schema 查询、函数调用、脚本运行和 suspension resume。
5. 设计 Skill/SKILL.md 如何告诉 LLM 上游业务能力、函数 allowlist、风险等级、审批规则与脚本编排示例。
6. 明确审批、确认码、suspension、resume 和上游 callback 链路；Java 是审批与确认码所有者。
7. 定义上游业务团队、Navigator Java 团队、Worker 团队与 Skill 团队的协作交付物。

## 条目列表

- [01-upstream-rest-to-business-function-and-skill-contract.md](./01-upstream-rest-to-business-function-and-skill-contract.md) - 架构规划 / 上游 REST API 到内部业务函数注册、Java-Worker 网关与 Skill 能力暴露契约
- [02-business-function-manifest-schema.md](./02-business-function-manifest-schema.md) - 契约设计 / Business Function Manifest 首版字段、可见性分层与订单关单申请样例
- [03-java-worker-gateway-api-contract.md](./03-java-worker-gateway-api-contract.md) - API 契约 / Worker 调 Java 的内部业务函数网关、鉴权、租户隔离、审计和错误模型
- [04-skill-capability-doc-template.md](./04-skill-capability-doc-template.md) - Skill 模板 / 上游业务能力、函数 allowlist、风险审批、数据探查策略和禁止事项
- [05-approval-suspension-and-upstream-callback-contract.md](./05-approval-suspension-and-upstream-callback-contract.md) - 契约设计 / 副作用操作审批、确认码、suspension、resume 和上游 callback 协议

## Acceptance Status

- acceptance_status: draft
- acceptance_decision: pending
- blocking_items: registry-storage-decision | worker-gateway-auth-decision | skill-allowlist-source-decision | approval-owner-decision
- follow_up_required: yes
