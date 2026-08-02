---
doc_type: version-index
version: 1.4.3-SNAPSHOT
status: int001-rejected-bug008-accepted-with-risks-bug009-rejected
canonical_delivery_spec: workitems/GOV-001-dev-s1-s2-integration-mvp.md
external_enablement: no
production_enablement: no
last_updated: 2026-08-03
---

# Foggy Navigator 1.4.3-SNAPSHOT

## Version Goal

1. 在 1.4.2 已有 external gate、ClientApp、task capability、Worker principal 与 ownership 基线上，形成面向不同上游接入形态的权限体系。
2. 明确区分 Navigator 实例管理主体、upstream system、ClientApp control、runtime caller、upstream user、Agent/task capability 与 Worker principal。
3. 按专属可信上游、公司 SaaS 平台和外部第三方三类场景冻结信任边界，再决定统一授权门面、数据模型、API、CLI 和迁移方案。
4. 保持 fail-closed、最小权限、凭据分层和全链路可审计，并在场景对齐中明确冻结绑定关系与资源归属的语义。
5. 让 CLI/SKILL 使用者在执行前明确看到当前 principal、credential lane、实例/upstream/tenant/ClientApp scope、允许动作和明确禁止动作。

## Current Status

- phase: int001-rejected-bug008-accepted-with-risks-bug009-rejected
- navi_core_s2_04_cross_project_retirement_handoff: HANDOFF_READY
- architecture_review: passed-with-complexity-guardrails
- p0_5_status: complete
- implementation_started: p1a-accepted-p1b-a-accepted-p1b-b0-accepted-p1c-a-accepted
- canonical_status: P1A_ACCEPTED_P1B_A_ACCEPTED_P1B_B0_ACCEPTED_P1C_A_ACCEPTED
- p1a_acceptance_status: accepted
- active_repair_spec: workitems/BUG-010-int001-process-group-empty-proof.md
- p1a_repair_status: ACCEPTED
- observer_bff_p1a_disposition: catalog-and-test-only
- p1b_a_status: ACCEPTED
- p1b_a_acceptance_status: accepted
- p1b_b0_status: ACCEPTED
- p1b_b0_acceptance_status: accepted
- p1c_a_status: ACCEPTED
- p1c_a_acceptance_status: accepted
- dev_s1_s2_integration_mvp_status: ACCEPTED
- dev_s1_s2_integration_mvp_acceptance_status: accepted
- synthetic_runtime_validation_spec: workitems/INT-001-synthetic-upstream-integration-harness.md
- owner_context_repair_spec: workitems/BUG-008-openapi-upstream-physical-langgraph-identity-context.md
- int001_status: REJECTED
- int001_acceptance_status: rejected
- bug008_status: ACCEPTED
- bug008_acceptance_status: accepted-with-risks
- bug009_status: READY_FOR_SIGNOFF
- bug010_status: READY_FOR_SIGNOFF
- runtime_request_audit_spec: workitems/BUG-017-runtime-request-audit-no-task-id.md
- bug017_status: READY_FOR_SIGNOFF
- runtime_binding_task_read_only_audit_spec: workitems/FEAT-001-runtime-binding-task-read-only-audit.md
- runtime_binding_task_read_only_audit_status: READY_FOR_SIGNOFF
- runtime_task_completion_readiness_spec: workitems/FEAT-003-runtime-task-completion-readiness.md
- runtime_task_completion_readiness_status: READY_FOR_SIGNOFF
- runtime_worker_readiness_convergence_spec: workitems/BUG-032-runtime-worker-readiness-and-preacceptance-failure-convergence.md
- runtime_worker_readiness_convergence_status: READY_FOR_SIGNOFF
- codex_worker_windows_installer_null_output_spec: workitems/BUG-033-codex-worker-windows-installer-null-output.md
- codex_worker_windows_installer_null_output_status: READY_FOR_SIGNOFF
- worker_home_uid_fallback_spec: workitems/BUG-034-worker-home-uid-fallback-and-obs-release.md
- worker_home_uid_fallback_status: READY_FOR_SIGNOFF
- typed_termination_cli_release_spec: workitems/REL-003-navigator-upstream-cli-1.0.39-snapshot-typed-termination.md
- typed_termination_cli_release_status: ACCEPTED
- bug035_status: ACCEPTED
- typed_termination_terminal_convergence_spec: workitems/BUG-036-typed-termination-terminal-convergence.md
- bug036_status: READY_FOR_SIGNOFF
- unified_session_task_lifecycle_owner_spec: workitems/ARCH-001-unified-session-task-lifecycle-owner.md
- arch001_status: REJECTED
- arch001_acceptance_status: rejected
- arch001_acceptance_record: evidence/ARCH-001-independent-signoff-2026-07-31.md
- arch001_replan_round: 7
- arch001_replan_review_status: APPROVED_AFTER_ROUND_7
- arch001_source_execution_authorized: true
- arch001_real_activation_authorized: false
- arch001_bounded_local_development_activation_spec: workitems/ARCH-001-ACT-002-bounded-local-development-activation.md
- arch001_bounded_local_development_activation_status: READY_FOR_SIGNOFF
- app_server_compact_repair_spec: workitems/BUG-021-app-server-compact-protocol.md
- bug021_status: READY_FOR_SIGNOFF
- app_server_force_recovery_spec: workitems/BUG-022-app-server-force-recovery-start.md
- bug022_status: READY_FOR_SIGNOFF
- chat_posix_file_link_resolution_spec: workitems/BUG-023-chat-posix-file-link-resolution.md
- bug023_status: ACCEPTED
- claude_never_registered_cancel_spec: workitems/BUG-029-claude-never-registered-cancel-convergence.md
- bug029_status: READY_FOR_SIGNOFF
- claude_owner_force_cancel_spec: workitems/BUG-030-claude-owner-force-cancel.md
- bug030_status: READY_FOR_SIGNOFF
- task_scoped_caller_token_spec: workitems/BUG-031-clean-runtime-provenance-and-task-scoped-caller-token.md
- bug031_status: APPROVED
- bug009_acceptance_status: rejected-pending-independent-resignoff
- runtime9_freeze_status: CONSUMED_FAIL_CLOSED
- runtime9_exact_run_id: `int001-bug009-20260722-r9-33154d77`
- runtime9_exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r9-33154d77`
- runtime9_current_authorization: none
- runtime9_preflight: strict runId PASS；exact run directory/projection/reservation absent；artifact root/registry/local Docker socket safe；exact-run Docker container/network/volume residue `0/0/0`；test-owned production-like Java residue `0`。
- runtime9_historical_one_shot_rule: Runtime 9 当时仅允许执行 exact command 一次；现已执行并永久消费，不得 retry、替换 runId、手工 cleanup、读取受限详情或将历史条件作为重新激活依据。
- runtime9_success_gate: no supervisor interruption；controlled health；exact parent `commandLine+cwd+runId+uid+session+startTicks`；exact listener `uid+java+argv+cwd+ancestor+socket+startTicks` + `EXACT_CANDIDATE_FOUND`；`listenerProofStageDiagnostic=FULL_ELIGIBLE`；`listenerProofEverEligible=true`；exactly one TERM；`dispatchSafe=true`；normal exact `EXIT_128`；schema-v4 redacted `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`；private absent；root residue `0`；exact reservation absent；Docker `0/0/0`。
- runtime9_evidence_boundary: 仅允许 fixed-enum/redacted stdout、fixed-schema projection、root receipt 固定字段、private-absent、root residue count、exact-reservation-absent 与 Docker redacted counts；禁止受限详情、共享 8112、真实 TMS/SIM/凭据/Worker/Gateway/Pool/Identity/Codex/external/production。disposable child 的 `NAVIGATOR_EXTERNAL_ENABLED=true` 仅为 loopback Open API route gate，`NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false`。
- external_enablement: no
- production_enablement: no
- current_scenario: P1A foundation/shadow、P1B-A fixture-only typed-management authentication core、P1B-B0 pure-offline pre-seed inventory validator 与 P1C-A CLI/SKILL permission visibility 均已独立 accepted。开发期 SIM/TMS 联调 MVP 仍只证明代码、CLI、手册和本地 preflight。INT-001 的历史签核仍为 rejected。BUG-009 Runtime 4–9 均 `CONSUMED_FAIL_CLOSED`，Runtime 10 已一次性 `CONSUMED_SUCCESS`。此前 BUG-level 独立签核因未证明 host process-group/descendant residue 为零而 rejected；BUG-010 已补齐 exact PGID absence fail-closed 修复和离线回归，BUG-009 当前为 `READY_FOR_SIGNOFF`，等待独立 re-signoff；当前无 runtime 权限。
- runtime8_exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r8-212fde1c`
- runtime8_status: `CONSUMED_FAIL_CLOSED`
- runtime8_success_gate: Runtime 7 strict gate 全部保持，并新增观测 `listenerProofStageDiagnostic=FULL_ELIGIBLE`；该诊断本身不授权 TERM 或成功。
- runtime8_evidence_boundary: 与 Runtime 7 相同，仅允许 fixed-enum/redacted stdout、fixed-schema projection、root receipt 固定字段、private-absent、root residue count、exact-reservation-absent 与 Docker redacted counts；禁止受限详情、共享 8112、真实 TMS/SIM/凭据/Worker/Gateway/Pool/Identity/Codex/external/production。
- runtime7_exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r7-6d3f8a1c`
- runtime7_status: `CONSUMED_FAIL_CLOSED`
- runtime6_exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r6-4c8e1d7a`
- runtime7_success_gate: no supervisor interruption；controlled health；exact parent `commandLine+cwd+runId+uid+session+startTicks`；exact listener `uid+java+argv+cwd+ancestor+socket+startTicks` + `EXACT_CANDIDATE_FOUND`；`listenerProofEverEligible=true`；`termDispatches=1`；`dispatchSafe=true`；normal exact `EXIT_128`；schema-v4 redacted `CLEANED/SIGNAL + HOLD_SIGNAL_RECEIVED + HEALTH_READY + NOT_APPLICABLE`；private absent；root residue 0；Docker 0/0/0；exact reservation absent。
- runtime7_evidence_boundary: 只允许 fixed-enum/redacted stdout、fixed-schema projection、root receipt 固定字段、private-absent、root residue count、redacted Docker counts 与 exact-reservation-absent；禁止读取 `private/children/log/profile/payload/process/Docker` 详情、手工 cleanup、重试、换 ID、共享 `8112`、真实 TMS/SIM/凭据/Worker/Gateway/Pool/Identity/Codex/external/production。disposable child 的 `NAVIGATOR_EXTERNAL_ENABLED=true` 仅为 loopback Open API route gate，`NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED=false`。
- runtime9_postterm_offline_gate: PASS；目标安全套件 `88/88`、synthetic TypeScript `112 passed / 1 skipped`、Python supervisor `97/97`，typecheck、shell/Python syntax、diff check、secret scan 与 test-owned Java residue check 均通过。该结果不满足 AC-2/AC-3，也不授权 Runtime 10。
- runtime9_postterm_review_status: PASS；code/security、test/runtime-readiness、canonical/docs 三路独立只读复核均无阻断。
- runtime10_freeze_status: CONSUMED_SUCCESS
- runtime10_exact_run_id: `int001-bug009-20260722-r10-9047a550`
- runtime10_exact_command: `PYTHONDONTWRITEBYTECODE=1 python3 tools/navigator-upstream/scripts/synthetic-upstream-forced-signal-supervisor.py --run-id int001-bug009-20260722-r10-9047a550`
- runtime10_current_authorization: none；exact command 已执行一次并 exit `0`，projection `SUCCESS_GATE_MET`，receipt `CLEANED/SIGNAL`，private/root/reservation/Docker 为 absent/0/absent/0-0-0。不得 retry、替换、手工 cleanup 或读取受限详情。
- runtime10_durable_record: `test-records/BUG-009-int001-runtime10-success-2026-07-22.md`
- next_action: 对 BUG-010 修复后的 BUG-009 执行独立 re-signoff；无需且不得重跑 Runtime 10 或创建替代 runtime。INT-001 仍保持 rejected，除非后续单独签核改变该状态。

