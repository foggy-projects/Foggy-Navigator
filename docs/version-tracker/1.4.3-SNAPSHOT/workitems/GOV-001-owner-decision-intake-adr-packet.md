---
doc_type: owner-decision-intake
version: 1.4.3-SNAPSHOT
ticket: GOV-001
status: PENDING_OWNER_INPUT
decision_authority: none
scope: P1B-B, P2, P3, P4
external_enablement: no
production_enablement: no
real_data_access: no
parent_delivery_spec: GOV-001-upstream-permission-and-trust-boundary.md
---

# GOV-001 Owner Decision Intake / ADR Packet

## Purpose and Use

- purpose: 收集 P1B-B、P2、P3、P4 进入后续实施契约前必须由业务、数据、安全、运行和发布责任人作出的事实性决策；避免把静态调研、CLI/fixture、local profile 或某个 external flag 误当作实施或发布授权。
- current_state: `PENDING_OWNER_INPUT`。本文没有选择任何方案，也不授予任何实现、seed、凭据、路由、开关、外部访问或 production 推进权限。
- completion_rule: 每一项选择须由对应的具名责任人（人员或受控团队标识）在受控系统留存不可变 approval reference。随后仍须建立独立的 `APPROVED` 实施契约，并逐项满足原 decision gate 的 exit criteria；本包本身不改变任何 gate 的 `DRAFT + BLOCKED` 状态。
- evidence_rule: 只引用脱敏的 ID/alias、状态、版本、时间、checksum、审批和受控证据位置。不得把 secret、token、key、完整 claim、私有 owner/tenant 业务数据、KMS 材料或真实 profile 写入本仓。

`[TBD: named owner]` 表示必须在受控决策记录中填入的具名人员/团队引用，而不是由本文件或角色名称自动获得的权限。

## Packet-wide Invariants

