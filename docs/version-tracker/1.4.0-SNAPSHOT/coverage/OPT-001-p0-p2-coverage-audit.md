---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.4.0-SNAPSHOT
target: OPT-001 P0-P2 isolated implementation
status: reviewed
conclusion: ready-for-acceptance
reviewed_by: Codex
reviewed_at: 2026-07-11
follow_up_required: yes
production_enablement: not-approved
---

# Test Coverage Audit

## Background

本审计核对 OPT-001 P0-P2 隔离范围的 requirement、缺陷和体验项是否都有可复核证据。P3-P6 生产 rollout 明确不在本次签收范围，相关零样本继续作为生产 gap 保留。

## Audit Basis

- [Requirement](../workitems/OPT-001-independent-codex-app-server-worker-requirement.md)
- [Progress](../workitems/OPT-001-independent-codex-app-server-worker-progress.md)
- [Implementation quality gate](../quality/OPT-001-p0-p2-implementation-quality.md)
- [Acceptance checklist](../acceptance/OPT-001-p0-p7-acceptance.md)
- Durable evidence under [`../evidence`](../evidence/).

## Coverage Matrix

| Requirement | Unit/contract | Integration | Live/E2E | Conclusion |
|---|---|---|---|---|
| CLI/schema/model-reasoning matrix | yes | yes | all-model direct Worker | covered-isolated |
| idempotent accept / 409 conflict | yes | HTTP contract | real accept/retry/conflict | covered-isolated |
| committed no-replay / terminal irreversible | yes | fault injection | disconnect/crash recovery | covered-isolated |
| durable abort / tombstone / ESN | yes | restart recovery | real abort/delete/reconnect | covered-isolated |
| pool capacity/reuse/drain/crash | yes | bootstrap/shutdown | hard crash and POSIX drain/rollback | covered-isolated |
| cross-lane pool fairness | yes | LRU/slow-close/capacity | P3 long-soak not started | covered-isolated; production-gap |
| Worker/runtime process tree | yes | real descendants/nonce outcome/unreadable evidence/Windows delayed exit | v5 Windows/WSL exact-package and zero-residue passed | covered-isolated |
| state identity and leases | yes | multi-process contention | hardlink/symlink/legacy/reclaim | covered-isolated |
| runtime registry / CAS / affinity | yes | Java reactor | A/B mismatch/recovery/delete affinity | covered-isolated |
| shared Worker availability | yes | Java + PC | shared-user/owner availability and PC live passed | covered-isolated |
| Worker -> Java -> unified SSE -> PC | yes | real services | task `20260711-8023` exact result/file and PC refresh passed | covered-isolated |
| delta/result/history contract | yes | Worker + Java + Session | last canonical/recovery/reconciliation and refreshed single-message history passed | covered-isolated |
| native subtask UI | yes | unified SSE/snapshot | native SSE `5`、snapshot `1`、PC `1/1` and desktop/320px passed | covered-isolated |
| MySQL expand/backfill/validate | assertions | 8.0.44 + 8.4.8 | current launcher validate | covered-isolated |
| N-1 compatibility | strict RX helpers | separate N-1 DB | legacy GET + migrate + CRUD/delete | covered-isolated |
| old deployed migration upgrade | parser/assertions | drop epoch + idempotent patch x2 | current prod validate | covered-isolated |
| Canary evidence correctness | yes | model/denominator/lease | P3 remains 0 samples | covered-isolated; production-gap |
| terminal memory/journal bounds | yes | 105 histories/concurrent replay/legacy journal | P3 long-soak not started | covered-isolated; production-gap |
| deterministic release/update | release tests | Worker `200` + reproducible v5 archive | Windows/WSL install/start/real Ultra/running update/stop/zero-residue passed | covered-isolated |
| P3 production canary | fail-closed collector | no production lane | 0/50, 0/72h, 0/2 | gap-production |
| P4-P6 rollout | design only | none | none | future-gates |
| P7 SDK retirement | N/A | N/A | N/A | deferred-by-product-decision |

## Evidence Summary

