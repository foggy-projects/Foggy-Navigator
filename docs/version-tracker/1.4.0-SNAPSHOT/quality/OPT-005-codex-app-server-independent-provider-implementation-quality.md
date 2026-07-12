---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.4.0-SNAPSHOT
target: OPT-005 independent Codex provider and OPT-006 Endpoint/Runtime integration
status: reviewed
decision: ready-with-risks
reviewed_by: Codex
reviewed_at: 2026-07-12
follow_up_required: yes
production_enablement: not-approved
---

# Implementation Quality Gate

## Background

本闸门审查 SDK Codex 与 Codex App Server 从 Worker Backend、A2A Provider、Session/Task、ModelConfig、Endpoint/Runtime、PC/Mobile 到 OpenAPI 的完整拆分。结论只覆盖当前源码的隔离交付，不批准 P3 生产路由。

## Check Basis

- [OPT-005 requirement](../workitems/OPT-005-codex-app-server-independent-provider.md)
- [OPT-005 implementation plan](../workitems/OPT-005-codex-app-server-independent-provider-plan.md)
- [OPT-006 Endpoint/Runtime workitem](../workitems/OPT-006-codex-app-server-endpoint-runtime-sync.md)
- [Automated evidence](../evidence/OPT-005-independent-provider-verification-v1.json)
- [Real-chain evidence](../evidence/opt-005-provider-fullchain-20260712-033210-be7f26ac/pc-sse-fullchain.json)

## Changed Surface

- `navigator-common` / `navigator-spi` / `metadata-config-module`: Backend/Provider 映射、模型策略、具体模型与 Worker capability 校验。
- `session-module`: Provider 绑定、跨 Provider mismatch、所有 Task 操作按持久化 Provider 路由。
- `addons/codex-worker-agent`: 独立 SDK/App Provider、Endpoint-synced Runtime、Task/SSE/provider-state 投影。
- `addons/claude-worker-agent` / `business-agent-module`: Agent 默认模型、OpenAPI launch/readiness、上游 provisioning。
- `tools/codex-agent-worker` / `tools/codex-app-server-worker`: 各自协议、Ultra 边界与 no-fallback。
- `packages/navigator-frontend` / `packages/foggy-mobile`: 模型目录、Provider badge、跨 Provider 新会话、Endpoint/Runtime 与原生子任务。
- migration/docs/tests: provider 分类、affinity、MySQL 双版本与版本化证据。

## Quality Checklist

| Check | Result | Notes |
|---|---|---|
| scope conformance | pass | `codex-biz-worker`、其他 Provider 与 P7 retirement 未被扩展 |
| provider isolation | pass | Backend/Provider 一一映射；真实双向故障均无 fallback |
| session integrity | pass | Provider 首次绑定后不可跨 Provider resume；UI 改为显式新 Session |
| configuration ownership | pass | Endpoint 是唯一 App endpoint/token 写源；无手工 Runtime 注册入口 |
| model capability | pass | SDK Ultra 拒绝；App Ultra 限 Sol/Terra；具体 defaultModel 与每个 available model 按 Worker 过滤 |
| runtime affinity | pass | App task 固定 runtime/revision/instance；SDK 固定 `SDK_EXEC` |
| security/privacy | pass | Endpoint token 加密且只回显 configured；owner/security route 与 secret scan 通过 |
| error handling | pass | Endpoint/runtime/Worker/model/grant 缺失均 fail closed，不向另一 Provider 改派 |
| concurrency/race | pass | PC availability request 有 sequence guard；pending 时禁止 Agent 保存；Task provider 持久化后路由 |
| compatibility | pass | 新 SPI 三参数方法为 default；实现覆盖具体模型校验，不破坏仓外旧实现源码编译 |
| responsive UX | pass | desktop/320px Playwright、tag overflow、长 runtime identity 与跨 Provider modal 通过 |
| test/build | pass | Java/Worker/PC/Mobile/migration/Playwright/package 全部通过 |
| documentation | pass | requirement、plan、progress、quality、coverage、acceptance 与 JSON 证据已回写 |

## Findings

- 初始 Endpoint 管理接口因 Security matcher 缺口返回 403；已补精确路径与子路径测试。
- capability tester 注入曾形成循环依赖；以延迟解析收敛并由完整 Spring reactor 验证。
- migration 初版混淆 Biz/Session provider；现按 SDK/Biz/App 独立分类，并在 MySQL 8.0/8.4 验证。
- SDK Thread、App Thread 与 A2A context 曾有跨 Provider 泄漏风险；现 thread 查找和 context persistence 均按 Provider scoped。
- SDK 默认 reasoning 一度被强制改为 Medium；已恢复原有默认，仅拒绝显式 Ultra。
- App Task provider 曾可能覆盖显式 provider，部分交互测试也误走 SDK；均已修正并补真实 App provider 测试。
- ModelConfig 只检查 base model 会泄漏 unsupported Ultra；现 worker-scoped DTO、Agent create/update 和 OpenAPI readiness 均检查具体模型。
- OpenAPI readiness 曾检查旧 Agent Worker，而实际 launch 使用 workspace Worker；现两者统一并检查 RESTRICTED ModelConfig grant。
- PC Agent capability pending 可提前保存；按钮和 handler 双重阻止，后端仍作最终校验。
- Mobile 跨 Provider 分支缺页级保护；实际页面现复用可测试执行函数，覆盖确认、取消和同 Provider 续接。
- 320px Runtime header 的 tag 曾收缩、桌面证据曾保留焦点 Tooltip；布局与验收重开流程均已修正。

## Risks / Follow-ups

- P3 的 50 terminal tasks、72 小时、2 次实例轮换和 release owner 批准仍为零，不得推断生产稳定性。
- `codex-app-server-worker` 仍锁定 CLI `0.144.1`；CLI/schema/capability 变化必须重新同步 revision 并重跑验收。
- 旧 Session/Thread 跨 Provider 恢复明确不支持；一次性 migration 只分类 Task，不提供兼容路由。
- Worker-scoped Codex ModelConfig 会对每个 catalog variant 做本地 capability 查询；目录很小，若未来动态目录显著扩大需评估批量接口。

## Recommended Next Skills

- 进入 `foggy-test-coverage-audit`，再执行 `foggy-acceptance-signoff`。
- 生产 canary 前重新使用 `foggy-implementation-quality-gate` 审查 P3 配置和真实目标环境。
- 新的协议/迁移/授权回归进入 `foggy-bug-regression-workflow`。

## Decision

- decision: ready-with-risks
- decision_scope: OPT-005/006 isolated implementation
- blockers: none
- production_enablement: not-approved
- follow_up_required: yes
