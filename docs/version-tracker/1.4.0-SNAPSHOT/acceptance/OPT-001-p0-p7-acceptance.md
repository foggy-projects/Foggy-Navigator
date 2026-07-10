---
acceptance_scope: feature-and-rollout
version: 1.4.0-SNAPSHOT
target: OPT-001 P0-P7
doc_role: acceptance-record
status: blocked
decision: blocked
production_enablement: not-approved
signed_off_by: null
signed_off_at: null
reviewed_by: Codex local code reviewer
reviewed_at: 2026-07-10
blocking_items:
  - P1 post-fix provider/crash live refresh plus POSIX and running-service update rollback
  - P2 real Worker-Java-SSE-PC chain and browser reconnect
  - P2 N-1 multi-replica and production migration validation
  - P3 release-owner cohort and 50-task 72-hour canary evidence
  - P4-P7 production default drain parity and retirement evidence
follow_up_required: yes
evidence_count: 8
---

# Feature And Rollout Acceptance

## Background

- Version: `1.4.0-SNAPSHOT`
- Target: `OPT-001` 独立 Codex App Server Worker
- Goal: 以独立 app-server Worker 承载全部已声明模型/reasoning，Ultra 首批灰度，之后逐 cohort 成为默认并退役 SDK lane。
- Review boundary: 当前只完成 P0 和 P1/P2 本地实现检查点审查；未改变生产路由。

## Acceptance Basis

- requirement: `docs/version-tracker/1.4.0-SNAPSHOT/workitems/OPT-001-independent-codex-app-server-worker-requirement.md`
- implementation plan/progress: 同目录 `plan` 与 `progress`
- quality gate: `docs/version-tracker/1.4.0-SNAPSHOT/quality/OPT-001-p0-p2-implementation-quality.md`
- coverage audit: `docs/version-tracker/1.4.0-SNAPSHOT/coverage/OPT-001-p0-p2-coverage-audit.md`

## Checklist

- [x] P0 契约、固定 CLI/schema、幂等接受、capability、affinity 和 rollback 语义完成。
- [ ] P1 完整退出：自动化、此前隔离 smoke、最终 health/matrix/HTTP 幂等和 0.1.0 本地 Windows package/install/update 通过；最终 provider/crash live 被账户额度阻塞，POSIX/运行中 drain/restart/rollback 未验。
- [ ] P2 完整退出：分层实现与回归通过；真实全链、N-1、多副本和生产 migration/validate 未验收。
- [ ] P3 Ultra canary：release owner 未签收 cohort；当前 0/50 task、0/72h、0/2 rotation。
- [ ] P4 Ultra default 与 legacy drain：依赖 P3 独立签收。
- [ ] P5 非 Ultra/功能 cohort：动态 catalog 与完整功能 parity 未关闭。
- [ ] P6 app-server default：未切换，SDK 仍是生产默认 lane。
- [ ] P7 SDK retirement：active/resumable/exception、保留期和回滚 artifact 均未满足。

## Evidence

- 新 Worker 87/87，typecheck/build/schema verify 通过。
- 0.1.0 deterministic ZIP 三次 SHA-256 一致：`59cf633a5781ee8adde28c3363342920f71131def1bfdde288d63233300ef5ea`；Windows temp install/in-place update 与状态保留通过。
- 旧 SDK Worker 115/115，typecheck/build 通过，Ultra 新任务 fail closed。
- Java reactor BUILD SUCCESS；Codex addon 214/214、Session 302/302。
- PC Vitest 159/159、type-check/build、mocked contract Playwright 2/2。
- MySQL 8.4.8 干净 schema migration/backfill rehearsal 通过。
- 固定 CLI `0.144.1` 的七模型静态 manifest 和 reasoning 边界已验证；此前逐档真实 Worker smoke 通过，最终硬化后 provider refresh 被账户 usage limit 阻塞。
- 最终 post-fix HTTP 幂等 first/same/changed 为 `202/202/409`；恢复/持久化 focused tests 46/46。主动中止、pool reuse、native 投影保留此前 live evidence，hard crash/restart 未在本轮重验。
- `git diff --check` 和依赖/端口/reasoning 静态检查通过，生产 3052/3061 lane 未被改动或停止。

## Risks / Open Items

- 当前证据证明的是本地代码与隔离 Worker，不证明目标生产网络、数据库、Java/PC 部署和多副本拓扑。
- 固定 manifest 不等于账号动态 catalog；未知未来模型不得自动成为平台可路由项。
- P3 的 duplicate side effect、affinity mismatch、credential/raw child leak 当前没有生产样本，不能把“0 条样本”写成“生产为 0”。
- 旧 SDK task/session 必须持续按原 affinity drain，任何 rollback 都只能停止新分配。

## Failed Items

- P1 final provider/crash live refresh: blocked by external account usage limit before `tool_use`。
- P1 target operations gate: POSIX actual install and running-service drain/restart/failure rollback not-run。
- P2 full-chain/N-1/multi-replica/production migration gate: not-run。
- P3-P7 production rollout gates: not-run and entry conditions unsatisfied。

## Final Decision

P0 验收通过；P1/P2 的本地实现检查点通过质量 review，但尚未满足各自完整 exit gate。OPT-001 整体结论为 `blocked`：先完成 P1 发布物和 P2 目标环境证据，再由 release owner 签收 P3 cohort。P3 未达到 50 task、72 小时和 2 次轮换之前，禁止进入 P4；P4-P6 未签收之前，禁止退役 SDK lane。

## Signoff Marker

- acceptance_status: blocked
- acceptance_decision: blocked
- local_code_checkpoint: reviewed-ready-with-risks
- production_enablement: not-approved
- P3_entry: not-approved
- signed_off_by: null
- signed_off_at: null
- blocking_items: see front matter and Failed Items
- follow_up_required: yes
