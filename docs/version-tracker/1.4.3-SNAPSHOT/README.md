---
doc_type: version-index
version: 1.4.3-SNAPSHOT
status: planning
canonical_delivery_spec: workitems/GOV-001-upstream-permission-and-trust-boundary.md
external_enablement: no
production_enablement: no
last_updated: 2026-07-18
---

# Foggy Navigator 1.4.3-SNAPSHOT

## Version Goal

1. 在 1.4.2 已有 external gate、ClientApp、task capability、Worker principal 与 ownership 基线上，形成面向不同上游接入形态的权限体系。
2. 明确区分 Navigator 实例管理主体、upstream system、ClientApp control、runtime caller、upstream user、Agent/task capability 与 Worker principal。
3. 按专属可信上游、公司 SaaS 平台和外部第三方三类场景冻结信任边界，再决定统一授权门面、数据模型、API、CLI 和迁移方案。
4. 保持 fail-closed、最小权限、凭据分层和全链路可审计，并在场景对齐中明确冻结绑定关系与资源归属的语义。

## Current Status

- phase: cross-scenario-architecture-alignment
- implementation_started: no
- canonical_status: DRAFT
- external_enablement: no
- production_enablement: no
- current_scenario: 三类上游信任模型已完成业务定位
- next_action: 以 S1/S2 为主实现目标冻结跨场景 principal/schema/API；S3 仅保留默认拒绝的设计扩展点，不进入当前实现

当前仅进行需求和架构设计，不授权修改代码、数据库、配置、Worker 或运行态。任何 external 开关均保持原有默认和语义，不能由本版本规划推导为 Provider ready 或 production ready。

## Workitems

| Workitem | Scope | Status |
|---|---|---|
| [GOV-001 上游权限体系与多场景信任边界](./workitems/GOV-001-upstream-permission-and-trust-boundary.md) | 当前权限模型基线、`foggy-world-sim` 专属实例 root、`tms-x3` SaaS 平台/租户分层，以及外部第三方的默认拒绝设计边界 | DRAFT |

## Scenario Sequence

| Scenario | Description | Status |
|---|---|---|
| S1 `foggy-world-sim` | 一个 Navigator 实例专门服务一个强绑定上游；该上游是实例 owner/root，对自有与非自有资源拥有全部控制面权限，但 ask 执行仍受 Agent/runtime/task 权限限制 | aligned |
| S2 `tms-x3` | 共享 Navigator 中的自有多租户业务 SaaS；TMS 主体在自身 upstream 范围拥有完整管理权并拆分 provisioning/security-admin 凭据；租户默认仅持有受限 runtime credential | aligned |
| S3 外部第三方 | 外部非公司主体接入 Navigator；默认不可信、最小权限，仅作为架构设计考虑，当前不提供 onboarding 或业务实现 | design-aligned / implementation-deferred |

## Global Constraints

1. `NAVIGATOR_EXTERNAL_ENABLED=true` 只表示 `/api/v1/open/**` 路由门禁开启，不表示 production ready、Provider ready 或 Worker Gateway external。
2. `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` 只控制 Gateway 是否强制完整 Worker principal，不是网络外放开关。
3. 不得通过创建额外 Worker、BizWorkerIdentity 或 WorkerPool member 修复 Codex Physical Worker 路由问题。
4. ClientApp runtime credential 不得承担实例管理、跨 ClientApp 修复或 break-glass 职责。
5. 资源绑定、资源操作授权和资源所有权是三个不同概念；绑定不隐式转移 owner，但 `foggy-world-sim` instance root 可通过独立、显式、可审计动作转移 owner。
6. ask 的最终能力始终取 Agent/runtime grant、task capability、Worker route 和执行策略的交集，上游控制面权限不得自动下沉为任务权限。
7. S1 中 `foggy-world-sim` 拥有 Navigator 实例内全部权限；fail-closed、凭据分层、secret 不回显、审计不可篡改、readiness 与 production gate 是所有主体均不可绕过的系统不变量，不是被扣留的权限。
8. root 权限属于 `foggy-world-sim` 主体；可由专属 root credential 或具名 root ClientApp 表达，但普通 ClientApp、runtime credential、task token 和 Worker credential 不自动继承。
9. 每个 Navi 环境实例都是独立授权域；root、platform admin、ClientApp、grant、credential、session、task token 和 Worker principal 均不得跨 `navigatorInstanceId` 继承或复用，即使两个实例由同一用户、服务或公司管理。
10. S2 中 `tms-x3` 平台管理主体可以管理其 upstream system 范围内的租户 ClientApp 和 Worker 分配；租户 ClientApp credential 不能继承该平台权限或跨 tenant/ClientApp。
11. ClientApp control credential 与 runtime key/secret 必须是不同 credential lane；租户默认运行面需求不得通过发放 upstream-admin 或宽泛 control credential 实现。
12. S2 使用共享 Navigator；`tms-x3` 是 upstream-system-scoped SaaS platform admin，不是 Navigator instance root。
13. S2 租户默认只获得 runtime key/secret；limited control 仅在明确自助需求下另行签发。
14. S2 Worker 默认 owner 为 `UPSTREAM_SYSTEM/tms-x3`，通过 grant/binding 分配给 tenant/ClientApp，不因 allocation 隐式转移 owner。
15. `tms-x3` 主体在自身 upstream 范围拥有完整管理权，但日常 provisioning 与破坏性 security-admin 必须使用不同 credential/profile。
16. 租户 limited control 仅允许管理本 ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已授权资源之间的 grant/binding；不得扩大 scope、继续 delegation，或管理 credential、Worker、其他 ClientApp/tenant。
17. TMS 可完整管理 TMS-owned Worker 的 create/update/reassign/delete；删除、owner transfer 等破坏性动作必须 step-up、影响预览和审计。
18. S3 外部第三方不是 instance root 或 SaaS platform admin；当前不签发第三方 credential、不开放第三方 onboarding，也不因设计预留启用任何 external/production 路径。
19. 未来第三方接入默认只能获得 exact upstream system + tenant + ClientApp 的最小 runtime 能力；control 必须按独立需求审批，upstream-admin、Worker 生命周期、跨 owner/tenant 和 production promotion 默认禁止。
20. 上游 trust profile 只能限制可授予权限上限，不能替代认证、授权或审计；未知、缺失或冲突的 profile 必须 fail closed。

## Evidence Boundary

- 本版本目录建立于 2026-07-18。
- 当前内容来自代码、配置、CLI help、专项 SKILL、1.4.2 文档及 #151/#152 issue 的只读调研。
- 2026-07-18 当前工作树复核确认：平台/Gateway 开关默认仍为 `false`，Java Gateway client 仍仅传播 task token，三类 Worker external profile 仍因执行策略 pending 而 fail closed，#151/#152 仍为 OPEN，CLI 1.0.18/1.0.21 漂移仍存在。
- 本轮没有执行测试、启动或重启服务，也没有修改凭据、资源绑定或 external 配置。
- 1.4.2 的历史测试与 issue 现场记录只作为设计输入，不作为 1.4.3 已实现或已验收证据。