P1A 的历史 [首次签核](./evidence/GOV-001-p1a-independent-signoff.md) `rejected` 记录原样保留；[BUG-002](./workitems/BUG-002-p1a-required-section-contract.md) 修复已通过 [独立 re-signoff](./evidence/BUG-002-p1a-required-section-independent-resignoff.md)，P1A foundation/shadow 状态为 accepted。P1B-A 新增五条 canonical typed-management auth route及 fixture-only credential/token verifier contract，现已通过 [独立签核](./evidence/GOV-001-p1b-a-independent-signoff.md)；它不 seed 或签发真实 SIM/TMS principal、grant、tenant authority 或 credential。P1B-B0 已通过 [独立签核](./evidence/GOV-001-p1b-b0-independent-signoff.md)：它只提供 synthetic/securely supplied inventory 的离线、无回显、无审批 classification，绝不读取真实 profile/secret/DB/network 或执行 seed。P1C-A 已通过 [独立签核](./evidence/GOV-001-p1c-a-independent-signoff.md)：它只使用 fixture、不能证明真实 S1/S2 principal 或 production readiness。[开发期 S1/S2 联调 MVP](./workitems/GOV-001-dev-s1-s2-integration-mvp.md) 已通过 [独立签核](./evidence/GOV-001-dev-s1-s2-integration-mvp-independent-signoff.md)：只覆盖动态 tenant list、CLI/手册 lane 边界和本地 preflight，不证明 real SIM/TMS runtime、Gateway external 或 production。Observer BFF 在 P1A 仍仅为 catalog/test-only，其 runtime shadow/audit 与 production hardening延后为独立设计，BFF 仍只能 local/trusted dev、production blocked。Owner 批准的 `GET /actuator` 第 415 条 route 和 200→404 唯一兼容例外继续有效。[INT-001](./workitems/INT-001-synthetic-upstream-integration-harness.md) 的 [独立签核](./evidence/INT-001-independent-signoff.md) 仍为 `rejected`。[BUG-009](./workitems/BUG-009-int001-forced-signal-owned-cleanup.md) 的 [既有独立签核](./evidence/BUG-009-independent-signoff-2026-07-22.md) 为 `rejected`；[BUG-010](./workitems/BUG-010-int001-process-group-empty-proof.md) 已离线修复该 PGID/descendant false-clean 缺口，BUG-009 现等待新的独立 re-signoff，且不得据此自动重新签收 INT-001。 [BUG-008](./workitems/BUG-008-openapi-upstream-physical-langgraph-identity-context.md) 通过 [独立签核](./evidence/BUG-008-independent-signoff.md) 为 `accepted-with-risks`：只修复 server-internal LangGraph physical identity owner-context 传播，不创建/改绑 Worker、BizWorkerIdentity、Pool member，也不涉及 Codex 或 Gateway。当前仍不包含业务数据 seed、真实上游联调、Gateway/external/prod enablement；`NAVIGATOR_EXTERNAL_ENABLED=true` 仍绝不表示 Provider、Worker Gateway 或 production ready。

