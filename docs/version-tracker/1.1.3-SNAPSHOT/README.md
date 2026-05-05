# 1.1.3-SNAPSHOT

本目录用于跟踪 `1.1.3-SNAPSHOT` 阶段围绕 Client App 接入、Navigator Java 服务业务函数注册、LangGraph Biz Worker 执行网关与 Skill 能力暴露的设计事项。

## 文档作用

- doc_type: version-index
- intended_for: platform-owner | upstream-business-owner | java-service-owner | worker-owner | skill-owner | execution-agent | reviewer
- purpose: 定义上游业务系统如何以 Client App 形式通过 Navigator Java 服务接入 Worker，并将 REST API 转换为受控内部业务函数与 LLM 可理解的 Skill 能力说明

## 当前重点方向

1. 明确交互方向：上游业务系统只与 Navigator Java 服务交互，Java 服务再与 LangGraph Biz Worker 交互。
2. 以 `client_app_id` 作为上游接入、授权、审计和路由的最小隔离单元；上游一个租户在 Navigator 侧就是一个 Client App。
3. 明确 Client App 与 Biz Worker 不是一对一关系，Java 通过 Biz Worker Pool 和路由策略分配 Worker。
4. 将上游 REST API 转换为 Navigator 内部 Business Function Registry，而不是把 REST/curl 直接暴露给 LLM。
5. 收敛 Business Function Manifest 首版字段、LLM 可见字段与 Java-only 字段边界。
6. 设计 Java Worker Gateway 内部 API：函数列表、schema 查询、函数调用、脚本运行和 suspension resume。
7. 设计 Skill/SKILL.md 如何告诉 LLM 上游业务能力、函数 allowlist、风险等级、审批规则与脚本编排示例。
8. 明确 1.1.3 只考虑账号技能、Client App Skill 和内置公共技能；角色技能延期，Client App 不能维护公共技能。
9. 明确审批、确认码、suspension、resume 和上游 callback 链路；Java 是审批与确认码所有者。
10. 定义上游业务团队、Navigator Java 团队、Worker 团队与 Skill 团队的协作交付物。

## 术语约定

- `Client App`：注册到 Navigator 的外部业务应用身份。它绑定内部 `tenantId`，持有 runtime credential，并通过 grant 获得 Skill、Business Function、LLM model config 和 Worker routing policy 的使用权限。
- `client_app_id`：Client App 的运行时主键，用于授权、审计、路由、callback 和 task/session 绑定。
- `upstream user` / `upstream_user_id`：Client App 内部的外部用户身份，仍保留 upstream 命名，表示该用户来自外部业务系统，不等同于 Navigator 内部用户。
- `upstream REST API` / `upstream callback`：外部业务系统提供或接收的业务接口，不代表 Navigator 内部注册应用实体。

## 条目列表

- [01-upstream-rest-to-business-function-and-skill-contract.md](./01-upstream-rest-to-business-function-and-skill-contract.md) - 架构规划 / 上游 REST API 到内部业务函数注册、Java-Worker 网关与 Skill 能力暴露契约
- [02-business-function-manifest-schema.md](./02-business-function-manifest-schema.md) - 契约设计 / Business Function Manifest 首版字段、可见性分层与订单关单申请样例
- [03-java-worker-gateway-api-contract.md](./03-java-worker-gateway-api-contract.md) - API 契约 / Worker 调 Java 的内部业务函数网关、鉴权、租户隔离、审计和错误模型
- [04-skill-capability-doc-template.md](./04-skill-capability-doc-template.md) - Skill 模板 / 上游业务能力、函数 allowlist、风险审批、数据探查策略和禁止事项
- [05-approval-suspension-and-upstream-callback-contract.md](./05-approval-suspension-and-upstream-callback-contract.md) - 契约设计 / 副作用操作审批、确认码、suspension、resume 和上游 callback 协议
- [06-client-app-identity-authorization-and-skill-scope.md](./06-client-app-identity-authorization-and-skill-scope.md) - 契约设计 / Client App 身份映射、接入凭证、Biz Worker Pool、Skill 作用域与审计边界
- [07-onboarding-and-runtime-lifecycle.md](./07-onboarding-and-runtime-lifecycle.md) - 生命周期设计 / Client App 接入、Worker 注册、LLM 配置归属与运行时闭环
- [08-implementation-plan.md](./08-implementation-plan.md) - 实施计划 / Client App 业务接入阶段拆解、重建状态与验收门槛
- [upstream-integration/](./upstream-integration/00-overview.md) - 上游接入文档 / SDK + 前端组件优先、REST 协议参考兜底的上游业务系统接入指南（00-overview ～ 10-demo-checklist）
  - [11-llm-sdk-usage-guide.md](./upstream-integration/11-llm-sdk-usage-guide.md) - LLM 使用手册 / 给上游 LLM coding agent 的 SDK 安装、使用边界与安全红线
  - [12-tms-business-agent-sdk-and-token-injection-plan.md](./upstream-integration/12-tms-business-agent-sdk-and-token-injection-plan.md) - Stage 10 计划 / TMS 接入所需 SDK、上游用户凭据注入、REST Adapter header 与 E2E 验证
  - [13-tms-minimal-onboarding-sample.md](./upstream-integration/13-tms-minimal-onboarding-sample.md) - TMS 样例 / SDK 初始化、`orderIdentifier` 字段约束与 mock E2E 证据
  - [14-upstream-auto-bootstrap-contract.md](./upstream-integration/14-upstream-auto-bootstrap-contract.md) - 自动化契约 / 上游 LLM 通过 manifest + env + SDK runner 自动完成 Business Agent bootstrap

## Acceptance Status

- acceptance_status: draft
- acceptance_decision: pending
- blocking_items: registry-storage-decision | worker-gateway-auth-decision | skill-allowlist-source-decision | approval-owner-decision | client-app-credential-decision | biz-worker-pool-decision
- follow_up_required: yes
