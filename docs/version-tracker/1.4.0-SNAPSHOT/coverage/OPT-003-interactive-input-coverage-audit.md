---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.4.0-SNAPSHOT
target: OPT-003 Codex app-server interactive input
status: reviewed
conclusion: ready-for-acceptance-with-gaps
reviewed_by: Codex
reviewed_at: 2026-07-11
follow_up_required: yes
production_enablement: not-approved
---

# Test Coverage Audit

## Background

本审计核对 OPT-003 的协议、状态、恢复、防重、隐私和 PC 体验是否有可复核证据。结论仅覆盖固定 CLI `0.144.1` 的隔离环境。

## Audit Basis

- [Workitem](../workitems/OPT-003-codex-app-server-interactive-input.md)
- [Implementation quality gate](../quality/OPT-003-interactive-input-implementation-quality.md)
- [Worker/live evidence](../evidence/OPT-003-ultra-native-input-v1.json)
- [PC evidence](../evidence/OPT-003-pc-interactive-input-v1.json)

## Coverage Matrix

| Requirement | Unit/contract | Integration | Live/E2E | Conclusion |
|---|---|---|---|---|
| native single-select same-turn response | Worker + Java + UI | HTTP/SSE/provider | ordinal `1` completed same turn | covered-isolated |
| multi-question structured response | protocol + UI | Java typed response | Target/Pace completed same turn | covered-isolated |
| `isOther` / free-text wire shape | protocol + UI | serialization | model emitted `isOther`; pure freeform not forced live | covered-with-gap |
| secret answer non-persistence | store/event/log tests | sanitized projection | model did not emit pure secret prompt | covered-with-gap |
| exact request/turn/runtime affinity | Worker + Java | response routing | duplicate/stale/restart paths rejected | covered-isolated |
| once-only response | Worker + Java | HTTP/SSE compensation | duplicate response rejected | covered-isolated |
| active turn resume rejection | Worker + Java + UI | provider/session | `continue` created no second task | covered-isolated |
| SSE disconnect/refresh semantics | store + frontend | status/snapshot | close/reopen restored same card | covered-isolated |
| request channel lost on restart | recovery tests | persisted task recovery | terminal `USER_INPUT_CHANNEL_LOST`; stale answer rejected | covered-isolated |
| command/file/unknown request denial | runtime tests | allowlist | no capability expansion | covered-isolated |
| desktop and 320px experience | component tests | Navigator integration | no overflow/overlap/errors | covered-isolated |
| release artifact | full Worker suite | schema/typecheck/build | `0.2.0` archive verified | covered-isolated |

## Evidence Summary

- Worker: `215 total / 208 passed / 7 platform-skipped / 0 failed`; schema digest、typecheck、build 和 `package:release` 通过。
- Worker `0.2.0` archive: SHA-256 `03949845DE8C405E1CC679D5DE5FB7F2AE86734C13C16E63102BC150A003343E`, `1,634,838` bytes, `176` entries。
- Maven reactor `mvn -pl addons/codex-worker-agent -am test`: Session `306/306`、Codex addon `276/276`；Claude answer normalization `2/2`。
- Frontend: foggy-chat `100/100`、Navigator `196/196`、Mobile `40/40`；chat-core/chat/Navigator builds 通过。
- 直连 Worker 验证了单选、数字回复、重复回复、终态 resume、多问题、SSE 断开、活动 thread 冲突和进程重启 channel-loss。
- Java-SSE-PC 任务 `20260711-0847` 完成单选，任务 `20260711-b814` 完成多问题；后者在等待输入时关闭并重开浏览器，问题卡恢复，误发 `continue` 未新增任务。
- 320px 检查 document/body 均为 `320`，问题卡与抽屉 handle 无重叠；console/page/request error 均为 `0`。

## Gaps

- gap 1: 固定 CLI 的模型侧工具未生成纯 secret/freeform-only 请求；当前证据是协议、持久化和 UI 自动化，不是完整 live model 闭环。
- gap 2: `experimentalApi` 行为可能随 CLI 升级变化；仅证明固定 `0.144.1` 和当前 schema digest。
- gap 3: P3 外部生产仍为 0/50 terminal task、0/72h、0/2 rotation；隔离证据不得计入。
- gap 4: Windows package 首次内嵌测试有两项 process-settle 时序波动，独立 full 和 release 重跑通过；生产前仍应观察生命周期稳定性。

## Recommended Next Skills

- 隔离核心场景没有阻断项，可带上述 gap 进入 `foggy-acceptance-signoff`。
- CLI 升级或生产缺陷使用 `foggy-bug-regression-workflow` 补失败测试和真实证据。

## Conclusion

- conclusion: ready-for-acceptance-with-gaps
- core_interactive_flow: covered-isolated
- disconnect_and_duplicate_guard: covered-isolated
- pure_secret_freeform_live: gap-nonblocking-for-current-cli
- can_enter_feature_acceptance: yes-isolated
- production_enablement: not-approved
- follow_up_required: yes