- App-server Worker `200 total / 193 passed / 7 platform-skipped / 0 failed`；typecheck/build/schema verify 通过；schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`。
- Release `0.1.1` v5 SHA-256/bytes/entries=`b6271e5a3220b0253d97b6d05c9fe5f5561331655e27faccd8ad254fbf6c31d9` / `1,500,249` / `168`；双构建字节一致、路径扫描通过；Windows/WSL exact-package install/start/real Ultra/running update/stop/zero-residue 通过。
- Legacy SDK Worker `116/116`；typecheck/build；保持 SDK 设计；新 Ultra fail closed、非 Ultra 和已有 SDK affinity 保持；仅测试断言适配 Windows 上的 POSIX server-script 路径。
- Codex Java addon `259/259`；Session focused `7/7`；reconciliation `10/10`；Metadata Query `13/13`；Launcher ownership context `1/1`；Code Review client context `1/1`。raw full reactor 被 Windows Surefire fork/path 基础设施问题阻断，受影响定向测试通过。
- Navigator PC Vitest `179/179`、`build:check` 通过；shared-user/fixed-task Playwright 刷新前后 final message=`1`、native=`1/1`，desktop/320px 无溢出、失败请求或控制台错误。
- MySQL 8.0.44 和 8.4.8 migration fixtures；N-1 detached baseline JAR strict read/migrate/validate/CRUD/soft-delete。
- Epoch compatibility migration 在缺列旧 schema 上执行并重复执行，随后最新 launcher `ddl-auto=validate` 通过。
- BUG-009 runtime `16/16`、pool `12/12`、typecheck/build/diff-check 与 test temp residue 0；final Worker full 和 v5 Windows/WSL exact-package operations 通过。
- Navigator task `20260711-8023` / Session `b2bc4a9c-3134-4d24-af50-5709ab9b91e6` COMPLETED；result=`FINAL_RESULT_OK`、文件=`FINAL_NATIVE_RESULT_OK`、native SSE=`5`、snapshot=`1`，prompt/Bearer/Worker token 暴露检查均为 false。
- Raw Maven full reactor 在 Windows Surefire fork/path 基础设施阶段被阻断；Codex addon `259/259` 和受影响定向测试通过，不声明 `1342/1342`。

- Durable records:
  [Windows exact-package](../evidence/OPT-001-exact-package-windows-v5.json),
  [WSL exact-package](../evidence/OPT-001-exact-package-wsl-v5.json),
  [Navigator Ultra task](../evidence/OPT-001-navigator-ultra-task-v5.json),
  [PC final acceptance](../evidence/OPT-001-pc-final-acceptance-v5.json).

### Defect Coverage

| Defect | Automation/static | Live status |
|---|---|---|
| [BUG-001](../workitems/BUG-001-app-server-delta-message-fragmentation.md) | covered | closed-isolated |
| [BUG-002](../workitems/BUG-002-app-worker-dotenv-state-dir.md) | covered | closed |
| [BUG-003](../workitems/BUG-003-worker-view-mobile-layout.md) | covered | closed-isolated |
| [BUG-004](../workitems/BUG-004-app-worker-operations-dotenv-run-dir.md) | covered | closed |
| [BUG-005](../workitems/BUG-005-app-worker-windows-install-path-spaces.md) | covered | closed |
| [BUG-006](../workitems/BUG-006-app-worker-macos-update-candidate-discovery.md) | covered | closed |
| [BUG-007](../workitems/BUG-007-app-server-final-result-aggregation.md) | covered, including recovered `assistant_text` dedupe | closed-isolated |
| [BUG-008](../workitems/BUG-008-canary-evidence-correctness.md) | covered-isolated | fixed-isolated; P3 pending |
| [BUG-009](../workitems/BUG-009-lifecycle-process-tree-and-stop-outcome.md) | runtime/pool/exact-package covered | closed-isolated |
| [BUG-010](../workitems/BUG-010-pc-app-server-boundary-and-shared-availability.md) | Java/PC/live covered | closed-isolated |
| [BUG-011](../workitems/BUG-011-terminal-broadcast-and-task-store-bounds.md) | covered-isolated | fixed-isolated; P3 soak pending |
| [BUG-012](../workitems/BUG-012-pool-cross-lane-lru-retirement.md) | covered-isolated | fixed-isolated; P3 soak pending |
| [BUG-013](../workitems/BUG-013-windows-process-tree-termination-settle-race.md) | delayed-exit and exact-package covered | closed-isolated |

## Gaps

- gap 1: P3 外部生产仍为 0/50 terminal Ultra task、0/72h 和 0/2 instance rotations；Canary denominator/lease、memory 和 Pool fairness 仍无生产样本。
- gap 2: 生产 duplicate side effect、affinity mismatch 和 credential/raw child leak 仍是无样本，不能引用隔离 smoke 代替。
- gap 3: 动态账号 model catalog、P5 feature parity、目标网络/权限/监控/release owner/回滚窗口未签收。
- gap 4: Raw Maven full reactor 受 Windows Surefire fork/path 基础设施阻断；本次只引用已通过的 scoped evidence，不宣称全 reactor 通过。

## Recommended Next Skills

- P0-P2 隔离范围可进入并已执行 `foggy-acceptance-signoff`。
- P3 只能在 release owner 批准后继续收集生产样本；若生产发现缺陷，转入 `foggy-bug-regression-workflow`。

## Conclusion

- conclusion: ready-for-acceptance
- P0: covered
- P1: isolated-accepted
- P2: isolated-accepted
- can_enter_feature_acceptance: yes-isolated
- can_enter_P3: no
- production_enablement: not-approved
- follow_up_required: yes
