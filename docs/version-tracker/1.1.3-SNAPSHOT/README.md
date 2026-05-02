# 1.1.3-SNAPSHOT

本目录用于跟踪 `1.1.3-SNAPSHOT` 阶段围绕上游业务系统接入、Navigator Java 服务业务函数注册、LangGraph Biz Worker 执行网关与 Skill 能力暴露的设计事项。

## 文档作用

- doc_type: version-index
- intended_for: platform-owner | upstream-business-owner | java-service-owner | worker-owner | skill-owner | execution-agent | reviewer
- purpose: 定义上游业务系统如何通过 Navigator Java 服务接入 Worker，并将 REST API 转换为受控内部业务函数与 LLM 可理解的 Skill 能力说明

## 当前重点方向

1. 明确交互方向：上游业务系统只与 Navigator Java 服务交互，Java 服务再与 LangGraph Biz Worker 交互。
2. 将上游 REST API 转换为 Navigator 内部 Business Function Registry，而不是把 REST/curl 直接暴露给 LLM。
3. 设计 Java 侧业务函数适配层：鉴权、权限、审计、审批、幂等、错误归一化与响应裁剪。
4. 设计 Worker 侧标准业务函数工具：查询函数、获取 schema、调用函数、运行脚本、暂停恢复。
5. 设计 Skill/SKILL.md 如何告诉 LLM 上游业务能力、可用函数边界、审批规则与脚本编排示例。
6. 定义上游业务团队、Navigator Java 团队、Worker 团队与 Skill 团队的协作交付物。

## 条目列表

- [01-upstream-rest-to-business-function-and-skill-contract.md](./01-upstream-rest-to-business-function-and-skill-contract.md) - 架构规划 / 上游 REST API 到内部业务函数注册、Java-Worker 网关与 Skill 能力暴露契约

## Acceptance Status

- acceptance_status: draft
- acceptance_decision: pending
- blocking_items: business-function-registry-schema | java-worker-internal-gateway-api | skill-capability-doc-template | approval-suspension-contract
- follow_up_required: yes
