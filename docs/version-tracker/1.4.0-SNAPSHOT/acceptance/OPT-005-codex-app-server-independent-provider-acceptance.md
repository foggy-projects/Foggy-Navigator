---
acceptance_scope: feature
version: 1.4.0-SNAPSHOT
target: OPT-005 independent Codex provider and OPT-006 Endpoint/Runtime integration
doc_role: acceptance-record
doc_purpose: isolated feature signoff; not a production routing approval
status: signed-off
decision: accepted-with-risks
production_enablement: not-approved
signed_off_by: Codex
signed_off_at: 2026-07-12
reviewed_by: Codex
blocking_items: []
follow_up_required: yes
evidence_count: 12
---

# Feature Acceptance

## Background

本记录签收 SDK Codex 与 Codex App Server 的独立 Backend、Provider、Session、Worker capability 及 OPT-006 Endpoint-synced Runtime 联合交付。它不改变旧 SDK Worker 的保留决定，也不批准 P3 生产路由。

## Acceptance Basis

- [OPT-005 requirement](../workitems/OPT-005-codex-app-server-independent-provider.md)
- [OPT-005 plan](../workitems/OPT-005-codex-app-server-independent-provider-plan.md)
- [OPT-006 workitem](../workitems/OPT-006-codex-app-server-endpoint-runtime-sync.md)
- [Implementation quality gate](../quality/OPT-005-codex-app-server-independent-provider-implementation-quality.md)
- [Coverage audit](../coverage/OPT-005-codex-app-server-independent-provider-coverage-audit.md)
- [Automated evidence](../evidence/OPT-005-independent-provider-verification-v1.json)

## Checklist

- [x] `OPENAI_CODEX` 只映射 `codex-worker`；`OPENAI_CODEX_APP_SERVER` 只映射 `codex-app-server-worker`。
- [x] 两 Provider 独立 discover、create、resume、status、SSE、cancel、delete，不互相 fallback。
- [x] Session 绑定 Provider 后不能跨 Provider resume；PC/Mobile 切换时创建新 Session。
- [x] SDK Task 只使用 SDK Thread/`SDK_EXEC`；App Task 固定 App Thread/Turn/runtime/revision/instance。
- [x] SDK Ultra fail closed；App Ultra 仅开放 capability 与授权共同支持的 Sol/Terra。
- [x] ModelConfig、Agent defaultModel、OpenAPI readiness 对实际 workspace Worker 和具体模型执行最终校验。
- [x] Endpoint Profile 是唯一 App endpoint/token 人工写源；Runtime 只由同步派生，无手工注册入口。
- [x] Endpoint token 加密、DTO 掩码、空 token、owner permission 和 Security route 均通过。
- [x] fingerprint/revision、Disabled/Dark、Draining、delete、archive/unarchive 与历史 affinity 通过。
- [x] Provider migration 在 MySQL 8.0.44/8.4.8 通过。
- [x] PC/Mobile Provider badge、模型目录、跨 Provider modal、Ultra 子任务和 desktop/320px 通过。
- [x] Java、两 Worker、PC、Mobile、Playwright、migration、Launcher package 和真实双链全部通过。
- [x] 双向故障 no-fallback、SSE 刷新恢复、隔离资源 cleanup 和 secret scan 通过。

## Evidence

- SDK task `20260712-e529`, session `4764849e-3236-4658-8db9-3ff0be3cd24f`, provider `codex-worker`, runtime `legacy-sdk:0d72d2a7@1`, type `SDK_EXEC`, exact result completed.
- App task `20260712-2b64`, session `799dbfb3-876d-4303-8b60-e382ce3a6b96`, provider `codex-app-server-worker`, runtime `appserver-8a5dac990f5e4e20b9b4b9f00aa6e646@1`, type `APP_SERVER`, exact Ultra result completed.
- Unified SSE used one browser connection and observed 29 relevant events for both tasks/sessions; console and page errors were zero.
- App Runtime Dark/disabled rejected creation before persistence and SDK session count stayed unchanged. Broken SDK endpoint produced a failed SDK task with no App binding; App Worker returned 404 for that task.
- Refresh screenshot preserves both `Codex SDK` and `Codex App Server` history badges. See [SDK](../evidence/opt-005-provider-fullchain-20260712-033210-be7f26ac/pc-sdk-chain.png), [App](../evidence/opt-005-provider-fullchain-20260712-033210-be7f26ac/pc-app-chain.png), and [reload](../evidence/opt-005-provider-fullchain-20260712-033210-be7f26ac/pc-provider-history-reload.png).
- Playwright screenshots cover model/provider split, cross-provider modal, Endpoint/Runtime and 320px layouts under `../evidence/OPT-005-*.png` and `../evidence/OPT-006-*.png`.
- `cleanup.json` proves all owned listeners/processes/container/state were removed; the existing 8112 process was untouched.

## Failed Items

- Isolated OPT-005/006 functional scope: none.
- Production P3 gate: not attempted and not accepted by this record.

## Risks / Open Items

- Production requires at least 50 terminal Ultra tasks, 72 hours, two rotations and release-owner approval.
- CLI `0.144.1` and schema digest remain compatibility pins; an upgrade requires a new Endpoint sync revision and reacceptance.
- Old cross-Provider Session/Thread recovery remains explicitly unsupported.
- Three browser subscribe/unsubscribe requests are cancelled by the intentional reload; SSE reconnect/snapshot succeeds and no application error is recorded.

## Final Decision

OPT-005 and the integrated OPT-006 scope are accepted with risks for isolated use. SDK and App Server now have independent provider identities, configuration sources, model capability checks, native session state and failure behavior. No fallback was observed or permitted. Production enablement remains not approved.

## Signoff Marker

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- acceptance_record: docs/version-tracker/1.4.0-SNAPSHOT/acceptance/OPT-005-codex-app-server-independent-provider-acceptance.md
- isolated_experience: accepted
- blocking_items: none
- production_enablement: not-approved
- P3_entry: not-approved
- signed_off_by: Codex
- signed_off_at: 2026-07-12
- follow_up_required: yes