## Workitems

| Workitem | Scope | Status |
|---|---|---|
| [NAVI-CORE-001 S2-04 CrossProject retirement handoff](./workitems/NAVI-CORE-001-S2-04-cross-project-retirement-handoff.md) | 当前文档对齐 mutation 默认 410、owner-scoped GET、旧 UI redirect 与仓库外 Skill tombstone owner handoff | HANDOFF_READY；服务端 gate 独立生效，外部 Skill/marketplace 未在本 workitem 修改 |
| [ARCH-001 Unified Session and Task Lifecycle Owner](./workitems/ARCH-001-unified-session-task-lifecycle-owner.md) | Codex SDK MVP-A 的 Session/Task/termination/Worker 分层 owner、Worker v1、offline freeze、Sentinel 与 deterministic reducer | APPROVED；Round 7 独立复审 0/0/0，Source Slice 0–8 可按顺序实现；真实 controller/process、首次非 fixture ENFORCED 与 live SIM 仍需单独授权 |
| [ARCH-001-ACT-002 Bounded Local-Development Lifecycle Activation](./workitems/ARCH-001-ACT-002-bounded-local-development-activation.md) | 当前 8112 与既有隔离 WSL 3151 的显式、一次性、默认关闭 activation authority | READY_FOR_SIGNOFF；不创建 Task、不调用模型、不发送 termination |
| [GOV-001 开发期 S1/S2 联调 MVP](./workitems/GOV-001-dev-s1-s2-integration-mvp.md) | SIM 专属实例和 TMS 平台/租户的本地联调路径；动态 tenant ClientApp list、CLI lane 提示、runtime-only 交付手册 | ACCEPTED；仅本地 preflight，非 live/runtime acceptance |
| [GOV-001 上游权限体系与多场景信任边界](./workitems/GOV-001-upstream-permission-and-trust-boundary.md) | 历史权限模型基线、S1 最终 instance root、S2 最终 SaaS platform/tenant 分层及 S3 默认拒绝边界 | 已接受基础切片保留；宽泛 P1B-B/P2/P3/P4 为当前开发期 MVP `NEEDS_REPLAN` / deferred |
| [GOV-001 P1B-B/P2/P3/P4 Owner Decision Intake](./workitems/GOV-001-owner-decision-intake-adr-packet.md) | 具名责任人、脱敏事实、架构/生产/迁移决策及 immutable approval reference 占位 | `PENDING_OWNER_INPUT` / deferred；不解除任何 `DRAFT + BLOCKED` gate |
| [GOV-001 P1C-A CLI/SKILL 权限可见性](./workitems/GOV-001-p1c-a-cli-skill-operator-ux.md) | typed-management-only whoami/permissions/non-binding explain、三态 config check、help/FAQ/runbook、canonical-manifest-derived input guard/provenance | ACCEPTED; no route cutover or release |
| [GOV-002 TMS SaaS CLI 三 Lane 对齐](./workitems/GOV-002-tms-saas-cli-lane-alignment.md) | `platform`、`app`、`runtime` 命令模型、legacy 兼容迁移和 platform-control / tenant-runtime profile 隔离 | READY_FOR_SIGNOFF；不包含真实 `SAAS_PLATFORM` seed/lifecycle 或发布 |
| [GOV-003 S1 system-admin ClientApp scope 管理](./workitems/GOV-003-s1-system-admin-clientapp-scope.md) | system-admin 以显式 target ClientApp 管理同 upstream 的 ClientApp-owned control-plane 资源 | ULTRA_EXECUTING；不改变 typed authority、Worker 或真实 runtime |
| [REL-001 Navigator Upstream CLI 1.0.22 发布](./workitems/REL-001-navigator-upstream-cli-1.0.22.md) | GOV-002 三 lane CLI 的双平台 archive、OBS installer、commit/push 与 SIM handoff | READY_FOR_SIGNOFF；不改变后端、Worker 或真实 upstream runtime |
| [REL-002 Navigator Upstream CLI 1.0.24 safe-ask 发布](./workitems/REL-002-navigator-upstream-cli-1.0.24-safe-ask.md) | BUG-016 safe-ask、request-scoped BusinessFunction 空集合与 POSIX profile `0600` 的 official OBS 发布及 SIM handoff | READY_FOR_SIGNOFF；1.0.24 official OBS 发布及公网安装验证通过 |
| [REL-003 Navigator Upstream CLI 1.0.39-SNAPSHOT typed termination 发布](./workitems/REL-003-navigator-upstream-cli-1.0.39-snapshot-typed-termination.md) | BUG-035 typed termination/reconciliation SDK、OBS CLI、当前 8112 部署与 SIM handoff | ACCEPTED；[签收记录](./evidence/REL-003-delivery-signoff-2026-07-30.md)，main/OBS/8112 均已完成并复核 |
| [BUG-002 P1A action required-section 合同缺失](./workitems/BUG-002-p1a-required-section-contract.md) | 修复 action-specific required-section catalog/context/validator 缺口和 runtime capability 误分类 | ACCEPTED |
| [BUG-007 task capability function-scope schema contract](./workitems/BUG-007-task-token-function-scope-schema-contract.md) | 对齐 task-scoped token 的 `function_scope_json` mapping/preflight/migration 契约，并重试本机 TMS runtime-only safe ask | ULTRA_EXECUTING；runtime credential file-safety gate BLOCKED |
| [INT-001 Synthetic Upstream Integration Harness](./workitems/INT-001-synthetic-upstream-integration-harness.md) | 可销毁、独立的 synthetic upstream runtime harness；用于本机发现/复现通用权限与运行时问题 | REJECTED；AC-2 forced-SIGNAL cleanup 为 `FAILED_CLEANUP/SIGNAL`，不替代真实 TMS/SIM 验收、Gateway external 或 production |
| [BUG-008 Open API upstream physical LangGraph owner context](./workitems/BUG-008-openapi-upstream-physical-langgraph-identity-context.md) | runtime-authenticated ClientApp 的 server-resolved upstream scope 进入 exact physical LangGraph identity lookup | ACCEPTED_WITH_RISKS；无 API/lane/Gateway/production 扩张 |
| [BUG-009 INT-001 forced-SIGNAL owned cleanup](./workitems/BUG-009-int001-forced-signal-owned-cleanup.md) | acceptance-found harness lifecycle defect；forced-SIGNAL cleanup 必须证明全部 run-owned process resource 清空 | READY_FOR_SIGNOFF；BUG-010 已补 PGID-empty fail-closed 证明，等待独立 re-signoff |
| [BUG-010 INT-001 process-group-empty cleanup proof](./workitems/BUG-010-int001-process-group-empty-proof.md) | 修复 BUG-009 拒签发现的 leader-dead / descendant-alive false-clean 缺口 | READY_FOR_SIGNOFF；仅离线 harness/test 修复，无 runtime 权限 |
| [BUG-013 Codex 待核验状态与终态后 SSE 异常](./workitems/BUG-013-codex-unverified-state-and-post-terminal-sse.md) | 将 `PROCESS_UNVERIFIED` 收敛为可查询状态卡，并阻止终态后的 SSE 错误触发恢复 | READY_FOR_SIGNOFF；需部署本提交后进行 live 验证 |
| [BUG-004 Codex 真实中止闭环与再次中止状态确认](./workitems/BUG-004-codex-cancel-execution-and-retry-confirmation.md) | SDK Worker 真实进程退出闭环；App Server exact thread/turn 状态检查与再次中止确认 | READY_FOR_SIGNOFF |
| [BUG-014 Codex SDK Worker 受控中止身份、重试与退出收口](./workitems/BUG-014-codex-sdk-termination-identity-and-reconciliation.md) | 解耦终止身份与 Gateway credential，修复未确认重试和 CLI 已退出后的 `ABORTED` 收口，并完成 SDK Worker 发版部署 | READY_FOR_SIGNOFF；Worker 1.0.19、目标部署与现场恢复已完成，等待独立签核 |
| [BUG-017 无 taskId runtime request audit](./workitems/BUG-017-runtime-request-audit-no-task-id.md) | ClientApp 自身 runtime-token/safe-ask correlation、短期脱敏阶段审计、无 taskId 查询与 CLI 1.0.25 发布 | READY_FOR_SIGNOFF；clean package、安装冒烟与全量测试已完成，等待独立签核 |
| [BUG-020 recovered task result 转发失败](./workitems/BUG-020-session-forward-recovered-task-result.md) | 将 completed task 恢复出的 synthetic result 核验并持久化为真实 assistant message 后转发 | READY_FOR_SIGNOFF；focused/backend reactor/frontend full baseline 已通过，待部署后 live smoke 与独立签核 |
| [BUG-021 Codex app-server Worker compact 协议收口](./workitems/BUG-021-app-server-compact-protocol.md) | 对齐 CLI 0.144.3 空 compact response 与 thread/item completion，恢复单实例池并发布 app-server Worker 0.3.24 | READY_FOR_SIGNOFF；现场恢复、failure-first 修复、完整 package/install/update/local-smoke、OBS 发布及远端逐字节复核均已完成 |
| [BUG-022 Codex app-server Worker 失败锁存恢复启动](./workitems/BUG-022-app-server-force-recovery-start.md) | 为 `stop.failed` 提供显式、精确身份约束的 force-kill-and-start 恢复命令，并发布 app-server Worker 0.3.25 | READY_FOR_SIGNOFF；focused/full package、正式候选 update/start/stop、本机升级及 OBS 远端逐字节校验均已完成 |
| [BUG-023 Chat POSIX 文件链接精确定位](./workitems/BUG-023-chat-posix-file-link-resolution.md) | 根内 POSIX/Windows 绝对 href 直接生成精确 File Browser deeplink，根外 fail closed；嵌套 Git 仓库搜索已修复并完成浏览器/API 回归 | ACCEPTED；[独立签收](./evidence/BUG-023-independent-signoff.md)通过，继续执行前端部署、Worker 升级与目标环境真实点击验收 |
| [BUG-029 Claude 从未注册任务取消收口](./workitems/BUG-029-claude-never-registered-cancel-convergence.md) | Worker 停机窗口内未注册且取消派发未确认的零进度任务，在三次明确 404 后安全收口 | READY_FOR_SIGNOFF；Java-only，部署后等待最多三轮调解 |
| [BUG-030 Claude 任务所有者强制中止](./workitems/BUG-030-claude-owner-force-cancel.md) | 所有用户可在二次确认后按自己的 taskId 强制中止，服务端解析并验证 Worker/PID/进程身份 | READY_FOR_SIGNOFF；Worker 0.1.13 已发布，待目标安装及 Java/前端部署后完成 live destructive smoke |
| [BUG-031 clean runtime provenance 与 task-scoped caller token](./workitems/BUG-031-clean-runtime-provenance-and-task-scoped-caller-token.md) | clean embedded SCM/build provenance；task token 为 current caller authority 在 exact Navigator instance、单个 Task/function scope 上的短期受限委托 | READY_FOR_SIGNOFF；实现、迁移、部署和 focused/live 验证已完成 |
| [BUG-032 runtime Worker readiness 与 pre-acceptance failure 收敛](./workitems/BUG-032-runtime-worker-readiness-and-preacceptance-failure-convergence.md) | exact physical Worker/execution role 真实可用性检查；SDK Worker 接单前失败可信终态与 token late-bind fail-closed | READY_FOR_SIGNOFF；离线 fail-closed、旧 Task 收敛及 live fixture Worker/model dispatch 已通过 |
| [BUG-033 Codex SDK Worker Windows installer 空输出崩溃](./workitems/BUG-033-codex-worker-windows-installer-null-output.md) | PowerShell 将 runtime dependency helper 的合法空输出视为 `$null` 并调用 `.Trim()` | READY_FOR_SIGNOFF；SDK Worker 1.0.29 已发布，Windows fresh install smoke 与远端逐字节复核通过 |
| [BUG-034 Worker 子进程 HOME 按执行 UID 回退与 OBS 发布](./workitems/BUG-034-worker-home-uid-fallback-and-obs-release.md) | Codex SDK/app-server、Claude、Gemini 与 LangGraph Biz Worker 在父环境缺失 HOME 时按有效 UID 对齐系统 home，并独立发布变更 Worker | READY_FOR_SIGNOFF；五个 OBS latest 已更新且 13 个归档逐字节复核通过 |
| [BUG-035 Open SDK typed termination/reconciliation contract](./workitems/BUG-035-open-sdk-typed-termination-reconciliation-contract.md) | 为 readiness、termination 与原 request-ID 只读 reconciliation 提供正式 typed SDK/服务端契约，同时保留旧 Map 与 legacy repair 兼容分支 | ACCEPTED；[签收记录](./evidence/BUG-035-delivery-signoff-2026-07-30.md)，release-only `1.0.39-SNAPSHOT` 发布由 REL-003 承接 |
| [BUG-036 Typed termination terminal convergence](./workitems/BUG-036-typed-termination-terminal-convergence.md) | 修复 Codex SDK Worker 首事件前取消竞态、accepted receipt 无界悬挂和 terminal cleanup gate 缺失 | READY_FOR_SIGNOFF；历史 Task 仅只读诊断，未重放或修改 |
| [BUG-038 Typed termination preflight rejection durable receipt](./workitems/BUG-038-typed-termination-preflight-rejection-receipt.md) | 修复真实 termination 在 mutable preflight/admission 拒绝发生于 durable attempt receipt 之前、终态误映射及 receipt 字段失真 | NEEDS_REPLAN；durable rejection 已验证，唯一 live 暴露普通 Task 缺少 ENFORCED lifecycle owner enrollment；未授权第二次 live/push/release |
| [FEAT-001 runtime binding/task read-only audit](./workitems/FEAT-001-runtime-binding-task-read-only-audit.md) | ClientApp runtime long-term credential 对 frozen binding 与既有 task durable 终态执行零 token、零 dispatch、零资源变更审计 | READY_FOR_SIGNOFF；CLI 1.0.26、server build、live zero-write audit 已完成，等待独立签核 |
| [FEAT-003 runtime task completion readiness](./workitems/FEAT-003-runtime-task-completion-readiness.md) | 基于 durable task、真实 Worker/provider 进程和脱敏 completion evidence 区分运行中、注册残留与完成候选 | READY_FOR_SIGNOFF；Worker 1.0.25、CLI 1.0.34 和 8112 launcher clean release/deploy 已完成，live stale-registration/process-absence、快速终态失败、自然完成 V2 durable receipt 与零副作用证据均通过 |

