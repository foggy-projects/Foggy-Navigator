---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.3-SNAPSHOT
ticket: GOV-001
status: DRAFT
canonical: true
execution_mode: ultra
scenario_s1_status: aligned
scenario_s2_status: aligned
scenario_s3_status: design-aligned-implementation-deferred
primary_implementation_scope: s1-s2
approved_by: pending
approved_at: pending
open_questions:
  - dedicated-instance-tenant-topology
  - dedicated-upstream-root-role-name-and-credential-lifecycle
---

# Delivery Spec: 上游权限体系与多场景信任边界

## Document Purpose

- intended_for: normal-analysis / future-ultra-implementation / independent-signoff
- purpose: 记录当前权限模型事实，并按真实上游场景冻结上游系统、ClientApp、runtime、Agent/task 和 Worker 的权限边界。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/GOV-001-upstream-permission-and-trust-boundary.md`
- current_boundary: 本文件仍为 `DRAFT`；只允许继续需求和架构对齐，不构成代码、数据库、配置或运行态变更授权。

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
  - 后续授权门面、CLI、SKILL、runbook、测试和审计的交付要求。
- affected_modules:
  - `user-auth-module`
  - `business-agent-module`
  - `session-module`
  - `addons/claude-worker-agent`
  - `navigator-open-sdk`
  - Worker implementations and Worker Gateway clients
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies:
  - `foggy-world-sim`、`tms-x3` 仅作为需求方和未来联调方；当前不授权修改其仓库。

## Non-Goals

- out_of_scope:
  - 本轮不实现权限代码、数据库迁移、API、CLI 或 UI。
  - 本轮不启用 Open API external、Worker Gateway strict、Worker external 或 production 路由。
  - 本轮不实现 S1、S2 或后续上游的最终权限模型。
  - 当前版本主实现目标仅为 S1/S2；不实现 S3 外部第三方 onboarding、credential 签发、管理 API、真实流量接入或 production 发布。
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
| 1. Platform route/profile gate | 请求是否能到达业务 Controller | `/api/v1/open/**` gate、network binding、loopback/ACL、Ingress/TLS | route reachable 只表示入口可达，不代表调用者已获授权 |
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
| `/api/v1/upstream-admin/**` | upstream system control principal | upstream-admin key + upstreamSystemId + authorized tenants + namespace + scopes | `NAVIGATOR_EXTERNAL_ENABLED` |
| ClientApp control APIs | exact ClientApp control principal，或具名平台管理例外 | exact tenant + ClientApp + control scopes + resource ownership/grant | runtime token；普通 upstream user declaration |
| `/internal/worker-gateway/v1/**` | task-bound Worker | task capability；strict profile 再加 exact Worker credential/lease/route | platform Open API gate |
| 其他 `/api/**` | Navigator user、operator 或专用 principal | `@RequireAuth`、resolver、Controller/Service ownership 的组合 | 不能仅根据 Spring Security matcher 判定匿名或已授权 |

Spring Security 当前对多类 API 使用 `permitAll`，而 `AuthInterceptor` 只填充上下文、不主动阻断。这里的 `permitAll` 不等于匿名可用，但真实 enforcement 分散在 Filter、AOP、Controller、resolver 和 Service；新增端点漏接任何一层都会形成高风险缺口。

### Credential lanes

| Lane | Principal / header | Intended use and scope | Current lifetime/default | Prohibited use or risk |
|---|---|---|---|---|
| Navigator operator | Navigator instance owner/operator；`X-Navi-Operator-Key` / `X-Navi-Operator-Api-Key` | bootstrap approval、admin credential 管理、break-glass、根安全控制 | 部署配置 SHA-256；不是普通业务 TTL credential | 不下发给上游业务应用；不用于日常 provisioning、readiness、ask 或 runtime |
| Upstream admin | upstream system control principal；`X-Navi-Admin-Key` / `NAVI_ADMIN_API_KEY` | ClientApp、upstream-owned/shared resource、WorkerHost、Directory、Model、Agent、grant 管理 | request/claim/admin credential 默认 24h；可经显式高风险审批产生无过期 key | 不能当 operator、runtime 或 task token；不得默认拥有其他 upstream system/instance 的资源 |
| Dedicated instance root（target） | exact Navigator instance + upstream system；S1 为 `foggy-world-sim` | 该专属实例全部控制面动作，包括跨 owner 管理、owner transfer、delete/revoke、credential rotation、grant delegation 和 instance promotion | 当前尚无冻结的 credential 形式或默认 TTL；可能由扩展 upstream-admin、独立 root credential 或具名 root ClientApp 表达 | 不得跨实例，不得复用为 runtime/task/Worker credential，不得绕过 readiness、production gate 或审计 |
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

| Issue | Confirmed evidence | Evidence not present | Current issue state checked 2026-07-18 |
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

## Scenario S1: foggy-world-sim Dedicated Navigator

### User-confirmed scenario semantics

1. 为 `foggy-world-sim` 专门启用一个 Navigator 服务实例；该实例完全为该上游服务，不与其他业务上游共享。
2. `foggy-world-sim` 是该 Navigator 实例的 owner/root 主体，对实例内全部 Navigator 控制面能力拥有权限。
3. root 权限属于 `foggy-world-sim` 主体本身；未来可由专属 root credential、扩展后的 upstream-admin，或一个被明确指定为 root 的具名 ClientApp 表达，最终形式仍待架构决策。
4. 对自有和非自有资源，root 均可执行 create、discover、bind、operate、owner transfer、delete/revoke、credential rotation 和 grant delegation。
5. `bind` 不隐式改变 owner；`owner transfer` 仍是独立、显式、可审计的动作，但两者都在 root 的权限范围内。
6. 普通 ClientApp、runtime credential、task token 和 Worker credential 不因属于该实例而自动继承 root 权限。
7. 对 Agent 发起 ask 时，任务实际能力仍由 runtime caller、upstream user、Agent 设计、task capability、Worker route 和执行策略共同约束，不由 root 控制面权限直接放大。
8. “拥有全部权限”不等于跳过 fail-closed、readiness、production gate、凭据分层、secret 隔离或审计约束。
9. S1 描述的是专属实例拓扑，不是可跨环境携带的全局权限等级；同一用户或服务管理另一个 Navi 实例时，必须在目标实例重新获得并使用该实例自己的 root principal、grant 和 credential。

### Design assessment

该业务目标在“一实例只服务一个强绑定上游”的前提下成立。S1 应建模为 `foggy-world-sim` 拥有 instance-scoped root authority，而不是把普通 `ClientApp` 类型本身定义为天然超级管理员：

| Layer | Principal | Authority |
|---|---|---|
| Instance owner/root | `foggy-world-sim`，绑定 exact `navigatorInstanceId + upstreamSystemId` | 该实例内全部 Navigator 控制面权限，包括 ClientApp、tenant 范围内资源、Worker、Directory、Agent、Model、binding、owner、grant、credential 和实例配置管理 |
| Root credential expression | 专属 root credential、扩展 upstream-admin，或被显式标记为 root 的具名 ClientApp | 只表达 `foggy-world-sim` 的 instance-root 权限；不能把同类普通 ClientApp 一并提升 |
| Ordinary ClientApp control | 一个未获 root designation 的具体 ClientApp | 只管理该 ClientApp 自有或明确授权的资源、grant 和 binding |
| Runtime caller | ClientApp runtime + delegated upstream user | readiness、owner-smoke、ask、messages；不能执行实例 root 管理 |
| Agent/task capability | Agent policy + task token | 控制单次任务的模型、函数、工具、目录和 Worker/lease 能力 |
| Worker principal | Physical Worker route or BizWorkerIdentity | 执行已授权任务；不能继承 instance-root 管理权 |
| Infrastructure recovery plane | 部署方的 OS/DB/KMS/secret-store recovery 能力 | 属于 Navigator 应用权限体系之外的基础设施保管职责；不应通过上游 API credential 暴露，也不构成扣留 root 的 Navigator 业务权限 |

“Instance owner/root”是已确认的业务角色；是否新增 enum、表、credential 类型或复用现有 upstream-admin，仍由后续架构决策确定。

### Resource and instance-control semantics

| Operation | Confirmed semantic |
|---|---|
| Create | root 可创建任意实例内资源，并显式指定合法 owner；不得依赖模糊的 platform 默认 owner |
| Discover | root 可发现实例内自有和非自有资源；secret 明文、密钥材料等不可读取内容不因 root 而回显 |
| Bind | root 可建立 Agent/Model/Directory/Worker/ClientApp 等关系；binding 与 owner record 分开存储和审计 |
| Operate | root 可使用、更新或管理任意实例内资源，不通过伪造 owner、tenant 或 caller 字段实现 |
| Transfer ownership | root 可显式转移 owner；不得由 bind 隐式触发，必须记录 before/after、reason 和结果 |
| Delete/revoke | root 可删除普通资源或撤销其可用性；不可篡改的审计证据和系统安全不变量不属于可删除业务资源 |
| Credential rotation | root 可签发、轮换和撤销其有权管理的实例 credential；不得读取旧 secret，审计不得记录新旧 secret 明文 |
| Grant delegation | root 可向实例内主体委托权限并撤销委托；grantee、scope、expiry 和 delegation chain 必须可审计，且不能越出该 Navigator 实例 |
| Instance configuration / promotion | root 可执行实例配置和 external/production 推进动作，但系统必须独立验证网络、身份、Worker policy、审计、迁移、回滚等前置条件；条件不满足时仍 fail closed |

S1 的授权结论冻结为：

> `foggy-world-sim` 是其专属 Navigator 实例的 owner/root，对实例内自有和非自有资源拥有全部控制面权限。该权限不跨 Navigator 实例生效，不自动下沉给普通 ClientApp、runtime、task 或 Worker principal，也不能绕过系统安全不变量。

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
6. instance root 只能在绑定的 `navigatorInstanceId` 内生效；不得访问其他 Navigator 实例或伪装成其他独立 upstream system。

## Scenario S2: tms-x3 Business SaaS

### User-provided scenario input

1. `tms-x3` 是自有业务 SaaS 系统，可以获得高于普通租户 ClientApp 的平台管理权限。
2. `tms-x3` 需要代表业务租户创建和管理 ClientApp。
3. `tms-x3` 需要为租户分配 Worker 等 Navigator 资源。
4. 业务租户可以取得其 ClientApp 的凭据，但该凭据必须受到限制，不能继承 `tms-x3` 平台主体的高权限。

### Confirmed baseline and remaining design

S2 使用共享 Navigator。`tms-x3` 是受信 SaaS upstream-system 管理主体，但不是 Navigator instance root。它可以跨自身获准业务租户执行 provisioning，但权限必须止于 exact `upstreamSystemId=tms-x3`，不能触达其他 upstream system、Navigator root 或基础设施恢复面。

| Layer | Principal | Authority boundary |
|---|---|---|
| Navigator instance root/operator | Navigator 部署与治理主体 | 管理实例级 upstream system、production policy、trust root 和 break-glass；不下发给 TMS 租户 |
| TMS SaaS platform subject | exact `upstreamSystemId=tms-x3` | 在获准 tenant 集合内拥有完整 upstream 管理权，但通过 provisioning 与 security-admin 两种凭据/profile 分层执行 |
| TMS provisioning principal | TMS platform subject 的日常自动化 lane | 创建/启用 tenant 与 ClientApp、签发初始 tenant credential、配置资源、创建/更新/分配/重分配 TMS-owned Worker、Directory、Model、Agent、grant 和 binding |
| TMS security-admin principal | TMS platform subject 的高风险 lane | tenant/ClientApp 停用或删除、owner transfer、资源 delete/revoke、credential rotation/revocation、grant delegation、Worker delete 和紧急恢复；必须 step-up、影响预览和审计 |
| Tenant ClientApp control | exact `upstreamSystemId + tenantId + clientAppId` | 仅在明确启用租户自助管理时，管理该 ClientApp 自有或获准资源；不能管理其他 ClientApp、tenant、upstream shared fleet 或平台 credential |
| Tenant ClientApp runtime | exact ClientApp key/secret 或短期 access token | runtime-token、readiness、owner-smoke、ask、messages 和被授权业务功能；不能创建 ClientApp、分配 Worker 或执行 provisioning |
| Tenant upstream user | 由已认证 ClientApp 委托并经 mapping/grant 限制 | 在该 ClientApp 和 tenant 内使用其用户级 Agent、Directory、task/context 能力 |
| Task capability + Worker principal | exact task、function、Worker/lease route | 只执行单次获授权任务，不继承 TMS platform admin 或 tenant control 权限 |

### Credential separation

S2 必须有 TMS provisioning、TMS security-admin 与 tenant runtime 三条 lane；出现租户自助需求时，再增加独立且不可混用的 limited control lane：

| Credential lane | Holder | Allowed | Denied |
|---|---|---|---|
| TMS provisioning | 仅 `tms-x3` 受控服务端自动化 | tenant/ClientApp create/update/enable、初始 credential issuance、资源配置、TMS-owned Worker create/update/reassign 和 grant/binding | destructive delete、owner transfer、credential emergency rotation/revoke、跨 upstream、Navigator root、ask/runtime |
| TMS security-admin | 仅 `tms-x3` 受控高权限服务或人工审批流程 | tenant/ClientApp suspend/delete、owner transfer、delete/revoke、credential rotation/revocation、grant delegation、Worker delete 和 recovery | 日常 ask/runtime、跨 upstream system、Navigator root、跳过 step-up/影响预览/审计 |
| Tenant ClientApp control（optional） | 租户受控服务端；仅在需要自助配置时签发 | exact ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已获授权资源之间的 grant/binding 具名 scope | credential lifecycle、Worker 管理、ClientApp lifecycle、owner transfer、grant delegation/扩权、其他 ClientApp/tenant、平台 credential 和 production 设置 |
| Tenant ClientApp runtime（default） | 租户业务服务端 | 换取短期 runtime token，并在 exact tenant/ClientApp grant 内 ask/readiness/messages | 任何控制面创建、credential 签发、Worker 分配、owner transfer 或跨 ClientApp 操作 |

`tms-x3` 主体拥有完整 upstream 管理权，但日常 provisioning credential 不能执行 security-admin 的破坏性动作。租户默认只获得 ClientApp runtime key/secret，不得拿到任何 TMS platform credential。只有在明确存在租户自助配置需求时，才另行签发 application-config-only control credential；所有 lane 必须独立过期、轮换、撤销和审计，不能用一个“万能 key”兼任。浏览器端只应持有短期、受 audience/TTL 限制的 token，不持有长期 secret。

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
| `foggy-world-sim` 是实例 owner/root | 专属实例即为该主体服务，root 对实例内 Navigator 控制面拥有全部权限 | 权限绑定 exact instance，不跨其他 Navigator 实例 |
| Navi 实例之间是独立授权域 | 用户已确认不同 Navi 环境实例的权限互不相关 | 不存在全局 S1/root 权限；同一用户、服务或公司身份也必须按目标 `navigatorInstanceId` 分别认证和授权，credential/grant/session/task/Worker principal 不得跨实例复用 |
| root 可管理自有与非自有资源的完整动作集 | 用户已确认非自有资源不只允许 discover/bind/operate | 包括 owner transfer、delete/revoke、credential rotation 和 grant delegation |
| binding 与 ownership 保持分离 | bind 与 owner transfer 的业务含义、恢复方式和审计要求不同 | root 同时拥有两种权限，但 bind 不隐式触发 transfer |
| root 权限属于上游主体而非普通 credential 类型 | 同一主体可通过专属 credential 或具名 root ClientApp 表达 | 普通 ClientApp、runtime、task 和 Worker credential 不自动继承 |
| 控制面管理权与 ask 执行权限分离 | 防止上游管理权限直接变成 Agent/Worker 任意执行能力 | ask 始终执行 runtime/Agent/task/Worker 权限交集 |
| 安全门是系统不变量，不是保留给另一主体的业务权限 | 保持 fail-closed、凭据分层、secret 隔离和可审计性 | root 可推进实例配置和 production 流程，但不能跳过 readiness 或自我伪造 production ready |
| `tms-x3` 是可获较高授权的自有业务 SaaS | TMS 平台需要代表业务租户完成 Navigator provisioning | 其权限高于租户 ClientApp，但不因“自有系统”自动等于 Navigator instance root |
| TMS 平台可为租户创建/管理 ClientApp 并分配 Worker | 这是 SaaS 平台统一开通租户运行环境的业务职责 | 在 `upstreamSystemId=tms-x3` 和获准 tenant 集合内拥有完整管理权；日常动作与破坏性动作使用不同 lane |
| 租户取得的 ClientApp credential 必须受限 | 防止一个租户继承 TMS platform admin 或访问其他租户 | credential 必须绑定 tenant + ClientApp + lane，不能下发 upstream-admin |
| S2 使用共享 Navigator | 在同一 Navigator 中验证 upstream system 与 tenant 隔离，不把 TMS 权限提升为实例 root | TMS platform admin 只能管理 `upstreamSystemId=tms-x3` 范围 |
| S2 tenant 默认仅获 runtime credential | 租户日常需求是业务调用，不应默认获得控制面写权限 | limited control 仅按明确自助需求独立签发 |
| S2 Worker 由 TMS upstream 持有 | 共享/专用分配不应隐式改变 Physical Worker ownership | owner=`UPSTREAM_SYSTEM/tms-x3`，tenant/ClientApp 通过 grant/binding 获得使用权 |
| TMS 主体使用 provisioning/security-admin 双角色 | 主体需要完整 upstream 管理能力，但常驻自动化凭据不应承担全部破坏性权限 | create/update/assign 走 provisioning；delete/transfer/rotate/revoke/delegate 等高风险动作走 security-admin + step-up |
| Tenant optional control 仅限应用配置 | 租户自助不应扩展到 credential、Worker 或其他 ClientApp/tenant | 只允许 exact ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已授权资源之间的 grant/binding；不能扩大 scope 或继续 delegation |
| TMS 完整管理 TMS-owned Worker 生命周期 | TMS 需要独立完成 SaaS tenant Worker provisioning 与回收 | create/update/reassign 可走 provisioning；delete/owner transfer/recovery 走 security-admin，并保持 Codex Physical Worker 规范路径 |
| 外部第三方默认不可信且不是当前主实现目标 | 当前没有真实第三方需求，不应为假设场景扩大接口或 credential 面 | 当前无允许路径；未来从 exact tenant + ClientApp runtime 最小权限起步，并以独立 workitem 冻结需求 |
| trust profile 只限制权限上限 | 防止把“绝对可信”误解为免认证，或把类型标签变成 hard-coded bypass | S1/S2/S3 均继续执行 credential、owner/grant、task、Worker、execution 和 audit 判定；未知 profile fail closed |
| Codex Physical Worker 保持直接路由 | #151 已验证该模式，不应以 Pool/Biz identity 绕过 | 禁止新增 Worker/Pool member 作为权限或 readiness 修复 |

三类上游的业务信任定位均已对齐：S1/S2 是主实现目标，S3 是默认拒绝的未来设计边界。文档仍保持 `DRAFT`，因为 S1 root principal/tenant topology，以及跨场景统一 principal/schema/API 尚未完成架构决策。

## Proposed Architecture Decisions

以下决策只影响已确认权限如何表达和治理，不改变 S1 的 instance-root、S2 的“平台高权限、租户凭据受限”，也不能把 S3 从 design-only 自动升级为可接入状态：

| Proposal | Rationale | Decision needed |
|---|---|---|
| root 使用可识别的 instance-scoped principal | 避免把普通 upstream-admin 或所有 ClientApp 误提升为 root | 在扩展 upstream-admin、独立 root credential、具名 root ClientApp 之间决策，并冻结 TTL、轮换、撤销和恢复机制 |
| tenant 拓扑与 root scope 分开建模 | 无论单 tenant 或多 tenant，root 都拥有该实例权限；普通 ClientApp/runtime 的隔离仍依赖 tenant | 决定单 tenant/多 tenant，以及 upstream system、tenant、ClientApp 的映射与迁移规则 |
| TMS subject + dual credential profiles | S2 动作语义已确认，需要映射到可识别且可审计的 principal/scope | 决定复用 upstream-admin scope 还是引入 SaaS provisioning/security-admin role，并冻结 allowed tenant claim、TTL、step-up 与撤销传播 |
| Tenant application-config control policy | scope 已确认为 Agent/Model/Directory/grant/binding，需要统一资源 action contract | 决定申请审批、TTL、各资源细粒度 action，以及一个 tenant 多 ClientApp 的 schema/唯一性 |
| TMS Worker lifecycle policy | ownership 和完整生命周期已确认，需要保证 destructive action 事务与恢复 | 决定 shared/dedicated 容量 policy、影响预览、delete/transfer recovery 和 offboarding transaction |
| upstream trust profile / policy extension point | 防止 S1/S2 权限成为所有 upstream 默认值，同时为未来 S3 保留安全扩展 | 决定使用显式字段、principal type 还是 policy metadata；无论形式如何，profile 只能收窄权限上限，未知值必须拒绝 |

## Target Authorization Architecture

### Unified authorization context and decision

建议把当前分散校验收敛为统一、可版本化的服务端上下文：

```text
AuthorizationContext
- surfaceProfile / networkTrustProfile / upstreamTrustProfile / navigatorInstanceId
- principalType / principalId / credentialId / assuranceLevel
- tenantId / upstreamSystemId / clientAppId / upstreamUserId
- action / resourceType / resourceId / ownerType / ownerId
- taskId / sessionId / capabilityTokenId / generation / functionScope
- routeKind / workerId / poolId / leaseId / workerBackend
- executionPolicyId / productionPolicyId
```

统一输出：

```text
PolicyDecision
- ALLOW | DENY
- stableReasonCode
- matchedScopes / matchedGrants
- resolvedOwner / resolvedRoute
- actorId / credentialId / capabilityTokenId
- policyVersion / correlationId / timestamp
```

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

### Principal hierarchy for dedicated upstream instances

```text
foggy-world-sim instance root (instanceId + upstreamSystemId scoped)
  ├─ manages all ClientApps and instance configuration
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

“Instance root”必须绑定 `navigatorInstanceId + upstreamSystemId`，但不代表必须新增 enum、表或 credential 前缀。若最终用具名 ClientApp 表达，该 ClientApp 必须获得显式 root designation；普通 ClientApp 类型本身仍不是 root。任何 root 表达都不能复用 ClientApp runtime token，也不能把管理面 root scope 自动注入 ask/task token。

### Principal hierarchy for business SaaS upstreams

```text
Navigator instance root/operator
  └─ TMS SaaS platform admin (upstreamSystemId=tms-x3)
       ├─ Tenant A
       │    ├─ ClientApp A1 limited control (optional)
       │    └─ ClientApp A1 runtime + delegated users
       ├─ Tenant B
       │    ├─ ClientApp B1 limited control (optional)
       │    └─ ClientApp B1 runtime + delegated users
       └─ TMS-owned Worker/Directory/Model inventory
            └─ allocated by explicit tenant/ClientApp grants and bindings
```

TMS platform admin 的横向管理范围只能覆盖其获准 tenant 集合；tenant ClientApp credential 的纵向范围只能落在一个 exact tenant + ClientApp。两者必须使用不同 principal/credential，并在审计中明确记录 acting platform、target tenant、target ClientApp、action 和 resource owner。

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

### Architecture decisions required before implementation

1. instance root 使用扩展 upstream-admin、独立 instance-scoped credential，还是具名 root ClientApp；其 TTL、轮换、撤销、恢复和 step-up 责任。
2. 专属实例的 tenant 拓扑：单 tenant、多个 tenant，及 upstream system 与 tenant 的映射/隔离规则；该决策不缩减 instance root 权限。
3. TMS platform subject 如何映射到 provisioning/security-admin principal、scope/claim、allowed tenant 集合、TTL、step-up 和撤销传播；已确认的完整 upstream 权限不得因此合并成一个万能 key。
4. Tenant application-config-only control 的申请审批、TTL、Agent/Model/Directory/grant/binding 细粒度 action，以及一个 tenant 多 ClientApp 的 schema/唯一性。
5. TMS-owned Worker 的 shared/dedicated 容量 policy、create/update/reassign/delete 的事务边界、影响预览、offboarding recovery 和独立 owner transfer 记录。
6. transfer/delete/rotate/delegate、trust-root 变更和 production promotion 的 step-up、影响预览、双人复核或独立签收机制；这些机制不得把已确认权限重新解释为无权限。
7. signed upstream-user assertion 的格式、issuer、audience、TTL、nonce、防重放和密钥轮换。
8. 通用 Open API 是否统一接入 authorization/ownership facade，或迁移到专用 Business Agent API。
9. Gateway 配置是改名为 `principal-required`，还是拆成 ingress exposure 与 authn requirement 两个配置。
10. `routeKind=PHYSICAL_WORKER|WORKER_POOL` 的 schema、存量迁移、唯一性和 legacy Pool 退役策略。
11. `CONTROL_PLANE_ALL`、长期 control/runtime credential 和 admin no-expiry 例外的审批、默认 TTL、轮换与审计边界；不得让普通 ClientApp 借宽泛 scope 获得 root 或 TMS platform admin。
12. Worker credential broker、进程/容器隔离、reliable audit outbox 和 production policy object 的责任主体及验收规则。
13. S1/S2 主线使用显式 upstream trust profile、principal type 还是 policy metadata 表达权限上限；schema/API 必须允许未来增加 external-third-party profile，但未知或未注册 profile 不得回退到任一可信默认值。

### Deferred S3 decisions (not blockers for S1/S2)

以下问题等真实第三方需求出现后由独立 workitem 冻结，不阻塞当前 S1/S2 主实现，也不得由当前会话猜测实现：

1. 第三方是共享 Navigator tenant、独立 upstream system，还是专属部署；其数据驻留、配额、计费和支持责任。
2. 第三方是否需要 limited control、多个 ClientApp、共享资源或 delegated administrator；默认答案均为否。
3. 第三方 upstream user 的强身份协议、信任根、密钥轮换、撤销和合规留存。
4. 第三方 production ingress、Worker 隔离、审计交付、SLA、事件响应和退出/删除流程。

## Acceptance Criteria

以下均为 DRAFT acceptance criteria；S1、S2 的业务权限与动作边界已确认，S3 仅要求默认拒绝和安全扩展性。具体 principal 编码、数据结构、API contract 和治理机制仍待架构决策：

- [ ] AC-1: `foggy-world-sim` instance root 可以在绑定的 Navigator 实例内对自有和非自有资源执行 create、discover、bind、operate、owner transfer、delete/revoke、credential rotation 和 grant delegation。
- [ ] AC-2: 普通 ClientApp control credential 不能自动获得专属上游根权限，也不能跨 ClientApp 管理资源。
- [ ] AC-3: Runtime credential 只能用于 runtime-token、readiness、owner-smoke、ask 和消息读取等运行面。
- [ ] AC-4: 绑定非自有资源不会隐式修改 owner；instance root 可另行执行独立、显式、可审计的 owner transfer。
- [ ] AC-5: ask 最终权限严格等于 runtime、upstream user、Agent、task capability、Worker route 和执行策略的交集。
- [ ] AC-6: 专属上游根权限不能跨 Navigator 实例或跨 upstream system 生效；同一用户或服务在实例 A 的 root/platform/control/runtime/Worker credential、grant、session 或 task token 重放到实例 B 时必须稳定拒绝。
- [ ] AC-7: 所有跨 owner 动作、credential 签发/轮换、grant delegation、binding、实例配置和 promotion 操作都有稳定 actor、credentialId、resource owner、reason、policy decision 和结果审计。
- [ ] AC-8: instance root 可执行实例配置与 external/production 推进动作，但任一 readiness、network、identity、Worker policy、audit、migration、rollback 或独立签收条件缺失时必须拒绝；root 不能删除审计或读取 secret 明文。
- [ ] AC-9: Codex Physical Worker 路由不要求新增 BizWorkerIdentity 或 WorkerPool membership。
- [ ] AC-10: 任一 external 开关开启都不能单独使 readiness 或 production acceptance 通过。
- [ ] AC-11: TMS platform subject 可以在 exact `upstreamSystemId=tms-x3` 和获准 tenant 集合内执行完整 upstream 管理；日常 create/update/assign 使用 provisioning lane，高风险 delete/transfer/rotate/revoke/delegate 使用 security-admin lane。
- [ ] AC-12: tenant ClientApp credential 必须绑定 exact upstream system + tenant + ClientApp；不能访问其他 tenant/ClientApp，也不能获得 Navigator root 或 TMS platform admin 权限。
- [ ] AC-13: TMS provisioning、TMS security-admin、tenant application-config control 和 tenant runtime credential 分开签发、过期、轮换和撤销；optional control 仅管理 exact ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已授权资源之间的 grant/binding，不能扩大 scope 或继续 delegation；runtime lane 不能执行控制面写操作。
- [ ] AC-14: TMS platform admin 不能管理其他 upstream system、Navigator instance root、production/trust-root 或基础设施 recovery 资源，除非未来部署拓扑决策明确将其提升为另一已定义角色。
- [ ] AC-15: TMS-owned Worker 的 allocation 与 ownership 分开记录；TMS 可 create/update/reassign/delete，delete/owner transfer/recovery 必须使用 security-admin + step-up；tenant/App 只获得 use grant/binding，Codex allocation 不创建额外 BizWorkerIdentity 或 WorkerPool member。
- [ ] AC-16: 外部第三方、未知 trust profile 或未完成 onboarding 的 principal 不得获得任何 runtime/control/admin/Worker 权限，也不能继承 S1 instance-root 或 S2 SaaS-platform 权限。
- [ ] AC-17: S1/S2 主实现仅提供可默认拒绝的 S3 schema/policy 扩展点和负向契约；不得签发真实第三方 credential、开放第三方 route、接受真实第三方流量或据此宣称 external/production ready。

## Contract / Data / Security Constraints

- API or event contract:
  - 后续 API 必须区分 instance/upstream control、ClientApp control、runtime 和 task capability，不复用一个万能 bearer。
  - 每个拒绝应输出稳定 reason code，不泄露其他 owner 资源是否存在。
  - caller 提交的 tenant、owner、upstream user 和 cwd 只能作为请求参数，由服务端 principal 和资源记录重建可信上下文。
  - trust profile 由服务端注册关系或 policy 重建，客户端不得自报；unknown/unregistered/conflicting profile 必须拒绝。
- data and migration:
  - 如引入 dedicated upstream root、instance scope、resource grant 或 `routeKind`，必须有显式 schema、存量数据映射和回滚方案。
  - bind relation 与 resource owner 必须分别存储，不以更新 owner 模拟绑定；显式 owner transfer 单独记录 before/after。
  - 为 S3 预留字段或 policy extension point 不等于创建第三方实体、credential 或默认授权记录。
- compatibility and rollback:
  - 本地 internal-dev 和受信 loopback Open API 可继续存在，但 token-only Gateway 兼容不得扩展到非可信网络。
  - 现有 credential lane 和 Physical Worker ID 优先保持兼容；不自动迁移为万能 ClientApp 或 Biz identity。
  - S3 保持 disabled/unprovisioned；未来启用必须通过独立版本 workitem 和显式迁移，不由 S1/S2 发布自动开启。
- permissions and secrets:
  - root credential 必须与普通 upstream-admin/control/runtime/task/Worker lane 可区分；即使复用现有 credential 类型，也必须有明确 instance-root designation 和审计语义。
  - 所有 credential、grant、session、task capability 和 Worker principal 必须绑定 exact `navigatorInstanceId` 或等价不可混淆的 instance audience；不同环境实例不得共享授权记录或接受跨实例重放。
  - 所有 credential 保持分层、可过期、可轮换、可撤销且仅存 hash/secret store。
  - 文档、CLI 输出和审计不得包含明文 secret。
  - 缺失 principal、owner、grant、route 或 execution policy 时一律 fail closed。

## Phased Delivery Proposal

| Phase | Scope | Local compatibility | Architecture gate / exit condition |
|---|---|---|---|
| P0 Scenario and semantic convergence | 对齐 S1/S2/S3 信任定位；补齐术语、CLI help、SKILL FAQ、runbook 和组合 readiness schema | 不改现有开关默认值、凭据行为或 internal-dev 调用路径 | S1/S2 所有会改变 allow/deny 的问题有 Owner 决策；S3 明确 design-only/deferred；CLI artifact/version 漂移有处理方案 |
| P1 Canonical authorization facade | 以 S1/S2 为主实现 route inventory、统一 `AuthorizationContext/PolicyDecision`、trust profile 上限、稳定拒绝码、owner/binding/action 模型和 decision trace | token-only 只保留在显式 trusted-loopback/internal profile；普通本地联调继续可用；S3 无 credential/route | `open_questions: []`，S1/S2 数据/兼容/回滚方案冻结，unknown/external profile 默认拒绝，canonical spec 可转 `APPROVED` |
| P2 Strong identity and explicit routing | signed upstream-user assertion、所有 Gateway client 传播 Worker principal、显式 `routeKind`、credential TTL/rotation 治理 | partial headers 始终拒绝；既有 Physical Worker ID 不迁移成 Biz identity，不要求 Pool member | 双 ClientApp/user/tenant 和 strict Gateway 全矩阵通过 |
| P3 Production boundary | network/Ingress/TLS/CORS、Worker execution policy、credential broker/OS isolation、reliable audit outbox、migration/rollback、production policy object | internal/trusted 与 production profile 分离，绝不自动升级 | 所有生产前置项自动检查并由独立审批人签收；任一缺失 fail closed |
| P4 Deprecation and independent signoff | 淘汰非 loopback token-only、旧 CLI、隐式 route 判别和无过期 credential 例外 | 设兼容期限、usage telemetry、回滚窗口 | 实现状态至多 `READY_FOR_SIGNOFF`；独立 signoff 映射全部 AC 后才能形成最终结论 |
| Future S3 separate workitem | 基于真实第三方需求决定 onboarding、identity、limited control、resource/Worker policy、配额/计费、合规和 production ingress | 不回改 1.4.3 的已验收事实，不继承 S1/S2 高权限默认值 | 另行 APPROVED spec、threat model、迁移和独立 production signoff；不属于当前主实现 |

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1/AC-6 | critical | dedicated instance + second isolated instance/upstream positive/negative integration matrix | exact commands、resource IDs、allow/deny reason codes |
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

所有验证必须记录实际执行命令、结果、环境、未运行原因和残余风险。测试代码存在不等于测试已运行通过。

## Minimum Acceptance Matrix

| Scenario | Expected allow | Expected deny | Current evidence / status |
|---|---|---|---|
| Local internal | internal API 使用各自合法凭据；Open API gate 保持 false | 所有 `/api/v1/open/**` 返回 `503 / EXTERNAL_SURFACE_DISABLED` | 当前源码契约已复核；本轮未实测 |
| Trusted local Open API | loopback/可信 ACL + platform gate=true + valid ClientApp runtime token + owner/grant 完整 | 错 tenant/App/user、失效 token、未授权 Agent/Directory/Model | #152 functional ask 仍无完成证据；只允许作为受信本机联调，不推导 production |
| Gateway token-only compatibility | strict=false + 三个 Worker header 全部缺失 + valid task token + trusted internal network | 缺 task token、任一 partial/blank/legacy header、非可信网络使用 | server source contract 存在；本轮未执行 |
| Gateway strict principal | strict=true + task token + exact Worker credential + exact lease/route/owner/backend | 任一 header 缺失、credential/lease/worker/tenant/owner 不匹配 | server path 已实现；Java client 当前仍不兼容，因此不能启用为现行默认 |
| S1 root manages owned resources | instance root 以 exact instance + upstreamSystem scope 执行 create/discover/bind/operate/transfer/delete/rotate/delegate | 普通 ClientApp/runtime credential 冒充 root，或 root credential 被用于 ask | 业务语义已确认；尚未实现或验收 |
| S1 root manages non-owned resources | instance root 对任意实例内资源执行完整动作集；bind 与 owner transfer 分开 | 通过伪造 owner、runtime token、含糊 `ALL` bypass 或跨实例 token 操作 | 业务语义已确认；root principal/data model 尚待架构决策 |
| S2 TMS platform tenant provisioning | 共享 Navi 中，TMS provisioning 在 allowed tenant 集合内创建/更新/启用 tenant 与 ClientApp、签发初始 credential、配置资源/grant/binding，并创建/更新/重分配 TMS-owned Worker；security-admin 执行停用/删除、owner transfer、rotate/revoke/delegate 和紧急恢复 | provisioning credential 执行破坏性 security-admin 动作；任一 TMS credential 管理其他 upstream system、未授权 tenant、Navigator root/trust-root/production policy | 业务语义已对齐；principal/schema/API 尚未实现或验收 |
| S2 tenant runtime | exact tenant + ClientApp runtime credential 换取短期 token 并执行获准 ask/readiness/messages | 创建 ClientApp、签发 credential、分配 Worker、控制面写、跨 tenant/App | runtime-default 已确认；credential contract 尚未实现或验收 |
| S2 tenant limited control | 仅在存在明确自助需求且显式签发独立 control credential 后，管理 exact ClientApp 的 Agent、ClientApp-owned Model 配置、Directory，以及已获授权资源之间的 grant/binding | 扩大 grant scope、继续 delegation、纳入未授权资源，管理 credential、Worker、ClientApp lifecycle、其他 ClientApp/tenant/TMS shared fleet，或使用 runtime secret 进入 control plane | application-config-only 边界已对齐；默认不签发，尚未实现或验收 |
| S2 Worker allocation | TMS provisioning 创建/更新/重分配 TMS-owned shared/dedicated Worker，并通过 grant/binding 分配给 tenant/App；security-admin 经 step-up、影响预览和审计执行 delete、owner transfer 与 recovery | tenant runtime/control 自行 `worker-host apply/update`、跨租户重绑、隐式 owner transfer、Codex Pool/Biz identity 绕路 | 完整生命周期与双 lane 语义已对齐；事务、恢复和测试尚未实现 |
| S2 cross tenant | TMS platform subject 仅在 allowed tenant 集合内横向管理；tenant credential 永远固定单 tenant | Tenant A control/runtime 访问 Tenant B ClientApp/resource/task，或 caller 自报 tenant 扩权 | 业务语义已对齐；必须形成双 tenant 负向矩阵 |
| S3 external third party | 当前无允许路径；未来仅在独立 workitem 批准、完成 onboarding 和全部 external/production gate 后，允许 exact external tenant + ClientApp runtime 访问自有或显式 grant 的资源 | 自报 trust profile/upstream/tenant、任何 upstream-admin/platform/root/Worker lifecycle、非自有资源 discover/bind/operate、跨 tenant/App、单开 external flag 即接入 | 设计边界已对齐；implementation deferred，不属于当前主实现或验收流量 |
| Same tenant, cross ClientApp | 已授权 instance root 或 TMS platform subject 可管理其 scope 内 App A/App B；普通 App 仅在 shared grant 明确时共享 | App A 普通 control/runtime 直接访问 App B private resource | 业务语义已对齐；仍需统一负向矩阵和显式 platform/root designation |
| Same ClientApp, cross upstream user | ClientApp/upstream shared 且 grant 明确的资源可共享 | user A 访问 user B 的 user-private Directory/task/context | 当前 assurance 为 delegated；强证明和统一谓词未闭合 |
| Cross tenant inside S1 instance | 若最终采用多 tenant，instance root 可管理该实例全部 tenant；普通 runtime/task lane 无普通跨 tenant 路径 | 普通 control/runtime/task/Worker capability 跨 tenant，或 caller 自报 tenant 扩权 | root 权限已确认；S1 单/多 tenant 拓扑待决策 |
| Cross Navigator instance/upstream system | 无普通允许路径；同一主体若需管理多个实例，必须分别在每个实例取得本地 principal/grant/credential | dedicated root、platform admin、ClientApp、runtime、task token、Worker principal 越出绑定 instance/upstreamSystemId，或凭同一用户/公司身份自动继承另一实例授权 | 目标必须 fail closed；需第二实例及同主体跨实例重放负向集成测试 |
| Codex Physical Worker | existing Physical Worker + `worker-host update` + correct owner/backend/Directory/model/user grant | Pool membership 要求、新建 Biz identity/替代 Worker、重绑 Directory 绕过 | #151 readiness/resources 曾 live 通过；完整 ask 未证明 |
| Worker external | external flag=true 且完整 auth/execution/network policy ready | 仍有 `EXTERNAL_EXECUTION_POLICY_PENDING` 时任何业务 ingress | 当前必须 unready/503；不得解除 pending |
| Production | authenticated instance root 发起 promotion，且 TLS/CORS、signed user、strict Gateway、Worker policy、审计、迁移、回滚、artifact identity、独立签收全部通过 | 任一子条件缺失、仅开启任一 external flag，或 TMS platform admin 未获 instance-root role 却尝试 promotion | 当前不允许、无 production ready 证据 |

## CLI, SKILL, Runbook, and Automation Backlog

### CLI help and inspection

1. 顶层 help 固定显示：`NAVIGATOR_EXTERNAL_ENABLED` only controls `/api/v1/open/**` routing; it does not enable production, providers, Worker Gateway, or Worker endpoints。
2. 将 Gateway help 描述为 Worker principal requirement，明确不是 network/Ingress exposure switch；如保留旧环境变量，应显示 deprecated semantic alias。
3. 每个命令标注允许的 credential lane：operator、dedicated instance root、SaaS platform admin/upstream-admin、ClientApp control、runtime、task capability、Worker principal；external-third-party lane 当前必须显示 disabled/unprovisioned，而不是回退到普通 upstream-admin。
4. `config check` 只输出 credential type、trust profile、tenant、upstream system、ClientApp、scope、expiry、status 和 hash/fingerprint，绝不输出 secret。
5. 增加只读 `inspect trust-boundary` 或等价能力，分别显示 upstream trust profile、platform surface、network binding、Gateway principal mode、Worker external readiness、Provider readiness、production approval。
6. 增加 `--explain-auth` / dry-run，显示将使用的 principal、action、owner scope、resource scope 和 expected policy path。
7. 对 `worker-pool register-worker/add-member --backend OPENAI_CODEX*`、direct Codex Biz identity 等路径继续硬拒绝，并提示规范 `worker-host verify/update` 路径。
8. 修复 1.0.18/1.0.21、`VERSION`、archive、`dist/lib`、`--version` 和 help snapshot 一致性。

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

### Automated test checklist

P0 contract tests:

- 所有 externally reachable route 必须注册明确 policy，未注册即构建/测试失败。
- `/api/v1/open` canonical、encoded、context-path 和 trailing-path 绕过矩阵。
- Worker principal 三个 header 的 8 种 presence 组合、blank、legacy header 和 strict/token-only 组合。
- tenant × ClientApp × upstream user × owner × action 的完整负向矩阵。
- task/status/messages/diagnostics/evidence 的跨 App/user 越权测试。
- Directory user-private/ClientApp-shared/upstream-system-shared 与 cwd spoofing 测试。
- missing/unknown/self-declared/conflicting upstream trust profile 一律拒绝，且不能回退到 S1 root 或 S2 platform admin。
- production CORS/trusted origin 与 dev fallback credential 启动拒绝测试。

P1 integration tests:

- Java、LangGraph、Codex SDK、Codex app-server Gateway client 的 Worker principal/lease 传播。
- Worker credential 过期、撤销、轮换、错误 version 和 lease mismatch。
- task token 错 audience/generation、过期、终态 tombstone、错误 function scope/worker route。
- Physical Worker route 不要求 Pool membership，且测试前后无新增 Worker/Biz identity/member。
- `poolId`/`workerId` collision、cross-tenant ambiguity 和未来 `routeKind` migration。
- operator/instance-root/SaaS-platform-admin/control/runtime/task/Worker credential 的 cross-lane rejection。
- S1 owned/non-owned resource 完整动作矩阵，包括 bind/owner transfer 分离、credential rotation、grant delegation 和 before-after assertion。
- S2 TMS provisioning 的 allowed-tenant create/update/assign 正向矩阵，以及 destructive security-admin action 的 cross-lane 拒绝。
- S2 TMS security-admin 的 delete/owner-transfer/rotate/revoke/delegate/recovery 正向矩阵，以及缺 step-up、影响预览或审计时的拒绝。
- S2 两个 tenant × 每 tenant 两个 ClientApp 的 application-config-only control/runtime 横向越权、grant scope amplification/delegation、credential/Worker/ClientApp-lifecycle 禁止动作和 credential lane 负向矩阵。
- S2 shared/dedicated Worker create/update/reassign/delete、grant/binding、revocation、owner invariance、destructive step-up 和 tenant offboarding 回收。
- S3 namespace 无 credential、ClientApp、grant、Worker route 或 open ingress 的 absence assertion；S1/S2 credential replay 到 external namespace 必须拒绝。

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

1. 如果直接把普通 ClientApp 的 `CONTROL_PLANE_ALL` 解释为实例 root，会模糊 upstream system、具名 root ClientApp 和普通 ClientApp；只有显式 root designation 才能表达 S1 权限。
2. 如果“可绑定非自有资源”通过改写 owner 实现，会破坏审计、撤销和未来多上游隔离。
3. 如果 instance root 与 control/runtime/task credential 复用同一 secret 或传播链，上游 credential 泄露会直接扩展到 ask、Worker 或浏览器侧。
4. 如果 instance-wide 权限不显式绑定 instance/upstreamSystemId，未来第二实例或第二上游可能出现越界。
5. 如果 root 管理权下沉到 ask runtime，Agent/task capability 将失去实际约束。
6. 如果“全部权限”被实现成绕过 policy/audit 的 hard-coded superuser，fail-closed、稳定拒绝码、撤销和未来场景隔离都将失效。
7. 如果向 TMS 租户下发 upstream-admin，任一租户 credential 泄露都可能扩展为跨租户 provisioning 和共享 Worker 控制。
8. 如果把 ClientApp control 与 runtime 合并成万能 credential，租户业务调用密钥会同时获得资源创建、grant 和 binding 写能力。
9. 如果 TMS platform admin 未绑定 exact upstreamSystemId 和 allowed tenant 集合，“自有系统高权限”会退化成跨 upstream/tenant 的隐式超级管理员。
10. 如果 Worker allocation 通过隐式 owner transfer、创建替代 Worker 或 Pool/Biz identity 绕路实现，tenant 回收、容量隔离和 Codex 路由都会失去可审计性。
11. 如果 trust profile 被实现成直接放行标签，“绝对可信”会退化为免认证 bypass，S2/S3 也可能因错误分类获得越权。
12. 如果为了假设中的第三方需求提前增加宽泛 upstream-admin、万能 ClientApp scope 或公开 onboarding，攻击面会在没有真实验收主体和 threat model 时扩大。

### Open questions

1. 专属实例是单 tenant，还是允许 `foggy-world-sim` 在同一实例管理多个 tenant？tenant 与 upstream system 的映射规则是什么？无论选择哪种拓扑，instance root 权限不缩减。
2. instance root 最终使用现有 upstream-admin 扩展、独立 instance-scoped credential，还是具名 root ClientApp？其 TTL、轮换、撤销、恢复和 step-up 责任是什么？

在以上 S1 open questions 和“Architecture decisions required before implementation”中的表达/治理决策冻结前，不得设置 `APPROVED`。

S3 的具体产品需求有意 deferred，不进入当前 open questions，也不阻塞 S1/S2 主实现；但 unknown/external profile 默认拒绝和不得继承 S1/S2 高权限是当前必须满足的架构约束。

## Ultra Execution Contract

- 当前状态为 `DRAFT`，不得启动 Ultra 实现。
- 方案批准后，Ultra 必须先读取本文件、项目 `AGENTS.md`、`CLAUDE.md`、相关模块规范和 `navigator-runtime-provisioning`。
- Ultra 可在批准 scope 内自主选择具体文件、类和实现结构，但主实现仅覆盖 S1/S2；不得把专属上游 root 或 SaaS platform admin 实现成 runtime 万能 token、普通 ClientApp 宽泛 scope 或隐式 owner bypass。
- S3 在当前 scope 只允许实现默认拒绝的 schema/policy extension point 和负向契约；不得创建第三方 onboarding、credential、开放 route、真实联调或 production 声明。
- 如实现需要改变目标、共享/专属实例兼容策略、已确认的 S1 root 权限、S2 平台/租户分层、S3 design-only 边界、数据迁移或安全边界，应设置 `NEEDS_REPLAN` 并停止扩展。
- 实现完成后回写 changed paths、精确测试结果、偏差和残余风险，状态最多更新为 `READY_FOR_SIGNOFF`。

## Implementation Result

> 当前未授权实现，由未来 Ultra 执行会话填写。

- implementation_summary: not-started
- changed_paths: none
- tests_and_results: not-run
- manual_or_experience_evidence: not-run
- deviations: none
- residual_risks: S1 principal/tenant topology、跨场景 principal/schema/API 表达与治理机制仍待决策；S3 真实需求和 threat model 尚未产生且明确不属于当前主实现；尚无实现或测试证据
- readiness: not-applicable-draft

## References

- version index: [1.4.3-SNAPSHOT](../README.md)
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
