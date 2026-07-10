---
audit_scope: feature
audit_mode: pre-acceptance-check
version: 1.4.0-SNAPSHOT
target: OPT-001 P0-P2 local implementation checkpoint
status: reviewed
conclusion: needs-more-tests
reviewed_by: Codex
reviewed_at: 2026-07-10
follow_up_required: yes
production_enablement: not-approved
---

# Test Coverage Audit

## Background

- 审计目标：核对 P0 契约、P1 独立 Worker 和 P2 双 runtime 控制面的 requirement-to-evidence 映射。
- 结论边界：本地代码与隔离 smoke 有较强覆盖，但缺少 P1/P2 exit 和 P3-P7 所需的发布、全链、兼容、长稳与生产证据。

## Audit Basis

- implementation quality: `docs/version-tracker/1.4.0-SNAPSHOT/quality/OPT-001-p0-p2-implementation-quality.md`
- requirement/plan/progress: `docs/version-tracker/1.4.0-SNAPSHOT/workitems/`
- automated runs: Worker npm、Java Maven reactor、PC Vitest/type/build/Playwright、真实 MySQL 和隔离 app-server smoke

## Coverage Matrix

| Item | Risk | Unit/contract | Integration | Live/E2E | Coverage |
|---|---|---|---|---|---|
| 固定 CLI/schema/model-reasoning matrix | critical | yes | yes | direct Worker live | covered-local |
| 幂等 accept、same payload、409 conflict | critical | yes | yes | direct Worker live | covered-local |
| committed 后不 replay、terminal 不可逆 | critical | yes | yes | final live blocked by quota | covered-automated; live-refresh-open |
| durable abort 与精确 `threadId+turnId` interrupt | critical | yes | yes | historical live only | covered-layered; post-fix live-refresh-open |
| journal/ESN 截断、重复、缺口和重启恢复 | critical | yes | focused 46/46 | final hard-kill blocked by quota | covered-automated; live-refresh-open |
| pool capacity/reuse/drain/crash isolation | critical | yes | partial | historical short live only | partial; post-fix/soak open |
| Runtime Registry、owner/revision/CAS/affinity | critical | yes | Java reactor | no real full chain | covered-layered |
| remote delete、late event、Session lock | critical | yes | Java reactor | no real full chain | covered-layered |
| native subtask allowlist、snapshot/SSE/PC projection | critical | yes | mocked contract | mocked Playwright | covered-layered; real chain open |
| Runtime UI、Ultra preflight、retry/epoch/multi-pane | major | yes | mocked API | Playwright 2/2 | covered-layered |
| MySQL runtime/native migration与 backfill | critical | no | MySQL 8.4.8 clean schema | no production DB | partial |
| N-1 Java/Worker/PC 与 CLI/schema mismatch | critical | partial | no target deployment | no | gap |
| instance-aware 多副本/共享 store 路由 | critical | partial | no | no | gap |
| package/install/update/rollback artifact | critical | deterministic archive tests | Windows temp install/update | no running-service/POSIX live | partial |
| P3 Ultra production canary/SLO/rollback | critical | no | no | 0 task / 0h | gap |
| P4-P7 default、全 cohort、drain、retirement | critical | no | no | no | future-gate |

## Evidence Summary

- New Worker: 87/87 tests；`npm run typecheck`、`npm run build`、`npm run verify:schema` passed；schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`。
- Release: 0.1.0 ZIP 三次独立构建 SHA-256 均为 `59cf633a5781ee8adde28c3363342920f71131def1bfdde288d63233300ef5ea`；Windows 临时安装/原地更新、`.env`/state/external CODEX_HOME hash 保留、禁止项扫描和 Bash syntax 通过。
- Legacy SDK Worker: 115/115 tests；typecheck/build passed；新 Ultra fail closed、既有 SDK affinity drain 保留。
- Java reactor: `mvn -pl addons/codex-worker-agent -am test` BUILD SUCCESS；Codex addon 214/214，Session reports 302/302。
- PC: Vitest 159/159、type-check/build passed；两个 mocked-contract Playwright spec 2/2。
- Database: 两个 migration 在一次性干净 MySQL 8.4.8 rehearsal 中通过，valid provider state 保留、invalid state 修复、最新 task binding backfill 与索引/长度核对通过。
- Static review: `git diff --check` passed；新 Worker只有 README/test 提及 SDK，无 SDK dependency/import；未发现 reasoning wildcard。
- Final post-fix live boundary: health、CLI `0.144.1`、schema/matrix 与 HTTP idempotency `202/202/409` 通过；真实 provider call 在任何 `tool_use` 前被账号 usage limit 阻断。focused recovery/runtime/durability 46/46 通过；未执行伪造的 hard-kill marker 测试。

## Gaps

- gap 1: 尚无一条真实 `codex-app-server-worker -> Java -> unified SSE -> PC` 自动化，mocked UI 证据不能替代该链路。
- gap 2: 本地 Windows package/install/update 已验证；尚未在 POSIX/目标机验证真实安装，以及运行中 drain/restart/故障回滚。
- gap 3: 尚未验证 N-1、instance-aware 多副本或共享持久化恢复，以及生产 profile migration/`ddl-auto=validate`。
- gap 4: 未完成 approval、additional directories、interactive server request、Biz/MCP 等 P5 功能 parity。
- gap 5: P3 要求的 50 个 terminal Ultra task、连续 72 小时、至少 2 次实例轮换和 SLO/rollback 均为零证据。
- gap 6: 最终硬化后的全模型/native/abort/tombstone/pool 与真实 hard-crash/restart smoke 需在测试账号额度恢复后重跑；此前 live 证据不能替代本次 refresh。

## Conclusion

- conclusion: needs-more-tests
- local_code_checkpoint: sufficient
- can_enter_feature_acceptance: yes-for-blocked-decision-only
- can_enter_P3: no
- production_enablement: not-approved
- follow_up_required: yes
