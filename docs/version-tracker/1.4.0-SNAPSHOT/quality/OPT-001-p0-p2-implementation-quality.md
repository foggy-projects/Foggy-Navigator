---
quality_scope: feature
quality_mode: pre-coverage-audit
version: 1.4.0-SNAPSHOT
target: OPT-001 P0-P2 isolated implementation
status: reviewed
decision: ready-for-coverage-audit
reviewed_by: Codex
reviewed_at: 2026-07-11
follow_up_required: yes
production_enablement: not-approved
---

# Implementation Quality Gate

## Background

OPT-001 已完成 P0-P2 隔离实现、release operations、真实 Ultra 链路和 PC 体验验证。本闸门只判断这些隔离交付是否具备进入覆盖审计的实现质量，不批准 P3-P6 生产路由。

## Check Basis

- [Requirement](../workitems/OPT-001-independent-codex-app-server-worker-requirement.md)
- [Implementation plan](../workitems/OPT-001-independent-codex-app-server-worker-plan.md)
- [Progress and execution check-in](../workitems/OPT-001-independent-codex-app-server-worker-progress.md)
- Current code diff, automated regression, exact-package and live evidence recorded below.

## Changed Surface

- 新 `tools/codex-app-server-worker` 的 app-server 生命周期、状态、发布和运维。
- Codex Java runtime registry、路由、Task/Session/instance affinity、SSE/native projection。
- Navigator PC runtime readiness、Ultra preflight 和原生子任务展示。
- Canary requested-model/denominator/lease correctness、Worker/runtime process tree、bounded terminal state、cross-lane Pool retirement 和共享 Worker minimum availability。
- 旧 `tools/codex-agent-worker` 仅核对边界：继续 SDK 设计，新 Ultra fail closed，已有 affinity drain。
- 不包含 P3-P6 生产放量，也不包含已延后的 P7 SDK retirement。

## Quality Checklist

| Check | Result | Notes |
|---|---|---|
| scope conformance | pass | 未新增 Provider/Task/Session/PC 页面；新 Worker 不 import SDK Worker 私有实现 |
| idempotency | pass | accepted 前原子持久化；同 key 同请求复用，异请求 409；committed 后不 replay |
| state durability | pass-isolated | task/event sentinel、连续 ESN、fsync 后发布、截断恢复；terminal broadcast 退役、resident summary 与 request ciphertext 单次持久化 |
| process lifecycle | pass-isolated | Worker/runtime 精确进程树、nonce outcome、close/abort/TTL/drain descendant verify、不可读 evidence 与 Windows delayed-exit fail-closed 已实现；v5 Windows/WSL exact-package 运维矩阵通过 |
| pool fairness and capacity | pass-isolated | 跨 lane LRU idle replacement、busy exclusion、concurrent reservation 和 close failure fail-closed 有回归 |
| state ownership | pass | canonical stateDir、writer/recovery lease；hardlink/symlink/unsafe legacy id fail closed |
| immutable affinity | pass | runtime/revision/instance/routing epoch 持久化；status/subscribe/abort/delete 不 fallback |
| canary evidence correctness | pass-isolated | APP_SERVER requested model 保留；invalid-instance violation 与 terminal denominator 隔离；lease reclaim claim fail-closed；生产验证仍属于 P3 |
| stream ordering and result | pass-isolated | delta 为 `TEXT_CHUNK`、completed 为 canonical `TEXT_COMPLETE`；turn/start 同批通知保序；最后 canonical 与恢复态 `assistant_text` 去重；真实任务 exact result 与刷新历史通过 |
| time contract | pass | 新任务持久化 `created_at_epoch_ms` UTC epoch；legacy LocalDateTime 不猜时区，旧行保持 NULL |
| query efficiency | pass | providerType 列表解析改为批量 SessionTask + Session 查询，无逐任务 N+1 |
| HTTP client ownership | pass | launcher default RestTemplate 与 metadata/code-review 专用 client 明确隔离 |
| privacy | pass | runtime token、请求、Codex Home、原始 child prompt/reasoning/tool payload 不进入 manifest/DTO/UI |
| shared availability boundary | pass-isolated | 共享用户仅获三字段 aggregate DTO；详细 runtime owner-only；ALL_CANARY@0 Ultra 语义与 router 对齐；shared/owner PC live 通过 |
| release engineering | pass-isolated | Worker `200` 项回归与 v5 SHA-256/bytes/entries 已形成；双构建字节一致、路径扫描及 Windows/WSL exact-package matrix 通过 |
| migration discipline | pass-isolated | MySQL 8.0/8.4、N-1、validate；旧版脚本已部署场景有幂等增量 SQL |
| user experience | pass-isolated | PC `179/179`、`build:check`、shared-user availability、pool-managed process boundary、exact result/刷新及 desktop/320px Playwright 均通过 |

## Findings

