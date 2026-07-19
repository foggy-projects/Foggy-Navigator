---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-001
status: APPROVED
overall_delivery_status: PARTIALLY_ACCEPTED_GATED
canonical: true
execution_mode: ultra
scenario_s1_status: aligned
scenario_s2_status: aligned
scenario_s3_status: design-aligned-implementation-deferred
primary_implementation_scope: s1-s2
authorization_schema_status: aligned
architecture_review_status: passed-with-complexity-guardrails
p0_5_status: complete
approved_by: project-owner-user-confirmed
approved_at: 2026-07-19
approved_execution_scope: p1b-a-typed-management-auth-core; progressive-p1b-to-p4-subject-to-explicit-stage-gates
p1a_status: accepted
p1a_acceptance_status: accepted
p1a_repair_ticket: BUG-002
p1a_repair_status: accepted
p1a_observer_bff_disposition: catalog-and-test-only
p1a_resignoff_required: false
p1b_status: APPROVED
p1b_current_slice: p1b-a-typed-management-auth-core
p1b_a_status: ACCEPTED
p1b_a_acceptance_status: accepted
p1b_a_delivery_spec: GOV-001-p1b-a-typed-management-auth-core.md
p1b_a_acceptance_record: evidence/GOV-001-p1b-a-independent-signoff.md
p1b_b0_status: ACCEPTED
p1b_b0_acceptance_status: accepted
p1b_b0_delivery_spec: GOV-001-p1b-b0-preseed-inventory-and-owner-approval.md
p1b_b0_acceptance_record: evidence/GOV-001-p1b-b0-independent-signoff.md
p1b_seed_status: offline-preseed-validator-accepted-real-facts-and-owner-approved-mapping-pending
p1c_a_status: ACCEPTED
p1c_a_acceptance_status: accepted
p1c_a_delivery_spec: GOV-001-p1c-a-cli-skill-operator-ux.md
p1c_a_acceptance_record: evidence/GOV-001-p1c-a-independent-signoff.md
p1c_status: p1c-a-accepted-fixture-only-no-route-cutover
p1b_b_gate_status: DRAFT_BLOCKED_PENDING_NAMED_SECURE_SOURCE_AND_FOUR_EYES_APPROVAL
owner_decision_intake: GOV-001-owner-decision-intake-adr-packet.md
p2_status: pending-external-identity-routing-decisions
p3_status: pending-production-infrastructure-owner-evidence
p4_status: pending-telemetry-and-release-owner-window
open_questions: []
---

# Delivery Spec: 上游权限体系与多场景信任边界

## Document Purpose

- intended_for: normal-analysis / future-ultra-implementation / independent-signoff
- purpose: 记录当前权限模型事实，并按真实上游场景冻结上游系统、ClientApp、runtime、Agent/task 和 Worker 的权限边界。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-upstream-permission-and-trust-boundary.md`
- current_boundary: 2026-07-19 Owner 已批准把 Spring Boot 3.4.2 discovery-links 的现有 `GET /actuator` ingress 纳入 deployment-aware catalog，保持 415 条并接入 P1A shadow/audit；BUG-002 required-section amendment 后 source/evidence SHA-256 为 `ef4c32ac4ca25ee695dff7bacd9845301266807d71fbcafe35ebba4872aadc7d`。同时关闭 discovery-links，接受 `/actuator` 从 HTTP 200 links 响应变为 404 的唯一兼容例外，子 Actuator endpoint 保持原有行为。P1A、fixture-only P1B-A、pure-offline P1B-B0 与 fixture-only P1C-A 均已 independently accepted；B0 只验证 synthetic/securely supplied envelope 的结构与隔离，P1C-A 只验证 typed-management CLI/SKILL 可见性，均不验证真实事实或批准。P1B-B/P2/P3/P4 的具名 owner 输入已汇总进 [Owner Decision Intake / ADR Packet](./GOV-001-owner-decision-intake-adr-packet.md)，仍是 `PENDING_OWNER_INPUT`。2026-07-19 Project Owner 授权继续进行 P1–P4 的 gate preparation 与 owner intake；真实 seed、route-family cutover、Gateway strict、external/production 或任何跨系统基础设施事实仍须满足本 work item 的逐阶段 gate，不能因该授权自动开启。

## Goal

- version_goal: 为 1.4.3 建立可区分专属可信上游、公司 SaaS 平台和外部第三方的上游权限体系；主实现面向 S1/S2，S3 仅保留安全扩展边界。
- target_outcome: 每类上游都能明确回答“用什么主体和凭据、可管理哪些资源、能否操作非自有资源、是否改变 owner、ask 最终由谁授权、哪些系统安全不变量不可绕过”。

## Scope

- in_scope:
  - 平台路由门禁、认证、授权、资源归属、task capability、Worker Gateway、Worker readiness 与生产边界的分层基线。
  - upstream system、ClientApp、upstream user、owner、Directory、WorkerHost、Physical Worker、BizWorkerIdentity 与 WorkerPool 的术语和关系。
  - 上游场景 S1：`foggy-world-sim` 使用一个专门为其服务的 Navigator 实例。
  - 专属上游根管理权、ClientApp control/runtime lane、非自有资源完整动作集和 ask 权限交集。
  - 上游场景 S2：`tms-x3` 作为多租户业务 SaaS 平台，为租户管理 ClientApp、凭据和 Worker 分配。
  - SaaS 平台管理主体、tenant、ClientApp control/runtime credential、tenant user 和共享/专属 Worker 的隔离关系。
  - 上游场景 S3：外部第三方作为默认不可信主体的最小权限设计边界，以及未来接入所需的 fail-closed 扩展点。
  - 三类 trust profile 的权限上限、凭据 lane 和不得相互继承的约束；trust profile 本身不构成授权。
  - 后续授权门面、CLI、SKILL、runbook、测试和审计的交付要求；CLI/SKILL 必须让上游明确知道当前 principal、credential lane、权限 scope 和禁止动作。
  - 已 accepted 的 P1A：六类 typed-management aggregate 的 additive schema、服务端 deployment identity、canonical sparse `AuthorizationContextV1` / `PolicyDecisionV1`、source-controlled action/policy catalog、legacy adapters、decision audit、route registration 和 shadow evaluator/diff。
  - 已 accepted 的 P1B-A：typed management credential/token verification、credential-source conflict 拒绝、management canonical guard、control exchange、security-action authorization、whoami、permissions 与 non-binding explain；仅用 test fixture 验证，不 seed 真实 S1/S2 主体或凭据。
  - 已 accepted 的 P1C-A：typed-management-only CLI `whoami`、`permissions`、non-binding explain、tri-state config check、help/FAQ/runbook 和 canonical-manifest provenance；不执行 route-family cutover、真实事实或凭据 lifecycle。
- affected_modules:
  - `user-auth-module`
  - `business-agent-module`
  - `session-module`
  - `addons/claude-worker-agent`
  - `navigator-open-sdk`
  - `tools/navigator-upstream`
  - `tools/navigator-upstream-cli`
  - `tools/navigator-chat-observer-bff`
  - Worker implementations and Worker Gateway clients
  - `.agents/skills/navigator-runtime-provisioning`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies:
  - `foggy-world-sim`、`tms-x3` 仅作为需求方和未来联调方；当前不授权修改其仓库。

## Non-Goals

- out_of_scope:
  - 当前不执行 P1B-B 及以后：不 seed/签发真实 S1/S2 typed principal/credential，不实现 credential/grant/tenant-authority lifecycle 写 API、既有 route-family enforcement cutover 或其余 UI/真实 credential lifecycle operator UX。
  - 本轮不启用 Open API external、Worker Gateway strict、Worker external 或 production 路由。
  - 本轮不实现 S1、S2 或后续上游的最终权限模型。
  - 当前版本主实现目标仅为 S1/S2；不实现 S3 外部第三方 onboarding、credential 签发、管理 API、真实流量接入或 production 发布。
  - 不实现上游跨多个 Navi 实例的统一路由、故障切换、配置同步、凭据聚合或集中托管；同一上游接入多个实例时，由上游维护多套独立连接 profile 并选择目标实例。
  - 不以通用 RBAC/ABAC 平台为预设实现方向。
  - 不把专属实例上游权限自动推广到共享 Navigator 实例。
- do_not_touch:
  - 不读取或记录真实 credential、claim token、账号或密钥。
  - 不修改 1.4.2 历史状态或将其测试证据改写为 1.4.3 证据。
  - 不通过新建 Worker、BizWorkerIdentity、WorkerPool member 或替代 Worker 修复 Codex Physical Worker 路由。
  - 不把请求体中的 tenant、owner、userId、cwd 或 reviewedBy 当成可信身份来源。

## Current Permission Baseline

当前允许判定是以下条件的交集，而不是单个 external 开关：

```text
入口/Profile
  AND 调用主体认证
  AND tenant/owner/ClientApp/upstream-user 授权
  AND task capability/function grant
  AND exact Worker/lease/route
  AND Worker execution readiness
  AND deployment/production approval
