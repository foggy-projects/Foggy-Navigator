---
acceptance_scope: feature
version: 1.4.0-SNAPSHOT
target: OPT-001 P0-P2 isolated implementation
doc_role: acceptance-record
status: signed-off
decision: accepted-with-risks
production_enablement: not-approved
signed_off_by: Codex
signed_off_at: 2026-07-11
production_signed_off_by: null
production_signed_off_at: null
reviewed_by: Codex
reviewed_at: 2026-07-11
blocking_items: []
follow_up_required: yes
evidence_count: 4
---

# Feature Acceptance

## Background

本记录签收 OPT-001 P0-P2 隔离实现与体验。P3-P6 是未开始的独立生产 rollout，不属于本次 accepted scope；`production_enablement` 因此继续为 `not-approved`。

## Acceptance Basis

- [Requirement](../workitems/OPT-001-independent-codex-app-server-worker-requirement.md)
- [Implementation plan](../workitems/OPT-001-independent-codex-app-server-worker-plan.md)
- [Progress](../workitems/OPT-001-independent-codex-app-server-worker-progress.md)
- [Implementation quality gate](../quality/OPT-001-p0-p2-implementation-quality.md)
- [Coverage audit](../coverage/OPT-001-p0-p2-coverage-audit.md)
- Durable evidence under [`../evidence`](../evidence/).

### Decision Boundary

- P0 代码/契约静态验收通过。
- P1/P2 的 final Worker full/archive、Windows/WSL exact-package、Java/Session/PC、双实例 affinity、真实 Ultra/SSE/native、刷新和 desktop/320px 证据均通过，isolated experience 已签收。
- 本记录不批准外部生产 Ultra canary，不把本地/隔离证据计入 P3 数值门槛。
- P7 SDK retirement 已按产品决定延后；旧 SDK Worker 继续保持现有设计，不是本记录的 blocker。

## Checklist

- [x] P0：固定 CLI/schema、幂等接受、durable state/ESN、capability、registry、immutable affinity 和 rollback 语义完成。
- [x] P1 final isolated signoff：核心/Canary/store/pool/lifecycle、Worker `200` 项回归、v5 可复现制品和 Windows/WSL exact-package lifecycle/update/零残留通过。
- [x] P2 final isolated signoff：Java/Session/PC、A/B affinity、MySQL 8.0/8.4、N-1、migration/validate、shared-user availability、process boundary、exact result、历史/刷新与 desktop/320px 通过。
- [ ] P3：release owner 未签收；外部生产 0/50 task、0/72h、0/2 rotation。
- [ ] P4：Ultra default 未开始，依赖 P3 独立签收。
- [ ] P5：非 Ultra/功能 cohort 未开始，动态 catalog 与功能 parity 未关闭。
- [ ] P6：app-server default 未开始，是否推进由后续产品与生产 gate 决定。
- N/A P7：`deferred-by-product-decision`，本版本不执行、不签收 SDK retirement。

## Evidence

- App-server Worker `200 total / 193 passed / 7 platform-skipped / 0 failed`；typecheck/build/schema verify 通过。
- `0.1.1` v5 SHA-256/bytes/entries=`b6271e5a3220b0253d97b6d05c9fe5f5561331655e27faccd8ad254fbf6c31d9` / `1,500,249` / `168`；双构建字节一致、路径扫描通过；Windows/WSL exact-package install/start/real Ultra/running update/stop/zero-residue 通过。
- Legacy SDK Worker `116/116`，typecheck/build；现有 SDK 设计、非 Ultra 路径和已有 affinity 保持，Ultra fail-closed；仅测试断言适配 Windows 上的 POSIX server-script 路径。
- Codex addon `259/259`；Session focused `7/7`；reconciliation `10/10`；Metadata `13/13`；Launcher ownership context `1/1`；Code Review context `1/1`。raw full reactor 被 Windows Surefire fork/path 基础设施问题阻断，受影响定向测试通过。
- PC Vitest `179/179`、`build:check`；shared-user/fixed-task Worker-Java-SSE-PC 刷新前后 final message 均为 `1`，native `1/1`，desktop/320px 无溢出、失败请求或控制台错误。
- MySQL 8.0.44/8.4.8；N-1 strict legacy GET、migration 后 validate/CRUD/soft-delete；旧 migration 缺 epoch 列的幂等升级路径通过。
- Worker 状态/租约/幂等、超大 affinity probe、bootstrap failure、永久 drain/release 有限重试和 EADDRINUSE 清理均有回归。
- Navigator task `20260711-8023` / Session `b2bc4a9c-3134-4d24-af50-5709ab9b91e6` COMPLETED；result=`FINAL_RESULT_OK`、文件=`FINAL_NATIVE_RESULT_OK`、native SSE=`5`、snapshot=`1`，prompt/Bearer/Worker token 暴露检查均为 false。