## Scenario Sequence

| Scenario | Description | Status |
|---|---|---|
| S1 `foggy-world-sim` | SIM 主体直接拥有绑定 Navigator 实例的完整控制面权限，可管理实例内全部 upstream、tenant、ClientApp 及自有/非自有资源；ask 执行仍受 Agent/runtime/task 权限限制 | aligned |
| S2 `tms-x3` | 共享 Navigator 中的自有多租户业务 SaaS；TMS 主体在自身 upstream 范围拥有完整管理权并拆分 provisioning/security-admin 凭据；租户默认仅持有受限 runtime credential | aligned |
| S3 外部第三方 | 外部非公司主体接入 Navigator；默认不可信、最小权限，仅作为架构设计考虑，当前不提供 onboarding 或业务实现 | design-aligned / implementation-deferred |

## Global Constraints

1. `NAVIGATOR_EXTERNAL_ENABLED=true` 只表示 `/api/v1/open/**` 路由门禁开启，不表示 production ready、Provider ready 或 Worker Gateway external。
2. `NAVIGATOR_WORKER_GATEWAY_EXTERNAL_ENABLED` 只控制 Gateway 是否强制完整 Worker principal，不是网络外放开关。
3. 不得通过创建额外 Worker、BizWorkerIdentity 或 WorkerPool member 修复 Codex Physical Worker 路由问题。
4. ClientApp runtime credential 不得承担实例管理、跨 ClientApp 修复或 break-glass 职责。
5. 资源绑定、资源操作授权和资源所有权是三个不同概念；绑定不隐式转移 owner，但 `foggy-world-sim` instance root 可通过独立、显式、可审计动作转移 owner。
6. ask 的最终能力始终取 Agent/runtime grant、task capability、Worker route 和执行策略的交集，上游控制面权限不得自动下沉为任务权限。
7. S1 中 `foggy-world-sim` 拥有 Navigator 实例内全部控制面权限，包括管理实例内其他 upstream system、tenant、ClientApp 及其资源；其唯一授权域边界是绑定的 `navigatorInstanceId`。fail-closed、凭据分层、secret 不回显、审计不可由业务主体绕过/修改/删除、readiness 与 production gate 是所有主体均不可绕过的系统不变量，不是被扣留的权限；跨 sink tamper-evident 证明仍属于 production gate。
8. root 权限属于 `foggy-world-sim` 主体，并由独立、instance-scoped 的 `INSTANCE_ROOT` principal/credential 表达；不得复用普通 upstream-admin 或具名 ClientApp 作为 root，普通 ClientApp、runtime credential、task token 和 Worker credential 不自动继承。
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
21. `foggy-world-sim`、`tms-x3` 等正常接入默认一套上游连接 profile 只绑定一个 `navigatorInstanceId`；若上游自行接入多个 Navi 实例，目标实例选择、故障切换和多套凭据保管由上游负责，Navi 仅在各实例内分别签发、校验、轮换、撤销和审计实例绑定凭据。
22. CLI/SKILL 必须显式区分 S1 instance root、S2 SaaS platform、ClientApp control/runtime、task capability 和 Worker principal；CLI 的本地提示与 preflight 只用于解释和防误用，最终授权始终由服务端 fail-closed policy 决定。
23. S1 同一 instance-root 主体必须拆分 `INSTANCE_ROOT_CONTROL` 和 `INSTANCE_ROOT_SECURITY`；前者用于日常实例管理，后者只用于 owner transfer、delete/revoke、credential rotate/revoke、grant delegation、trust-root、recovery 和 production promotion 等高风险操作，不得合并为一把常驻万能 key。
24. S1 credential lifecycle 采用“本地兼容、生产严格”：internal-dev 长期凭据默认 180d、上限 365d，control 只可在显式 trusted-loopback profile 直用；production 长期凭据默认 30d、上限 90d，并强制短期 access token；security 在任何环境均须换取 action-bound、single-use 的短期授权，dev credential 不得被 production 接受。
25. S2 TMS 使用显式 `principalType=SAAS_PLATFORM`，同一平台主体拆分 `SAAS_PROVISIONING` 和 `SAAS_SECURITY_ADMIN` credential lane，并复用统一 credential/policy/audit 模型；legacy upstream-admin 不得自动提升为 SaaS platform 或 security-admin，只能经核验审批重新签发 provisioning。
26. S2 复用 S1 的 internal-dev/production 生命周期基线，并使用服务端 versioned `tenantScopeMode=UPSTREAM_OWNED` platform grant；provisioning 可创建/管理 TMS-owned tenant，迁入迁出/停用删除必须走 security-admin，跨 upstream transfer 还需 instance-root security。
27. canonical authorization contract 固定为 `navi.authorization.v1`；服务端维护唯一 `AuthorizationContext/PolicyDecision`、policy 和 action catalog，CLI/SDK/SKILL 不得各自实现第二套授权语义。
28. CLI 离线 `config check` 只能返回 `VALID|INVALID|UNVERIFIED`，不能返回 `ALLOW`；在线 `--explain-auth` 是 `nonBinding=true` 的 preflight，真实 mutation 必须重新 enforcement 并生成新的 decisionId。
29. `auth whoami` / `inspect permissions` 必须分开显示主体 `authorityCeiling` 与当前 credential 的 `effectiveCredentialActions`；多 key profile 逐 lane 显示，绝不做权限并集。
30. trusted-loopback、instance/environment binding、platform grant、owner 和 Worker route 均由服务端解析；本地 profile、URL、请求体或 legacy tenant 列表不能成为授权事实。
31. `NAVI_ADMIN_API_KEY` 及当前 `X-Navi-Admin-Key` header 只表示 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN`；已退役的 `X-Navi-Admin-Api-Key` 不得重新接受。新 typed management credential 不得与 legacy/control/runtime credential 同请求混用，多个 credential source 必须 fail closed。
32. `navigator-chat-observer-bff` 是独立部署面，不继承 launcher 的 `ExternalSurfaceGateFilter`；当前默认 `0.0.0.0:5181` 且无入站 observer session，只能作为 local/trusted dev tool，在会话、附件 capability、CSRF/origin 和网络策略闭合前不得进入 production。

## Evidence Boundary

- 本版本目录建立于 2026-07-18。
- 当前内容来自代码、配置、CLI help、专项 SKILL、1.4.2 文档及 #151/#152 issue 的只读调研。
- 2026-07-18 当前工作树复核确认：平台/Gateway 开关默认仍为 `false`，Java Gateway client 仍仅传播 task token，三类 Worker external profile 仍因执行策略 pending 而 fail closed，#151/#152 仍为 OPEN，CLI 1.0.18/1.0.21 漂移仍存在。
- 2026-07-18 已完成方案级 `navi.authorization.v1` 冻结：服务端 canonical context/decision、typed credential/token/platform-grant claim、CLI whoami/permissions/explain、离线三态检查与 legacy 不自动提升边界均已落档；尚无实现或运行证据。
- 2026-07-18 主流架构与复杂度复核结论为 `passed-with-complexity-guardrails`：S1/S2 的 instance/account admin、delegated SaaS admin、credential lane、capability token、ownership/binding 分离均符合常见 IAM/PAM/Zero-Trust 方向；P0.5 明确禁止通用 RBAC/ABAC DSL、全量 credential/token 物理合表和一次性全路由切换。
- 2026-07-18 P0.5 冻结了 typed management 的最小物理模型、lane API、legacy adapter、shadow/cutover/rollback 和治理责任；该日尚不构成实现授权，后续 gate 关闭与授权状态见 2026-07-19 记录。
- 2026-07-18 已完成原始 414 条 method-level route/action manifest 静态基线及其 [静态评审](./evidence/GOV-001-p0.5-method-route-manifest-review.md)：397 条 launcher MVC、12 条 Observer BFF MVC、1 条 WebSocket、4 条 Actuator family；244 条非 GET MVC 无未分类项。该 review 是历史 route-count/disposition 快照，不是当前 catalog、运行测试或 production readiness 证明。当前 manifest evidence 见同路径 [CSV](./evidence/GOV-001-p0.5-method-route-manifest.csv)，CrossProject retirement addendum 与 digest 见 [NAVI-CORE-001 S2-04 handoff](./workitems/NAVI-CORE-001-S2-04-cross-project-retirement-handoff.md)。
- 同次复核确认 Observer BFF 的 12 条入口均须 `LOCAL_TOOL_RESTRICT`；`NAVIGATOR_EXTERNAL_ENABLED` 只影响其后续下游 Navigator `/open` 请求，不能保护或 production-enable BFF ingress。
- 2026-07-19 已完成 [seed/legacy mapping 静态复核](./evidence/GOV-001-p0.5-seed-legacy-mapping-review.md)：没有任何 legacy record 可自动提升；upstream-admin 唯一合法 canonical adapter 为 `UPSTREAM_SYSTEM_ADMIN + LEGACY_UPSTREAM_ADMIN`。未查询实际数据库或读取 secret，实际脱敏 inventory 固定为 P1B seed 前置。
- 2026-07-19 Project Owner 已确认 canonical contract，并只授权 P1A foundation/shadow；P1B typed seed/credential 与 P1C cutover/CLI/SKILL 仍需后续明确授权。
- 2026-07-19 编码交接前复核确认：源码仍为证据基线 `1ebe435fd0048024380be875478303d265c68791`，平台/Gateway 开关默认仍为 `false`，Java Gateway client 仍只传播 task token，三类 Worker external profile 仍因执行策略 pending 而 fail closed，#151/#152 仍为 OPEN。
- 2026-07-19 Ultra P1A 实施后的只读框架复核发现：Spring Boot 3.4.2 默认启用 actuator discovery，当前非空 `/actuator` base path 注册 `GET /actuator` discovery-links ingress；当时冻结 manifest 只有四个 Actuator family、共 414 条且未登记该入口，因此形成 route-manifest/兼容性待决项。
- 2026-07-19 Project Owner 已同时批准该入口登记和关闭 discovery-links：当前 manifest 为 415 条（5 条 Actuator family）。BUG-002 required-section amendment 后 source/evidence 的当前 SHA-256 为 `ef4c32ac4ca25ee695dff7bacd9845301266807d71fbcafe35ebba4872aadc7d`。配置关闭 `GET /actuator` discovery-links，接受其从 HTTP 200 links 响应变为 404 的唯一兼容例外；子 Actuator endpoint 不因此改变。该决策解除该 route-manifest blocker，P1A 继续执行，不构成 external、Gateway 或 production readiness 信号。
- 2026-07-19 [P1A 首次独立签核](./evidence/GOV-001-p1a-independent-signoff.md) 结论为 `rejected`：P1A-3 明确失败，manifest 无 required-section 声明且所有 20 个 `runtime.*` action 被启发式要求 capability，违反“仅 ask 增加 capability intent”的冻结合同；P1A-6 当时因 Observer BFF 无 runtime shadow/audit 判为 partial。Project Owner 随后批准 [BUG-002](./workitems/BUG-002-p1a-required-section-contract.md) 并将 Observer BFF 的 P1A 义务修订为 catalog/test-only；BUG-002 修复已通过 [独立 re-signoff](./evidence/BUG-002-p1a-required-section-independent-resignoff.md)，当前 BUG-002 与 P1A foundation/shadow 均为 accepted。该签核时 P1B 尚未启动；后续 P1B-A、P1B-B0 与 P1C-A 的 accepted 状态见本 README 的 Current Status 和各自 work item。
- 用于发现 actuator discovery 缺口的本轮只读框架复核没有执行测试、启动或重启服务，也没有修改代码、数据库、凭据、资源绑定、Worker 路由或 external 配置。
- 1.4.2 的历史测试与 issue 现场记录只作为设计输入，不作为 1.4.3 已实现或已验收证据。