```

### Layered panorama

| Layer | Security question | Current mechanisms | Required invariant |
|---|---|---|---|
| 1. Platform route/profile gate | 请求是否能到达业务 Controller | launcher `/api/v1/open/**` gate、独立 deployment network binding、loopback/ACL、Ingress/TLS | route reachable 只表示入口可达，不代表调用者已获授权；不同 deployment 不继承彼此 filter |
| 2. Authentication | 谁在调用 | Navigator user/operator、upstream-admin、ClientApp control/runtime、Worker credential | principal、credentialId、状态、过期和撤销必须可确定 |
| 3. Authorization and ownership | 该主体能否操作目标资源 | tenant、upstreamSystemId、ClientApp、upstream user、`ownerType + ownerId`、grant/binding | 不能以 tenant 相同或请求体 owner 字段代替资源授权 |
| 4. Runtime capability | 该次 task 能做什么 | task token v2、audience、generation、TTL、function snapshot、Agent/model/directory grant | capability 只能收窄长期主体权限，不能扩权 |
| 5. Worker route and Gateway | 哪个执行者可代表该 task 调用 Gateway | exact worker、lease、physical/pool route、Worker credential | task capability 与 Worker principal 必须求交；歧义即拒绝 |
| 6. Execution policy | Worker 是否允许真实执行 | canonical workspace、tools、sandbox、approval、network、credential isolation | readiness 缺任何安全项均不得执行 external workload |
| 7. Deployment/production | 当前部署能否承担外部生产流量 | artifact identity、migration、rollback、audit、production approval | 不存在由单个布尔开关推导 production ready 的路径 |

### Switch and readiness semantics

| Switch / state | Exact meaning | Subject and scope | Default/current state | Prohibited interpretation or use |
|---|---|---|---|---|
| `NAVIGATOR_EXTERNAL_ENABLED` | 只控制规范路径 `/api/v1/open` 与 `/api/v1/open/**` 是否进入 Controller；关闭时返回 `503 / EXTERNAL_SURFACE_DISABLED` | Navigator 平台 Open API routing surface | 默认 `false` | 不表示 production ready、Provider ready、Worker ready 或 Worker Gateway external；不覆盖 upstream-admin、Gateway 或其他 Controller |
| `/api/v1/health/external-surface` | 报告平台 Open API routing gate 状态 | 运维/联调观察者 | `readinessScope=platform-routing-only`，`providerReadinessAssessed=false`，`productionReady=false` | 不得把 `surfaceReady=true` 当生产批准或 Provider 验收 |
| `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` | 控制 Gateway 是否强制完整 Worker principal headers | Worker 调用 Navigator Gateway 的入站认证 | 默认 `false` | 不是监听地址、Ingress、TLS 或网络暴露开关；当前调用端未完整传播 headers 时不得直接开启 |
| Gateway token-only compatibility | strict=false 且三个 Worker principal header 全部缺失时，允许有效 task token 进入 internal-dev 兼容路径 | 仅受信内部网络 | 当前兼容保留 | 不能用于非可信网络；partial、blank、legacy header 不能回退到此路径 |
| `BIZ_WORKER_EXTERNAL_ENABLED` | LangGraph Biz Worker 的外部运行意图 | 单个 LangGraph Worker 进程 | 默认 `false`；true 时仍因 `EXTERNAL_EXECUTION_POLICY_PENDING` unready | 不表示 Gateway strict、Provider ready 或生产可用 |
| `CODEX_WORKER_EXTERNAL_ENABLED` | Codex SDK Worker 的外部运行意图 | 单个 Codex SDK Worker 进程 | 默认 `false`；true 时仍 unready | 不表示 Codex Provider 或 Navigator 平台已可外放 |
| `CODEX_APP_SERVER_EXTERNAL_ENABLED` | Codex app-server Worker 的外部运行意图 | 单个 app-server Worker 进程 | 默认 `false`；true 时仍 unready | 不表示 app-server 可以接受公网业务请求 |
| production enablement | 网络、身份、执行策略、凭据、审计、迁移、回滚和独立签收的组合结论 | 整个部署 | 当前没有单一 enablement 开关，且无 production ready 证据 | 禁止用任何 external flag、health 200 或源码测试替代生产签收 |

Gateway strict 路径要求以下四项共同存在并匹配：

1. `X-Task-Scoped-Token`
2. `X-Navigator-Worker-Id`
3. `X-Navigator-Worker-Credential`
4. `X-Navigator-Worker-Lease-Id`

只要出现任一 Worker principal header，就必须三项完整；partial、blank 或 legacy `X-Worker-Id` 一律拒绝。当前 Java `WorkerGatewayClient` 仍只发送 `X-Task-Scoped-Token`，因此 strict server gate 与现有调用链尚不兼容。

### API surface semantics

| API surface | Intended caller and credential | Required authorization | Not controlled by |
|---|---|---|---|
| `/api/v1/open/**` | ClientApp runtime caller | platform gate + runtime credential/access token + tenant/ClientApp/upstream-user/resource grant | Gateway external flag、Worker external flag、production approval |
| Observer BFF `/api/v1/observer/**` 及其代理的 `/api/v1/open/**` | 当前是无入站 observer identity 的本地浏览器调用，BFF 复用 server-held 或 login-derived Navigator runtime authority | target 必须是 exact observer session + runtime principal + task/attachment capability；当前只能 local/trusted dev | launcher `ExternalSurfaceGateFilter`；`NAVIGATOR_EXTERNAL_ENABLED` 仅作用于后续下游 Navigator `/open` 请求 |
| `/api/v1/upstream-admin/**` | upstream system control principal | upstream-admin key + upstreamSystemId + authorized tenants + namespace + scopes | `NAVIGATOR_EXTERNAL_ENABLED` |
| ClientApp control APIs | exact ClientApp control principal，或具名平台管理例外 | exact tenant + ClientApp + control scopes + resource ownership/grant | runtime token；普通 upstream user declaration |
| `/internal/worker-gateway/v1/**` | task-bound Worker | task capability；strict profile 再加 exact Worker credential/lease/route | platform Open API gate |
| 其他 `/api/**` | Navigator user、operator 或专用 principal | `@RequireAuth`、resolver、Controller/Service ownership 的组合 | 不能仅根据 Spring Security matcher 判定匿名或已授权 |

Spring Security 当前对多类 API 使用 `permitAll`，而 `AuthInterceptor` 只填充上下文、不主动阻断。这里的 `permitAll` 不等于匿名可用，但真实 enforcement 分散在 Filter、AOP、Controller、resolver 和 Service；新增端点漏接任何一层都会形成高风险缺口。Observer BFF 是独立 Spring Boot deployment，不应套用 launcher 的这条解释：其当前 12 条 route 没有入站 observer auth/session，必须按独立 ingress 风险处理。

### Credential lanes

| Lane | Principal / header | Intended use and scope | Current lifetime/default | Prohibited use or risk |
|---|---|---|---|---|
| Navigator operator | Navigator instance owner/operator；`X-Navi-Operator-Key` / `X-Navi-Operator-Api-Key` | bootstrap approval、admin credential 管理、break-glass、根安全控制 | 部署配置 SHA-256；不是普通业务 TTL credential | 不下发给上游业务应用；不用于日常 provisioning、readiness、ask 或 runtime |
| Upstream admin | upstream system control principal；`X-Navi-Admin-Key` / `NAVI_ADMIN_API_KEY` | ClientApp、upstream-owned/shared resource、WorkerHost、Directory、Model、Agent、grant 管理 | request/claim/admin credential 默认 24h；可经显式高风险审批产生无过期 key | 不能当 operator、runtime 或 task token；不得默认拥有其他 upstream system/instance 的资源 |
| `INSTANCE_ROOT`（target） | 独立 instance-scoped principal；S1 root subject 为 `foggy-world-sim`，其来源 upstream 仅用于身份/审计 | 该实例全部控制面动作，包括跨 upstream/tenant/owner 管理、owner transfer、delete/revoke、credential rotation、grant delegation 和 instance promotion | 同一 principal 强制拆分 `INSTANCE_ROOT_CONTROL` + `INSTANCE_ROOT_SECURITY`；internal-dev 采用长 TTL/受信 loopback control 直用兼容，production 强制短 access/action token，统一执行轮换、撤销和 recovery 约束 | 不得退化为普通 upstream-admin 或具名 ClientApp，不得跨实例、冒充其他 upstream 的 runtime/control principal、复用为 runtime/task/Worker credential，或绕过 readiness、production gate、审计 |
| `SAAS_PLATFORM`（target） | 独立 typed platform principal；S2 subject 为 `tms-x3` 平台，绑定 exact instance + `upstreamSystemId=tms-x3` + versioned platform grant | 该 upstream 的 `UPSTREAM_OWNED` tenant 范围内完整平台管理；`SAAS_PROVISIONING` 执行日常 create/update/assign，`SAAS_SECURITY_ADMIN` 执行 delete/transfer/rotate/revoke/delegate/recovery | 复用 S1 的 internal-dev/production TTL/token/rotation/revoke 基线；credential/token 引用 server-side platform grant/version，不固化可扩张 tenant wildcard | 不得跨 upstream/instance、成为 instance root、用于 ask/runtime，或由 legacy upstream-admin/tenant ClientApp 自动继承；security lane 不能由 provisioning 回退 |
| ClientApp control | exact tenant + ClientApp；`X-Client-App-Control-Key` / `NAVI_CONTROL_API_KEY` | 单 ClientApp 的 Model、Agent、Directory、grant、binding 和 credential 管理 | 当前发放可显式设置 expiry，缺省可形成长期 credential | 不能跨 ClientApp/tenant；不能用于 ask；`CONTROL_PLANE_ALL` 例外必须限制主体并审计 |
| Runtime long-term credential | exact tenant + ClientApp；app key/secret | 换取短期 runtime access token | 当前缺省可无过期；应轮换和可撤销 | 不直接承担控制面管理；不得写入仓库、日志或不受控浏览器存储 |
| Runtime access token | exact tenant + ClientApp；`NAVI_CLIENT_APP_ACCESS_TOKEN` | runtime-token 后的 readiness、owner-smoke、ask、messages/live smoke | 默认及硬上限 30m；DB 仅存 hash | 不得创建资源、grant、binding、WorkerHost 或跨 ClientApp 调用 |
| Task capability | exact task/session/skill/user/worker/lease/function scope；`btt_` | Worker Gateway 单任务最小能力 | 默认 30m，硬上限 60m；audience=`WORKER_GATEWAY` | 不是长期身份；不能用于控制面或其他 task；当前 user assurance 只能标为 `client-app-delegated` |
| Worker principal | current strict lane 由 exact Worker identity credential + lease 解析；现行 `bwc_` 来自 BizWorkerIdentity | Worker 调用 Navigator Gateway 的入站 principal，并与 task route 求交 | 默认 30d；允许 60s 至 365d；strict path 拒绝 v0 registration token | 不能作为 Navigator 调用 Worker 的出站 bearer；不能据此要求 Codex Physical Worker 新建 Biz identity；不能暴露给模型可控 CLI/MCP/子进程 |

### Identity, resource, and routing semantics

| Concept | Exact meaning | Authorization invariant | Prohibited interpretation |
|---|---|---|---|
| `tenant` | Navigator 内的最高级数据隔离键；upstream-admin 可被授权多个 tenant | tenant 一致后仍需校验 owner、ClientApp、upstream user 和 grant | tenant 相同不等于资源可见或可操作 |
| `ClientApp` | tenant 内的上游应用隔离主体；拥有 control/runtime lane 和业务 grant | exact tenant + ClientApp + ACTIVE 状态 | 不是 Navigator operator，也不天然代表整个 upstream system |
| upstream user | 当前由已认证 ClientApp 代办声明，再经 mapping/grant 限制的业务用户 | assurance 只能标为 `client-app-delegated`；external 前需另做强证明决策 | 不能把请求中的 userId 当独立认证事实 |
| `owner` | 资源所有权维度；当前 enum 为 `PLATFORM`、`UPSTREAM_SYSTEM`、`CLIENT_APP`、`UPSTREAM_USER` | 必须同时校验 `ownerType + ownerId` | 不能用 tenant、binding 或 caller 提交字段替代 owner record |
| `Directory` | Navigator 授权的 canonical workspace 资源 | tenant、owner、visibility scope、ClientApp/user、Worker、resolver、readOnly、allowed path | 不能把请求中的 cwd 当权威路径，也不能靠重绑 Directory 绕过 readiness |
| `WorkerHost` | 宿主和 Physical Worker 的 provisioning 聚合/manifest 概念 | apply/verify/update 必须指向明确宿主和既有 Worker | 不是第二套执行身份或自动 Pool member |
| Physical Worker | 实际宿主、Directory 和执行 capability 的载体 | Codex 通过既有 Physical Worker 的 `claudeCode.codexConfig` 解析 | 不要求新增 BizWorkerIdentity 或 WorkerPool membership |
| `BizWorkerIdentity` | Biz role 的治理身份和 Worker→Gateway principal | owner 仅允许明确的 PLATFORM/UPSTREAM_SYSTEM 语义，且 credential/lease 精确匹配 | `OPENAI_CODEX` backend 不是 BizWorkerIdentity；其 credential 不是出站 bearer |
| `WorkerPool` | Biz Worker 的 legacy/internal compatibility route artifact | membership 只适用于 pool route | 不能用于 onboard Codex Physical Worker 或修复 #151 类路由问题 |
| `OPENAI_CODEX` | model/worker backend capability | 由既有 Physical Worker capability 和 Agent/model grant 决定 | 不是 identity、owner 或要求新建 Pool member 的信号 |

Codex Physical Worker 的规范路径保持：`worker-host verify` → `worker-host update --worker-id <existingPhysicalWorkerId>` → `workers.claudeCode.codexConfig`。禁止设置 `workers.codex.workerId`、新建 direct Codex Biz identity、添加 Pool member、新建替代 Worker，或重绑 Directory 规避问题。

### Issue evidence boundary

| Issue | Confirmed evidence | Evidence not present | Current issue state checked 2026-07-19 |
|---|---|---|---|
| [#151](https://github.com/foggy-projects/Foggy-Navigator/issues/151) | 2026-07-17 live 评论证明 existing Physical Worker readiness/resources 可通过，`WORKER_POOL_MEMBERSHIP` blocker 消失，未新建 Worker/Pool/Biz identity | 未提交 ask、未发生完整 Worker execution；不能外推 production acceptance | OPEN |
| [#152](https://github.com/foggy-projects/Foggy-Navigator/issues/152) | task-token v2 migration/startup preflight 后服务可启动；部署信息可观察 | functional ask 被 `NAVIGATOR_EXTERNAL_ENABLED=false` 的 503 挡住；不能把 schema/preflight 成功写成 ask 成功 | OPEN |

## Ranked Risks and Gaps

### P0: must close before any non-trusted externalization

| Rank | Finding | Risk / required response |
|---|---|---|
| P0-1 | 鉴权分散在 Filter、AOP、Controller、resolver、Service，框架层大量 `permitAll` | 新端点或重构漏接 policy 时可能形成未授权入口；目标必须是统一、默认拒绝的 authorization facade |
| P0-2 | 通用 task/status/messages/diagnostics/evidence 与 Business Agent 路径的 ownership 谓词尚未证明完全一致 | 存在跨 ClientApp/upstream user 横向访问风险；必须以 route inventory 和完整负向矩阵确认，不能仅凭静态阅读宣称安全 |
| P0-3 | upstream user 只有 `client-app-delegated` assurance | 适合 trusted/internal，不足以承担真正外部用户身份；signed assertion 或同等级机制需架构决策 |
| P0-4 | internal-dev 不等于网络隔离，且当前 CORS 为宽泛 origin pattern + credentials | 若误暴露到非可信网卡/Ingress，会把兼容路径变成真实攻击面；production profile 必须显式收紧网络与 CORS |
| P0-5 | `NAVIGATOR_CREDENTIAL_KEY` 等存在开发 fallback | production 启动必须验证已覆盖并 fail closed，不能静默使用开发根密钥 |
| P0-6 | 三类 Worker external execution policy 尚未闭合 | `EXTERNAL_EXECUTION_POLICY_PENDING` 必须继续阻断 external workload，不能通过删 reason 或改 health 绕过 |
| P0-7 | 独立 `navigator-chat-observer-bff` 默认监听 `0.0.0.0:5181`，无入站认证/session，并复用服务端 runtime authority | 任意可达调用者可能借用 server-held/login-derived credential 执行 ask/task/session 或改变进程级 runtime credential；该 BFF 只能作为 local/trusted dev tool，在 observer session、附件 capability、CSRF/origin 和网络策略闭合前 production blocked |

### P1: high probability of misconfiguration or acceptance blockage

| Rank | Finding | Risk / required response |
|---|---|---|
| P1-1 | `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` 名称像网络外放，实际是 Worker principal requirement | 运维可能错误打开或错误宣称 Gateway external；需改名/alias 或在 CLI/help 中固定否定性说明 |
| P1-2 | platform gate、Gateway strict、Worker readiness、Provider readiness、production approval 没有组合状态视图 | 联调容易把任一绿灯解释为全链 ready；需统一 inspect/readiness schema |
| P1-3 | Physical Worker、BizWorkerIdentity、WorkerPool、backend 混用 | 最容易诱发 #151 类错误修复；CLI 和 SKILL 必须硬性阻止 Codex→Pool/Biz identity 绕路 |
| P1-4 | Java Gateway client 仍只传播 task token | strict server gate 现在开启会破坏现有调用链；必须先完成所有客户端 principal/lease 传播和兼容迁移 |
| P1-5 | upstream-admin/control 默认 scopes 较宽，control/runtime 可形成长期 key，admin 允许显式无过期例外 | 泄露半径和权限驻留时间过大；需默认 TTL、轮换、审批和撤销传播决策 |
| P1-6 | `poolId`/`workerId` 仍依赖运行时语义判别 | 存量冲突或跨 tenant 歧义会使路由难以审计；需决定显式 `routeKind` 及迁移策略 |
| P1-7 | CLI 发布物漂移：仓内已安装 1.0.18，`dist/output` 为 1.0.21 | 用户可能依据旧 help 进行危险操作；必须统一 VERSION、artifact、`--version` 和 help snapshot |

### P2: auditability and long-term governance gaps

| Rank | Finding | Risk / required response |
|---|---|---|
| P2-1 | 缺统一 `PolicyDecision` 记录，部分拒绝/恢复仍是日志或 best-effort telemetry | 难以稳定回答 actor、credential、owner、reason 和结果；需 reliable audit sink/outbox |
| P2-2 | 当前 SKILL 已有 Codex 正确路径，但缺 external/Gateway/production 与 credential-lane 的集中 FAQ/QA | 操作者仍可能在不同文档间拼接出错误结论；需统一 FAQ 和负向检查表 |
| P2-3 | 历史版本文档、源码测试、issue live 证据和当前部署状态容易混写 | 会造成虚假验收；每条 evidence 必须标记 source fact / historical live / current execution / not run |
| P2-4 | CLI artifact、`/actuator/info`、部署 commit 的追溯不总是稳定 | 无法证明实际运行版本包含目标安全修复；需 artifact identity contract |
| P2-5 | upstream assertion、credential 和 audit 数据的 KMS/TDE/留存策略未冻结 | 实现前需基础设施和合规 owner 做正式决策 |

## Mainstream Architecture and Complexity Review

### Review verdict

结论为 `passed-with-complexity-guardrails`：当前目标权限语义没有偏离主流 IAM/PAM/Zero-Trust 方向，不需要推翻重做；主要风险是把逻辑统一模型误实现成一次性全库、全路由和全凭据重写。P0.5 的任务是固定减法边界，避免 1.4.3 演变为自研通用 IAM 平台。

| Design choice | Mainstream comparison | Verdict / constraint |
|---|---|---|
| `navigatorInstanceId` 作为首要授权域，S1 使用独立 instance root | 类似 cloud account/project administrator 或 dedicated control-plane owner | aligned；authority 只在单实例生效，不能形成跨实例 global super-admin |
| S2 使用 upstream-scoped delegated SaaS administrator | 类似 delegated tenant/platform administrator | aligned；平台 authority 与租户 ClientApp credential 必须分层，平台不能自动成为 instance root |
| control/security、provisioning/security-admin、runtime/task/Worker credential 分 lane | 类似 PAM 的 standing privilege + step-up/JIT privilege 与 workload identity 分离 | aligned；主体权限上限不等于当前 credential 的有效动作 |
| owner、binding、grant 分离 | 类似资源所有权、使用关系和授权关系分离 | aligned；bind 不得隐式 transfer owner，非自有资源操作必须有显式 authority/grant |
| task capability 与 Worker principal/lease 求交 | 类似 capability-based authorization + workload identity | aligned；task token 只能收窄，不能替代 Worker principal 或控制面 credential |
| server-side canonical decision，CLI preflight non-binding | 类似 PDP/PEP 分工 | aligned；服务端是唯一 enforcement authority，CLI/help 只能解释和阻止明显误用 |
| opaque reference credential/token + online revoke/generation | 常见于内部 service credential 与高撤销要求场景 | acceptable for S1/S2；不要为当前内部场景自研完整 OAuth server，S3 出现真实需求后应优先评估标准 OIDC/OAuth 2.1 federation |
| `upstreamTrustProfile` | 类似服务端 trust tier / entitlement ceiling metadata | acceptable only as ceiling；不得由客户端自报、不得代替 principal、credential、grant 或 policy decision |
| action-bound single-use security authorization | 类似 JIT privilege / transaction authorization | aligned but intentionally narrow；只用于破坏性动作，不扩展成通用审批/BPM 引擎 |

### Complexity risks and mandatory reductions

| Complexity risk | P0.5 reduction |
|---|---|
| `principalType × trustProfile × credentialLane × actionSet × grant` 形成组合爆炸 | v1 只实现 `INSTANCE_ROOT`、`SAAS_PLATFORM` 两种新 management principal 和四条 management lane；action 使用少量 versioned action set，不支持租户自定义角色或 policy expression |
| `AuthorizationContextV1` 字段多，所有请求都填满会导致耦合 | context 使用 sparse typed sections；每个 registered action 的 target resolver 声明必需 section，缺必需字段拒绝，非相关 section 不构造 |
| canonical claim 被误解为单一物理表 | canonical DTO 是逻辑视图；现有 upstream-admin、control、runtime、task、Worker record 继续保留，由 resolver adapter 映射，不在 1.4.3 全量搬表 |
| 各 lane 分别实现 policy engine | 所有 lane 共用一个 policy/action catalog 和 decision contract；不引入通用 RBAC/ABAC DSL、外部 policy engine或数据库动态规则解释器 |
| 审批和审计一次性做到企业级平台 | P1 只实现 action-bound authorization、durable append-only decision record 和审计查询；跨 sink reliable outbox、tamper-evident retention 与合规归档仍是 production gate |
| CLI 与服务端各自维护权限规则 | 服务端 manifest 是 authority；CLI 使用同一 manifest 的构建期 help snapshot，只做离线一致性检查和在线 explain，不提供动态 help 服务或本地 allow engine |
| 为未来 S3 预先扩展公网身份和多租户策略 | S3 只保留 unknown/default-deny extension point；不实现 onboarding、federation、第三方 credential、配额或公网 ingress |

### Complexity budget

1. 1.4.3 新增授权持久化 aggregate 上限为六类：management principal、management credential、management token、platform grant、tenant authority、policy decision audit。
2. 不新增通用 `role`、`permission`、`role_permission`、policy-expression 或组织层级表；action catalog 和 policy 保持 source-controlled、versioned artifact。
3. 不迁移或合并现有 ClientApp control/runtime、runtime access token、task capability、Biz Worker credential 的物理表；只增加 canonical adapter 和缺失的 instance/route claim 迁移计划。
4. 不在同一切片同时切换所有 Controller；先 shadow decision，再按 route family cutover。新 typed security route 从第一天起只允许 canonical enforcement，绝不回退 legacy。
5. 不在 P0.5/P1 实现 S3、production external、Worker Gateway token 化、通用 approval workflow、跨 Navigator federation 或管理 UI。
6. 若实现需要突破上述预算，必须把 work item 设为 `NEEDS_REPLAN`，不能以“统一”名义静默扩大范围。

## Scenario S1: foggy-world-sim Dedicated Navigator

### User-confirmed scenario semantics

1. 为 `foggy-world-sim` 专门启用一个 Navigator 服务实例；该实例完全为该上游服务，不与其他业务上游共享。
2. `foggy-world-sim` 是该 Navigator 实例的 owner/root 主体，对实例内全部 Navigator 控制面能力拥有权限；该权限覆盖实例内所有 upstream system、tenant、ClientApp 及自有/非自有资源。
3. root 权限属于 `foggy-world-sim` 主体本身，使用独立、instance-scoped 的 `INSTANCE_ROOT` principal/credential 表达；不复用 upstream-admin，也不把具名 ClientApp 提升为 root。
4. 对自有和非自有资源，root 均可执行 create、discover、bind、operate、owner transfer、delete/revoke、credential rotation 和 grant delegation。
5. `bind` 不隐式改变 owner；`owner transfer` 仍是独立、显式、可审计的动作，但两者都在 root 的权限范围内。
6. 普通 ClientApp、runtime credential、task token 和 Worker credential 不因属于该实例而自动继承 root 权限。
7. 对 Agent 发起 ask 时，任务实际能力仍由 runtime caller、upstream user、Agent 设计、task capability、Worker route 和执行策略共同约束，不由 root 控制面权限直接放大。
8. “拥有全部权限”不等于跳过 fail-closed、readiness、production gate、凭据分层、secret 隔离或审计约束。
9. S1 描述的是专属实例拓扑，不是可跨环境携带的全局权限等级；同一用户或服务管理另一个 Navi 实例时，必须在目标实例重新获得并使用该实例自己的 root principal、grant 和 credential。
10. S1 的 root authority 只以 `navigatorInstanceId` 为授权域边界。`foggy-world-sim` 自身的 `upstreamSystemId` 用于识别 root 主体和审计来源，不把其权限收窄到 SIM-owned 资源；root 管理其他 upstream/tenant 时必须以 root 管理动作执行，不能伪装成目标 upstream 的 runtime principal。

### Design assessment

该业务目标在“一实例只服务一个强绑定上游”的前提下成立。S1 应建模为 `foggy-world-sim` 拥有 instance-scoped root authority，而不是把普通 `ClientApp` 类型本身定义为天然超级管理员：

| Layer | Principal | Authority |
|---|---|---|
| Instance owner/root | `foggy-world-sim`，身份记录绑定 exact `navigatorInstanceId + root subject`，并保留其来源 `upstreamSystemId` 供审计 | 该实例内全部 Navigator 控制面权限，包括所有 upstream system、tenant、ClientApp、Worker、Directory、Agent、Model、binding、owner、grant、credential 和实例配置管理；authority scope 不受 SIM 自身 tenant/upstream 限制 |
| Root credential expression | 独立 `INSTANCE_ROOT` principal/credential，authority audience 为 exact `navigatorInstanceId` | 只表达 `foggy-world-sim` 的 instance-root 权限；普通 upstream-admin、ClientApp、runtime、task 或 Worker credential 均不能兼容回退为 root |
| Ordinary ClientApp control | 一个未获 root designation 的具体 ClientApp | 只管理该 ClientApp 自有或明确授权的资源、grant 和 binding |
| Runtime caller | ClientApp runtime + delegated upstream user | readiness、owner-smoke、ask、messages；不能执行实例 root 管理 |
| Agent/task capability | Agent policy + task token | 控制单次任务的模型、函数、工具、目录和 Worker/lease 能力 |
| Worker principal | Physical Worker route or BizWorkerIdentity | 执行已授权任务；不能继承 instance-root 管理权 |
| Infrastructure recovery plane | 部署方的 OS/DB/KMS/secret-store recovery 能力 | 属于 Navigator 应用权限体系之外的基础设施保管职责；不应通过上游 API credential 暴露，也不构成扣留 root 的 Navigator 业务权限 |

“Instance owner/root”是已确认的业务角色；其权限范围已经冻结为整个 `navigatorInstanceId`，并由独立 `INSTANCE_ROOT` principal/credential 表达。P0.5 已冻结最小物理模型、`X-Navi-Principal-Credential` / management token 输入、control exchange、security action authorization 和 legacy 兼容迁移边界，method-level route manifest、seed/legacy mapping review 与 Owner approval 均已完成；初始 P1A handoff 当时只授权 P1A foundation/shadow。后续 P1B-A、P1B-B0 与 P1C-A 已按各自独立签核 accepted，但仍不得复用 upstream-admin 或 ClientApp 语义表达 root。S1 使用一个还是多个 tenant 只影响部署数据与普通 ClientApp/runtime 隔离，不再影响 root allow/deny 语义。

### Resource and instance-control semantics

| Operation | Confirmed semantic |
|---|---|
| Create | root 可创建任意实例内资源，并显式指定合法 owner；不得依赖模糊的 platform 默认 owner |
| Discover | root 可发现实例内自有和非自有资源；secret 明文、密钥材料等不可读取内容不因 root 而回显 |
| Bind | root 可建立 Agent/Model/Directory/Worker/ClientApp 等关系；binding 与 owner record 分开存储和审计 |
| Operate | root 可使用、更新或管理任意实例内资源，不通过伪造 owner、tenant 或 caller 字段实现 |
| Transfer ownership | root 可显式转移 owner；不得由 bind 隐式触发，必须记录 before/after、reason 和结果 |
| Delete/revoke | root 可删除普通资源或撤销其可用性；受保护的 append-only 审计证据和系统安全不变量不属于可删除业务资源 |
| Credential rotation | root 可签发、轮换和撤销其有权管理的实例 credential；不得读取旧 secret，审计不得记录新旧 secret 明文 |
| Grant delegation | root 可向实例内主体委托权限并撤销委托；grantee、scope、expiry 和 delegation chain 必须可审计，且不能越出该 Navigator 实例 |
| Instance configuration / promotion | root 可执行实例配置和 external/production 推进动作，但系统必须独立验证网络、身份、Worker policy、审计、迁移、回滚等前置条件；条件不满足时仍 fail closed |

S1 的授权结论冻结为：

> `foggy-world-sim` 是其专属 Navigator 实例的 owner/root，对实例内所有 upstream、tenant、ClientApp 以及自有和非自有资源拥有全部控制面权限。该权限只受 `navigatorInstanceId` 边界限制，不自动下沉给普通 ClientApp、runtime、task 或 Worker principal，也不能绕过系统安全不变量。

### Ask and runtime invariant

即使 `foggy-world-sim` 拥有实例内业务控制面管理权，一次 ask 的最终权限仍必须按以下交集计算：

```text
authenticated ClientApp runtime
  ∩ delegated upstream user grant
  ∩ Agent model/workspace/worker/function policy
  ∩ task-scoped capability
  ∩ exact Worker/lease route
  ∩ Worker execution policy
```

专属上游根权限只能创建、配置或授权上述资源，不能在任务执行时隐式绕过这些限制。运行时请求不得携带“我是 root”字段来提升 task capability。

### Non-bypassable system invariants

以下是所有主体共同遵守的安全不变量，不是从 `foggy-world-sim` root 权限中扣留的另一组 Navigator 权限：

1. 任何 root 操作仍需通过可验证的 root principal 和正确 credential lane；缺失、冲突、过期或撤销时 fail closed。
2. root credential 不得作为普通 control/runtime/task/Worker credential 使用，也不得注入 ask、模型上下文、Worker 子进程或业务日志。
3. runtime ask 始终按 runtime、upstream user、Agent、task capability、exact Worker/lease route 和执行策略求交，root 身份不形成运行时万能 capability。
4. external/production 操作只有在网络、TLS/CORS、identity、Worker execution policy、credential、migration、rollback、artifact identity、audit 和独立签收前置条件满足时才能成功；任何 external flag 都不能覆盖这些判定。
5. 审计记录必须可靠、追加式、不可由业务权限篡改或抹除；credential secret、KMS/签名私钥和数据库 root 不通过 Navigator API 读取。
6. instance root 只能在绑定的 `navigatorInstanceId` 内生效；可以管理本实例内其他 upstream system，但必须以 root principal 和具名管理动作执行，不得访问其他 Navigator 实例，也不得伪装成目标 upstream 的 control/runtime principal。

## Scenario S2: tms-x3 Business SaaS

### User-provided scenario input

1. `tms-x3` 是自有业务 SaaS 系统，可以获得高于普通租户 ClientApp 的平台管理权限。
2. `tms-x3` 需要代表业务租户创建和管理 ClientApp。
3. `tms-x3` 需要为租户分配 Worker 等 Navigator 资源。
4. 业务租户可以取得其 ClientApp 的凭据，但该凭据必须受到限制，不能继承 `tms-x3` 平台主体的高权限。

### Confirmed baseline and typed principal model

S2 使用共享 Navigator。`tms-x3` 是受信 SaaS upstream-system 管理主体，但不是 Navigator instance root。它使用统一授权模型中的显式 `principalType=SAAS_PLATFORM` 表达平台主体，并通过 `SAAS_PROVISIONING` / `SAAS_SECURITY_ADMIN` 两条 credential lane 执行动作。权限必须止于 exact `upstreamSystemId=tms-x3` + server-authorized tenant 集合，不能触达其他 upstream system、Navigator root 或基础设施恢复面。

| Layer | Principal | Authority boundary |
|---|---|---|
| Navigator instance root/operator | Navigator 部署与治理主体 | 管理实例级 upstream system、production policy、trust root 和 break-glass；不下发给 TMS 租户 |
| TMS SaaS platform subject | `principalType=SAAS_PLATFORM`，exact `upstreamSystemId=tms-x3` | 在获准 tenant 集合内拥有完整 upstream 管理权；主体稳定，credential 可替换，不由普通 upstream-admin scope 推导 |
| TMS provisioning principal | 同一 `SAAS_PLATFORM` subject 的 `credentialLane=SAAS_PROVISIONING` | 创建/启用 tenant 与 ClientApp、签发初始 tenant credential、配置资源、创建/更新/分配/重分配 TMS-owned Worker、Directory、Model、Agent、grant 和 binding |
| TMS security-admin principal | 同一 `SAAS_PLATFORM` subject 的 `credentialLane=SAAS_SECURITY_ADMIN` | tenant/ClientApp 停用或删除、owner transfer、资源 delete/revoke、credential rotation/revocation、grant delegation、Worker delete 和紧急恢复；必须 step-up、影响预览和审计 |
| Tenant ClientApp control | exact `upstreamSystemId + tenantId + clientAppId` | 仅在明确启用租户自助管理时，管理该 ClientApp 自有或获准资源；不能管理其他 ClientApp、tenant、upstream shared fleet 或平台 credential |
| Tenant ClientApp runtime | exact ClientApp key/secret 或短期 access token | runtime-token、readiness、owner-smoke、ask、messages 和被授权业务功能；不能创建 ClientApp、分配 Worker 或执行 provisioning |
| Tenant upstream user | 由已认证 ClientApp 委托并经 mapping/grant 限制 | 在该 ClientApp 和 tenant 内使用其用户级 Agent、Directory、task/context 能力 |
| Task capability + Worker principal | exact task、function、Worker/lease route | 只执行单次获授权任务，不继承 TMS platform admin 或 tenant control 权限 |

### Credential separation

S2 必须有 TMS provisioning、TMS security-admin 与 tenant runtime 三条 lane；出现租户自助需求时，再增加独立且不可混用的 limited control lane：

| Credential lane | Holder | Allowed | Denied |
|---|---|---|---|
| `SAAS_PROVISIONING` | 仅 `tms-x3` 受控服务端自动化 | tenant/ClientApp create/update/enable、初始 credential issuance、资源配置、TMS-owned Worker create/update/reassign 和 grant/binding | destructive delete、owner transfer、credential emergency rotation/revoke、跨 upstream、Navigator root、ask/runtime |
| `SAAS_SECURITY_ADMIN` | 仅 `tms-x3` 受控高权限服务或人工审批流程 | tenant/ClientApp suspend/delete、owner transfer、delete/revoke、credential rotation/revocation、grant delegation、Worker delete 和 recovery | 日常 ask/runtime、跨 upstream system、Navigator root、跳过 step-up/影响预览/审计 |
| Tenant ClientApp control（optional） | 租户受控服务端；仅在需要自助配置时签发 | exact ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已获授权资源之间的 grant/binding 具名 scope | credential lifecycle、Worker 管理、ClientApp lifecycle、owner transfer、grant delegation/扩权、其他 ClientApp/tenant、平台 credential 和 production 设置 |
| Tenant ClientApp runtime（default） | 租户业务服务端 | 换取短期 runtime token，并在 exact tenant/ClientApp grant 内 ask/readiness/messages | 任何控制面创建、credential 签发、Worker 分配、owner transfer 或跨 ClientApp 操作 |

`tms-x3` 主体拥有完整 upstream 管理权，但当前 credential 只能执行其 lane 对应的动作；`SAAS_PROVISIONING` 不能兼容回退为 `SAAS_SECURITY_ADMIN`。租户默认只获得 ClientApp runtime key/secret，不得拿到任何 TMS platform credential。只有在明确存在租户自助配置需求时，才另行签发 application-config-only control credential；所有 lane 必须独立过期、轮换、撤销和审计，不能用一个“万能 key”兼任。浏览器端只应持有短期、受 audience/TTL 限制的 token，不持有长期 secret。

类型化统一模型不创建 TMS 专属授权孤岛：`INSTANCE_ROOT`、`SAAS_PLATFORM`、ClientApp、task 与 Worker principal 共享统一 credential metadata、`AuthorizationContext/PolicyDecision` 和审计契约，但 principal type、lane、authority scope 和 policy 必须显式区分。现有 upstream-admin credential 保留为 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN` 兼容主体；它不能自动变成 `SAAS_PLATFORM`，更不能获得 `SAAS_SECURITY_ADMIN`。只有经过具名审批与重新签发，才可把已验证的 TMS legacy 管理关系迁移为新的 `SAAS_PROVISIONING` credential。

### SAAS_PLATFORM lifecycle and tenant grant

S2 复用 S1 的“本地兼容、生产严格”生命周期基线：

| Parameter | `SAAS_PROVISIONING` | `SAAS_SECURITY_ADMIN` | Invariant |
|---|---|---|---|
| internal-dev long-term credential | 默认 180d、硬上限 365d；仅显式 trusted-loopback 可直用 | 默认 180d、硬上限 365d；不得直接执行高风险 API | dev credential/token 必须被 production profile 拒绝 |
| production long-term credential | 默认 30d、硬上限 90d；必须换取默认 15m、硬上限 30m 的 access token | 默认 30d、硬上限 90d；必须受控保管并换取 action authorization | 禁止 no-expiry；绑定 exact instance + `upstreamSystemId` + platform grant |
| security action authorization | not applicable | 默认 5m、single-use，硬上限 15m | 绑定 action + target + impact digest + reason + credential generation + platform grant version |
| rotation / revocation | 默认 24h、硬上限 72h 的双 credential overlap；撤销最迟 60s 收敛 | 相同 | 最多两把 active；grant/credential version 不匹配时拒绝 |

tenant authority 不固化为每把 credential 内的可扩张 tenant wildcard，也不要求每新增一个 TMS 租户就轮换平台 key。目标采用服务端版本化 platform grant：

~~~text
SaasPlatformGrant
- grantId / version / status
- navigatorInstanceId
- principalId = tms-x3-platform
- upstreamSystemId = tms-x3
- tenantScopeMode = UPSTREAM_OWNED
- environmentProfile
- issuedBy / approvedBy / reason / timestamps
~~~

1. `UPSTREAM_OWNED` 表示只允许管理服务端权威归属关系中 `upstreamSystemId=tms-x3` 的 tenant，不是调用者可提交或解释的 `*` wildcard。
2. `SAAS_PROVISIONING` 可以创建新的 TMS-owned tenant，并在该 tenant 下创建/管理 ClientApp、资源、grant/binding 和 TMS-owned Worker allocation；新 tenant 自动进入同一 platform grant 的动态范围，但必须产生审计。
3. 已存在 tenant 的归属迁入/迁出、upstream owner 变更、tenant suspend/delete/offboarding 必须使用 `SAAS_SECURITY_ADMIN`；涉及其他 upstream system 时，还必须由 `INSTANCE_ROOT_SECURITY` 执行或批准具名 cross-upstream transfer。
4. credential/token 引用 `platformGrantId` 和 grant version；服务端每次判定从权威 tenant 归属重建 scope。grant 被暂停、撤销或版本失配时，新请求立即 fail closed，缓存最迟 60s 收敛。
5. 多个公司平台共享同一 Navi 时，各自拥有不同 `SAAS_PLATFORM` principal、upstreamSystemId 和 grant；同名 tenant/user 或同一公司身份不形成跨平台继承。
6. legacy upstream-admin 的静态 `authorizedTenantIds` 只在兼容 route 内继续生效；迁移为 TMS platform 时，先核验实际 upstream/tenant/owner 关系，再创建 `UPSTREAM_OWNED` grant 和重新签发 provisioning credential，不把旧列表直接解释为跨 upstream authority。

### Tenant and ownership boundary

建议服务端从已认证 TMS platform principal 或 ClientApp credential 重建 `upstreamSystemId + tenantId + clientAppId`，而不是信任请求体声明。S2 中：

1. `tms-x3` platform subject 可以跨其获准 tenant 集合管理资源，但每次操作仍需显式 tenant、action、resource owner、credential lane 和 reason。
2. 租户 ClientApp credential 只能进入一个 exact tenant + ClientApp scope；相同 upstream system 不等于可以跨租户。
3. TMS 为租户创建的专属资源建议 owner 为对应 ClientApp 或 upstream user；TMS 共享资源建议 owner 为 `UPSTREAM_SYSTEM/tms-x3`，再通过 tenant/ClientApp grant 和 binding 使用。
4. binding 不改变 owner。TMS subject 可对其 upstream 范围内租户资源执行 owner transfer、delete/revoke、credential rotation 和 grant delegation，但必须使用 security-admin lane。
5. 租户默认没有 control credential；可选 control 只允许本 ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已获授权资源之间的 grant/binding 应用配置。它不能把未授权资源纳入本 App、扩大 grant scope 或向其他主体 delegation。一个 tenant 可映射一个或多个 ClientApp，但每套 credential 必须固定一个 exact ClientApp。

### Worker allocation boundary

S2 的“分配 Worker”冻结为 TMS platform admin 从 TMS-owned Worker inventory 中为 tenant/ClientApp 建立 use grant 和 binding，而不是把 Physical Worker owner 转给租户：

1. shared 或 tenant-dedicated Worker 均由 `UPSTREAM_SYSTEM/tms-x3` 持有 owner，tenant/ClientApp 只获得使用权。
2. 租户 runtime credential 不能创建 WorkerHost、修改 Physical Worker、变更 WorkerPool membership 或把 Worker 重绑到其他 tenant/ClientApp。
3. 如租户获得有限 control credential，Worker 动作默认仍为只读发现已分配 route，不包含 `worker-host apply/update` 或跨 ClientApp allocation。
4. Codex 仍必须使用 existing Physical Worker 的 `worker-host verify` → `worker-host update --worker-id` → `workers.claudeCode.codexConfig` 路径；不得因 SaaS tenant allocation 新建 BizWorkerIdentity 或 WorkerPool member。
5. TMS provisioning 可执行 TMS-owned Worker create/update/reassign；security-admin 可执行 delete、owner transfer 和紧急回收。破坏性动作必须 step-up、影响预览和可靠审计，tenant offboarding 必须先撤销 runtime/task/grant/binding 再回收 Worker allocation。

### Runtime invariant

TMS platform admin 可以创建、配置和授权租户资源，但不能把自身管理权限注入租户 ask。一次 TMS tenant ask 仍按以下交集执行：

```text
authenticated tenant ClientApp runtime
  ∩ exact tenant + ClientApp
  ∩ delegated upstream user grant
  ∩ Agent model/workspace/worker/function policy
  ∩ task-scoped capability
  ∩ exact Worker/lease route
  ∩ Worker execution policy
```

## Scenario S3: External Third-Party Integration (Design-Only)

### User-confirmed positioning

1. 外部非公司主体接入 Navigator 是第三类、也是当前可预见的最后一类上游信任形态。
2. 当前没有真实外部第三方需求，不需要在 1.4.3 主线实现 onboarding、credential、API 或联调能力。
3. 当前架构必须考虑未来第三方接入，避免把 S1 的 instance-root 或 S2 的公司 SaaS platform 权限写成所有 upstream 的默认能力。
4. S3 是设计约束和负向兼容目标，不是当前业务验收或 production 发布目标。

### Three-class trust model

| Scenario | Business trust | Maximum authority | Current implementation priority |
|---|---|---|---|
| S1 dedicated upstream | `foggy-world-sim` 对专属 Navigator 实例绝对可信 | exact instance 内 owner/root 控制面权限；仍不能绕过认证、运行时 capability、readiness、production gate 或审计 | primary |
| S2 internal SaaS platform | `tms-x3` 平台主体可信，平台 tenant/ClientApp 受限 | exact upstream system + allowed tenant 集合内的 provisioning/security-admin；租户默认仅 runtime | primary |
| S3 external third party | 默认不可信 | 当前无允许路径；未来最多从 exact upstream system + tenant + ClientApp 的最小 runtime 起步，其他权限逐项审批 | design-only / deferred |

“绝对可信”“平台可信”“默认不可信”描述的是业务主体可被授予的权限上限，不替代 credential authentication、resource authorization、task capability、Worker principal、execution policy 或 audit。trust profile 缺失、未知或与 principal 冲突时必须拒绝。

### Default-deny boundary

S3 当前冻结为以下边界：

1. 当前不注册或签发外部第三方 upstream-admin、ClientApp control/runtime credential，不提供外部第三方 onboarding API，也不接受真实第三方业务流量。
2. 未来如启动独立需求，Navigator operator 或经批准的 onboarding service 创建 exact external upstream system、tenant 和 ClientApp；第三方不能自助声明 trust profile、owner、tenant 或 admin scope。
3. 第三方默认最多获得 exact tenant + ClientApp 的 runtime credential，只能访问自有或显式 grant 的 Agent、Model、Directory、function 和 task；不得 discover、bind 或 operate 其他 owner 的资源。
4. 如未来确有配置自助需求，limited control 必须独立审批、独立 credential、具名 action 和短 TTL；不得包含 upstream-admin、credential lifecycle、ClientApp lifecycle、Worker 管理、owner transfer、grant delegation、跨 tenant/ClientApp 或 production 权限。
5. 第三方不能创建或更新 WorkerHost、Physical Worker、BizWorkerIdentity、WorkerPool member，也不能选择未显式分配的 Worker route；只可使用 Navigator 已授权的 exact Worker/lease capability。
6. 对真实外部用户身份，当前 `client-app-delegated` assurance 不足以自动宣称强认证。未来接入必须另行冻结签名 assertion、issuer/audience、TTL、nonce、防重放、密钥轮换和撤销模型。
7. `NAVIGATOR_EXTERNAL_ENABLED=true`、Gateway strict、Worker external 或任一 health 结果都不能创建 S3 权限或完成第三方 onboarding；网络、TLS/CORS/Ingress、执行策略、可靠审计、迁移、回滚和独立签收仍是单独前置条件。

### Deferred implementation contract

S1/S2 主实现可以增加显式 trust profile 或等价 policy extension point，但只能用于限制最大可授予权限，不能通过 profile 名称直接放行。当前版本不得为 S3 增加真实 credential、开放路由或兼容性万能 scope。未来第三方需求出现后，必须新建独立 workitem，基于真实 threat model 冻结数据驻留、配额、计费、支持责任、身份 assurance、资源共享和生产验收，不回填为 1.4.3 已交付能力。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 1.4.3 从真实上游场景逐项对齐权限体系 | 避免先构造过度通用的 RBAC/ABAC，再反推业务 | S1、S2 业务语义已对齐；S3 已冻结为 design-only、implementation-deferred |
| `foggy-world-sim` 使用专属 Navigator 实例 | 用户已明确该实例完全为其服务 | 不自动推导为 external 或 production ready |
| `foggy-world-sim` 是实例 owner/root | 专属实例即为该主体服务，root 对实例内 Navigator 控制面拥有全部权限 | authority scope 覆盖实例内全部 upstream system、tenant、ClientApp 和资源，只绑定 exact instance，不跨其他 Navigator 实例 |
| S1 tenant/upstream 拓扑不限制 root authority | 用户确认 SIM 直接拥有整个 Navi 实例权限 | 单 tenant、多 tenant 或实例内出现其他 upstream 只影响部署数据和普通主体隔离；SIM root 均可管理，但不得伪装成目标 upstream 的 runtime principal |
| Navi 实例之间是独立授权域 | 用户已确认不同 Navi 环境实例的权限互不相关 | 不存在全局 S1/root 权限；同一用户、服务或公司身份也必须按目标 `navigatorInstanceId` 分别认证和授权，credential/grant/session/task/Worker principal 不得跨实例复用 |
| 正常上游连接 profile 只绑定一个 Navi 实例 | SIM、TMS 的常规部署只连接一个 `navigatorInstanceId`；多实例接入属于上游自己的部署选择 | 上游负责目标实例选择、故障切换和多套凭据保管；每个 Navi 实例仍负责本实例 credential 的签发、校验、轮换、撤销和审计，不提供跨实例万能 credential 或自动同步 |
| root 可管理自有与非自有资源的完整动作集 | 用户已确认非自有资源不只允许 discover/bind/operate | 包括 owner transfer、delete/revoke、credential rotation 和 grant delegation |
| binding 与 ownership 保持分离 | bind 与 owner transfer 的业务含义、恢复方式和审计要求不同 | root 同时拥有两种权限，但 bind 不隐式触发 transfer |
| root 权限属于上游主体并使用独立 `INSTANCE_ROOT` principal/credential | 避免把普通 upstream-admin、具名 ClientApp 或宽泛 scope 误提升为实例 root | authority 绑定 exact instance；普通 ClientApp、upstream-admin、runtime、task 和 Worker credential 不自动继承或兼容回退 |
| `INSTANCE_ROOT` 强制拆分 control/security 双凭据档位 | SIM 可拥有完整实例权限，但日常自动化不应常驻携带破坏性和信任根权限 | `INSTANCE_ROOT_CONTROL` 执行日常管理；`INSTANCE_ROOT_SECURITY` 只用于高风险动作并必须 step-up、影响预览、reason、action-bound 短期授权和可靠审计；两者不得合并或互相回退 |
| instance-root 生命周期采用“本地兼容、生产严格” | 保持现有本地上游 CLI/自动化可用，同时不把本地便利路径带入 production | internal-dev 长期凭据默认 180d/上限 365d，control 仅在 trusted-loopback 可直用；production 长期凭据默认 30d/上限 90d，并强制短期 access/action token；security 在所有环境均不得直接执行高风险 API |
| 控制面管理权与 ask 执行权限分离 | 防止上游管理权限直接变成 Agent/Worker 任意执行能力 | ask 始终执行 runtime/Agent/task/Worker 权限交集 |
| 安全门是系统不变量，不是保留给另一主体的业务权限 | 保持 fail-closed、凭据分层、secret 隔离和可审计性 | root 可推进实例配置和 production 流程，但不能跳过 readiness 或自我伪造 production ready |
| `tms-x3` 是可获较高授权的自有业务 SaaS | TMS 平台需要代表业务租户完成 Navigator provisioning | 其权限高于租户 ClientApp，但不因“自有系统”自动等于 Navigator instance root |
| TMS 平台可为租户创建/管理 ClientApp 并分配 Worker | 这是 SaaS 平台统一开通租户运行环境的业务职责 | 在 `upstreamSystemId=tms-x3` 和获准 tenant 集合内拥有完整管理权；日常动作与破坏性动作使用不同 lane |
| 租户取得的 ClientApp credential 必须受限 | 防止一个租户继承 TMS platform admin 或访问其他租户 | credential 必须绑定 tenant + ClientApp + lane，不能下发 upstream-admin |
| S2 使用共享 Navigator | 在同一 Navigator 中验证 upstream system 与 tenant 隔离，不把 TMS 权限提升为实例 root | TMS platform admin 只能管理 `upstreamSystemId=tms-x3` 范围 |
| S2 tenant 默认仅获 runtime credential | 租户日常需求是业务调用，不应默认获得控制面写权限 | limited control 仅按明确自助需求独立签发 |
| S2 Worker 由 TMS upstream 持有 | 共享/专用分配不应隐式改变 Physical Worker ownership | owner=`UPSTREAM_SYSTEM/tms-x3`，tenant/ClientApp 通过 grant/binding 获得使用权 |
| TMS 主体使用 provisioning/security-admin 双角色 | 主体需要完整 upstream 管理能力，但常驻自动化凭据不应承担全部破坏性权限 | create/update/assign 走 provisioning；delete/transfer/rotate/revoke/delegate 等高风险动作走 security-admin + step-up |
| TMS 使用类型化统一 principal/credential 模型 | 现有 upstream-admin 只有 upstream/tenant/scopes，无法可靠表达 SaaS platform 与双 lane 的安全语义；TMS 专属表又会复制授权基础设施 | 显式 `principalType=SAAS_PLATFORM`，credential lane 为 `SAAS_PROVISIONING` / `SAAS_SECURITY_ADMIN`；共享统一 credential/policy/audit 基础设施，不创建 TMS-only 授权孤岛 |
| legacy upstream-admin 不自动提升为 SaaS platform | 避免旧宽 scope、`CLIENT_APP_ADMIN_ALL` 或兼容桥被解释为新平台/security 权限 | 存量 credential 识别为 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN`；仅经具名审批重新签发为 `SAAS_PROVISIONING`，永不自动获得 `SAAS_SECURITY_ADMIN` |
| TMS 平台生命周期复用 S1 环境基线 | 统一凭据治理并保持本地联调兼容，不为 TMS 发明另一套 TTL/撤销语义 | internal-dev 180d/365d + trusted-loopback provisioning 直用；production 30d/90d + 15m/30m access token；security 5m/15m single-use action authorization；24h/72h 轮换、60s 撤销 |
| TMS tenant scope 使用服务端 `UPSTREAM_OWNED` dynamic grant | SaaS 自动开通不能每新增租户就重签 key，也不能使用客户端 wildcard | credential/token 引用 platform grant/version；provisioning 可创建/管理 TMS-owned tenant，迁入迁出/停用删除走 security-admin，跨 upstream transfer 还需 instance-root security |
| Tenant optional control 仅限应用配置 | 租户自助不应扩展到 credential、Worker 或其他 ClientApp/tenant | 只允许 exact ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已授权资源之间的 grant/binding；不能扩大 scope 或继续 delegation |
| TMS 完整管理 TMS-owned Worker 生命周期 | TMS 需要独立完成 SaaS tenant Worker provisioning 与回收 | create/update/reassign 可走 provisioning；delete/owner transfer/recovery 走 security-admin，并保持 Codex Physical Worker 规范路径 |
| 外部第三方默认不可信且不是当前主实现目标 | 当前没有真实第三方需求，不应为假设场景扩大接口或 credential 面 | 当前无允许路径；未来从 exact tenant + ClientApp runtime 最小权限起步，并以独立 workitem 冻结需求 |
| trust profile 只限制权限上限 | 防止把“绝对可信”误解为免认证，或把类型标签变成 hard-coded bypass | S1/S2/S3 均继续执行 credential、owner/grant、task、Worker、execution 和 audit 判定；未知 profile fail closed |
| CLI/SKILL 必须暴露调用者权限边界 | 上游使用 CLI 时需要在操作前明确知道自己持有什么主体、凭据和 scope | 每个命令显示 required principal/lane/scope/denied actions；只读 inspect 不回显 secret，服务端仍是最终授权来源 |
| Codex Physical Worker 保持直接路由 | #151 已验证该模式，不应以 Pool/Biz identity 绕过 | 禁止新增 Worker/Pool member 作为权限或 readiness 修复 |

三类上游的业务信任定位均已对齐：S1/S2 是主实现目标，S3 是默认拒绝的未来设计边界。S1 tenant/upstream 拓扑、instance-wide root authority、独立 `INSTANCE_ROOT` principal、control/security 双凭据档位及“本地兼容、生产严格”的生命周期参数均已确认；S2 的 `SAAS_PLATFORM` 类型化统一模型、双 lane、生命周期和 `UPSTREAM_OWNED` dynamic tenant grant 也已确认。统一 `AuthorizationContext/PolicyDecision`、credential/token/grant claim 及 CLI 在线/离线权限自描述 schema 已冻结为 `navi.authorization.v1`。主流架构复核、原始 414 条 method-level route manifest、seed/legacy 静态映射复核和 Owner approval 均已完成；Owner 后续批准的 `/actuator` amendment 将当前 P1A catalog 更新为 415 条。初始编码授权限定为 P1A foundation/shadow；P1B-A、P1B-B0 与 P1C-A 已在各自受限范围内独立 accepted，P1B-B/P2/P3/P4 仍由各自 gate 阻断。

## P0.5 Implementation Contract Freeze

### Phase status and initial P1A handoff snapshot

- phase_status: `complete`
- implementation_authorized: `historical-yes-p1a-only`
- architecture_review: `passed-with-complexity-guardrails`
- objective: 把已确认的 S1/S2 权限语义压缩为可增量实施、可回滚、不会重写全部存量凭据体系的唯一合同。

P0.5 的原始 route manifest 门槛已于 2026-07-18 关闭：原始基线形成 414 条 deployment-aware 入口记录，覆盖 397 条 launcher MVC、12 条 Observer BFF MVC、1 条 SSH WebSocket 和 4 条 Actuator family；244 条非 GET MVC 均已分类，完整性断言为零错误。2026-07-19 Owner 批准第 415 条 `framework:get:/actuator` / `actuator.discovery-links.read` amendment，当前 catalog 覆盖 397 条 launcher MVC、12 条 Observer BFF MVC、1 条 SSH WebSocket 和 5 条 Actuator family。BUG-002 required-section amendment 保持 route 数量和 action 语义不变；source/evidence 当前 SHA-256 为 `ef4c32ac4ca25ee695dff7bacd9845301266807d71fbcafe35ebba4872aadc7d`。证据见 [method-level manifest](../evidence/GOV-001-p0.5-method-route-manifest.csv) 和 [static review](../evidence/GOV-001-p0.5-method-route-manifest-review.md)。

P0.5 的剩余门槛已于 2026-07-19 关闭：

1. [seed/legacy mapping 静态复核](../evidence/GOV-001-p0.5-seed-legacy-mapping-review.md)确认没有任何 legacy record 可自动提升；upstream-admin 唯一合法映射为 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN`。实际数据库脱敏 inventory 明确推迟到 P1B seed 前执行，不阻塞不写业务数据的 P1A。
2. Project Owner 已在连续场景对齐、架构复核和初始开工确认中批准本合同；frontmatter `open_questions` 已清零。当时批准只覆盖 P1A；后续 P1B-A、P1B-B0 与 P1C-A 的独立授权和 acceptance 见各 slice work item，real P1B-B/P2/P3/P4 仍需后续 gate 输入。

### Implementation slices

| Slice | Scope | Explicit non-goal | Exit |
|---|---|---|---|
| P1A foundation and shadow | 新增 typed management 最小持久化、canonical resolver、sparse context、policy decision、route manifest 和 shadow evaluator | 不改变任何现有 allow/deny，不签发 S1/S2 typed credential，不切换 Open API/Gateway，不新增 management/audit/diff HTTP API | schema/migration tests、resolver contract tests、100% route manifest coverage、shadow diff 可通过 repository/service/test surface 查询 |
| P1B typed S1/S2 management | 显式 seed `INSTANCE_ROOT`/`SAAS_PLATFORM`、签发双 lane credential、control exchange、security action authorization、whoami/permissions/explain | 不迁移 runtime/task/Worker credential，不实现 S3 或 production external | S1/S2 principal/lane/lifecycle、跨实例、跨 upstream、single-use/revoke tests 通过 |
| P1C route-family cutover and operator UX | 现有 management route 按 family 接入 canonical enforcement；CLI/SKILL/help/runbook 对齐 | 不一次性切换全部 Controller，不删除 legacy 表/header，不开启任何 external flag | 每个 family 独立 cutover/rollback、CLI artifact/help snapshot、一致性和负向矩阵通过 |

### Logical contract versus physical persistence

`CredentialRecordV1`、`TokenEnvelopeV1` 和 `AuthorizationContextV1` 是 canonical logical view，不要求首期使用一张万能表。现有 runtime/control/task/Worker record 继续作为各自 lane 的持久化事实，通过 adapter 映射到 canonical context；只有 S1/S2 typed management 新增物理模型。

| Aggregate / table | P0.5 physical contract | Minimum uniqueness and query contract |
|---|---|---|
| management principal / `authorization_principal` | 仅保存新 typed management principal：`navigatorInstanceId`、`principalType`、`principalId`、`sourceUpstreamSystemId`、`upstreamTrustProfile`、`environmentProfile`、status/version/timestamps | unique `(navigatorInstanceId, principalType, principalId)`；按 instance/type/status 查询；trust profile 是服务端 ceiling 字段，不单独建动态角色表 |
| management credential / `authorization_credential` | 仅保存 `INSTANCE_ROOT_CONTROL`、`INSTANCE_ROOT_SECURITY`、`SAAS_PROVISIONING`、`SAAS_SECURITY_ADMIN`；保存 verifier/ref、fingerprint、principal id、lane、generation、action-set ref、expiry/rotation/revoke metadata | unique credential id 与 secret verifier/ref；index `(principalId, lane, status, expiresAt)`；同 principal/lane 最多两份 active 由事务 + optimistic version 保证 |
| management token / `authorization_management_token` | 仅保存 `CONTROL_ACCESS`、`SECURITY_ACTION`；opaque token hash/ref、credential generation、instance/environment、action/target/impact/reason digest、approval id、nonce、status/expiry/consumedAt | unique token id/hash；`SECURITY_ACTION` nonce unique 且原子 single-use；index `(credentialId, purpose, status, expiresAt)`；不承载 runtime/task/Worker token |
| platform grant / `authorization_platform_grant` | 保存 S2 `UPSTREAM_OWNED` dynamic grant、action-set ref、status/version、审批和审计引用 | 每个 `(navigatorInstanceId, environmentProfile, principalId, upstreamSystemId)` 只有一个稳定 grant record；状态变化递增 version，不复制 tenant wildcard/list |
| tenant authority / `authorization_tenant_authority` | 保存 Navigator tenant 当前权威 upstream owner：instance、tenant、upstreamSystem、status、source/migration reference、version | unique `(navigatorInstanceId, tenantId)`；按 instance/upstream/status 查询；任何冲突不得从 ClientApp 或请求体猜 owner |
| policy decision audit / `authorization_decision` | append-only 保存脱敏 principal/credential/action/target/route summary、request/impact digest、decision/reason、policy/catalog/build、correlation 和时间 | unique decision id；index correlation、principal、credential、action、target、decision/reason、evaluatedAt；不保存 secret、完整 token、敏感请求体 |

持久化约束：

1. Entity 继续遵循项目 JPA composition 规则，使用 opaque foreign-key fields，不新增 JPA association graph。
2. action catalog、action set 和 policy 在 1.4.3 保持 source-controlled versioned manifest，不创建通用 role/permission/policy-expression 表。
3. `upstreamTrustProfile` 首期随 typed principal 保存；不存在 principal record、profile unknown/conflict 时拒绝，不再单独建设 trust registry 服务。
4. P1 的 `authorization_decision` 是 durable append-only 审计事实，但不能宣称已经 tamper-proof；可靠 outbox、外部不可变 sink、TDE/KMS 和留存属于 P3 production gate。
5. 现有以下物理事实不搬表：`upstream_client_app_admin_credential`、`client_app_control_credential`、`client_app_runtime_credential`、`client_app_runtime_access_token`、`business_task_scoped_token`、Biz Worker credential fields。resolver adapter 必须保留其当前语义并显式标注 legacy/current lane。

### Server-owned deployment identity for P1A

P1A 必须新增服务端拥有、请求不可覆盖的 deployment identity provider，作为所有 canonical context、decision、audit 和未来 credential audience 的唯一实例来源：

1. `navigatorInstanceId` 与 `environmentProfile` 由当前部署配置/启动 profile 解析并在进程生命周期内保持稳定；request body、header、CLI profile、endpoint URL、tenant/upstream 字段只能做一致性检查，不能改变服务端值。
2. internal/local-dev 必须保持现有启动兼容；允许明确标记为 dev-only 的稳定 fallback，但必须产生可见告警并输出 `productionUsable=false`。
3. production profile 必须显式配置非占位 instance identity 和合法 environment profile；缺失、未知、占位或冲突时启动失败。
4. legacy adapter 可把当前 deployment identity 加入 shadow context，但必须记录其来源为 deployment-inferred legacy context；不得反向宣称 legacy record 原生绑定 instance，也不得据此 seed typed principal/grant/credential。
5. P1A 不改变现有跨实例 enforcement。canonical shadow 对缺失权威 instance binding 的 legacy record 应产生稳定的 unknown/deny reason 和 diff，不得静默补全后返回 allow。

### Resolver and policy boundary

统一 resolver 顺序固定为：检测 credential-source conflict → 验证一份 credential/token → 构造 principal/credential section → 解析服务端 instance/trust/grant → 按 registered target resolver 解析资源 owner/binding → 按 action 需要补 capability/Worker section → 形成 decision。不得遍历多种 header 直到某一种成功。

每个 action manifest 声明其最小 context section：

| Action class | Required sections |
|---|---|
| introspection | environment + principal + credential + authority |
| management read/write | introspection sections + resolvedTarget；platform dynamic tenant action另需 platformGrant + tenantAuthority |
| destructive/security | management sections + action token + exact action/target/impact/reason/approval binding |
| ClientApp runtime | environment + runtime principal/credential + resolvedTarget + delegation/grant；ask 再加 capability intent |
| Worker Gateway | task capability + exact workerRoute + Worker principal/credential/lease |
| production promotion | instance-root security + full productionPolicy + audit readiness + independent approval |

### Typed management API and credential-source contract

新 typed management namespace 固定为 `/api/v1/management/v1/**`。它不是 Open API，不受 `NAVIGATOR_EXTERNAL_ENABLED` 控制，也不因路径存在而获得外部网络暴露；默认沿用 internal/management network boundary。首期只接受 `X-Navi-Principal-Credential` 或 `Authorization: Bearer <management-token>` 其中之一，拒绝 legacy admin、ClientApp control/runtime、task 和 Worker credential。

| Logical operation | Method/path family | Accepted source | Invariant |
|---|---|---|---|
| control exchange | `POST /api/v1/management/v1/auth/exchange` | typed long-term control/provisioning credential | production 返回短期 `CONTROL_ACCESS`；internal-dev 是否允许直接使用长期 credential 仍由服务端 trusted-loopback policy 决定 |
| security authorization | `POST /api/v1/management/v1/auth/security-actions/authorize` | typed security credential + step-up/approval material | 只签发 exact action/target/impact/reason 的 single-use `SECURITY_ACTION`，不签发万能 security session |
| identity inspection | `GET /api/v1/management/v1/auth/whoami` | exactly one typed credential or management token | 不回显 secret；显示 principal、lane、instance/environment、status/expiry |
| permission inspection | `GET /api/v1/management/v1/auth/permissions` | exactly one typed credential or management token | 分开显示 authority ceiling 与 effective credential actions；无 target 时不承诺 allow |
| decision preflight | `POST /api/v1/management/v1/auth/explain` | exactly one typed credential or management token | `PREFLIGHT + nonBinding=true`；mutation 必须产生新 enforcement decision |
| credential lifecycle | `/api/v1/management/v1/credentials/**` | same-principal security action token；bootstrap/recovery 使用 operator + independent approval | rotate/revoke/recover 均为 stable action，不能由 control/provisioning lane 回退 |
| platform grant / tenant authority | `/api/v1/management/v1/platform-grants/**`、`/tenant-authorities/**` | root security；S2 自身 provisioning/security 只执行已定义的 create/manage/offboard subset | tenant owner transfer 与 cross-upstream adoption 始终需要 instance-root security |

`X-Navi-Admin-Key` 只在登记的 legacy route 保持原语义；`X-Navi-Admin-Api-Key` 继续明确拒绝。新 typed namespace 不做 header alias、优先级或权限并集。

### Route-to-action family inventory

下表冻结 route family 的目标语义。具体 method/path 已展开为 [machine-readable manifest](../evidence/GOV-001-p0.5-method-route-manifest.csv)，其静态评审见 [review evidence](../evidence/GOV-001-p0.5-method-route-manifest-review.md)。任何新增 endpoint 未注册时 canonical evaluator 返回 `AUTHZ_ACTION_UNREGISTERED`，不能使用 Controller 默认 allow。

| Current/new surface | Stable action family | Accepted principal/lane | Target resolver and cutover note |
|---|---|---|---|
| `/api/v1/management/v1/auth/**` | `auth.exchange`、`auth.security-authorize`、`auth.whoami`、`auth.permissions.inspect`、`auth.decision.explain` | typed root/platform lane only | principal/credential/grant resolver；new route 从首日 canonical-only |
| `/api/v1/management/v1/credentials/**` | `credential.issue|rotate|revoke|recover` | root/platform security action；operator recovery boundary | credential target + principal authority；无 legacy fallback |
| `/api/v1/management/v1/platform-grants/**`、`tenant-authorities/**` | `platform-grant.*`、`tenant-authority.*` | root security；SaaS lane 仅自身 upstream subset | grant + tenant authority resolver；transfer/adoption 为 security action |
| `/api/v1/admin/**` | `bootstrap.*`、`credential.*`、`tenant.*`、`client-app.*`、`resource.repair` | Navigator user/operator；未来 root security subset | 当前 operator enforcement 保留，先 shadow；不能因 Spring `permitAll` 省略 manifest |
| `/api/v1/upstream-admin/**` | `client-app.*`、`agent.*`、`model.*`、`directory.*`、`worker.*`、`binding.*`、`grant.*` | legacy upstream-admin；typed root/platform 依 action set | exact upstream/tenant/resource owner resolver；legacy scope 映射为显式 action set，不自动提升 |
| `/api/v1/client-apps/**`、`/api/v1/business-agent/**` control routes | `client-app-config.*`、`agent.*`、`model.*`、`directory.*`、`binding.*`、`grant.*` | exact ClientApp control；typed root/platform 管理例外 | tenant + exact ClientApp + owner/grant；runtime credential 拒绝 |
| `/api/v1/open/**` | `runtime.token.exchange`、`runtime.ask`、`runtime.task.read|cancel`、`runtime.session.*`、`runtime.artifact.*` | exact ClientApp runtime/access token | platform gate + runtime principal + ClientApp/user/resource resolver；不接受 management credential |
| Observer BFF `/api/v1/observer/**`、其代理的 `/api/v1/open/**` | `observer.auth.*`、`observer.attachment.*`、`observer.runtime.*` | target 仅允许 bound observer session/capability；当前无合格入站 principal | 独立 deployment，不继承 launcher filter；当前 12 条全部 `LOCAL_TOOL_RESTRICT`，只允许 local/trusted dev，production blocked |
| `/internal/worker-gateway/v1/**` | `gateway.function.invoke` 等 exact capability action | task token；strict 时再加 Worker principal/lease | capability + route + Worker owner/backend；本 P0.5 不改变 Gateway default 或 client propagation |
| `/api/v1/agents/**`、`/api/v1/tasks/**`、`/api/v1/sessions/**`、`/api/v1/shared/**` | `agent.*`、`task.*`、`session.*`、`share.*` | Navigator user/share key 或明确 runtime delegate | method-level inventory 必须证明 task/session/message/diagnostic/evidence 使用一致 owner predicate；未证明前不得对外 |
| health/readiness surfaces | `health.read`、`readiness.inspect` | public/internal observer as separately registered | 只报告各层状态，不产生 authority；任何 ready 字段不得聚合成 production approval |

### Migration, shadow cutover, legacy sunset, and rollback

1. **Additive schema:** 先提交 forward/rollback SQL、JPA schema tests 和 `ddl-auto=validate` 计划；1.4.3 不删除或改写 legacy 表，不回填明文 secret。
2. **Explicit seed:** S1/S2 principal、grant、tenant authority 只由 operator-approved mapping 创建。`foggy-world-sim`、`tms-x3` 字符串、legacy `ALL` scope 或 `authorizedTenantIds` 不能自动触发提升。
3. **Legacy adapter:** legacy upstream-admin/control/runtime/task/Worker 由 adapter 构造 canonical context；adapter 输出明确的 `principalType`/lane/assurance，不能补造缺失 instance、owner 或 route 权威事实。
4. **Per-family mode:** 既有 route family 依次处于 `LEGACY_ENFORCE` → `SHADOW_EVALUATE` → `CANONICAL_ENFORCE`。unknown mode 启动失败；new typed/security route 只能为 `CANONICAL_ENFORCE`。
5. **Shadow rule:** shadow 仍以当前 enforcement 响应为准，同时记录 canonical decision diff；canonical allow/deny 与 legacy 不一致必须归因、修复或具名接受，不能用“兼容”忽略。
6. **Cutover threshold:** method-level manifest、正负向 contract tests、migration checks 全通过，且目标 route family 在一个完整联调周期内无未解释 decision divergence；共享环境默认观察不少于 14 天，本地可由可重复全量 replay 代替时间窗口。
7. **Rollback:** 既有非 security route 可回到 `SHADOW_EVALUATE`，但只能恢复已登记 legacy 行为；new root/security action 永不回退 legacy。代码回滚保留新表和 append-only audit，撤销新 token/credential，不以 drop table 作为常规回滚。
8. **Legacy sunset:** 1.4.3 只允许观测和受控并存，不移除 `X-Navi-Admin-Key`。停止新签发最早在后续 minor 且完成一个发布周期；移除 header 必须独立 APPROVED workitem、连续 30 天零有效使用和可验证 rollback。已退役 `X-Navi-Admin-Api-Key` 不参与此窗口，始终拒绝。

### Governance ownership

| Role | Owns | Must not own alone |
|---|---|---|
| Navigator authorization owner | principal/lane 语义、policy、action catalog、reason code、route registration review | production promotion 的最终独立签收 |
| Resource module owner | 本模块 target resolver、owner/binding/grant predicate、method-level manifest 与测试 | 修改全局 trust ceiling 或另建 lane policy |
| Data migration owner | forward/rollback SQL、seed/mapping report、conflict quarantine、`ddl-auto=validate` evidence | 将 legacy key 自动提升为 root/platform principal |
| Runtime/Worker owner | task capability、Worker principal/lease、client propagation 与 execution policy | 用新增 Worker/Biz identity/Pool member掩盖 Physical Worker route 问题 |
| Deployment security owner | instance identity、trusted proxy/network profile、secret verifier root、KMS/TDE/audit sink、production policy | 单人自批 production promotion 或绕过应用 policy |
| Upstream owner (SIM/TMS) | 申请/保管自身 credential、确认 resource/tenant mapping、完成业务负向矩阵 | 修改 Navigator policy、声明自己的 trust profile 或把 platform credential下发给租户 |
| Independent signoff owner | 将每项 critical AC 映射到运行证据并给出最终结论 | 参与同一实现后自行替代独立签收 |

policy/action catalog 的变更必须由 authorization owner 审查；CLI help snapshot 从同一 manifest 构建。生产前必须把上述角色映射到具名责任人，但 P1 本地实现可先使用项目角色，不在代码中硬编码个人或公司组织结构。

### Architecture decisions intentionally deferred

以下事项不阻塞 P1A/P1B，禁止借此扩大 P0.5：

1. S3 真实第三方的 OIDC/OAuth 2.1 federation、issuer、client registration、配额和公网 onboarding。
2. upstream user 强身份 assertion 及 nonce/key-rotation 机制；当前继续标记 `client-app-delegated`，不得宣称强认证。
3. production secret broker/KMS/TDE 产品选型、外部不可变 audit sink 和合规留存年限；在其冻结前 production gate 始终关闭。
4. Gateway 配置最终改名、Worker Gateway token purpose、全客户端 strict principal propagation 和 legacy Pool 退役。
5. TMS shared/dedicated Worker 容量算法和 UI；authorization 只冻结 owner、grant、lane、destructive action 与恢复边界。

## Target Authorization Architecture

### Unified authorization context and decision

统一授权合同冻结为 `schemaVersion=navi.authorization.v1`。服务端维护唯一权威 schema、policy 和 action catalog；CLI、SDK、审计与测试只消费该合同，不再各自推导一套权限语义。

版本规则：

1. JSON 字段使用 `camelCase`，枚举使用稳定的 `UPPER_SNAKE_CASE`，action 使用稳定的 dotted id，例如 `resource.transfer-owner`。
2. `schemaVersion` 只描述 DTO/claim 结构；`policyVersion`、`actionCatalogVersion`、`serverBuild` 分别描述授权规则、动作目录和运行制品，不能互相替代。
3. v1 内只允许增加 optional 字段或新 reason code；删除字段、改变字段含义、放宽默认 allow、改变 audience/instance binding 均必须升级 schema major version。
4. 服务端遇到未知 principal type、credential lane、action、route kind、trust profile 或必需 claim 时必须拒绝。旧 CLI 遇到未知枚举时可以显示原值，但不得据此执行写操作或本地报告 `ALLOW`。
5. ID 均为 opaque identifier；credential/token prefix 只用于防误操作和脱敏显示，不参与授权判定。

`AuthorizationContext` 是服务端内部 canonical context，不是客户端可提交的“权限声明”。建议结构如下：

```text
AuthorizationContextV1
- schemaVersion = navi.authorization.v1
- evaluationMode = ENFORCEMENT | PREFLIGHT
- correlationId
- request
  - surface / routeId / httpMethod / action / requestDigest
  - requestedTarget {resourceType, resourceId, upstreamSystemId, tenantId, clientAppId}
- environment
  - navigatorInstanceId / environmentProfile / networkTrustProfile
  - upstreamTrustProfile / externalSurfaceState / productionState
- principal
  - principalType / principalId / sourceUpstreamSystemId / assuranceLevel
- credential
  - credentialId / credentialLane / fingerprint / generation / status
  - credentialEnvironmentProfile / issuedAt / expiresAt
  - directCredentialAllowed
- authority
  - authorityScope = INSTANCE | UPSTREAM_SYSTEM | TENANT_CLIENT_APP | TASK_ROUTE
  - upstreamSystemId / tenantId / clientAppId
  - platformGrant {grantId, version, status, tenantScopeMode}
  - actionSetId / actionSetVersion
- delegation
  - upstreamUserId / upstreamUserAssurance / grantIds
- resolvedTarget
  - resourceType / resourceId / navigatorInstanceId
  - upstreamSystemId / tenantId / clientAppId
  - ownerType / ownerId / bindingIds / grantIds
- capability
  - tokenType / tokenId / audience / generation / issuedAt / expiresAt
  - taskId / sessionId / modelConfigId / directoryId / functionScopeDigest
- workerRoute
  - routeKind = PHYSICAL_WORKER | WORKER_POOL
  - workerId / poolId / leaseId / workerBackend
  - workerPrincipalId / workerCredentialGeneration
- policy
  - policyVersion / actionCatalogVersion / executionPolicyId / productionPolicyId
```

字段可信来源固定如下：

| Field group | Authority source | Rule |
|---|---|---|
| `principal` / `credential` | 服务端 credential/token resolver | 客户端 header 只提供待验证材料；解析结果不得由请求体覆盖 |
| `environment.navigatorInstanceId` | 当前部署实例身份 + credential/token audience | profile 中的 instance id 仅用于一致性检查，不能改变服务端实例 |
| `networkTrustProfile` / `directCredentialAllowed` | 服务端根据 environment profile、真实连接来源和可信代理链计算 | `localhost` URL、CLI `--profile` 或客户端自报 loopback 都不能授予 direct-use |
| `upstreamTrustProfile` | 服务端已注册 principal/upstream policy | 客户端不得提交 `S1`、`trusted` 等标签请求放行 |
| `authority` / platform grant | 服务端 principal record、grant record 和权威 tenant ownership 映射 | `UPSTREAM_OWNED` 不是 wildcard；每次判定按当前 owner/grant/version 重建 |
| `requestedTarget` | 客户端请求 | 始终为 untrusted selector；必须解析为 `resolvedTarget` 后才可授权 |
| `resolvedTarget` / owner / binding | 服务端资源和关系记录 | owner 无法唯一解析、资源跨实例或 route 歧义时拒绝 |
| `capability` / `workerRoute` | 服务端 token record、task binding、Worker credential/lease | task token 和 Worker principal 求交，任一缺失或不匹配均拒绝 |

统一在线输出为：

```text
PolicyDecisionV1
- schemaVersion = navi.authorization.v1
- decisionId / correlationId
- evaluationMode = ENFORCEMENT | PREFLIGHT
- decision = ALLOW | DENY
- nonBinding
- reasonCode / publicMessage / remediationHints
- principalSummary {principalType, principalId, credentialLane, credentialId, fingerprint}
- authoritySummary {authorityScope, navigatorInstanceId, upstreamSystemId, tenantId, clientAppId}
- required {principalTypes, credentialLanes, actions, stepUp, approval}
- matched {actionSetId, grantIds, platformGrantId, platformGrantVersion}
- resolvedTargetSummary / resolvedRouteSummary
- policyVersion / actionCatalogVersion / serverBuild / evaluatedAt
```

`PREFLIGHT + ALLOW` 只表示在该时刻、该目标和该 policy version 下没有发现阻断，必须返回 `nonBinding=true`。真正 mutation 必须重新构造 `AuthorizationContext` 并执行 `ENFORCEMENT`；不得复用 preflight decision、decisionId 或客户端缓存绕过实时授权。`ENFORCEMENT` decision 才能作为本次请求的审计事实，但它也不是可转发的 capability token。

拒绝码采用稳定、安全、不泄露其他 owner 资源存在性的分层命名。v1 至少固定以下公共 reason code；更细的内部诊断只能进入受控审计：

| Category | Stable public reason codes |
|---|---|
| Surface / profile | `SURFACE_EXTERNAL_DISABLED`, `AUTHZ_ENVIRONMENT_MISMATCH`, `AUTHZ_INSTANCE_MISMATCH`, `AUTHZ_TRUST_PROFILE_DENIED` |
| Authentication | `AUTHN_CREDENTIAL_MISSING`, `AUTHN_CREDENTIAL_CONFLICT`, `AUTHN_CREDENTIAL_INVALID`, `AUTHN_CREDENTIAL_EXPIRED`, `AUTHN_CREDENTIAL_REVOKED`, `AUTHN_CREDENTIAL_GENERATION_MISMATCH` |
| Principal / lane | `AUTHZ_PRINCIPAL_TYPE_DENIED`, `AUTHZ_CREDENTIAL_LANE_DENIED`, `AUTHZ_LEGACY_ROUTE_ONLY` |
| Action / target | `AUTHZ_ACTION_UNREGISTERED`, `AUTHZ_ACTION_DENIED`, `AUTHZ_RESOURCE_SCOPE_DENIED`, `AUTHZ_OWNER_SCOPE_DENIED` |
| Grant / delegation | `AUTHZ_GRANT_MISSING`, `AUTHZ_GRANT_STALE`, `AUTHZ_PLATFORM_GRANT_DENIED`, `AUTHZ_UPSTREAM_USER_DELEGATION_DENIED` |
| High-impact action | `AUTHZ_STEP_UP_REQUIRED`, `AUTHZ_APPROVAL_REQUIRED`, `AUTHZ_ACTION_TOKEN_MISMATCH`, `AUTHZ_ACTION_TOKEN_REPLAYED` |
| Runtime / Worker | `CAPABILITY_TASK_SCOPE_DENIED`, `WORKER_PRINCIPAL_REQUIRED`, `WORKER_PRINCIPAL_INCOMPLETE`, `WORKER_PRINCIPAL_MISMATCH`, `WORKER_ROUTE_AMBIGUOUS` |
| Readiness / production | `READINESS_EXECUTION_POLICY_NOT_READY`, `DEPLOYMENT_PRODUCTION_GATES_INCOMPLETE`, `AUDIT_SINK_NOT_READY` |
| Contract | `AUTHZ_SCHEMA_UNSUPPORTED`, `AUTHZ_POLICY_VERSION_UNSUPPORTED`, `AUTHZ_CATALOG_VERSION_MISMATCH` |

服务端在 ingress 生成 `correlationId` 和每次判定唯一的 `decisionId`。客户端可提交单独的 `clientRequestId` 便于串联，但不能指定或覆盖服务端 decision identity。preflight 与 mutation 应共享 client request/correlation 链路，但必须产生两个不同 `decisionId`。

### Unified credential, token, and platform-grant claims

统一 credential record 复用一套基础设施，但 principal type 和 credential lane 仍是强语义，不得通过 scope 字符串或 header 名猜测：

```text
CredentialRecordV1
- schemaVersion / credentialId / fingerprint / secretHashRef
- principalType / principalId / credentialLane
- navigatorInstanceId / environmentProfile
- authorityScope / upstreamSystemId / tenantId / clientAppId
- platformGrantId / platformGrantVersion
- actionSetId / actionSetVersion
- generation / status / issuedAt / expiresAt / revokedAt
- supersedesCredentialId / rotationWindowEndsAt
- issuedBy / approvedBy / reasonRef / createdAt / updatedAt
```

长期 credential 默认使用 opaque random secret，服务端只保存 hash 或 secret-store reference。短期 token 也优先使用 opaque reference token + server-side record，以满足 60s 撤销收敛、generation 联动和 security token single-use；`claim` 是逻辑合同，不要求改成自包含 JWT。未来如使用签名 token，仍必须执行在线 generation/revocation/nonce 检查，不能因签名有效跳过服务端状态。

所有短期 token 共享以下 envelope：

```text
TokenEnvelopeV1
- schemaVersion / tokenId / tokenPurpose / audience
- navigatorInstanceId / environmentProfile
- principalType / principalId / credentialLane
- credentialId / credentialGeneration
- authorityScope / upstreamSystemId / tenantId / clientAppId
- platformGrantId / platformGrantVersion
- issuedAt / notBefore / expiresAt / status
```

按 token purpose 增加的必需绑定：

| Token purpose | Additional required claims | Explicit invariant |
|---|---|---|
| `CONTROL_ACCESS` | action-set reference、可选 target scope | production 管理 API 只接受短期 access token；不能进入 security action 或 runtime |
| `SECURITY_ACTION` | exact action、target digest、impact digest、reason reference/digest、approval id、single-use nonce | 默认 5m、硬上限 15m；任一请求摘要不匹配或 nonce 已消费即拒绝 |
| `CLIENT_APP_RUNTIME` | exact tenant + ClientApp、runtime grant/version | 只用于 Open API runtime；不能进入 control/provisioning/security route |
| `TASK_CAPABILITY` | task/session、delegated user assurance、Agent/model/directory/function scope snapshot、exact routeKind/worker-or-pool/lease | capability 只能收窄 runtime 权限；必须与 Worker principal 求交 |
| `WORKER_GATEWAY`（reserved / future-only） | exact Worker identity、owner、backend、credential generation、lease | v1 当前不签发、不接收该 purpose；现行 Worker principal 仍使用完整 credential + lease headers。未来启用须经独立 APPROVED 合同，且不能替代 task capability，也不能作为 Navigator→Worker 出站 bearer |

`PlatformGrantV1` 固定为服务端版本化记录：

```text
PlatformGrantV1
- schemaVersion / grantId / version / status
- navigatorInstanceId / environmentProfile
- principalType = SAAS_PLATFORM / principalId / upstreamSystemId
- tenantScopeMode = UPSTREAM_OWNED
- actionSetId / actionSetVersion
- issuedBy / approvedBy / reasonRef
- createdAt / updatedAt / suspendedAt / revokedAt
```

credential/token 只引用 `platformGrantId + version`，不复制 tenant wildcard 或动态 tenant 清单。服务端以当前 tenant owner 记录计算是否属于 TMS；调用者提交的 tenantId、旧 `authorizedTenantIds` 或本地 profile 均不能扩大 grant。

现有 task token v2 可作为 internal compatibility 输入，但 canonical task capability 下一版本必须补齐 `navigatorInstanceId`、明确 `routeKind`、credential/grant generation reference 和结构化 function-scope digest。升级不得创建额外 Worker、BizWorkerIdentity 或 WorkerPool member；Codex 仍解析为 existing Physical Worker route。

### Canonical introspection and CLI boundary

服务端提供三个逻辑只读操作。typed S1/S2 management 使用 P0.5 固定的 `/api/v1/management/v1/auth/**`；legacy upstream-admin、ClientApp control/runtime 如需同等能力，必须通过各自 surface 的 adapter/alias 调用同一 service contract。不得建立绕过 `/api/v1/open/**` gate、同时尝试多种 header 的“万能鉴权端点”：

| Logical operation | Purpose | Result |
|---|---|---|
| `auth.whoami` | 解析当前这一份 credential/token 的真实 principal、lane、instance/environment binding 和状态 | `PrincipalInspectionV1`，不返回 secret 或完整 token |
| `auth.permissions.inspect` | 显示主体 authority ceiling、当前 credential 的 effective action set、grant/status 和明确禁止项 | `PermissionInspectionV1`；无具体 target 时不是 allow 保证 |
| `auth.decision.explain` | 对一个 canonical action + target 进行在线、只读 policy preflight | `PolicyDecisionV1` with `evaluationMode=PREFLIGHT` and `nonBinding=true` |

CLI 合同冻结为：

1. `navi upstream auth whoami` 在线调用 `auth.whoami`。若 legacy profile 同时保存 admin/control/runtime 等多种 credential，必须逐项显示为独立 identity 或要求显式选择 credential source；绝不能合并为一个“综合超级权限”。
2. `navi upstream inspect permissions` 在线显示两层结果：`authorityCeiling` 表示主体最多可被允许什么，`effectiveCredentialActions` 表示当前 credential lane 实际承载什么。S1 必须显示 root 主体的 instance authority，同时明确 control credential 不含 security actions。
3. mutating command 的 `--explain-auth` 必须在线调用 `auth.decision.explain`，只输出 preflight，不执行 mutation；断网、server schema 不兼容或无法解析 target 时返回 `UNVERIFIED`/稳定错误，不能本地猜测 `ALLOW`。
4. 正常 mutation 可使用相同 action metadata 生成提示，但最终请求仍由服务端重新授权。CLI 本地 preflight 失败可以阻止明显误用；本地通过不能代替服务端 decision。
5. `config check` 是离线 profile 检查，只能输出 `VALID`、`INVALID`、`UNVERIFIED`：
   - `VALID`：本地字段、profile 绑定、credential source 和命令 lane 没有可确定冲突；不表示服务端允许。
   - `INVALID`：本地已确定 instance/environment/lane 冲突、多个互斥 credential source、缺必需字段或 secret 文件不安全；写请求不得发送。
   - `UNVERIFIED`：需要服务端当前 credential/grant/policy/network 状态才能判断；不得显示 `ALLOW`。
6. `trusted-loopback` 只由服务端根据真实连接和可信代理配置认定。CLI 即使连接 `127.0.0.1` 也只能显示 local candidate，不能自行设置 `directCredentialAllowed=true`。
7. CLI 内置由服务端 action catalog 生成的 help snapshot，包含 command→action、required principal/lane、risk tier、target resolver 和 denied lane。在线时比较 `actionCatalogVersion`；版本不一致时 help 可继续展示，但写操作必须依赖服务端 explain/enforcement，CLI 不得用旧 snapshot 放行。
8. text 与 machine-readable 输出共享同一 schema；JSON 输出保留 `schemaVersion`、`policyVersion`、`actionCatalogVersion`、`correlationId` 和 reason code，text 输出不得隐藏决定权限边界的字段。

推荐的新 typed management profile 使用一份 active `NAVI_PRINCIPAL_CREDENTIAL`，并显式保存非敏感期望值 `NAVI_NAVIGATOR_INSTANCE_ID`、`NAVI_ENVIRONMENT_PROFILE`、`NAVI_EXPECTED_PRINCIPAL_TYPE`、`NAVI_EXPECTED_CREDENTIAL_LANE`。这些字段只用于 CLI 一致性检查，真实 principal/lane 始终以服务端 credential record 为准。现有多-key 本地 profile 可在迁移期继续使用，但 CLI 必须分 lane 展示，不能将多把 key 的权限做并集。

### Legacy compatibility and retirement

兼容迁移固定遵循“识别旧语义、绝不自动提升、先观测再退役”：

1. `NAVI_ADMIN_API_KEY` / `X-Navi-Admin-Key` 只解析为 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN`，保留已登记 legacy route/scope；不能因 `upstreamSystemId=tms-x3` 自动解析为 `SAAS_PLATFORM`。已退役的 `X-Navi-Admin-Api-Key` 当前由服务端明确拒绝，不得在兼容迁移中重新接受。
2. `NAVI_CONTROL_API_KEY` / `X-Client-App-Control-Key` 继续只解析为 exact ClientApp control；ClientApp runtime key/secret/access token 继续只解析为 runtime。迁移到 v1 schema 不改变现有 allow/deny。
3. 新 typed management credential 的长期材料通过 `NAVI_PRINCIPAL_CREDENTIAL` / `X-Navi-Principal-Credential` 进入 exchange 或显式 trusted-loopback direct-control 路径；production access/action token 使用 `Authorization: Bearer`。同一请求出现多个 credential source 时返回 `AUTHN_CREDENTIAL_CONFLICT`，不得按优先级静默选择。
4. `admin-key inspect` 在兼容期作为 `auth whoami` 的 legacy alias，输出必须增加 principal type、lane、instance/environment、deprecated 状态和 `securityAdminEligible=false`。新文档和自动化不得继续把它描述为 TMS 平台万能管理凭据。
5. TMS 迁移先核验 upstream/tenant/owner，再独立签发 `SAAS_PROVISIONING`；`SAAS_SECURITY_ADMIN` 另行审批签发。旧 key 与新 credential 可在有期限的 shadow/observation window 并存，但权限不做并集。
6. task token、runtime token、Worker principal 的新 claim 采用 client-first/server-compatible rollout；Gateway strict 只有所有 client 完整传播 Worker principal/lease 且负向矩阵通过后才能切换。
7. legacy route 必须有 usage telemetry、告警、owner、退役日期和回滚窗口。到期后先停止新签发，再拒绝新 typed action，最后移除 legacy header；任一阶段均不得把旧 key 升格以“保持兼容”。

目标判定公式：

```text
surface and network profile
  ∩ registered upstream trust profile ceiling
  ∩ authenticated credential scope
  ∩ instance/upstream/tenant policy
  ∩ owner and resource grant
  ∩ ClientApp/upstream-user delegation
  ∩ task capability
  ∩ exact Worker route and lease
  ∩ execution policy
  ∩ deployment/production approval
```

任何必需字段缺失、主体冲突、route 歧义、owner 无法解析或 policy 未注册时，一律 `DENY`。服务端不得通过“找不到 Pool 就猜为 Physical Worker”、信任请求体 owner/cwd 或自动回退万能 scope 来完成授权。

`navigatorInstanceId` 是授权域的首要边界，而不是可选审计标签。所有 root/platform/control/runtime/Worker principal、grant、session 和 task capability 都必须由服务端绑定目标实例；来自其他实例的同名 principal、相同用户身份或 credential/token 一律不能形成权限继承。未来即使接入统一身份提供方，也只允许复用身份认证结果，不复用实例内授权记录。

上游连接契约默认采用“一套 connection profile 对应一个 `navigatorInstanceId`”。上游如同时连接实例 A、B，应维护两套 endpoint、instance identity 和 credential profile，并在自身系统内选择调用目标；Navi 不负责跨实例路由、failover 或 credential 聚合。Navi 的责任止于每个实例独立完成 credential lifecycle、instance audience 校验、撤销传播和审计，并稳定拒绝 profile/endpoint/instance 不一致的请求。

### Principal hierarchy for dedicated upstream instances

```text
foggy-world-sim instance root (authority scoped by navigatorInstanceId)
  ├─ manages all ClientApps and instance configuration
  ├─ manages every upstream system and tenant inside the instance
  ├─ administers owned and non-owned resources
  ├─ create / discover / bind / operate / transfer / delete / rotate / delegate
  └─ may initiate external/production promotion, subject to non-bypassable gates

Ordinary ClientApp control
  └─ manages one exact ClientApp and its approved resources

ClientApp runtime + delegated upstream user
  └─ creates asks/readiness/messages within runtime grants

Task capability + Worker principal
  └─ executes one authorized task on one exact route/lease

Infrastructure recovery plane
  └─ protects OS/DB/KMS/secret-store recovery and audit durability outside upstream API credentials
```

“Instance root”必须绑定 exact `navigatorInstanceId`；其 root subject 可以记录来源 `upstreamSystemId=foggy-world-sim`，但该字段是身份和审计锚点，不是 authority scope 限制。`INSTANCE_ROOT` 是独立 principal/credential lane，不能由普通 upstream-admin、具名 ClientApp 或 `CONTROL_PLANE_ALL` 推导。任何 root credential 都不能复用为 ClientApp runtime token，也不能把管理面 root scope 自动注入 ask/task token。root 管理实例内其他 upstream/tenant 时使用 root action，不取得或模拟对方的 control/runtime 身份。

### Confirmed INSTANCE_ROOT credential profiles and lifecycle

双凭据 profile 已确认为强制契约；它不减少 SIM 的实例权限，只把同一 root principal 的日常操作与高风险操作分成不同 credential profile：

| Profile | Recommended use | Explicit deny / additional requirement |
|---|---|---|
| `INSTANCE_ROOT_CONTROL` | 日常 create/discover/update/bind/operate、普通 provisioning、实例内 upstream/tenant/ClientApp 与资源配置 | 不直接执行 owner transfer、delete/revoke、root/credential rotation、grant delegation、trust-root 或 production promotion |
| `INSTANCE_ROOT_SECURITY` | owner transfer、delete/revoke、credential rotate/revoke、grant delegation、紧急恢复、trust-root 与 production promotion | 必须独立保管，并要求 step-up、影响预览、reason、短期 action authorization 和可靠审计；不得用于日常自动化或 ask |

已确认生命周期约束：

1. root principal 是稳定主体，credential 是可替换认证材料；轮换 credential 不改变 owner/root 身份和授权记录。
2. 所有 root credential/token 绑定 exact `navigatorInstanceId` audience，不携带可由调用者扩大范围的 tenant/upstream wildcard。
3. secret 只在签发时显示一次，服务端只保存 hash/版本/状态；CLI、SKILL、日志和审计只显示 fingerprint。
4. control credential 应有默认 TTL 和硬上限，禁止静默无过期；允许最多两把 active credential 在短暂轮换窗口内并存，窗口结束自动撤销旧版本。
5. security profile 不作为常驻业务 key 使用；高风险操作应换取绑定 action、target、impact digest 和短 TTL 的 step-up authorization。
6. 撤销、过期、instance audience mismatch、credential generation mismatch 或 step-up 缺失时立即 fail closed；缓存不得让已撤销 root credential 长时间继续生效。
7. root credential 丢失后的恢复只能走实例部署方的 break-glass/recovery plane，并记录 append-only 审计；production 还必须满足外部 tamper-evident retention gate。S2 platform、普通 upstream-admin 或 ClientApp 均不能重建 `INSTANCE_ROOT`。
8. internal-dev 可保留兼容入口，但必须显式标记 dev-only，不能通过同一 profile 或 credential promotion 到 production。

生命周期参数按“本地兼容、生产严格”冻结：

| Parameter | Internal-dev / trusted-loopback | Production / non-dev | Invariant |
|---|---|---|---|
| `INSTANCE_ROOT_CONTROL` long-term credential | 默认 180d，硬上限 365d | 默认 30d，硬上限 90d | 禁止 no-expiry；绑定 exact instance、environment profile 和 credential generation |
| control request authentication | 可在显式 `internal-dev` + trusted-loopback profile 下直接使用 control credential；也可换取默认 15m、硬上限 30m 的 access token | 必须换取默认 15m、硬上限 30m 的 access token，长期 credential 不直接调用业务 API | direct-use 只适用于 control，不得升级为 security；dev credential/token 必须被 production profile 拒绝 |
| `INSTANCE_ROOT_SECURITY` long-term authenticator | 默认 180d，硬上限 365d，独立/受控保管 | 默认 30d，硬上限 90d，离线或受控保管 | 任何环境都不得直接作为高风险业务 API bearer，必须换取 action-bound authorization |
| security action authorization | 默认 5m、single-use | 默认 5m、single-use | 硬上限 15m；绑定 action + target + impact digest + reason + credential generation，任一不匹配即拒绝 |
| rotation overlap | 默认 24h，最多两把 active credential | 默认 24h，最多两把 active credential | 硬上限 72h；窗口结束自动撤销旧版，不允许第三把 active |
| revocation propagation | 撤销接口成功后立即阻止新授权 | 撤销接口成功后立即阻止新授权 | 多节点/缓存最迟 60s 内收敛；无法确认有效状态时拒绝 root 操作，已签发 token 受 credential generation/revocation 联动约束 |
| break-glass recovery owner | 具名 Navigator instance deployment custodian；允许单人执行，但必须写可靠审计并明确 `dev-only` | deployment security operator 发起，独立 approver 批准，二者不得是同一 principal | recovery 只能重建/撤销 root credential 和恢复 root access，不得伪造 ClientApp runtime/task/Worker principal；S2 平台或普通 upstream 不得执行 |

internal-dev 的放宽只减少本地凭据轮换与短 token exchange 的操作成本，不扩大 action scope，不绕过 audit/readiness，也不形成任何 production 兼容性承诺。环境、endpoint、credential audience 或 profile 任一冲突时必须拒绝。

### Principal hierarchy for business SaaS upstreams

```text
Navigator instance root/operator
  └─ SAAS_PLATFORM subject (principalId=tms-x3-platform, upstreamSystemId=tms-x3)
       ├─ SAAS_PROVISIONING credential lane
       ├─ SAAS_SECURITY_ADMIN credential lane
       ├─ Tenant A
       │    ├─ ClientApp A1 limited control (optional)
       │    └─ ClientApp A1 runtime + delegated users
       ├─ Tenant B
       │    ├─ ClientApp B1 limited control (optional)
       │    └─ ClientApp B1 runtime + delegated users
       └─ TMS-owned Worker/Directory/Model inventory
            └─ allocated by explicit tenant/ClientApp grants and bindings
```

TMS platform admin 的横向管理范围只能覆盖其获准 tenant 集合；tenant ClientApp credential 的纵向范围只能落在一个 exact tenant + ClientApp。平台与租户必须使用不同 principal，平台内部 provisioning/security 必须使用不同 credential lane；审计需明确记录 principal type/id、credential lane/id、acting platform、target tenant、target ClientApp、action 和 resource owner。

### Principal hierarchy for external third parties (future only)

```text
Navigator operator / approved onboarding service
  └─ registered external upstream system (future workitem only)
       └─ exact tenant + ClientApp runtime
            ├─ owned or explicitly granted Agent/Model/Directory/function
            └─ task capability + exact Worker/lease route

No default upstream-admin
No platform-admin or instance-root inheritance
No Worker lifecycle or cross-owner/tenant authority
```

当前这棵层级没有可用 credential 或开放 route，只用于约束 S1/S2 schema 不得假设“所有 upstream 都可信”。未来 limited control、第三方用户强身份和 production ingress 必须由独立 workitem 决定。

### Action model

以下动作必须在 policy 和审计中独立表达，不能由一个含糊的 `ALL` 或 `bind` 推导：

| Action | Meaning | Audit minimum |
|---|---|---|
| `resource.create` | 创建资源并显式设定 owner | actor、credential、new owner、resource type/id |
| `resource.discover` | 查看实例范围内可管理资源的最小元数据 | actor、query scope、result count；敏感存在性按 policy 脱敏 |
| `resource.bind` | 建立 Agent/Model/Directory/Worker/ClientApp 等关系 | actor、binding type、both resource IDs、owners、before/after |
| `resource.operate` | 使用或更新资源业务状态 | actor、action、resource owner、matched grant、result |
| `resource.transfer-owner` | 显式改变 owner | old/new owner、reason、approval、recovery path |
| `resource.delete` / `resource.revoke` | 删除资源或撤销可用性 | dependencies、impact preview、approval、result |
| `credential.rotate` | 轮换特定资源或主体 credential | credentialId、actor、old/new version、revocation result；不记录 secret |
| `grant.delegate` | 将权限继续授予其他主体 | grantor、grantee、scope、expiry、delegation depth |
| `instance.configure` | 修改实例级 Navigator 配置 | setting key、before/after fingerprint、actor、reason；敏感值不入审计 |
| `deployment.promote` | 推进 external/production 状态 | preflight result、policy version、approver/signoff、artifact、rollback plan |

### Closed P0.5 gates and historical P1A handoff boundary

P0.5 已完成并通过静态完整性复核。初始 P1A handoff 时没有待决权限语义或 P1A 开工问题：

1. method-level route/action manifest 已完成；新增未登记 route/action 在 canonical evaluator 中必须 fail closed。
2. seed/legacy mapping 规则已完成；P1A 不 seed 业务数据。P1B 前仍必须执行只包含 ID/fingerprint/status/conflict 的实际环境 inventory，并逐条取得 Owner/operator 审批。
3. Owner 当时只授权 P1A。P1B typed credential/seed 与 P1C cutover/CLI/SKILL 均不得由 P1A 实现会话顺带开展；后续 accepted slice 与仍被 gate 阻断的阶段以 frontmatter、Current Status 和 Owner intake 为准。

以下实现细节由 Ultra 在批准合同内自主决定，不再作为架构 open question：具体 Java 类/包、repository 方法、DTO 拆分、SQL 语句组织、manifest 文件格式和测试 fixture 结构。若这些局部选择需要突破六 aggregate 预算、引入通用 policy DSL、改变 legacy allow/deny 或扩大到 S3/production，则必须 `NEEDS_REPLAN`。

### Deferred S3 decisions (not blockers for S1/S2)

以下问题等真实第三方需求出现后由独立 workitem 冻结，不阻塞当前 S1/S2 主实现，也不得由当前会话猜测实现：

1. 第三方是共享 Navigator tenant、独立 upstream system，还是专属部署；其数据驻留、配额、计费和支持责任。
2. 第三方是否需要 limited control、多个 ClientApp、共享资源或 delegated administrator；默认答案均为否。
3. 第三方 upstream user 的强身份协议、信任根、密钥轮换、撤销和合规留存。
4. 第三方 production ingress、Worker 隔离、审计交付、SLA、事件响应和退出/删除流程。

## Acceptance Criteria

以下为 real P1B-B–P4 的已批准整体目标 acceptance criteria，不重复 P1B-A/P1C-A 的独立 acceptance criteria。P1A、P1B-A、P1B-B0 或 P1C-A 的完成不得声称 S1/S2 real management、route cutover、external 或 production 已实现或验收。

### P1A authorized acceptance subset

- [x] P1A-1: 六类 aggregate 使用 additive JPA/schema 实现，并提供 forward/rollback SQL、schema/JPA tests 和 production `ddl-auto=validate` 证据；不删除、改写或自动回填 legacy 表。
- [x] P1A-2: server-owned deployment identity 提供稳定 `navigatorInstanceId + environmentProfile`；请求不可覆盖，local-dev 保持兼容，production 缺显式 identity 时 fail closed。
- [x] P1A-3: canonical sparse `AuthorizationContextV1` 与 `PolicyDecisionV1` 包含 schema/policy/action-catalog/server-build version、decision/correlation ID、stable reason code 和所需 section 校验；未知/缺失 enum、action、route 或 required section 返回 deny/unknown shadow decision。BUG-002 已建立逐 action required-section 合同并通过独立 re-signoff。
- [x] P1A-4: source-controlled action/policy catalog 覆盖当前 415 条 deployment-aware manifest（含 Owner 批准的 `framework:get:/actuator`）；新增入口未注册时测试失败，Observer BFF 与 launcher 同 path 不得合并。
- [x] P1A-5: legacy adapters 只映射当前 lane；upstream-admin 固定为 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN`，control/runtime/task/Worker 各自保持原 lane，不得自动产生 root/platform/security authority。
- [x] P1A-6: 除 Owner 批准的 discovery-links `/actuator` 200→404 唯一兼容例外外，现有 enforcement response 是唯一生效结果；canonical evaluator 只 shadow 计算并记录 legacy/canonical decision diff。Launcher 保持 non-enforcing sidecar；**Owner-approved amendment:** Observer BFF 的 P1A 义务仅为 deployment-aware catalog 登记、required-section 声明和 test continuity，不要求 runtime evaluator/audit wiring。BFF runtime shadow/audit 与 production hardening 延后为独立设计，且不因此 amendment 获得 external/production readiness。
- [x] P1A-7: `authorization_decision` append-only 保存脱敏 decision/diff，并可在 repository/service/test surface 按 correlation、principal、action、route、reason 查询；P1A 不为此新增 HTTP/CLI 查询入口，且不得保存 secret、完整 token、账号材料、敏感请求体或 `upstreamUserToken`。
- [x] P1A-8: P1A 不创建/签发 S1/S2 principal、grant、tenant authority、management credential/token，不实现 P1B/P1C API/CLI/SKILL，不修改 external/Gateway/Worker/production 开关，不改变 Codex Physical Worker 路由或新增 Worker/BizWorkerIdentity/Pool member。

2026-07-19 历史独立签核结论为 `rejected`，记录原样保留于 [GOV-001 P1A independent signoff](../evidence/GOV-001-p1a-independent-signoff.md)。BUG-002 修复和 Owner 批准的 Observer BFF catalog/test-only amendment 随后通过 [独立 re-signoff](../evidence/BUG-002-p1a-required-section-independent-resignoff.md)，P1A-1 至 P1A-8 现均已签核通过。P1B 的每个后续切片仍须按本 work item 的 stage gate 激活，不因 P1A accepted 自动扩大。

### P1B-A activated acceptance subset: typed management authentication core

P1B-A 已由 Project Owner 在 2026-07-19 批准，且只覆盖本小节。它是 fixture-only 的 canonical authentication slice，不是 S1/S2 的真实开通、route cutover、CLI/Skill 交付或 production readiness。

- [x] P1B-A-1: `/api/v1/management/v1/auth/**` 只接受且只解析一个来源：`X-Navi-Principal-Credential` 或 `Authorization: Bearer <management-token>`。缺失、两个来源同时出现，或任何 legacy admin、ClientApp control/runtime、task、Worker credential/header 参与时，均在 Controller 前以稳定、不泄密的 deny reason 拒绝；不存在优先级、fallback 或权限并集。
- [x] P1B-A-2: typed credential resolver 以 `navigatorInstanceId + environmentProfile + principal type + credential lane + status + expiry + generation` 为完整验证边界；repository convenience query 不得作为授权判定的唯一约束。无可用 verifier、未知 enum/lane、instance/profile mismatch、撤销/过期或 credential/principal 不一致一律 fail closed。
- [x] P1B-A-3: management bearer resolver 只接受本 schema 的 `CONTROL_ACCESS` 或 `SECURITY_ACTION` token，并验证 token hash/reference、credential generation、instance/profile、status、expiry 和 action binding。`SECURITY_ACTION` 必须以原子 compare-and-set 单次消费；重复、并发或绑定不一致均拒绝，不能回退成 control access。
- [x] P1B-A-4: 新 management ingress 经过独立 canonical guard，而非 `AuthInterceptor`、`LegacyAuthorizationContextAdapter` 或 P1A shadow evaluator。guard 保证 `exactly one typed source → typed resolver → canonical enforcement decision → Controller`；任何漏挂 guard 的 `/api/v1/management/v1/**` route 都由负向测试阻断。
- [x] P1B-A-5: 实现且仅实现五个 canonical-only endpoint：`POST /auth/exchange`、`POST /auth/security-actions/authorize`、`GET /auth/whoami`、`GET /auth/permissions`、`POST /auth/explain`。`exchange` 只产生短期 `CONTROL_ACCESS`；`security-actions/authorize` 只产生 action/target/impact/reason-bound 的短期 single-use `SECURITY_ACTION`；`explain` 始终 `PREFLIGHT + nonBinding=true`。响应不得回显 credential、bearer、secret、verifier material 或其他 owner 资源存在性。
- [x] P1B-A-6: P1B-A 使用 test fixture/mocked verifier 建立正负向合同，绝不在 migration、application config、测试 fixture 或文档中 seed/打印真实 `foggy-world-sim`、`tms-x3` principal、grant、tenant authority 或 credential。默认部署无实际 verifier/seed 时保持 fail closed。
- [x] P1B-A-7: 五个新 route 全部加入 source catalog 与 evidence manifest，并显式声明 required sections；coverage/count/hash contract、credential-source conflict、unregistered/unguarded namespace、instance/profile/lane/expiry/revocation/generation、token replay/concurrency、non-binding explain 和 secret-redaction 测试必须实际运行通过。

P1B-A 的明确非目标：不开放 credential、platform grant 或 tenant authority lifecycle write API；不创建真实 S1/S2 seed；不切换旧 route family；不修改当时的 CLI/SKILL surface；不启用 `NAVIGATOR_EXTERNAL_ENABLED`、`NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED`、Worker external 或 production；不改变 Codex Physical Worker 路由，也不创建 Worker、BizWorkerIdentity 或 WorkerPool member。P1C-A 随后在独立 contract 中完成受限 CLI/SKILL UX；任何 real lifecycle、cutover 或运行态边界变更仍须设为 `NEEDS_REPLAN`。

### Deferred overall acceptance criteria (P1B-B–P4; not P1B-A/P1C-A ACs)

- [ ] AC-1: `foggy-world-sim` instance root 可以在绑定的 Navigator 实例内对所有 upstream system、tenant、ClientApp 以及自有/非自有资源执行 create、discover、bind、operate、owner transfer、delete/revoke、credential rotation 和 grant delegation。
- [ ] AC-2: `INSTANCE_ROOT` 必须使用独立 principal/credential；普通 upstream-admin、具名 ClientApp、`CONTROL_PLANE_ALL`、ClientApp control/runtime、task 或 Worker credential 不能表达、继承或兼容回退为 root，也不能跨自身 scope 管理资源。
- [ ] AC-3: Runtime credential 只能用于 runtime-token、readiness、owner-smoke、ask 和消息读取等运行面。
- [ ] AC-4: 绑定非自有资源不会隐式修改 owner；instance root 可另行执行独立、显式、可审计的 owner transfer。
- [ ] AC-5: ask 最终权限严格等于 runtime、upstream user、Agent、task capability、Worker route 和执行策略的交集。
- [ ] AC-6: S1 root authority 可以跨本实例内 upstream system/tenant 执行具名 root 管理动作，但不能跨 Navigator 实例生效，也不能冒充目标 upstream 的 control/runtime principal；同一用户或服务在实例 A 的 root/platform/control/runtime/Worker credential、grant、session 或 task token 重放到实例 B 时必须稳定拒绝。
- [ ] AC-7: 所有跨 owner 动作、credential 签发/轮换、grant delegation、binding、实例配置和 promotion 操作都有稳定 actor、credentialId、resource owner、reason、policy decision 和结果审计。
- [ ] AC-8: instance root 可执行实例配置与 external/production 推进动作，但任一 readiness、network、identity、Worker policy、audit、migration、rollback 或独立签收条件缺失时必须拒绝；root 不能删除审计或读取 secret 明文。
- [ ] AC-9: Codex Physical Worker 路由不要求新增 BizWorkerIdentity 或 WorkerPool membership。
- [ ] AC-10: 任一 external 开关开启都不能单独使 readiness 或 production acceptance 通过。
- [ ] AC-11: TMS platform subject 以显式 `principalType=SAAS_PLATFORM` 表达，并可在 exact `upstreamSystemId=tms-x3` 和 versioned `tenantScopeMode=UPSTREAM_OWNED` platform grant 内执行完整 upstream 管理；日常 create/update/assign 使用 `SAAS_PROVISIONING`，高风险 delete/transfer/rotate/revoke/delegate 使用 `SAAS_SECURITY_ADMIN`。两条 lane 共享稳定平台主体但使用独立 credential，不能合并或回退。
- [ ] AC-12: tenant ClientApp credential 必须绑定 exact upstream system + tenant + ClientApp；不能访问其他 tenant/ClientApp，也不能获得 Navigator root 或 TMS platform admin 权限。
- [ ] AC-13: `SAAS_PROVISIONING`、`SAAS_SECURITY_ADMIN`、tenant application-config control 和 tenant runtime credential 分开签发、过期、轮换和撤销；optional control 仅管理 exact ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已授权资源之间的 grant/binding，不能扩大 scope 或继续 delegation；runtime lane 不能执行控制面写操作。存量 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN` 不得自动成为 SaaS platform/security credential，只能经审批重新签发 provisioning。
- [ ] AC-14: `SAAS_PLATFORM` 不能管理其他 upstream system、Navigator instance root、production/trust-root 或基础设施 recovery 资源，除非未来部署拓扑决策通过另一套独立 principal/credential 明确授予；不得在同一 TMS credential 上叠加 root scope。
- [ ] AC-15: TMS-owned Worker 的 allocation 与 ownership 分开记录；TMS 可 create/update/reassign/delete，delete/owner transfer/recovery 必须使用 security-admin + step-up；tenant/App 只获得 use grant/binding，Codex allocation 不创建额外 BizWorkerIdentity 或 WorkerPool member。
- [ ] AC-16: 外部第三方、未知 trust profile 或未完成 onboarding 的 principal 不得获得任何 runtime/control/admin/Worker 权限，也不能继承 S1 instance-root 或 S2 SaaS-platform 权限。
- [ ] AC-17: S1/S2 主实现仅提供可默认拒绝的 S3 schema/policy 扩展点和负向契约；不得签发真实第三方 credential、开放第三方 route、接受真实第三方流量或据此宣称 external/production ready。
- [ ] AC-18: 一套上游 connection profile 只能绑定一个 `navigatorInstanceId`；同一上游若维护实例 A/B 两套 profile，由上游选择目标并保管各自凭据，Navi 必须拒绝 endpoint、instance audience 或 credential 所属实例不一致的调用，且不提供跨实例 credential 聚合或自动授权同步。
- [ ] AC-19: CLI/SKILL 能让上游在执行前明确识别 current principal、credential lane、`navigatorInstanceId`、authority ceiling、effective credential actions、target upstream/tenant/ClientApp、允许动作、明确禁止动作、expiry/status 和服务端最终判定边界；输出、help、日志和测试证据均不得包含 secret。多 credential profile 必须逐 lane 显示，不能合并权限。
- [ ] AC-20: `INSTANCE_ROOT` principal 与 credential 生命周期分离，并强制拆分 `INSTANCE_ROOT_CONTROL` + `INSTANCE_ROOT_SECURITY`；credential 必须绑定 exact instance + environment profile、只显示一次 secret、hash-only 存储、可过期/轮换/撤销并支持可靠恢复。internal-dev 长期凭据默认 180d/上限 365d，control 直用只允许 trusted-loopback 且必须显式显示 dev-only；production 长期凭据默认 30d/上限 90d，并必须使用默认 15m/上限 30m 的 control access token；高风险动作在任一环境缺 `INSTANCE_ROOT_SECURITY`、step-up、影响预览、reason 或默认 5m/上限 15m 的 action-bound single-use authorization 时必须拒绝。最多两把 credential 在默认 24h/上限 72h 轮换窗口并存，撤销最迟 60s 收敛，control credential 不得兼容回退。
- [ ] AC-21: `SAAS_PLATFORM` 复用 AC-20 的 internal-dev/production TTL、短 token、single-use security action authorization、双 credential rotation 和 60s revoke 基线；credential/token 必须引用 exact instance + upstream + platform grant id/version。provisioning 可创建并管理新的 TMS-owned tenant，但不能迁入非自有 tenant；tenant 迁入/迁出、suspend/delete/offboarding 必须使用 `SAAS_SECURITY_ADMIN`，跨 upstream transfer 还必须由 `INSTANCE_ROOT_SECURITY` 执行或批准。grant/tenant ownership/version 冲突时必须拒绝。
- [ ] AC-22: 所有授权入口使用 `navi.authorization.v1` 的服务端 canonical `AuthorizationContextV1/PolicyDecisionV1`，记录独立 schema/policy/action-catalog/server-build version、stable reason code、correlationId 和 decisionId。CLI 离线只能返回 `VALID|INVALID|UNVERIFIED`，在线 preflight 必须 `nonBinding=true`，mutation 必须重新执行 enforcement；未知 action/principal/lane/trust/route/schema、多个 credential source 或必需字段缺失时 fail closed。

## Contract / Data / Security Constraints

- API or event contract:
  - 后续 API 必须区分 instance/upstream control、ClientApp control、runtime 和 task capability，不复用一个万能 bearer。
  - 所有 lane/surface 共用 `navi.authorization.v1` DTO/action/reason 语义；typed management introspection 固定在 `/api/v1/management/v1/auth/**`，其他 lane 只能经各自 surface 的 adapter/alias 使用同一 contract。不得建立绕过 Open API gate 或按多个 header 猜 principal 的万能端点。
  - externally reachable route 必须绑定 stable `routeId + action + target resolver + accepted principal/lane + risk tier`；未注册 route/action 必须拒绝并由 contract test 阻断。
  - 每个拒绝应输出稳定 reason code，不泄露其他 owner 资源是否存在。
  - online preflight 必须标记 `nonBinding=true`，mutation 必须重新授权并生成新的 decisionId；CLI/SDK 不得把 preflight 缓存当 capability。
  - caller 提交的 tenant、owner、upstream user 和 cwd 只能作为请求参数，由服务端 principal 和资源记录重建可信上下文。
  - trust profile 由服务端注册关系或 policy 重建，客户端不得自报；unknown/unregistered/conflicting profile 必须拒绝。
- data and migration:
  - 如引入 dedicated upstream root、instance scope、resource grant 或 `routeKind`，必须有显式 schema、存量数据映射和回滚方案。
  - bind relation 与 resource owner 必须分别存储，不以更新 owner 模拟绑定；显式 owner transfer 单独记录 before/after。
  - 为 S3 预留字段或 policy extension point 不等于创建第三方实体、credential 或默认授权记录。
  - 统一 credential record 必须显式保存 `principalType`、`principalId`、`credentialLane`、`navigatorInstanceId`、authority scope、environment profile、generation/status/expiry；现有 upstream-admin 的 upstream/tenant/scopes 字段不得继续隐式代替 principal type/lane。
  - token record/claim 必须使用统一 envelope 并按 purpose 增加 exact audience、instance/environment、credential generation、grant version 和 target/capability binding；security action token 必须支持 single-use nonce。
  - 存量 upstream-admin 默认映射为 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN`；不得数据迁移时批量提升为 `SAAS_PLATFORM`。TMS 只有在 upstream/tenant/owner 关系核验后，经具名审批重新签发 `SAAS_PROVISIONING`；`SAAS_SECURITY_ADMIN` 必须独立签发。
  - platform tenant authority 必须使用服务端 grant + 权威 tenant ownership 映射；credential/token 只引用 grant id/version，不携带调用者可扩大的 tenant wildcard。legacy 静态 tenant 列表迁移时只能作为核验输入，不能直接生成跨 upstream grant。
- compatibility and rollback:
  - 本地 internal-dev 和受信 loopback Open API 可继续存在，但 token-only Gateway 兼容不得扩展到非可信网络。
  - 现有 credential lane 和 Physical Worker ID 优先保持兼容；不自动迁移为万能 ClientApp 或 Biz identity。
  - legacy upstream-admin 兼容只覆盖明确登记的旧 route/scope，不得调用新的 SaaS security-admin、instance-root、trust-root 或 production policy；必须有 usage telemetry、告警、退役期限和回滚方案。
  - `admin-key inspect` 只作为 legacy `auth whoami` alias；多 credential source 不做优先级回退或权限并集，冲突必须稳定拒绝。
  - S3 保持 disabled/unprovisioned；未来启用必须通过独立版本 workitem 和显式迁移，不由 S1/S2 发布自动开启。
- permissions and secrets:
  - `INSTANCE_ROOT` 必须是独立 principal/credential lane，不得复用普通 upstream-admin、ClientApp、control/runtime/task/Worker credential 类型或通过宽泛 scope 推导；底层存储可复用通用 credential 基础设施，但外部语义、audience、policy 和审计必须独立。
  - 所有 credential、grant、session、task capability 和 Worker principal 必须绑定 exact `navigatorInstanceId` 或等价不可混淆的 instance audience；不同环境实例不得共享授权记录或接受跨实例重放。
  - 所有 credential 保持分层、可过期、可轮换、可撤销且仅存 hash/secret store。
  - `NAVI_EXPECTED_PRINCIPAL_TYPE`、`NAVI_EXPECTED_CREDENTIAL_LANE`、本地 tenant/instance/profile 等 CLI metadata 只用于一致性检查，不能成为服务端授权来源。
  - 文档、CLI 输出和审计不得包含明文 secret。
  - 缺失 principal、owner、grant、route 或 execution policy 时一律 fail closed。

## Phased Delivery Proposal

| Phase | Scope | Local compatibility | Architecture gate / exit condition |
|---|---|---|---|
| P0 Scenario and canonical contract convergence | 对齐 S1/S2/S3 信任定位；冻结 principal/lane/lifecycle、`navi.authorization.v1`、CLI 在线/离线边界、FAQ、runbook 和组合 readiness schema | 不改现有开关默认值、凭据行为或 internal-dev 调用路径 | S1/S2 allow/deny 与 canonical schema 已完成 Owner 对齐；S3 明确 design-only/deferred；本阶段仍不授权编码 |
| P0.5 Implementation handoff freeze | 主流架构复核、复杂度预算、六 aggregate 最小物理模型、typed management API、route family、migration/shadow/rollback、legacy sunset 和治理角色 | 现有 legacy profile/headers 只在明确兼容 route 保留；不自动提升 | complete；method manifest、seed/legacy mapping review 与 Owner approval 已完成；initial P1A-only authorization is historical, current slice/gate status is in this work item's frontmatter and Current Status |
| P1A Foundation and shadow | additive schema、typed/legacy resolver adapter、sparse `AuthorizationContext`、`PolicyDecision`、route manifest、decision audit、shadow diff | 不改变当前 allow/deny，不签发 typed S1/S2 credential，不开启 external | 100% route manifest、migration/schema/resolver tests 和可查询 shadow diff 通过 |
| P1B Typed S1/S2 management | seed S1/S2 principal/grant/tenant authority；双 lane credential、control exchange、security action token、whoami/permissions/explain | runtime/task/Worker 表不搬迁；S3 和 production external 不实现 | S1/S2 lifecycle、single-use/revoke、跨 instance/upstream/lane 正负向合同通过 |
| P1C Route cutover and operator UX | route-family canonical enforcement、CLI/SKILL/help/runbook 和 legacy telemetry | 按 family 独立切换；new security route 不回退，legacy header 不在 1.4.3 删除 | 每个 family 的 shadow divergence 清零、cutover/rollback 与 CLI artifact/help snapshot 可验证 |
| P2 Strong identity and explicit routing | signed upstream-user assertion、所有 Gateway client 传播 Worker principal、显式 `routeKind`、credential TTL/rotation 治理 | partial headers 始终拒绝；既有 Physical Worker ID 不迁移成 Biz identity，不要求 Pool member | 双 ClientApp/user/tenant 和 strict Gateway 全矩阵通过 |
| P3 Production boundary | network/Ingress/TLS/CORS、Worker execution policy、credential broker/OS isolation、reliable audit outbox、migration/rollback、production policy object | internal/trusted 与 production profile 分离，绝不自动升级 | 所有生产前置项自动检查并由独立审批人签收；任一缺失 fail closed |
| P4 Deprecation and independent signoff | 淘汰非 loopback token-only、旧 CLI、隐式 route 判别和无过期 credential 例外 | 设兼容期限、usage telemetry、回滚窗口 | 实现状态至多 `READY_FOR_SIGNOFF`；独立 signoff 映射全部 AC 后才能形成最终结论 |
| Future S3 separate workitem | 基于真实第三方需求决定 onboarding、identity、limited control、resource/Worker policy、配额/计费、合规和 production ingress | 不回改 1.4.3 的已验收事实，不继承 S1/S2 高权限默认值 | 另行 APPROVED spec、threat model、迁移和独立 production signoff；不属于当前主实现 |

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| P1A-1 | critical | forward/rollback SQL、JPA mapping、fresh schema、upgrade schema、production `ddl-auto=validate` | exact commands、schema diff、migration/rollback result、未运行原因 |
| P1A-2 | critical | local explicit/fallback identity、request override rejection、production missing/placeholder/unknown identity startup rejection | sanitized config keys、startup/test result、resolved instance/environment summary |
| P1A-3/P1A-4 | critical | context/decision schema compatibility、415 route catalog completeness、deployment-aware duplicate protection、unknown action/route fail-closed shadow result | catalog checksum/version、coverage assertion、stable reason/decision/correlation IDs |
| P1A-5/P1A-6 | critical | 每个 legacy lane 的 adapter contract、credential-source conflict、现有 allow/deny regression、shadow allow/deny divergence matrix | before/after response assertions、sanitized context/decision、diff query result |
| P1A-7/P1A-8 | critical | decision redaction/query、no-seed/no-credential/no-external/no-Worker-mutation assertions、tracked secret scan | audit field assertions、DB row-count/absence checks、configuration and mutation scan |
| AC-1/AC-6 | critical | S1 root 在同实例跨 upstream/tenant 的正向管理矩阵、root 冒充目标 runtime 的负向矩阵，以及第二隔离实例的跨实例重放矩阵 | exact commands、resource IDs、effective principal、allow/deny reason codes |
| AC-2/AC-3 | critical | upstream-root/control/runtime credential cross-lane negative tests | HTTP/CLI results without secrets |
| AC-4 | major | bind、operate、transfer、delete action matrix and DB ownership assertions | before/after owner and binding records |
| AC-5 | critical | Agent/model/directory/function/worker/task capability intersection E2E | task token claims、resolved resources、Worker route and denial evidence |
| AC-7/AC-8 | critical | audit durability、credential rotation/revocation、instance promotion preflight、secret/audit invariant negative tests | reliable audit records、policy decisions、recovery and rejection results |
| AC-9 | major | existing Physical Worker readiness and ask without Pool mutation | worker-host/readiness/owner-smoke/ask evidence and mutation scan |
| AC-10 | critical | platform/Gateway/Worker external flag combination matrix | readiness output proving no flag implies production ready |
| AC-11/AC-14 | critical | TMS provisioning/security-admin action split、allowed-tenant positive matrix + cross-upstream/root negative matrix | actor、credential lane、target tenant/App、action、allow/deny reason and audit decision |
| AC-12/AC-13 | critical | two-tenant/two-ClientApp application-config-control/runtime cross-lane and horizontal negative tests | credential fingerprints/scopes/expiry only, HTTP/CLI results without secrets |
| AC-15 | critical | TMS-owned shared/dedicated Worker create/update/reassign/delete、step-up、owner/binding assertions and Codex mutation scan | Worker owner、grant/binding before-after、route、recovery and no-new-identity evidence |
| AC-16/AC-17 | critical | unknown/unregistered/external trust profile、self-declared profile、S1/S2 credential replay into S3 namespace and third-party route absence negative tests | stable deny reasons、no credential/resource/route creation、configuration and migration assertions |
| AC-18 | critical | 同一上游的实例 A/B 双 profile 正向调用，以及 credential、endpoint、instance audience 交叉重放负向矩阵 | profile fingerprint、目标 instance、allow/deny reason 和无跨实例同步证明，不记录 secret |
| AC-19 | major | CLI permission block、`auth whoami`/`inspect permissions`、`--explain-auth`、lane/scope preflight、server-decision fallback、help snapshot 和 secret redaction | CLI artifact/version、脱敏输出、各 principal 的 scope 显示、stable reason/correlation id 和 SKILL QA 记录 |
| AC-20 | critical | independent `INSTANCE_ROOT` issuance、exact-instance/environment audience、control/security cross-lane、dev trusted-loopback direct-use、production short-token、TTL、双 credential rotation、60s revocation propagation、single-use step-up/action binding 和 dev/production break-glass recovery 正负向测试 | credential fingerprint/version/status、environment/direct-use/productionUsable、policy decision、rotation/revocation timeline、recovery approval/audit；不记录 secret |
| AC-21 | critical | `SAAS_PLATFORM` lifecycle parity、platform grant id/version、`UPSTREAM_OWNED` dynamic tenant creation、non-owned tenant adoption denial、security-admin tenant suspend/delete/offboarding、cross-upstream root approval、grant revoke/version mismatch 和 legacy migration tests | principal/lane/fingerprint、platform grant/version、tenant ownership before/after、policy decision、rotation/revocation/migration audit；不记录 secret |
| AC-22 | critical | schema/policy/catalog version compatibility、offline `VALID|INVALID|UNVERIFIED`、online non-binding preflight、mutation re-authorization、credential-source conflict、unknown enum/action/route、reason-code redaction 和 preflight/enforcement decision identity 测试 | sanitized `AuthorizationContextV1/PolicyDecisionV1`、不同 decisionId、同一 correlation chain、server build/catalog/policy version 和 stable reason；不记录 secret |

所有验证必须记录实际执行命令、结果、环境、未运行原因和残余风险。测试代码存在不等于测试已运行通过。P1A `READY_FOR_SIGNOFF` 只要求 P1A-1 至 P1A-8 及其回归义务；后续阶段 AC 不因尚未实施而阻塞 P1A，但不得被勾选或宣称通过。

## Minimum Acceptance Matrix

| Scenario | Expected allow | Expected deny | Current evidence / status |
|---|---|---|---|
| Local internal | internal API 使用各自合法凭据；Open API gate 保持 false | 所有 `/api/v1/open/**` 返回 `503 / EXTERNAL_SURFACE_DISABLED` | 当前源码契约已复核；本轮未实测 |
| CLI permission introspection | `auth whoami` 在线显示每份 credential 的 principal/lane/instance/environment；`inspect permissions` 分开显示 authority ceiling 与 effective credential actions；`--explain-auth` 返回 non-binding server preflight | 离线 `config check` 报 `ALLOW`、多 credential 权限做并集、control key 被显示为可执行 security action、preflight decision 被 mutation 复用 | `navi.authorization.v1` 合同已冻结；尚未实现或验收 |
| Trusted local Open API | loopback/可信 ACL + platform gate=true + valid ClientApp runtime token + owner/grant 完整 | 错 tenant/App/user、失效 token、未授权 Agent/Directory/Model | #152 functional ask 仍无完成证据；只允许作为受信本机联调，不推导 production |
| Observer BFF local tool | target 为 loopback/显式受信开发网络 + exact observer session + bound runtime principal + task/attachment capability | anonymous/跨 session 调用、非可信网卡/Ingress、process-global credential 复用、仅凭附件 UUID 读取、将下游 external gate 当 BFF auth | 当前 12 条均 `LOCAL_TOOL_RESTRICT`；默认 `0.0.0.0:5181` 且无入站 session，因此只有 production-blocked 风险证据，没有安全正向验收 |
| Gateway token-only compatibility | strict=false + 三个 Worker header 全部缺失 + valid task token + trusted internal network | 缺 task token、任一 partial/blank/legacy header、非可信网络使用 | server source contract 存在；本轮未执行 |
| Gateway strict principal | strict=true + task token + exact Worker credential + exact lease/route/owner/backend | 任一 header 缺失、credential/lease/worker/tenant/owner 不匹配 | server path 已实现；Java client 当前仍不兼容，因此不能启用为现行默认 |
| S1 root manages instance resources | instance root 以 exact `navigatorInstanceId` scope 管理实例内任意 upstream/tenant/ClientApp 及资源，执行 create/discover/bind/operate/transfer/delete/rotate/delegate | 普通 ClientApp/runtime credential 冒充 root、root 冒充目标 upstream runtime、root credential 被用于 ask，或跨实例调用 | 业务语义已确认；尚未实现或验收 |
| S1 root manages non-owned resources | instance root 对任意实例内资源执行完整动作集；bind 与 owner transfer 分开 | 通过伪造 owner、runtime token、含糊 `ALL` bypass 或跨实例 token 操作 | 业务语义、root principal 及最小 credential/token/data model 合同已冻结；尚未实现或验收 |
| S1 root credential lifecycle | internal-dev trusted-loopback 可直接使用未过期的 `INSTANCE_ROOT_CONTROL`；production 使用短期 control access token；所有高风险动作使用 `INSTANCE_ROOT_SECURITY` 换取 action-bound single-use authorization | internal-dev credential 用于 production、非 loopback 直用 control、control 执行 security 动作、security 长期 credential 直接调用、第三把 active credential、撤销/旧 generation 继续成功 | 生命周期语义与参数已确认；credential/token/API 表达尚未实现或验收 |
| S2 TMS platform tenant provisioning | 共享 Navi 中，`SAAS_PLATFORM` 的 `SAAS_PROVISIONING` lane 在 allowed tenant 集合内创建/更新/启用 tenant 与 ClientApp、签发初始 credential、配置资源/grant/binding，并创建/更新/重分配 TMS-owned Worker；`SAAS_SECURITY_ADMIN` 执行停用/删除、owner transfer、rotate/revoke/delegate 和紧急恢复 | provisioning credential 执行破坏性 security-admin 动作；legacy upstream-admin 自动提升；任一 TMS credential 管理其他 upstream system、未授权 tenant、Navigator root/trust-root/production policy | principal/lane 模型和业务语义已确认；token/schema/API 尚未实现或验收 |
| S2 dynamic tenant grant | `SAAS_PROVISIONING` 在 `UPSTREAM_OWNED` grant 下创建新 TMS tenant 后立即可管理该 tenant；credential 无需因 tenant 增加而轮换 | 调用者自报 wildcard、provisioning 迁入非自有 tenant、grant/ownership/version 不匹配、TMS credential 跨 upstream；迁入迁出/停用删除缺 security-admin，cross-upstream transfer 缺 instance-root security | lifecycle/grant 语义已确认；权威 tenant ownership 与 grant API 尚未实现或验收 |
| S2 tenant runtime | exact tenant + ClientApp runtime credential 换取短期 token 并执行获准 ask/readiness/messages | 创建 ClientApp、签发 credential、分配 Worker、控制面写、跨 tenant/App | runtime-default 已确认；credential contract 尚未实现或验收 |
| S2 tenant limited control | 仅在存在明确自助需求且显式签发独立 control credential 后，管理 exact ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已获授权资源之间的 grant/binding | 扩大 grant scope、继续 delegation、纳入未授权资源，管理 credential、Worker、ClientApp lifecycle、其他 ClientApp/tenant/TMS shared fleet，或使用 runtime secret 进入 control plane | application-config-only 边界已对齐；默认不签发，尚未实现或验收 |
| S2 Worker allocation | TMS provisioning 创建/更新/重分配 TMS-owned shared/dedicated Worker，并通过 grant/binding 分配给 tenant/App；security-admin 经 step-up、影响预览和审计执行 delete、owner transfer 与 recovery | tenant runtime/control 自行 `worker-host apply/update`、跨租户重绑、隐式 owner transfer、Codex Pool/Biz identity 绕路 | 完整生命周期与双 lane 语义已对齐；事务、恢复和测试尚未实现 |
| S2 cross tenant | TMS platform subject 仅在 allowed tenant 集合内横向管理；tenant credential 永远固定单 tenant | Tenant A control/runtime 访问 Tenant B ClientApp/resource/task，或 caller 自报 tenant 扩权 | 业务语义已对齐；必须形成双 tenant 负向矩阵 |
| S3 external third party | 当前无允许路径；未来仅在独立 workitem 批准、完成 onboarding 和全部 external/production gate 后，允许 exact external tenant + ClientApp runtime 访问自有或显式 grant 的资源 | 自报 trust profile/upstream/tenant、任何 upstream-admin/platform/root/Worker lifecycle、非自有资源 discover/bind/operate、跨 tenant/App、单开 external flag 即接入 | 设计边界已对齐；implementation deferred，不属于当前主实现或验收流量 |
| Same tenant, cross ClientApp | 已授权 instance root 或 TMS platform subject 可管理其 scope 内 App A/App B；普通 App 仅在 shared grant 明确时共享 | App A 普通 control/runtime 直接访问 App B private resource | 业务语义已对齐；仍需统一负向矩阵和显式 platform/root designation |
| Same ClientApp, cross upstream user | ClientApp/upstream shared 且 grant 明确的资源可共享 | user A 访问 user B 的 user-private Directory/task/context | 当前 assurance 为 delegated；强证明和统一谓词未闭合 |
| Cross tenant/upstream inside S1 instance | instance root 可管理本实例全部 tenant/upstream；普通 platform/control/runtime/task lane 仍只能在自身 scope 内操作 | 普通 principal 跨 tenant/upstream，caller 自报 scope 扩权，或 root 冒充目标 upstream runtime principal | instance-wide root 语义已确认；单/多 tenant 只作为部署选择 |
| Cross Navigator instance / ordinary cross-upstream | 同一主体若需管理多个实例，必须分别在每个实例取得本地 principal/grant/credential；S1 root 仅在本实例内可跨 upstream 管理 | 任一 principal 越出绑定 instance；S2 platform、ClientApp、runtime、task token、Worker principal 越出绑定 upstream/tenant/App，或凭同一用户/公司身份自动继承另一实例授权 | 目标必须 fail closed；需第二实例及同主体跨实例重放负向集成测试 |
| Codex Physical Worker | existing Physical Worker + `worker-host update` + correct owner/backend/Directory/model/user grant | Pool membership 要求、新建 Biz identity/替代 Worker、重绑 Directory 绕过 | #151 readiness/resources 曾 live 通过；完整 ask 未证明 |
| Worker external | external flag=true 且完整 auth/execution/network policy ready | 仍有 `EXTERNAL_EXECUTION_POLICY_PENDING` 时任何业务 ingress | 当前必须 unready/503；不得解除 pending |
| Production | authenticated instance root 发起 promotion，且 TLS/CORS、signed user、strict Gateway、Worker policy、审计、迁移、回滚、artifact identity、独立签收全部通过 | 任一子条件缺失、仅开启任一 external flag，或 TMS platform admin 未获 instance-root role 却尝试 promotion | 当前不允许、无 production ready 证据 |

## CLI, SKILL, Runbook, and Automation Backlog

### CLI help and inspection

1. 顶层 help 固定显示：`NAVIGATOR_EXTERNAL_ENABLED` only controls `/api/v1/open/**` routing; it does not enable production, providers, Worker Gateway, or Worker endpoints。
2. 将 Gateway help 描述为 Worker principal requirement，明确不是 network/Ingress exposure switch；如保留旧环境变量，应显示 deprecated semantic alias。
3. 每个命令 help 固定显示 permission block：required principal、accepted credential lane、authority scope、target resource scope、allowed action、explicitly denied actions、step-up/approval requirement；external-third-party lane 当前必须显示 disabled/unprovisioned，而不是回退到普通 upstream-admin。
4. 增加只读 `auth whoami`、`inspect permissions`，输出 `schemaVersion=navi.authorization.v1`、current principal/type、credential lane/id fingerprint、`navigatorInstanceId`、authority ceiling、effective credential actions、root/upstream/tenant/ClientApp scope、trust profile ceiling、expiry/status 和 server build/policy/catalog version，绝不输出 secret。
5. 权限 inspect 必须直观区分：S1 root=`INSTANCE`、S2 platform=`UPSTREAM_SYSTEM`、tenant control/runtime=`TENANT + CLIENT_APP`、task/Worker=`TASK + ROUTE/LEASE`；S1 root 的来源 upstream 只作为主体/审计信息，不误显示为 authority ceiling。
6. `config check` 只检查 profile 与 credential metadata 一致性，结果固定为 `VALID|INVALID|UNVERIFIED`；能够确定 lane/scope/instance/environment/credential-source 冲突时，在发送写请求前 fail closed。任何离线结果都不得声称服务端 `ALLOW`。
7. 增加只读 `inspect trust-boundary` 或等价能力，分别显示 upstream trust profile、platform surface、network binding、Gateway principal mode、Worker external readiness、Provider readiness、production approval。
8. 增加在线 `--explain-auth` / dry-run，显示将使用的 principal、action、owner scope、resource scope、target instance/upstream/tenant/App 和 expected policy path，返回 `PREFLIGHT + nonBinding=true` 且不执行 mutation；断网或 schema/catalog 不兼容时只能报 `UNVERIFIED`/稳定错误。
9. mutating command 的成功/拒绝输出应携带非敏感的 effective principal、target scope、stable reason code、correlationId 和 enforcement decisionId；不得复用 explain-auth 的 preflight decisionId。
10. 对 `worker-pool register-worker/add-member --backend OPENAI_CODEX*`、direct Codex Biz identity 等路径继续硬拒绝，并提示规范 `worker-host verify/update` 路径。
11. 修复 1.0.18/1.0.21、`VERSION`、archive、`dist/lib`、`--version` 和 help snapshot 一致性。
12. S1 root 的 `auth whoami` / `inspect permissions` 必须显示 `principalType=INSTANCE_ROOT`、`credentialLane=INSTANCE_ROOT_CONTROL|INSTANCE_ROOT_SECURITY`、exact `navigatorInstanceId`、`environmentProfile`、`directCredentialAllowed`、expiry/status/fingerprint 和 `productionUsable`；internal-dev credential 必须明确显示 `productionUsable=false`。
13. root mutating command 的 permission block 必须将日常动作标为 `INSTANCE_ROOT_CONTROL`，将 transfer/delete/revoke/rotate/delegate/trust-root/recovery/promotion 标为 `INSTANCE_ROOT_SECURITY + step-up + action authorization`；CLI 不得尝试用 control 自动回退、静默换 lane 或把“SIM 拥有全部权限”显示成“当前这把 credential 可执行全部动作”。
14. S2 TMS 的 inspect 必须显示 `principalType=SAAS_PLATFORM`、`credentialLane=SAAS_PROVISIONING|SAAS_SECURITY_ADMIN`、exact instance/upstream、`tenantScopeMode=UPSTREAM_OWNED`、platform grant id/version/status、owned-tenant count 或安全摘要、expiry/status/fingerprint；authority scope 显示为 `UPSTREAM_SYSTEM`，不得误显示为 `INSTANCE`。
15. legacy upstream-admin inspect 必须显示 `principalType=UPSTREAM_SYSTEM_ADMIN`、`credentialLane=LEGACY_UPSTREAM_ADMIN`、兼容 route/scope、deprecation 状态和 `securityAdminEligible=false`；CLI 不得仅因 `upstreamSystemId=tms-x3` 自动把它识别为 `SAAS_PLATFORM`。
16. TMS 命令的 `--explain-auth` 必须在线解析目标 tenant 的权威 upstream owner 与 platform grant version，显示 `ownedByPlatform=true|false` 和 required lane；不能仅凭本地 profile 中的 tenantId 或旧 `authorizedTenantIds` 判断允许。
17. 多 credential legacy profile 的 `auth whoami` 必须逐 credential source 输出独立结果或要求显式选择；`inspect permissions` 与 mutation 不得合并 admin/control/runtime/root/platform lane 的权限。
18. 新 typed management profile 使用 `NAVI_PRINCIPAL_CREDENTIAL`，并以 `NAVI_NAVIGATOR_INSTANCE_ID`、`NAVI_ENVIRONMENT_PROFILE`、`NAVI_EXPECTED_PRINCIPAL_TYPE`、`NAVI_EXPECTED_CREDENTIAL_LANE` 做本地一致性检查；这些字段不得作为服务端授权事实。
19. CLI help snapshot 必须由 canonical action catalog 生成并携带 `actionCatalogVersion`；online server version 不一致时显示告警并依赖服务端 explain/enforcement，不能让旧 help snapshot 放行写操作。
20. Observer BFF 的启动/help/inspect 必须显示其独立 deployment、实际 bind address、入站 session 状态、active runtime principal 摘要和 `productionUsable=false`；不得因代理路径以 `/api/v1/open/**` 命名而显示为受 launcher external gate 保护。

### SKILL FAQ / QA

至少补充以下固定问答和检查项：

1. `NAVIGATOR_EXTERNAL_ENABLED=true` 是否等于 production ready？——否。
2. `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=true` 是否会开放网络端口？——否。
3. 何时可以开启 Gateway strict？——仅所有 client 已传播 exact Worker principal/lease 且完整负向矩阵通过后。
4. Codex readiness 失败能否新建 BizWorkerIdentity 或添加 Pool member？——不能。
5. tenant、owner、upstream system、ClientApp、upstream user、binding 分别解决什么问题？
6. upstream user 当前 assurance 是什么？——`client-app-delegated`。
7. 为什么不能信任请求里的 cwd、owner、tenantId 或 userId？
8. operator、dedicated root、SaaS platform admin/upstream-admin、control、runtime、task、Worker credential 各能做什么？
9. internal-dev 是否允许暴露到非可信网卡？——不允许把兼容模式当安全边界。
10. S1 dedicated root 与普通 ClientApp 有何区别，哪些能力绝不下沉到 ask？
11. S2 TMS platform subject 与 tenant ClientApp credential 有何区别？——前者在获准 TMS tenant 范围管理 provisioning/security-admin，后者固定单 tenant + ClientApp。
12. TMS provisioning 与 security-admin 为什么分开？——provisioning 用于日常 create/update/assign；delete、owner transfer、rotate/revoke、delegate 和紧急恢复必须使用 security-admin，并执行 step-up、影响预览和审计。
13. 租户拿到“ClientApp 凭据”究竟是 control 还是 runtime？——默认 runtime；需要自助配置时另行签发 limited control，不得混用。
14. tenant limited control 能做什么？——仅管理 exact ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已授权资源之间的 grant/binding；不能扩大 scope、继续 delegation，或管理 credential、Worker、ClientApp lifecycle、其他 ClientApp/tenant。
15. “分配 Worker”是否改变 Worker owner？——默认否；先用显式 grant/binding 分配使用权，owner transfer 是独立 security-admin 动作。
16. TMS 能否完整管理 TMS-owned Worker？——可以；create/update/reassign 走 provisioning，delete/owner transfer/recovery 走 security-admin，且 Codex 仍使用既有 Physical Worker 路径。
17. TMS platform subject 能否跨其他 upstream system 或启用 production？——默认否，除非另获具名 instance-root role 且所有 production gate 通过。
18. S3 外部第三方是 1.4.3 主实现目标吗？——不是；只要求 schema/policy 可安全扩展且当前默认拒绝，不实现 onboarding、credential 或真实流量。
19. 外部第三方未来默认可获得什么权限？——从 exact tenant + ClientApp runtime 最小权限起步；upstream-admin、Worker lifecycle、跨 owner/tenant 和 production promotion 默认禁止。
20. 将 upstream 标记为 trusted 是否可直接放行？——不能；trust profile 只限制权限上限，所有 credential、owner/grant、task、Worker、execution 和 audit 判定仍必须通过。
21. 开启 `NAVIGATOR_EXTERNAL_ENABLED` 能否让第三方接入？——不能；它只打开 Open API 路由门禁，不创建 principal、credential、grant、Worker route 或 production approval。
22. 对每次 QA 强制记录 CLI artifact version、Navigator build identity、profile、flags 和未运行项。
23. S1 SIM root 能否管理同一实例中的其他 upstream/tenant？——可以，root authority 覆盖整个 `navigatorInstanceId`；但必须使用 root 管理动作，不能冒充目标 upstream 的 control/runtime principal。
24. 使用 CLI 时如何确认自己的权限？——先执行 canonical `auth whoami`/`inspect permissions`，核对 principal、credential lane、instance、authority scope、target scope、expiry/status，再查看目标命令的 permission block；信息缺失或冲突时停止写操作。
25. CLI 显示“权限足够”是否等于服务端必然放行？——不是；CLI 只做解释和 preflight，服务端仍按 owner/grant/task/Worker/execution/production policy 形成最终 `PolicyDecision`。
26. SIM 是 instance root，为什么 control credential 仍不能 delete/rotate/promote？——SIM 主体拥有完整权限，但当前 credential lane 只承载其中的日常动作；高风险权限由同一主体的独立 security lane + step-up 表达，不能据主体总权限推导当前 key 的权限。
27. internal-dev 的 root control credential 能否用于 production？——不能；它必须带 dev-only environment binding，CLI 显示 `productionUsable=false`，服务端对 production endpoint/profile 或 promotion 动作稳定拒绝。
28. internal-dev 是否可以直接使用 security credential 执行高风险动作？——不能；本地只放宽 control 的 trusted-loopback 直用和长期凭据 TTL，security 在任何环境都必须换取 action-bound、single-use 的短期授权。
29. TMS 平台凭据是否仍叫 upstream-admin？——目标语义不是；TMS 使用显式 `SAAS_PLATFORM` principal，并区分 `SAAS_PROVISIONING` 与 `SAAS_SECURITY_ADMIN`。底层可复用统一 credential 基础设施，但 CLI/API/audit 不得继续用一个含糊的 upstream-admin 表达两条 lane。
30. 现有 TMS `NAVI_ADMIN_API_KEY` 会自动获得新平台权限吗？——不会；存量 key 先作为 `LEGACY_UPSTREAM_ADMIN` 保持已登记兼容能力，只有核验 upstream/tenant/owner 后经审批重新签发 provisioning；security-admin 始终独立签发。
31. TMS 每新增一个租户是否需要轮换平台凭据？——不需要；`SAAS_PLATFORM` 使用服务端 `UPSTREAM_OWNED` dynamic grant，新建的 TMS-owned tenant 在审计后进入授权范围。凭据仍绑定 grant id/version，不能携带客户端 wildcard。
32. TMS 能否把其他平台已有 tenant 直接绑定进来？——不能；provisioning 只能创建/管理 TMS-owned tenant。迁入/迁出必须走 security-admin，跨 upstream 还需 instance-root security 的具名 transfer，不得靠修改请求 tenantId 或本地 profile 扩权。
33. `config check=VALID` 是否表示命令一定有权限？——不表示；它只说明本地 profile 没有可确定冲突。离线检查只有 `VALID|INVALID|UNVERIFIED`，永远不返回 `ALLOW`。
34. `--explain-auth` 返回 `ALLOW` 后能否直接复用该结果写入？——不能；它是 `nonBinding=true` 的在线 preflight，不产生 capability。真实 mutation 必须重新授权并产生不同 decisionId。
35. 一个 profile 同时有 admin/control/runtime key 时，CLI 是否把权限合并？——不能；必须逐 credential source 显示或显式选择，任何 lane 的权限都不能与另一把 key 做并集。
36. 为什么 `NAVI_BASE_URL=http://127.0.0.1` 仍不能由 CLI 认定 trusted-loopback？——URL 只是客户端配置；真实连接来源、代理链和 environment policy 必须由服务端解析，只有服务端可返回 `directCredentialAllowed=true`。
37. `policyVersion`、`actionCatalogVersion` 和 CLI version 分别代表什么？——policy 是当前判定规则，catalog 是 action/command 合同，CLI version 是客户端制品；三者均需显示且不能互相替代。
38. Observer BFF 的 `/api/v1/open/**` 是否自动受 `NAVIGATOR_EXTERNAL_ENABLED` 保护？——否；BFF ingress 是独立 deployment，该开关只影响它随后请求 Navigator launcher 的下游 `/open` 路由。
39. 当前 Observer BFF 能否暴露到局域网或 production？——不能；默认 `0.0.0.0:5181`、无入站 observer session 且会复用 server-held/login-derived runtime authority，只能在显式网络隔离的 local/trusted dev 环境使用。

### Operational runbook

1. external flag 变更前置检查、变更窗口、观察指标和回滚。
2. trusted-loopback Open API 联调步骤与网络隔离证明。
3. Gateway token-only → strict 的 client-first 迁移顺序。
4. operator/upstream root/TMS provisioning/TMS security-admin/control/runtime/Worker credential 的独立签发、轮换、吊销、泄露响应和审计查询。
5. tenant × ClientApp × upstream user × owner 的正负向验证步骤。
6. S1 cross-owner create/discover/bind/operate/transfer/delete/rotate/delegate 的 step-up、影响预览、审计和恢复流程。
7. S2 TMS tenant onboarding：仅用 provisioning lane 创建/启用 tenant 与 ClientApp、签发默认 runtime credential、一次性交付 secret，并配置 Worker/Directory/Model/Agent grant/binding；limited control 仅按明确自助需求另发。
8. S2 security-admin 高风险操作：tenant/ClientApp 停用或删除、owner transfer、credential rotate/revoke、grant delegation、Worker delete/recovery 的 step-up、影响预览、审批、审计和回滚。
9. S2 tenant offboarding：先撤销 runtime/control credential 和 task token，再解除 binding/grant，最后按 owner policy 归档或删除资源；不得以隐式 owner transfer 代替回收。
10. S2 TMS-owned Worker 生命周期：provisioning create/update/reassign，security-admin delete/transfer/recovery，验证 allocation 不改变 owner。
11. Codex Physical Worker 专项排障，明确禁止“多创建一个身份/Pool member”。
12. Directory canonical path、allowed path、readOnly 和 Worker binding 验证。
13. production 启用所需 artifact identity、migration、TLS/CORS/Ingress、Worker policy、audit delivery 和独立签收。
14. 稳定拒绝码到处置动作的映射；缺 principal/owner/route/policy 时保持 fail closed。
15. S3 future-only onboarding runbook 仅保留禁用模板和启动条件；没有独立 APPROVED workitem 时不得签发 credential、创建第三方 grant 或执行真实联调。
16. S1 root credential 生命周期：分别签发 control/security、一次性 secret 交付、fingerprint 核验、internal-dev/production profile 绑定、短 token/action authorization、最多双 active 轮换、60s 撤销收敛验证，以及 dev 单 custodian / production operator + independent approver 的 break-glass 恢复演练。
17. S2 typed platform migration：盘点 legacy upstream-admin route/scope/tenant 使用，显示 `LEGACY_UPSTREAM_ADMIN` 身份，核验 TMS upstream/tenant/owner 后重新签发 `SAAS_PROVISIONING`，独立签发 `SAAS_SECURITY_ADMIN`，验证旧 key 不得调用新 security route，并记录 usage telemetry、回滚与退役窗口。
18. S2 platform grant lifecycle：创建/查看/暂停/恢复/撤销 `UPSTREAM_OWNED` grant，验证 provisioning 新建 TMS tenant 自动纳入、非自有 tenant 迁入拒绝、security-admin offboarding、cross-upstream instance-root approval、grant version stale 与 60s 撤销收敛。
19. CLI 权限自检：先记录 CLI/server build、schema/policy/catalog version，再执行 `config check`、`auth whoami`、`inspect permissions` 和目标命令 `--explain-auth`；确认 authority ceiling 与 current lane action 分开显示，且 preflight decisionId 未被 mutation 复用。
20. credential-source 冲突处理：多 key profile 逐 lane 盘点并迁移为独立 typed profile；在完成迁移前禁止通过删除报错、调整 header 优先级或合并权限绕过 `AUTHN_CREDENTIAL_CONFLICT`。
21. reason/correlation 审计排障：使用服务端 correlationId/decisionId 查询脱敏 decision trace；公开响应只使用稳定安全 reason code，资源存在性和内部 policy detail 仅进入受控审计。
22. Observer BFF local-only 运行：强制记录 bind address/可达网段、禁止 Ingress/公网暴露、每个操作者独立 session、附件清理与 capability 绑定、login-derived credential 轮换/清除；任一条件缺失时停止 BFF，而不是开启 `NAVIGATOR_EXTERNAL_ENABLED` 规避。

### Automated test checklist

P0 contract tests:

- 所有 externally reachable route 必须注册明确 policy，未注册即构建/测试失败。
- `AuthorizationContextV1/PolicyDecisionV1/CredentialRecordV1/TokenEnvelopeV1/PlatformGrantV1` JSON schema、必需字段、枚举、secret redaction 和 additive-version compatibility tests。
- offline `VALID|INVALID|UNVERIFIED` truth table；任何输入均不得产生 offline `ALLOW`。online preflight 必须 `nonBinding=true`，mutation 必须产生新的 enforcement decisionId。
- server/CLI schema、policy、action-catalog version 相同/不同/未知组合；旧 CLI 遇未知 action/lane/trust/route 时不得执行写操作。
- 多 credential source、多个 auth header、legacy+typed 同时出现的 conflict matrix；不得验证 header 优先级或权限并集路径。
- CLI 每个命令的 permission block、`auth whoami`/`inspect permissions` schema、secret redaction 和 artifact help snapshot contract tests。
- S1 root 显示 `INSTANCE` authority，S2 platform 显示 `UPSTREAM_SYSTEM` authority，tenant control/runtime 显示 exact tenant + ClientApp；错误 profile/lane/scope 组合必须 preflight 拒绝或明确交由服务端拒绝，不能误报允许。
- S1 root CLI 必须显示 control/security lane、environment profile、direct-use mode 和 `productionUsable`；dev credential、过期 credential 或 control→security 动作不得被 help/preflight 误报为允许。
- S2 CLI 必须显示 `SAAS_PLATFORM` 与 provisioning/security lane；legacy upstream-admin 必须显示 legacy/deprecation/`securityAdminEligible=false`，不能因 TMS upstream id 被本地推导为平台或 security principal。
- `/api/v1/open` canonical、encoded、context-path 和 trailing-path 绕过矩阵。
- Worker principal 三个 header 的 8 种 presence 组合、blank、legacy header 和 strict/token-only 组合。
- tenant × ClientApp × upstream user × owner × action 的完整负向矩阵。
- task/status/messages/diagnostics/evidence 的跨 App/user 越权测试。
- Directory user-private/ClientApp-shared/upstream-system-shared 与 cwd spoofing 测试。
- missing/unknown/self-declared/conflicting upstream trust profile 一律拒绝，且不能回退到 S1 root 或 S2 platform admin。
- production CORS/trusted origin 与 dev fallback credential 启动拒绝测试。
- Observer BFF route catalog 独立于 launcher；未认证访问 config/login/attachment/ask/task/session 的负向合同，以及 BFF ingress 不受 `NAVIGATOR_EXTERNAL_ENABLED` 保护的回归测试。

P1 integration tests:

- Java、LangGraph、Codex SDK、Codex app-server Gateway client 的 Worker principal/lease 传播。
- Worker credential 过期、撤销、轮换、错误 version 和 lease mismatch。
- task token 错 audience/generation、过期、终态 tombstone、错误 function scope/worker route。
- Physical Worker route 不要求 Pool membership，且测试前后无新增 Worker/Biz identity/member。
- `poolId`/`workerId` collision、cross-tenant ambiguity 和未来 `routeKind` migration。
- operator/instance-root/SaaS-platform-admin/control/runtime/task/Worker credential 的 cross-lane rejection。
- S1 owned/non-owned resource 完整动作矩阵，包括 bind/owner transfer 分离、credential rotation、grant delegation 和 before-after assertion。
- S1 root lifecycle：internal-dev control trusted-loopback 直用正向、非 loopback/production 重放负向、production 短 control token、security single-use action token、action/target/impact/reason mismatch、最多双 active rotation、旧 generation 与撤销后最迟 60s 收敛、dev/production break-glass 审批差异。
- S2 TMS provisioning 的 allowed-tenant create/update/assign 正向矩阵，以及 destructive security-admin action 的 cross-lane 拒绝。
- S2 TMS security-admin 的 delete/owner-transfer/rotate/revoke/delegate/recovery 正向矩阵，以及缺 step-up、影响预览或审计时的拒绝。
- legacy upstream-admin 对已登记兼容 route 的回归、对 `SAAS_SECURITY_ADMIN`/instance-root/production route 的稳定拒绝，以及显式审批重签 provisioning 前后 principal/lane/tenant/owner 对照和回滚测试。
- `UPSTREAM_OWNED` dynamic grant：provisioning 创建新 TMS tenant 正向、无需 credential rotation、调用者 wildcard/非自有 tenant/adoption 负向、grant suspend/revoke/version stale、tenant owner 变更、security-admin offboarding、cross-upstream transfer 缺 instance-root security 的拒绝。
- S2 两个 tenant × 每 tenant 两个 ClientApp 的 application-config-only control/runtime 横向越权、grant scope amplification/delegation、credential/Worker/ClientApp-lifecycle 禁止动作和 credential lane 负向矩阵。
- S2 shared/dedicated Worker create/update/reassign/delete、grant/binding、revocation、owner invariance、destructive step-up 和 tenant offboarding 回收。
- S3 namespace 无 credential、ClientApp、grant、Worker route 或 open ingress 的 absence assertion；S1/S2 credential replay 到 external namespace 必须拒绝。
- Observer BFF observer-session 隔离、process-global credential 消除、attachment session/task capability、CSRF/origin、bind-address/network profile 和 production startup rejection 集成测试。

P2 production-gate tests:

- 非 loopback external 启动的 network/TLS/CORS/secret 前置条件。
- signed user assertion 的 expiry、issuer/audience、nonce replay 和 key rotation。
- workspace/tool/sandbox/approval/network execution policy 的 deny-by-default。
- reliable audit outbox 的失败恢复、去重、顺序和脱敏。
- migration、`ddl-auto=validate`、rollback 和跨版本兼容。
- CLI release archive/version/help snapshot 与 `/actuator/info` artifact identity 一致性。
- production policy 任一子条件缺失时 fail closed，且不能由单个 external flag 覆盖。
- 未来 S3 workitem 未批准时，即使 platform/Gateway/Worker external flags 全部开启也不得形成第三方可用路径。

## Risks and Open Questions

### Known risks

1. 如果直接把普通 ClientApp 的 `CONTROL_PLANE_ALL` 或所谓“具名 root ClientApp”解释为实例 root，会模糊 upstream system 与 ClientApp 边界；只有独立、instance-scoped 的 `INSTANCE_ROOT` designation 才能表达 S1 权限。
2. 如果“可绑定非自有资源”通过改写 owner 实现，会破坏审计、撤销和未来多上游隔离。
3. 如果 instance root 与 control/runtime/task credential 复用同一 secret 或传播链，上游 credential 泄露会直接扩展到 ask、Worker 或浏览器侧。
4. 如果 instance-wide 权限不显式绑定 `navigatorInstanceId`，或没有把 root subject/source upstream 与 authority scope 分开记录，未来第二实例可能越界，CLI/审计也可能把 SIM root 错误显示为普通 upstream-admin。
5. 如果 root 管理权下沉到 ask runtime，Agent/task capability 将失去实际约束。
6. 如果“全部权限”被实现成绕过 policy/audit 的 hard-coded superuser，fail-closed、稳定拒绝码、撤销和未来场景隔离都将失效。
7. 如果向 TMS 租户下发 upstream-admin，任一租户 credential 泄露都可能扩展为跨租户 provisioning 和共享 Worker 控制。
8. 如果把 ClientApp control 与 runtime 合并成万能 credential，租户业务调用密钥会同时获得资源创建、grant 和 binding 写能力。
9. 如果 TMS platform admin 未绑定 exact upstreamSystemId 和 allowed tenant 集合，“自有系统高权限”会退化成跨 upstream/tenant 的隐式超级管理员。
10. 如果 Worker allocation 通过隐式 owner transfer、创建替代 Worker 或 Pool/Biz identity 绕路实现，tenant 回收、容量隔离和 Codex 路由都会失去可审计性。
11. 如果 trust profile 被实现成直接放行标签，“绝对可信”会退化为免认证 bypass，S2/S3 也可能因错误分类获得越权。
12. 如果为了假设中的第三方需求提前增加宽泛 upstream-admin、万能 ClientApp scope 或公开 onboarding，攻击面会在没有真实验收主体和 threat model 时扩大。
13. 如果把 Observer BFF 的 `/api/v1/open/**` 路径误认为受 launcher `ExternalSurfaceGateFilter` 保护，或仅凭 `NAVIGATOR_EXTERNAL_ENABLED` 判断安全性，会遗漏其独立 `0.0.0.0:5181` ingress、无入站 session 和 server-held runtime authority 复用风险。

### Resolved P1A compatibility decision

1. Spring Boot 3.4.2 actuator discovery-links 在 non-empty `/actuator` base path 下形成实际 `GET /actuator` ingress。2026-07-19 Owner 已批准其以 `framework:get:/actuator` / `actuator.discovery-links.read` 作为第 415 条 deployment-aware route/action；批准 SHA-256 `d0360de638c47fbb9e88cb349aec8b92559894bed9554d7200ba10223d12efa9` 是 BUG-002 required-section amendment 前的历史 manifest snapshot。并关闭 discovery-links。`/actuator` 从 HTTP 200 links 响应变为 404 是唯一批准的兼容例外；子 Actuator endpoint 不因此改变。`ActuatorDiscoveryContractTest` 与 `ActuatorDiscoveryRouteContractTest` 覆盖响应和 sidecar shadow/audit。

该已解决的 amendment 不改变 `navi.authorization.v1`、credential/token/platform-grant claim、CLI introspection、trusted-loopback 服务端判定、shadow/cutover/rollback 或 legacy 不自动提升边界；不得把它作为扩大 P1A/P1B/P1C 的理由。

S1 单 tenant、多 tenant 或实例内出现其他 upstream 已不再是权限 open question：SIM root 始终覆盖整个 `navigatorInstanceId`。默认 tenant 创建和存量映射仍需在数据/迁移设计中明确，但不得改变 root allow/deny。

P1B 前的实际数据库脱敏 inventory、P1B/P1C 开工授权和 production 基础设施选型是后续阶段 gate，不是 P1A open question。未经新的明确授权不得实施。

S3 的具体产品需求有意 deferred，不进入当前 open questions，也不阻塞 S1/S2 主实现；但 unknown/external profile 默认拒绝和不得继承 S1/S2 高权限是当前必须满足的架构约束。

## P1A Repair Authorization

- 2026-07-19 Project Owner 已批准 [BUG-002](./BUG-002-p1a-required-section-contract.md) 作为 P1A-3 的唯一修复合同；授权范围仅为 action required-section catalog/context/validator、legacy adapter 与对应测试/evidence 同步。
- Project Owner 已批准 Observer BFF 在 P1A 采用 `catalog-and-test-only`：12 条 route 继续纳入 deployment-aware catalog、required-section 声明与 test continuity，但不得在 BUG-002 中修改 BFF runtime `src/main`、接入 shadow evaluator/audit，或顺带实施 ingress/session/capability/production hardening。
- 该授权不改写 2026-07-19 首次独立签核的历史 `rejected` 结论。BUG-002 完成后必须重新独立签核；该条件已由 2026-07-19 accepted re-signoff 满足，且 P1B/P1C 仍需各自明确授权。
- BUG-002 与 P1A repair gate 已关闭；Observer BFF P1A-6 范围待决项已关闭，但其 runtime/production 风险仍作为后续独立工作保留。

## Historical Ultra Execution Contract

- 本节记录 BUG-002 开工时的 Ultra 约束：当时 P1A implementation submission 为 `READY_FOR_SIGNOFF`，首次独立 signoff 为 `rejected`，只授权实施已批准的 [BUG-002](./BUG-002-p1a-required-section-contract.md)。该实施与 re-signoff 现已完成并 accepted；这些历史约束不构成 P1B 授权。
- Ultra 必须先读取本文件、项目 `AGENTS.md`、`CLAUDE.md`、相关模块规范、`foggy-delivery-spec` 和 `navigator-runtime-provisioning`。
- BUG-002 修复只包括 action-specific required-section catalog、canonical sparse typed context、validator/evaluator、legacy adapter、catalog/evidence checksum 与自动化回归；Ultra 可在批准合同内自主选择具体文件、类和实现结构。
- P1A 的 decision/diff 可查询性只要求 repository/service/test 或既有受控 internal diagnostics surface；不得新增 `/api/v1/management/v1/**`、audit/diff HTTP API、CLI command 或 UI。
- 不得改变任何现有 allow/deny，不得 seed/签发 S1/S2 typed credential，不得实施 P1B/P1C management API、CLI、SKILL UX、S3 onboarding、真实上游联调或 production/external enablement。
- Observer BFF 仅允许 catalog/test-scope 连续性调整；不得修改其 runtime `src/main`、接入 evaluator/interceptor/audit store，或实施 observer session、attachment capability、CSRF/origin、bind-address、网络暴露或 production hardening。
- Codex 必须继续走 existing Physical Worker + `claudeCode.codexConfig`；不得新增 Worker、BizWorkerIdentity 或 WorkerPool member，也不得修改 Physical Worker 路由来完成本事项。
- 如实现需要改变目标、共享/专属实例兼容策略、已确认的 S1 root 权限、S2 平台/租户分层、S3 design-only 边界、数据迁移或安全边界，应设置 `NEEDS_REPLAN` 并停止扩展。
- 实现完成后回写 changed paths、精确测试结果、偏差和残余风险，状态最多更新为 `READY_FOR_SIGNOFF`。

## Implementation Result

> P1A foundation/shadow 已于 2026-07-19 通过独立 re-signoff；历史 `rejected` 记录继续保留，当前 accepted 范围仅为 P1A。

- implementation_summary: 在 `navigator-common` 实现六个 additive `authorization_*` aggregate 的 JPA/schema、forward/rollback migration、server-owned deployment identity、sparse `AuthorizationContextV1` / `PolicyDecisionV1`、415 条 source-controlled action/route catalog、legacy lane adapter、redacted append-only decision audit 与 non-binding shadow evaluator；`user-auth-module` 仅在 legacy request 完成后旁路观察 legacy outcome、HTTP status/body 与业务副作用。Owner 批准的唯一响应变化是关闭 discovery-links 后 `/actuator` 由 HTTP 200 links 变为 404，且以 `framework:get:/actuator` / `actuator.discovery-links.read` 形成 sidecar shadow/audit；子 Actuator endpoint 保持原有配置行为。未进入 P1B/P1C、enforcement/cutover 或 production/external/Gateway/Worker 工作。
- changed_paths: `navigator-common/src/main/java/com/foggy/navigator/common/authorization/**`、`entity/Authorization*Entity.java`、`repository/Authorization*Repository*.java`、`src/main/resources/authorization/route-manifest-v1.csv`、相关 common tests/pom；`user-auth-module` 的 shadow adapter/interceptor/response advice、`WebMvcConfig` 及测试；`launcher` 的 deployment identity production guard、`application.yml` discovery 配置、pom 和 route/Actuator contract tests；`tools/navigator-chat-observer-bff` 仅 test-scope dependency 与 catalog/context coverage test；`docs/migration/2026-07-19-gov-001-authorization-foundation*.sql`、本 version work item/README/evidence。未修改 legacy table、credential issuance、external/Gateway/Worker/Codex route、CLI、UI 或 BFF runtime hardening；工作树中既有的 `packages/navigator-frontend/src/views/ClaudeWorkerView.vue` 非本事项变更已保留且未纳入 P1A。
- tests_and_results: `mvn test -pl navigator-common` passed (83 tests; 3 opt-in MySQL tests skipped); implementation and reviewer runs of the opt-in MySQL suite passed. Independent reviewer command `mvn test -pl navigator-common -Dgov001.mysql.integration=true -Dtest=AuthorizationContractTest,DeploymentIdentityResolverTest,AuthorizationDecisionAuditStoreImplTest,AuthorizationPersistenceMySqlIntegrationTest` passed on 2026-07-19 with 19/19 tests, including MySQL 8.0.44 fresh schema、legacy-preserving upgrade、controlled rollback 和 `ddl-auto=validate` 3/3. `mvn test -pl tools/navigator-chat-observer-bff -am -Dtest=ObserverBffRouteManifestCoverageTest,ObserverBffContextContinuityTest -Dsurefire.failIfNoSpecifiedTests=false` passed with BFF 3/3 and 3-module reactor success. `mvn test -pl user-auth-module -am` passed; current HEAD 最近一次 `mvn test -pl launcher -am` passed as a 14-module reactor with exit 0. Earlier BUG-001 reproduction failure is no longer current: BUG-001 has been independently fixed and committed at current HEAD. `git diff --check` passed before signoff; at the original P1A submission before BUG-002, source and evidence manifests had 416 lines including header / 415 entries and SHA-256 `d0360de638c47fbb9e88cb349aec8b92559894bed9554d7200ba10223d12efa9`; BUG-002's current source/evidence SHA-256 is recorded separately as `ef4c32ac4ca25ee695dff7bacd9845301266807d71fbcafe35ebba4872aadc7d`. Changed-file secret scan completed with no high-confidence secret material, and scoped forbidden-surface/Worker/CLI mutation review passed. Those historical passing tests do not cover the required-section contract identified by independent signoff.
- manual_or_experience_evidence: `ActuatorDiscoveryContractTest` locks `/actuator` at 404 and child health endpoint at 200 after discovery-links is disabled. `ActuatorDiscoveryRouteContractTest` proves the approved 404 still produces a sidecar `framework:get:/actuator` audit with legacy `DENY` / `HTTP_STATUS_404`, without making catalog registration an enforcement gate. Route coverage tests keep launcher/Observer BFF duplicate paths deployment-distinct and reject unregistered route/action shadow evaluation. Test execution used local test contexts/Testcontainers only; no real upstream, credential profile, business data, external switch, Worker route or production service was changed.
- deviations: Owner-approved compatibility exception: `management.endpoints.web.discovery.enabled=false` changes `GET /actuator` discovery links from 200 to 404. At the time of independent signoff, Observer BFF had catalog/test coverage but no runtime shadow/audit and was classified as P1A-6 partial. Project Owner subsequently approved the `catalog-and-test-only` P1A amendment, so this is now an approved scope boundary rather than an unresolved repair item; BFF runtime remains unchanged. The preserved frontend dirty change is unrelated and excluded from P1A.
- residual_risks: BUG-002 已关闭 required-section 合同和 runtime capability 误分类阻断。Observer BFF 仍无 runtime sidecar decision audit 且保持 local/trusted-dev、production-blocked；该风险按 Owner amendment 明确延期，不属于 P1A 阻断项。`authorization_decision` append-only 仅在 repository/entity 层保证，未声明 DB trigger、独立 privilege model 或 tamper-evident sink。P1B seed/typed credential lifecycle、P1C cutover/CLI/SKILL、S3、production secret/audit infrastructure、strong upstream-user identity、Gateway strict client propagation 和 explicit `routeKind` 仍未实施。
- readiness: P1A 已通过独立 re-signoff 并 accepted。该状态不表示整体 GOV-001、S1/S2 typed management、P1B/P1C、external/Gateway/Worker strict 或 production 已完成；下一步必须由 Project Owner 单独授权 P1B 或其他明确阶段。

### P1B-A independent signoff (ACCEPTED)

- implementation_summary: 增加独立 typed-management ingress guard、typed principal credential / management bearer 解析与 canonical enforcement decision 绑定；只提供五个 `/api/v1/management/v1/auth/**` canonical endpoint。control exchange 只签发短期 `CONTROL_ACCESS`；security authorization 只签发 action/target/impact/reason-bound 的短期 `SECURITY_ACTION`，并以 repository compare-and-set 实施单次消费。`whoami`、`permissions` 与 `explain` 只使用 request-local safe context，`explain` 固定为 non-binding preflight。成功签发时只向已认证调用者一次性序列化新的 opaque bearer；presented credential、token ID、token reference、verifier material 及诊断 `toString()` 均不回显。
- changed_paths: `navigator-common/.../authorization/TypedManagementAuthorizationService`、typed management DTO/codec/verifier interfaces、`IssuedManagementToken` 及其 tests；`user-auth-module/.../TypedManagementAuthInterceptor`、`SecurityConfig`、`WebMvcConfig` 及 guard/MVC negative tests；`business-agent-module/.../ManagementAuthController`、endpoint service、forms/DTOs 及 tests；source/evidence route manifest 的五条 management route；本 work item 与版本索引。未触碰 legacy route family、real seed、CLI/SKILL、Open API/Gateway/Worker external 开关、Worker/Codex Physical Worker 路由或 Observer BFF runtime。
- tests_and_results: 2026-07-19 `mvn test -pl launcher -am` completed with 14-module `BUILD SUCCESS` (4m43s). Current Surefire reports show `TypedManagementAuthorizationServiceTest` 8/8, `TypedManagementAuthInterceptorTest` 66/66, `TypedManagementSecurityConfigTest` 3/3, mapped-but-unregistered MVC contract 1/1, `ManagementAuthEndpointServiceTest` 13/13 and `ManagementAuthControllerTest` 3/3. The mapped-but-unregistered contract proves a Controller mapping inside `/api/v1/management/v1/auth/**` is denied with `403 AUTHZ_ACTION_UNREGISTERED` before Controller execution.
- integrity_evidence: source and evidence manifests are byte-identical, 421 lines / 420 entries, SHA-256 `55cd6b2f67c98ace16fcc58334d9dfccb8f36d7045cd9f5eb5f6bd5ba58231f2`; exactly five typed-management routes and no duplicate route ID. 2026-07-19 `git diff --check` passed (only pre-existing CRLF conversion warnings), and the scoped high-confidence secret scan returned no match.
- deviations: none from the P1B-A fixture-only contract. The unregistered MVC regression test is included to make the canonical guard attachment observable rather than relying only on a direct interceptor test.
- residual_risks: no real S1/S2 principal, credential, grant, tenant authority, verifier or owner mapping exists; default deployment remains fail closed. P1B-A has no resource target/owner/grant resolver, legacy route-family cutover, CLI/SKILL operator UX, signed upstream-user assertion, Gateway strict propagation, production infrastructure or release evidence. It is not external, Provider, Worker Gateway or production readiness.
- readiness: P1B-A only is independently `ACCEPTED`; P1B-B and later remain gated. The next formal action is a separate P1B-B inventory/owner-operator approval contract, not real seed or credential issuance.

### P1B-B0 independent signoff (ACCEPTED)

- implementation_summary: 新增纯离线 `navi.authorization.preseed-inventory.v1` schema、strict JSON codec、canonical checksum、classification/result/validator、synthetic fixtures 和 secure-source/four-eyes runbook。重复 JSON key 在 tree construction 前被拒绝，secret-like input 从不回显，legacy upstream-admin/scope/tenant-list 不会自动提升。
- changed_paths: `navigator-common/.../authorization/preseed/**`、focused validator test/resources、`runbooks/GOV-001-p1b-preseed-inventory-and-owner-approval.md`、B0 work item 与 [independent signoff](../evidence/GOV-001-p1b-b0-independent-signoff.md)。未修改任何运行时 API、CLI、数据库、route、Worker/Gateway/Codex 路由、external 或 production 配置。
- tests_and_results: independent reviewer reran `mvn test -pl navigator-common -Dtest=PreseedInventoryValidatorTest` (10/10) and `mvn test -pl navigator-common` (120 tests, 0 failures/errors; 3 opt-in Testcontainers skipped), both `BUILD SUCCESS`; `git diff --check` exit 0 with only unrelated CRLF warnings.
- residual_risks: B0 不能确认真实 S1/S2 subject、instance/profile、owner/tenant/ClientApp mapping、credential/verifier/KMS ownership 或四眼审批；它不授权 real seed、issuance、cutover、Gateway/external/Worker enablement 或 production。
- readiness: B0 is independently `ACCEPTED` as an offline precondition only. A separately approved real P1B-B contract remains mandatory before any real data or mutation.

## Parent Delivery Status

- overall_acceptance_status: not-accepted
- overall_acceptance_decision: blocked
- accepted_slices: P1A, P1B-A, P1B-B0, P1C-A
- blocking_items: P1B-B factual inventory/four-eyes approval; P2 identity/Gateway decisions; P3 production-owner evidence; P4 telemetry/release decisions
- acceptance_record: none; slice records are listed below
- follow_up_required: yes

## References

- version index: [1.4.3-SNAPSHOT](../README.md)
- P0.5 method-level route manifest: [CSV](../evidence/GOV-001-p0.5-method-route-manifest.csv), [static review](../evidence/GOV-001-p0.5-method-route-manifest-review.md)
- P0.5 seed/legacy mapping review: [static review](../evidence/GOV-001-p0.5-seed-legacy-mapping-review.md)
- P1A historical independent signoff: [rejected](../evidence/GOV-001-p1a-independent-signoff.md)
- P1A independent re-signoff: [accepted](../evidence/BUG-002-p1a-required-section-independent-resignoff.md)
- P1B-A independent signoff: [accepted](../evidence/GOV-001-p1b-a-independent-signoff.md)
- P1B-B0 independent signoff: [accepted](../evidence/GOV-001-p1b-b0-independent-signoff.md)
- P1C-A independent signoff: [accepted](../evidence/GOV-001-p1c-a-independent-signoff.md)
- P1B-B/P2/P3/P4 owner decision intake: [pending owner input](./GOV-001-owner-decision-intake-adr-packet.md)
- accepted repair: [BUG-002 required-section contract](./BUG-002-p1a-required-section-contract.md)
- prior trust boundary: [1.4.2 GOV-001](../../1.4.2-SNAPSHOT/workitems/GOV-001-internal-external-trust-boundary.md)
- prior Worker/upstream-user boundary: [1.4.2 GOV-002](../../1.4.2-SNAPSHOT/workitems/GOV-002-biz-worker-and-upstream-user-boundary.md)
- current version baseline: [1.4.2 README](../../1.4.2-SNAPSHOT/README.md)
- issue evidence: [#151](https://github.com/foggy-projects/Foggy-Navigator/issues/151), [#152](https://github.com/foggy-projects/Foggy-Navigator/issues/152)
- code/config anchors:
  - `launcher/src/main/resources/application.yml`
  - `addons/claude-worker-agent/.../ExternalSurfaceGateFilter.java`
  - `business-agent-module/.../WorkerGatewayRequestAuthorizationService.java`
  - `business-agent-module/.../ClientAppControlCredentialService.java`
  - `business-agent-module/.../ClientAppRuntimeCredentialResolver.java`
  - `business-agent-module/.../A2AgentResourceResolver.java`
  - `navigator-open-sdk/.../UpstreamCli.java`