- Durable records:
  [Windows exact-package](../evidence/OPT-001-exact-package-windows-v5.json),
  [WSL exact-package](../evidence/OPT-001-exact-package-wsl-v5.json),
  [Navigator Ultra task](../evidence/OPT-001-navigator-ultra-task-v5.json),
  [PC final acceptance](../evidence/OPT-001-pc-final-acceptance-v5.json).

### Defect Signoff

| Defect | Status |
|---|---|
| [BUG-001](../workitems/BUG-001-app-server-delta-message-fragmentation.md) | closed-isolated |
| [BUG-002](../workitems/BUG-002-app-worker-dotenv-state-dir.md) | closed |
| [BUG-003](../workitems/BUG-003-worker-view-mobile-layout.md) | closed-isolated |
| [BUG-004](../workitems/BUG-004-app-worker-operations-dotenv-run-dir.md) | closed |
| [BUG-005](../workitems/BUG-005-app-worker-windows-install-path-spaces.md) | closed |
| [BUG-006](../workitems/BUG-006-app-worker-macos-update-candidate-discovery.md) | closed |
| [BUG-007](../workitems/BUG-007-app-server-final-result-aggregation.md) | closed-isolated |
| [BUG-008](../workitems/BUG-008-canary-evidence-correctness.md) | fixed-isolated; P3 validation pending |
| [BUG-009](../workitems/BUG-009-lifecycle-process-tree-and-stop-outcome.md) | closed-isolated |
| [BUG-010](../workitems/BUG-010-pc-app-server-boundary-and-shared-availability.md) | closed-isolated |
| [BUG-011](../workitems/BUG-011-terminal-broadcast-and-task-store-bounds.md) | fixed-isolated; P3 soak pending |
| [BUG-012](../workitems/BUG-012-pool-cross-lane-lru-retirement.md) | fixed-isolated; P3 soak pending |
| [BUG-013](../workitems/BUG-013-windows-process-tree-termination-settle-race.md) | closed-isolated |

## Failed Items

- P0-P2 isolated scope: none.
- P3-P6 are unstarted and excluded from this signoff; they are not converted into passed items or counted as isolated failures.

## Risks / Open Items

- 生产 duplicate side effect、affinity mismatch、credential/raw child leak 都没有生产样本；不能表述为生产值 0。
- 固定 CLI manifest 不等于动态账号 catalog，未知未来模型不得自动开放。
- P5 的 approval、additional directories、interactive server request、Biz/MCP 等功能仍须逐 cohort 验证。
- 已执行旧版 affinity SQL 的环境必须执行 `docs/migration/2026-07-10-codex-task-created-at-epoch-ms.sql`。
- Raw full reactor 在 Windows Surefire fork/path 基础设施阶段被阻断；相关变更模块和上下文测试均通过。
- 旧 exact-final 失败任务和 v4 update BLOCKED 只作为复现证据；isolated signoff 使用 v5 final package、任务 `20260711-8023` 和最终 PC Playwright 证据。

## Final Decision

OPT-001 的 P0-P2、BUG-001~013 相关隔离实现及体验证据已签收；BUG-008/011/012 的生产验证仍属于 P3。该结论不批准生产：目标环境尚未由 release owner 签收，P3 仍为 0/50 terminal Ultra task、0/72h 和 0/2 rotations，`production_enablement` 保持 `not-approved`。P4-P6 在 P3 独立生产签收前不得开始；旧 SDK Worker 保持现状，不等待也不执行退役。

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: Codex
- signed_off_at: 2026-07-11
- acceptance_record: docs/version-tracker/1.4.0-SNAPSHOT/acceptance/OPT-001-p0-p7-acceptance.md
- blocking_items: none
- isolated_experience: accepted
- production_enablement: not-approved
- P3_entry: not-approved
- P4-P6: not-started
- P7: N/A-deferred-by-product-decision
- isolated_signed_off_by: Codex
- isolated_signed_off_at: 2026-07-11
- production_signed_off_by: null
- production_signed_off_at: null
- follow_up_required: yes