1. `NAVIGATOR_EXTERNAL_ENABLED=true` 仅控制 `/api/v1/open/**` 平台路由门禁；它绝不表示 Provider、Worker Gateway、Worker external 或 production ready。
2. `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` 仅要求完整 Worker-principal headers；它不是端口、TLS、Ingress 或网络暴露开关。
3. 任何缺失、冲突、过期、无法验证或来源不明的事实都必须 `DEFER` 或 `QUARANTINE`，不得以方便为由补推、放宽或降级为允许。
4. Codex 只走既有 Physical Worker → `worker-host verify` → `worker-host update --worker-id ...` / `claudeCode.codexConfig`。不得通过新建 Worker、`BizWorkerIdentity` 或 `WorkerPool` member 修复路由。
5. `NAVI_ADMIN_API_KEY` / `X-Navi-Admin-Key` 始终只是 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN` 兼容主体，不能被本包或后续迁移自动提升为 S1 root、S2 platform/security、runtime/task/Worker 或 production authority。

## Draft ADR Register — Directions Recorded, Gate Approval Pending

以下 ADR 基于当前 source-only preflight。2026-07-19 Project Owner 已在本协作会话选择 P2/P3 的设计方向和 P1B-B 的 `DEFER`；该会话选择被冻结为后续设计输入，但不替代每个 gate 所要求的具名 owner、脱敏事实和受控系统中的 immutable approval reference。因此 P1B-B、P2、P3、P4 继续保持 `DRAFT + BLOCKED`，implementation authorization 仍为 `none`。

| ADR | Recorded direction | Authorization boundary |
|---|---|---|
| `ADR-P1B-001` | `DEFER` — 不读取真实实例事实；保持 B0 offline-only | `VALID` offline envelope、legacy key、CLI profile 或高层 S1/S2 语义不等于真实 mapping/seed approval |
| `ADR-P2-001` | `CONTROLLED_EXCHANGE_JWS` — 受控 exchange 校验注册 issuer 的短时非对称 assertion，再签发独立 task capability/Worker lease；client-first Gateway strict | `client-app-delegated`、自报 userId、task token 或 Worker credential 不能互相替代或升级 |
| `ADR-P3-001` | `PRIVATE_STAGED_DESIGN` — 单实例、私网、受限上游 service-to-service；S3、Gateway strict 和 Worker external 不进入首批切片 | `NAVIGATOR_EXTERNAL_ENABLED`、Gateway flag、health 或 local prod profile 不是网络/Worker/production readiness |
| `ADR-P4-001` | `DEFER` — 先等待 P2/P3 与 privacy-safe telemetry/inventory 的具名审批 | source-only 零调用、未定义观察窗口或单一 fixture 不是 retirement 证据 |

### Recorded Project Owner Direction (2026-07-19)

- source: 当前协作会话中的 Project Owner 选择；controlled approval references: `pending`。
- P1B-B: `DEFER`；不读取 secure source、不执行 seed 或 credential lifecycle mutation。
- P2: `CONTROLLED_EXCHANGE_JWS`；这是 target architecture direction，不授权 issuer/KMS/replay store、client/server code、strict flag 或 external mode。
- P3: `PRIVATE_STAGED_DESIGN`；这是 target architecture direction，不授权 ingress/TLS/CORS/proxy/KMS/Worker/audit/production profile change 或 promotion。
- P4: `DEFER`，直至 P2/P3 以及 telemetry/privacy/release evidence 都通过各自 gate。
- durable effect: 在具名 security/data/operations/release owner 与 immutable approval reference 补齐前，任何缺失或冲突事实必须继续 `DEFER`/`QUARANTINE`，不得用本会话选择代替四眼审批或 independent signoff。

### ADR-P1B-001 — Instance Facts and Future Seed Lifecycle

- status: `PROPOSED`, recorded direction `DEFER`; does not permit secure-source read, seed, credential issuance, lifecycle mutation or route cutover.
- scope: 一个 exact `navigatorInstanceId + environmentProfile + build/migration baseline`。S1 是该专属实例内的 `INSTANCE_ROOT`，S2 是同一实例内受 `upstreamSystemId=tms-x3` 和 server-owned `UPSTREAM_OWNED` tenant scope 限定的 `SAAS_PLATFORM`；这些已对齐的语义不取代逐实例事实核验。
- options: `DEFER`（继续 B0 offline-only）；`QUARANTINE`（不明/冲突/陈旧记录不 seed、不签发）；`PREPARE_FUTURE_SPEC`（全部事实、rollback 和四眼审批齐备后，仅起草独立 implementation spec）。
- required approval facts: instance/profile/build binding；S1/S2 subject、owner/tenant/ClientApp/Worker/Directory/Agent/Model/binding 的 source authority、effective time 与 checksum；credential lane/status/expiry/generation 的安全 fingerprint 和 verifier/KMS custody；冲突 disposition、rollback/revoke/audit/support owner。
- accountable approvals: deployment/instance custodian、SIM data owner、TMS platform owner、tenant-data owner、resource owner/operator、credential/KMS custodian，以及与 executor 分离的 security approver。所有人须用受控系统中的 immutable approval reference 表示。

### ADR-P2-001 — Strong Identity and Client-first Gateway Strictness

- status: `PROPOSED`, recorded direction `CONTROLLED_EXCHANGE_JWS`; strict flag、assertion issuance、client/server changes 和 Worker external 均不由本 ADR 授权。
- recommended design: 上游身份仅在受控 exchange 边界接受注册 issuer 的短时非对称 assertion，固定 exact instance audience，并约束 `iss/sub/azp/tenant/clientApp/upstreamSystem/iat/nbf/exp/jti`。未知 issuer/key、错误 audience、过期/撤销/replay assertion、验证器或撤销存储不可用一律拒绝。Navigator task capability 仍为短时、服务端可撤销的最小权限快照；Worker principal/lease 独立绑定，不能扩大 task、user、Agent 或 ClientApp 权限。
- routing decision: 引入权威、版本化的 `routeKind = PHYSICAL_WORKER | WORKER_POOL` 与独立 `routeId`；不得继续从重载 `workerPoolId` 推断。碰撞数据必须 quarantine，兼容双读写和 rollback 先于 strict evaluation。
- custody decision: Worker 长期 credential 只能经 OS-isolated local connector/sidecar 进入受信 Worker 调用路径，绝不进入模型可控的 Codex Shell/MCP 子进程、日志或 trace。Codex 仍只允许 existing Physical Worker → `worker-host verify` → `worker-host update --worker-id ...` / `claudeCode.codexConfig`，不得新建 Worker、`BizWorkerIdentity` 或 `WorkerPool` member。
- implementation order: 先批准 assertion/replay/revoke/outage 与 `routeKind` 迁移契约；再由 Java LangGraph、Python LangGraph、Codex SDK、Codex app-server（如适用）各 caller owner 完成无泄漏 propagation；随后服务端做 strict intersection validation；所有 client 的 negative/replay/revoke/cross-tenant E2E 和审计验证通过后，才可提出受控环境 strict evaluation。
- fail-closed: 当前 Java 与 Codex SDK source 仅传播 task token，Python 仅为条件性传播；任一 caller 未盘点、partial header、route collision、P1B-B mapping 未获批或无法隔离 Codex credential，均保持 strict=false 的内部兼容，不开启 `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED`。

### ADR-P3-001 — First Production Boundary

- status: `PROPOSED`, recorded direction `PRIVATE_STAGED_DESIGN`; no production profile, ingress, TLS, CORS, proxy, KMS, Worker policy, audit sink or external setting may change from this ADR.
- options: `DEFER / LOCAL_INTERNAL_ONLY`；`PRIVATE_STAGED_DESIGN`（recorded Project Owner direction; formal gate approval pending）；`PUBLIC_MULTI_CLIENT / S3`（未来独立 product/security work item）。
- recommended slice: 一个 exact `navigatorInstanceId + environmentProfile + artifact + migration baseline + route family` 的私网、受限 company-upstream/BFF service-to-service 部署。入口只允许明确的身份与网络 ACL；TLS、trusted proxy、forwarded-header rewrite、rate-limit 和 CORS/CSRF 各有唯一执法点。浏览器 CORS 默认拒绝或仅允许 exact origin；S3 保持 unprovisioned，Gateway strict/Worker external 不自动进入首批 workload。
- promotion prerequisites: SRE/security 的 topology/TLS/proxy/origin negative evidence；KMS/broker custodian 的 workload identity、rotation/revoke、break-glass/outage evidence；每个 Worker 的 workspace/tool/approval/sandbox/egress/resource/kill-revoke deny-by-default policy；audit owner 的 durable outbox/retry/order/dedup/redaction/retention/outage evidence；release/DBA/SRE 的 artifact/migration/rollback/canary/incident 与 independent release approval。
- fail-closed: 通配 CORS 与 credential、untrusted forwarding header、P2 未完成、Worker `EXTERNAL_EXECUTION_POLICY_PENDING`、audit durability/outage 未证明、rollback rehearsal 失败或独立签核缺失，均拒绝 promotion。external flags 不能替代其中任一证据。

### ADR-P4-001 — Privacy-safe Legacy Migration and Independent Release Signoff

- status: `PROPOSED`, recorded direction `DEFER`; no legacy fallback may be deleted, relaxed, revived or silently extended from this ADR.
- route-family order: 分开处理 (1) `LEGACY_UPSTREAM_ADMIN` / `X-Navi-Admin-Key`，(2) `CLIENT_APP_MANAGE → CLIENT_APP_RUNTIME_KEY_ISSUE` compatibility bridge，(3) `--claim-ttl-minutes 0|-1` no-expiry claim，(4) legacy CLI/archive/help/provenance。token-only Gateway 与 implicit routing 先由 P2 解决，不作为首个 retirement family；已退役 `X-Navi-Admin-Api-Key` 永远保持 deny-only。
- telemetry contract: 仅允许版本化、脱敏聚合的 route-family/lane/action-risk、allow-deny outcome/reason、安全 artifact version、environment/time bucket。caller cohort/pseudonym 只能在 privacy owner 批准其构造、轮换、访问与留存后使用。禁止 raw header/key/token/JWT/body、upstream-user/owner/tenant/resource ID 和可重放 trace correlation。
- options: `DEFER`；`QUARANTINE`（caller/version/telemetry 不明时不退役）；`STAGED_ROUTE_FAMILY`（recommended, not selected）；`TIME_BOUNDED_EXCEPTION`（具名 owner、reason、expiry、telemetry、rollback threshold 和独立批准齐备时才可保留）。
- release order: P2 identity/routing 与 P3 audit/production decision → controlled real inventory + privacy approval → 一 route family 的 compatibility window/notice/support/exception/rollback matrix → independently `APPROVED` implementation spec → real observation window + rollback rehearsal → 仅该 family deny/remove → independent signoff。任一 critical acceptance evidence 缺失即 `blocked` 或 `rejected`，不得以风险接受代替未知事实。

### Decision and Execution Order

1. 每个 owner 在受控系统完成相应 ADR 的选择，并留存 immutable approval reference；未选择即 `DEFER`。
2. P1B-B 只在 exact instance facts、四眼批准和安全 evidence 可用后建立独立 `APPROVED` seed/lifecycle spec。
3. P2 在 P1B-B 所需 binding facts 与架构 owner 决策完成后，按 client-first 实施并保留 internal compatibility。
4. P3 仅在 P2 和全部 production-owner evidence 完成后，为一个 bounded private slice 建立独立 implementation spec。
5. P4 仅在 P2/P3、privacy-safe telemetry 与 release owners 都到位后，按单 route family 迁移；最终 release 必须独立 signoff。

## P1B-B — Real Inventory, Owner Mapping, and Future Seed Lifecycle

| Intake field | Required content |
|---|---|
| Named accountable owners | `[TBD: deployment operator / instance custodian]`；`[TBD: S1 SIM business-data owner]`；`[TBD: S2 TMS platform owner]`；`[TBD: S2 tenant-data owner]`；`[TBD: resource owner/operator]`；`[TBD: credential/verifier/KMS custodian]`；`[TBD: security approver, distinct from executor]`。不适用的 S1/S2 主体须由 instance custodian 明确标记为不在本次 scope。 |
| Concrete decision | 对一个 exact `navigatorInstanceId + environmentProfile + build/migration baseline`，确认 S1 `INSTANCE_ROOT`、S2 `SAAS_PLATFORM`、tenant/ClientApp/Worker/Directory/Agent/Model/owner/binding 的事实来源和冲突处置；决定是否仅为一个后续、受限 seed/lifecycle implementation spec 准备输入。 |
| Acceptable options — none selected | `DEFER`: 不读取、不导入真实事实，保持 B0 offline validator；`QUARANTINE`: 对缺失、陈旧、重复、冲突或无法归属的记录不 seed、不签发、不迁移；`PREPARE_FUTURE_SPEC`: 全部所需事实和四眼审批齐备后，仅允许起草独立实施契约，仍不执行 seed。任何局部 scope（仅 S1、仅 S2、单 tenant）必须显式列出，不得外推。 |
| Minimum sanitized evidence | 受控证据位置和 source authority/effective time/checksum；opaque instance/profile/build/migration reference；脱敏的 owner/tenant/ClientApp/Worker/binding 状态和冲突 disposition；lane/status/expiry/generation/safe fingerprint 与 verifier/KMS custody reference；回滚、撤销、audit/support owner reference。不得包含 secret 或可重放凭据。 |
| Explicit non-authorization boundary | 本项不授权读 secure source、运行 seed、签发/轮换/撤销 credential、创建 grant/tenant authority、切换 route family、分配 Worker，或开启任何 Open API/Gateway/Worker external/production 设置。`VALID` 的 B0 envelope 也不等于事实正确或 owner approval。 |
| Approval / signature references | Request: `[TBD: P1B-B decision request ref]`; business/data owner: `[TBD: immutable approval ref]`; deployment operator: `[TBD: immutable approval ref]`; security approver: `[TBD: immutable approval ref]`; executor separation attestation: `[TBD: ref]`; future implementation authorization: `[TBD: separate APPROVED work item]`. |

## P2 — Strong Upstream Identity and Worker Gateway Strictness

| Intake field | Required content |
|---|---|
| Named accountable owners | `[TBD: security architecture owner]` 与 `[TBD: upstream identity owner]` 共同负责 assertion trust/replay；`[TBD: platform operations owner]` 负责 revocation/outage；`[TBD: Worker architecture owner]` 负责 principal/lease；`[TBD: data + Worker migration owner]` 负责 `routeKind`；每条调用链各有 `[TBD: Java LangGraph client owner]`、`[TBD: Python LangGraph client owner]`、`[TBD: Codex SDK client owner]`、`[TBD: Codex app-server client owner, if applicable]`；`[TBD: security/audit owner]` 负责 trace/redaction。 |
| Concrete decision | 固定 signed upstream-user assertion 的 issuer/audience/binding/JWKS/rotation/expiry、replay/revocation/outage fail-closed 语义；固定 exact Worker principal/credential/lease 和 explicit `routeKind` 的权威模型、迁移/回滚；固定每个 Gateway caller 的无泄漏传播和 headerless trusted-internal compatibility 退出条件。 |
| Acceptable options — none selected | `DEFER`: 保持现有 strict=false internal compatibility，不宣称强 identity 或 external readiness；`QUARANTINE`: 任一未盘点或未证明的 client/route 不能参与 strict rollout；`CLIENT_FIRST_STAGED_DESIGN`: 仅在独立实施契约内按调用端先行、server 验证随后、全部 client E2E 通过后再评估 strict flag。不得选择“task token、ClientApp credential 或自报 userId 替代 Worker principal/lease”的方案。 |
| Minimum sanitized evidence | 签名 assertion threat model 和 issuer/key-custody reference（无 private key/JWT）；replay/revoke/cache/outage decision record；caller inventory 与安全的 propagation trace/negative-test reference（无 header/token value）；routeKind vocabulary、schema/migration/rollback and collision analysis；P1B-B 所需 exact binding facts 的受控 reference；audit correlation/redaction/retention handoff reference。 |
| Explicit non-authorization boundary | 本项不签发 assertion、不改 client/server、不启用 `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED`、不把 Python helper 或 fixture 视为全链路证明，也不启用 Worker external、Open API external 或 production。不得用 Biz Worker credential 充当普适身份，或为 Codex 新建 Worker/Biz identity/Pool member。 |
| Approval / signature references | Security architecture: `[TBD: immutable approval ref]`; upstream identity owner: `[TBD: ref]`; Worker architecture: `[TBD: ref]`; data/Worker migration: `[TBD: ref]`; each client owner: `[TBD: per-client ref]`; security/audit: `[TBD: ref]`; future implementation authorization: `[TBD: separate APPROVED work item]`. |

## P3 — Production Boundary

| Intake field | Required content |
|---|---|
| Named accountable owners | `[TBD: SRE owner]` + `[TBD: security architecture owner]` 负责 ingress/TLS/proxy/CORS/ACL；`[TBD: platform security/KMS-broker custodian]`；`[TBD: each Worker owner]` + `[TBD: Worker security owner]`；`[TBD: audit/compliance owner]` + `[TBD: audit operations owner]`；`[TBD: release owner]` + `[TBD: DBA/SRE migration owner]`；`[TBD: product + security owner]` 负责 S3 保持未 provisioned 的范围决定。 |
| Concrete decision | 为一个明确 deployment scope 决定生产 ingress/network、trusted-proxy、TLS、CORS/CSRF、credential broker/KMS、Worker sandbox/egress/kill-revoke、可靠 audit/outbox、artifact/migration/rollback/canary/incident response 及独立 release authority 的完整边界；同时明确 S3 是否继续不在 scope。 |
| Acceptable options — none selected | `DEFER / LOCAL_INTERNAL_ONLY`: 不进行 production promotion；`PRIVATE_STAGED_DESIGN`: 仅设计一个非公网、受限部署切片，仍须独立实施和实测；`QUARANTINE`: 缺少 execution policy、KMS/audit/outage 或 release evidence 的 Worker/provider/surface 保持 unready/deny；`S3_UNPROVISIONED`: 外部第三方持续不接入，直到另有产品/安全 work item。任何方案都不得将 external flag 当作替代证据。 |
| Minimum sanitized evidence | 受控 topology/zone/DNS/ACL/TLS/trusted-proxy/CORS decision reference 与 spoof/origin negative-test reference；secret-manager/KMS/broker custody/rotation/revocation/break-glass/outage reference（无 secret）；Worker workspace/tool/approval/sandbox/egress/resource/kill policy reference；audit sink retry/order/dedup/redaction/retention/access reference；artifact/build/migration/rollback/canary/monitoring/incident and independent-release evidence reference。 |
| Explicit non-authorization boundary | 本项不修改 bind address、Ingress、TLS、CORS、proxy、KMS、Worker external policy、audit sink、production profile 或发布脚本；不移除 `EXTERNAL_EXECUTION_POLICY_PENDING`，不发行 production credential，不开启 Open API/Gateway strict/Worker external/production，也不把 source test、health 200 或 local `prod` profile 说成 production proof。 |
| Approval / signature references | SRE/security: `[TBD: immutable approval ref]`; KMS/broker custodian: `[TBD: ref]`; Worker/security: `[TBD: per-worker ref]`; audit/compliance/operations: `[TBD: ref]`; release + DBA/SRE: `[TBD: ref]`; S3 scope: `[TBD: product/security ref]`; future implementation authorization: `[TBD: separate APPROVED work item]`. |

## P4 — Legacy Retirement and Independent Release Signoff

| Intake field | Required content |
|---|---|
| Named accountable owners | `[TBD: each legacy route/client owner]`；`[TBD: observability owner]` + `[TBD: privacy/retention owner]`；`[TBD: product/release owner]`；`[TBD: SRE + security rollback owner]`；`[TBD: independent reviewer]`；`[TBD: final release promotion authority]`。实现者不得兼任 independent reviewer。 |
| Concrete decision | 固定每个 legacy route family/header/credential lane/token-only path/no-expiry exception 的可观测 inventory、兼容窗口、eligible trusted-internal caller、notice/support、exception expiry、rollback threshold/kill switch、删除顺序和 release AC-to-evidence/signoff authority。 |
| Acceptable options — none selected | `DEFER`: 维持已登记的受限 legacy compatibility，不扩大权限或延长默认期限；`QUARANTINE`: caller/version/telemetry 不明的 family 不退役且不被视为零使用；`STAGED_ROUTE_FAMILY`: 只为一条可观测 family 起草独立迁移实施契约；`TIME_BOUNDED_EXCEPTION`: 仅在具名 owner、reason、expiry、telemetry 与 rollback 都存在时保留例外。不得选择将 legacy key 提升为 root/platform/security/Worker/production authority 的方案。 |
| Minimum sanitized evidence | versioned route/header/lane/token-kind/caller/artifact-help inventory（无 credential/header value）；按 route family 的 privacy-safe usage/decision/client-build aggregation 与 retention/access policy；published compatibility date/notice/support/exception references；rollback rehearsal/kill-switch/communication reference；最终 AC-to-evidence matrix、changed-path/test/deployment/audit evidence and independent-review reference。 |
| Explicit non-authorization boundary | 本项不删除、放宽、复活或新建 legacy fallback；不记录 credential、token、request body、私有 resource existence 或 upstream assertion；不以 fixture/local zero-call/未定义窗口作为退役证据；不因本包或单一 telemetry 记录把任一 GOV-001 slice/external surface/Gateway/production 标为 `ACCEPTED`。 |
| Approval / signature references | Route/client owners: `[TBD: per-family ref]`; observability/privacy: `[TBD: ref]`; product/release window: `[TBD: ref]`; SRE/security rollback: `[TBD: ref]`; independent reviewer: `[TBD: ref]`; final promotion authority: `[TBD: ref]`; future implementation authorization: `[TBD: separate APPROVED work item]`. |

## Intake Completion Checklist

- [ ] Every role above has a named person or controlled team reference in a secure decision system; role labels alone are not sufficient.
- [ ] Each selected option identifies exact instance/environment/route family scope and an expiry or reassessment date where compatibility is retained.
- [ ] Evidence is sanitized, immutable/reviewable, and accessible to the required independent reviewer without copying secrets into this repository.
- [ ] Any missing, contradictory, stale or non-verifiable item is recorded as `DEFER` or `QUARANTINE` with its accountable owner and next review date.
- [ ] A separate `APPROVED` implementation work item, rather than this intake packet, defines code/config/data changes, rollback, tests and signoff.

## References

- [GOV-001 parent delivery spec](./GOV-001-upstream-permission-and-trust-boundary.md)
- [P1B-B real inventory and seed activation gate](./GOV-001-p1b-b-real-inventory-owner-operator-seed-activation-gate.md)
- [P2 strong identity and Gateway strictness gate](./GOV-001-p2-strong-identity-and-gateway-strictness-decision-gate.md)
- [P3 production boundary gate](./GOV-001-p3-production-boundary-decision-gate.md)
- [P4 legacy retirement and independent release signoff gate](./GOV-001-p4-legacy-retirement-and-independent-release-signoff-decision-gate.md)