- 最终 review 发现并修复了永久 drain/release 无限重试、启动失败残留、state identity 链接别名、超大失配请求未关闭、production canary 时间歧义和列表 provider N+1。
- `created_at_epoch_ms` 是生产 canary 唯一可信任务时间；旧行或旧二进制创建的 NULL 行会被生产 collector 排除，不用 server/JVM 默认时区推断。
- production canary 对 runtime/revision/instance/model/provider/cohort marker 和外部证据均 fail closed；本地结果不能标成生产 PASS。
- 新 Worker 支持固定 CLI manifest 中全部模型/reasoning，但功能 parity 与生产路由按 cohort 分离。
- Java Codex `259/259`、frontend `179/179`、Session focused `7/7` 和 recovery reconciliation `10/10` 通过；Legacy SDK Worker `116/116`、typecheck/build 通过并保持 SDK 设计与 Ultra fail-closed。raw full reactor 在 Windows Surefire fork/path 基础设施阶段被阻断，受影响定向测试通过，不声明 `1342/1342`。
- Worker `200 total / 193 passed / 7 platform-skipped / 0 failed`，v5 制品 SHA-256 `b6271e5a3220b0253d97b6d05c9fe5f5561331655e27faccd8ad254fbf6c31d9`、`1,500,249` bytes、`168` entries；双构建、路径扫描及 Windows/WSL exact-package operations 通过。
- 真实 Navigator task `20260711-8023` 完成，exact result/file、native SSE/snapshot、隐私检查和 PC 刷新前后单一 final message、native `1/1`、desktop/320px 均通过。
- 未发现需要回退 P0-P2 隔离实现的问题；isolated experience 可以签收，但该结论不批准 P3 或任何生产路由。

### Acceptance Defects

| Defect | Quality conclusion |
|---|---|
| [BUG-001](../workitems/BUG-001-app-server-delta-message-fragmentation.md) | closed-isolated；单卡片与刷新后单一 final message 通过 |
| [BUG-002](../workitems/BUG-002-app-worker-dotenv-state-dir.md) | closed，最终真包双平台通过 |
| [BUG-003](../workitems/BUG-003-worker-view-mobile-layout.md) | closed-isolated；desktop/320px Playwright 通过 |
| [BUG-004](../workitems/BUG-004-app-worker-operations-dotenv-run-dir.md) | closed，最终真包双平台通过 |
| [BUG-005](../workitems/BUG-005-app-worker-windows-install-path-spaces.md) | closed，最终真包 Windows 路径矩阵通过 |
| [BUG-006](../workitems/BUG-006-app-worker-macos-update-candidate-discovery.md) | closed，Bash 3.2/Ubuntu 证据通过 |
| [BUG-007](../workitems/BUG-007-app-server-final-result-aggregation.md) | closed-isolated；exact result 与刷新去重通过 |
| [BUG-008](../workitems/BUG-008-canary-evidence-correctness.md) | fixed-isolated；生产验证属于 P3 |
| [BUG-009](../workitems/BUG-009-lifecycle-process-tree-and-stop-outcome.md) | closed-isolated；v5 Windows/WSL exact-package 与 zero-residue 通过 |
| [BUG-010](../workitems/BUG-010-pc-app-server-boundary-and-shared-availability.md) | closed-isolated；shared/owner availability、process boundary 与 PC live 通过 |
| [BUG-011](../workitems/BUG-011-terminal-broadcast-and-task-store-bounds.md) | fixed-isolated；final Worker full/package 已通过，P3 soak 待补 |
| [BUG-012](../workitems/BUG-012-pool-cross-lane-lru-retirement.md) | fixed-isolated；final Worker full/package 已通过，P3 soak 待补 |
| [BUG-013](../workitems/BUG-013-windows-process-tree-termination-settle-race.md) | closed-isolated；bounded termination settle 与 v5 双平台 running update 通过 |

## Risks / Follow-ups

- P3 仍无外部生产任务、72 小时或实例轮换证据，不能批准 Ultra canary。
- 固定 CLI 静态 catalog 尚不是账号动态 catalog；未来模型不能依赖 wildcard 自动开放。
- approval、additional directories、interactive server request、Biz/MCP 等 P5 parity 尚未关闭。
- 若环境已执行旧版 affinity SQL，必须追加幂等 epoch migration；重跑原一次性 SQL 不是升级方案。
- Raw full reactor 在 Windows Surefire fork/path 基础设施阶段被阻断；相关 Codex/metadata/launcher context tests 已通过。
- v5 final full/artifact 已生成；旧 `642121...`、`4ebb...` 与 v4 `71a0...` 制品不得用于 release signoff。
- 本地 exact-package、真实 Ultra 和 PC Playwright 只证明 P0-P2 isolated quality；不得计入 P3 的 50 task、72h 或 2 rotations。

## Recommended Next Skills

- 当前实现可进入并已完成 `foggy-test-coverage-audit`。
- 只有 release owner 批准目标生产环境后，才进入 P3 canary evidence collection；不得提前执行 P4-P6。

## Decision

- decision: ready-for-coverage-audit
- decision_scope: P0-P2 isolated accepted；P3-P6 production rollout excluded
- production_routing_change: no
- P3_entry_approved: no
- isolated_experience: accepted
- old_sdk_worker: retained-sdk-design-ultra-fail-closed
- follow_up_required: yes
