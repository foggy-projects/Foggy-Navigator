---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-021
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
assurance_level: standard
approved_by: user
approved_at: 2026-07-27
open_questions: []
---

# Delivery Spec: Codex app-server Worker compact 协议收口修复

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 Codex CLI `0.144.3` compact 完成协议、现场恢复、回归保护和 app-server Worker 独立发版的唯一交付契约。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-021-app-server-compact-protocol.md`

## Goal

- version_goal: 让 retained context maintenance 在 Codex CLI `0.144.3` 的真实 compact 协议下可靠终止，不再永久占用单实例 app-server 池。
- target_outcome: Worker 接受 `thread/compact/start` 的空对象响应，并依据同 thread 的 `thread/compacted` 或 `ContextCompaction` 完成 item 收口；现场遗留任务进入真实终态，目标安装使用独立 `CODEX_HOME` 重启；修复作为 app-server Worker `0.3.24` 发布。
- critical_outcomes:
  - 不依赖 compact response 中不存在的 `turn.id`，也不依赖 compact 专属 `turn/completed`。
  - 完成通知必须与请求 thread 精确关联；无关 turn/item/notification 不得误完成 compact。
  - 现场取消和重启只作用于重新核验属于 `/home/sa/.codex-app-server-worker` 的实例。
  - official OBS `latest.json`、archive、checksum 和 bootstrap 指向可验证的新版本。
- success_is_sufficient_when: failure-first 回归先失败后通过，完整 Worker gate、隔离候选安装/启动/health/local smoke、现场恢复和远端制品校验全部有精确证据。

## Scope

- in_scope:
  - 通过本地受控 Worker 终止链路取消任务 `20260727-6510`、`20260727-9d36`，并确认 durable terminal state；不使用已泄露平台 Bearer token。
  - 重新核验 listener PID、cwd、命令行、安装目录、Worker ID 和 health 后，只重启 `/home/sa/.codex-app-server-worker`；启动显式使用 `/home/sa/.codex-app-server-worker/codex-home`。
  - 对齐 Codex CLI `0.144.3` `ThreadCompactStartResponse={}`、`thread/compacted` 和 `item/completed` `ContextCompaction` 协议，同时保留必要的向后兼容和 fail-closed 关联。
  - 增加稳定自动化回归，覆盖空响应、两类完成通知、无关通知隔离、终止/超时处理和资源释放。
  - 将 app-server Worker 从 `0.3.23` 单调升级为 `0.3.24`，完成 unit/schema/typecheck/build/package、隔离 install/update dry-run、候选启动 health 和 documented `local-smoke`。
  - 从 clean、pushed commit 发布 app-server Worker OBS 产物并远端复核。
- affected_modules:
  - `tools/codex-app-server-worker`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: 本机 `/home/sa/.codex-app-server-worker` 安装、Codex CLI `0.144.3` schema、既有 OBS app-server Worker 发布根。

## Non-Goals

- out_of_scope:
  - 不修改、不停止、不重启、不升级 `/home/sa/.codex-worker` 3053 SDK Worker。
  - 不修改 Navigator Java、前端、SDK Worker或同级 `tms-x3`、`foggy-world-sim`、`foggy-data-mcp` 仓库。
  - 不升级 `@openai/codex`，不改变模型、认证、Gateway、pool size、任务路由或 public HTTP schema。
  - 不执行 50-task/72-hour production soak、付费 live model query 或跨项目 runtime 验收。
  - 不复述、复用或持久化已泄露 Bearer token；凭据轮换由环境 owner 单独完成。
- do_not_touch: `/home/sa/.codex-worker`、其他工作区 Worker、同级仓库、生产/外部 enablement、现有任务之外的业务状态。
- non_blocking_or_waivable_items: Windows 原生候选启动可由跨平台 package/installer tests 覆盖；Linux/WSL 候选启动和 health 不可豁免。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| compact start response 按 schema 视为无 payload 的 acknowledged request | CLI `0.144.3` 生成 schema 明确为任意空 object，现场亦返回 `{}` | 不再读取 `response.turn.id` |
| 优先接受同 thread 的 `thread/compacted`，并接受同 thread 的 `item/completed` + `item.type=contextCompaction` | 前者是已弃用但实际存在的通知，后者是 schema 推荐的新协议 | 任何其他 thread、item type 或普通 `turn/completed` 均不得结束 compact |
| compact operation 不再暴露伪造的 compact turn ID | 新协议没有可靠的 compact turn identity | HTTP `compact_turn_id` 保持可选；真实通知带 turnId 时可用于诊断，但不能作为启动响应前提 |
| 现场 Worker 必须使用独立 CODEX_HOME | 防止继承 `/home/sa/.codex` 的用户态配置、记忆或凭据 | `.env`/进程环境核验必须指向 `/home/sa/.codex-app-server-worker/codex-home` |
| 只发布 app-server Worker `0.3.24` | packaged runtime 变更只位于 app-server Worker | SDK Worker 版本与 OBS latest 保持不变 |

## Acceptance Criteria

- [x] AC-1: failure-first 测试证明旧实现会因 `{}` response 永久等待或协议失败；修复后 `thread/compacted` 与 `ContextCompaction` item 两条真实完成路径均成功，普通 `turn/completed` 和其他 thread/item 不会误完成。
- [x] AC-2: compact 的 abort、timeout、fatal 和 cleanup 继续 fail closed；完成后 active thread/runtime lease 被释放，同一单实例池可继续处理后续工作。
- [x] AC-3: 任务 `20260727-6510`、`20260727-9d36` 通过目标 Worker 受控取消链路进入 `ABORTED` 或等价真实终态，并有 Worker 本地 durable/read-only 证据；不使用泄露 token。
- [x] AC-4: 重新核验后的 `/home/sa/.codex-app-server-worker` 被单独重启，health 的 version/Worker ID/listener/cwd 与目标一致，进程环境中的 `CODEX_HOME` 精确为隔离目录；3053 SDK Worker 未被操作。
- [x] AC-5: `npm test`、schema verification、typecheck、clean build、`npm run package:release`、archive/forbidden-file/integrity 检查全部通过。
- [x] AC-6: `0.3.24` 候选完成隔离 fresh install、update dry-run、依赖安装、启动和 `/health`；documented `local-smoke` 以非生产证据模式通过或明确记录唯一外部环境阻断。
- [ ] AC-7: 从 clean、pushed commit 发布 OBS；远端 `latest.json` 为 `codex-app-server-worker 0.3.24`，archive bytes/SHA-256、checksum、installers 和 release evidence 与本地候选一致；提供下载链接和升级命令。

## Contract / Data / Security Constraints

- API or event contract: Worker HTTP compact endpoint和 operation 状态枚举不变；`compact_turn_id` 继续可选。内部 app-server completion correlation 扩展为 schema-defined compact notifications。
- data and migration: 无数据库或 durable journal schema 迁移；既有 `running` compact operation 在进程重启恢复为 `unknown` 的语义不变。
- compatibility and rollback: 可回滚到 `0.3.23`，但旧 compact deadlock 会恢复；发布禁止同版本覆盖。CLI pin 保持 `0.144.3`。
- permissions and secrets: 不输出 token、API key、auth.json、`.env` 内容或 CODEX_HOME 私有文件；只记录配置键是否符合、稳定 ID、路径和无秘密 health 字段。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/AC-2 | must-pass | critical | focused runtime tests、full unit suite | CLI 0.144.3 generated schema | failure-first output、test count、精确断言 |
| AC-3/AC-4 | must-pass | critical | local task state/termination、process ownership、health/env inspection | 已确认任务无 native identifiers 仅作诊断 | 脱敏终态、PID/cwd/listener/Worker ID/CODEX_HOME |
| AC-5 | must-pass | critical | unit/schema/typecheck/build/package | unchanged schema lock may be reused only after verify | commands、exit status、artifact report |
| AC-6 | must-pass | major | isolated install/update dry-run/start/health/local-smoke | package bytes reusable while source/version unchanged | install path、health/version、smoke report |
| AC-7 | must-pass | critical | commit/push、OBS upload、remote byte/hash verification | local deterministic archive | commit、version、bytes、SHA、URLs、latest manifest |

## Validation Budget and Evidence Sufficiency

- assurance_level: standard
- lightweight_validation: focused failure-first tests、schema inspection、typecheck、ownership/read-only checks，单项 `<5m`。
- medium_validation: full unit/schema/build/package、isolated install/start/local smoke、OBS publish/remote verify，单项预计 `5-30m`。
- expensive_validation: none expected；如单次 package/publish 因主机性能超过 30 分钟，最多两次且仅在源码、版本、依赖或候选字节改变后重跑。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none；用户已明确要求完整 Worker lane，但未授权 production soak。
- estimated_full_chain_wall_clock: 15-35 分钟，依据现有 Node unit/package/install gate 与 OBS 上传规模。
- full_chain_prerequisites: clean pushed release commit、可用 OBS 配置、目标进程归属已核验。
- user_approval_status: approved-for-worker-lane-only
- decision_if_not_approved: N/A
- expensive_validation_trigger: package、installer、startup、release source 或 artifact identity 改变。
- maximum_expensive_attempts: 2；连续两次因非产品环境失败则 `NEEDS_REPLAN`。
- reusable_evidence: CLI schema、未变候选 archive checksum、已通过且输入未变的 focused tests。
- stop_when_evidence_is_sufficient: AC-1 至 AC-7 均有可审查证据，远端字节核验完成，工作项更新为 `READY_FOR_SIGNOFF`。
- validation_not_required: Java/backend/frontend build、SDK Worker tests/publish、生产 soak、真实业务模型调用。

## Waiver Policy

- waivable_items: Windows native runtime launch；需保留跨平台 installer/package 自动化证据。
- authorized_role: project owner / independent signoff reviewer
- non_waivable_guards: process ownership、隔离 CODEX_HOME、compact correlation、Linux candidate health、artifact integrity、secret boundary。
- required_risk_record: 任何 smoke 外部阻断必须写明对 AC 的影响，不能描述为通过。

## Bug Context

- bug_source: user-report
- severity: critical
- environment: 本机安装 `/home/sa/.codex-app-server-worker`，旧版本 `0.3.22`；仓库基线 `0.3.23`；Codex CLI `0.144.3`；单实例池。
- current_behavior: `thread/compact/start` 实际返回 `{}`，真实完成由 `thread/compacted` 或 `ContextCompaction` item 通知；Worker 却要求 `response.turn.id` 并等待相同 ID 的 `turn/completed`，使 `retained_context_maintenance` 永久持有 runtime/pool。
- expected_behavior: 空响应只表示请求已接受；同 thread 的 schema-defined compact completion 通知结束 operation、发布相关 usage，并释放 runtime/pool。
- reproduction_steps:
  1. 对已有 thread 发起 retained context compact。
  2. CLI 返回 `{}`，约一分钟后发送 compact completion notification/item。
  3. 观察旧 Worker 未识别完成，operation/单实例 pool 持续占用，后续任务返回 `APP_SERVER_POOL_ACQUIRE_TIMEOUT`。
- reproduction_status: confirmed
- existing_evidence: 2026-07-27 10:15:21 发起、10:16:22 原生完成；任务 `6510`、`9d36` 无 app_server_instance_id/thread_id/turn_id 且持续 pool acquire timeout；CLI `0.144.3` generated schema。
- existing_tests: 当前 `app-server-runtime.test.ts` 伪造 `{turn:{id}}` 与 `turn/completed`，因此把错误假设固化为通过。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - `thread/compacted` 已标记 deprecated，必须同时覆盖 `ContextCompaction` item，避免下一次 CLI 兼容断裂。
  - compact notification 的 `turnId` 属于承载 compaction 的真实 turn，但 start response 不提供该 ID；只能在 completion 时采集，不能提前用于 interrupt。
  - 重启会把正在运行但未被旧 Worker正确识别的 maintenance operation 恢复为 `unknown`；任务取消终态必须独立核验。
  - 对话中曾泄露的平台 Bearer token 已持久化，环境 owner 必须轮换；本工作项不复用该凭据。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、app-server Worker README 和 `codex-worker-deploy` skill。
- 按 canonical 顺序执行：受控现场恢复 → failure-first test → implementation → focused/full gates → version/release commit → package/install/smoke → OBS publish/remote verify → evidence 回写。
- 在 scope 内自主决定具体函数和测试组织，但不得改变 CLI pin、HTTP contract、认证或 pool topology。
- 如需修改其他模块、使用泄露凭据、操作 3053 SDK Worker、升级 CLI 或运行 production soak，设置 `NEEDS_REPLAN` 并停止扩展。
- 完成后填写 `Implementation Result` 并设置 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: compact start response 仅作为 acknowledged request；Worker 缓冲同 thread 通知，并以 `thread/compacted` 或 `item/completed` 的 `contextCompaction` item 关联真实 completion turnId。普通 `turn/completed`、其他 thread 和其他 item type 均不能完成 compact；无完成通知的 abort 继续以 `APP_SERVER_COMPACT_ABORT_UNCONFIRMED` fail closed。
- changed_paths:
  - `tools/codex-app-server-worker/src/app-server/runtime.ts`
  - `tools/codex-app-server-worker/tests/app-server-runtime.test.ts`
  - `tools/codex-app-server-worker/package.json`
  - `tools/codex-app-server-worker/package-lock.json`
  - `tools/codex-app-server-worker/src/version.ts`
  - `docs/version-tracker/1.4.3-SNAPSHOT/README.md`
  - `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-021-app-server-compact-protocol.md`
- tests_and_results:
  - failure-first: `node --import tsx --test --test-name-pattern='manual thread compaction' tests/app-server-runtime.test.ts` 在旧实现下两项均因 `Codex app-server did not return a compact turn id` 失败；修复后 focused 三项通过。
  - full unit: `npm test`，tests `344`、pass `343`、fail `0`、skip `1`（Windows conditional）。
  - schema: `npm run verify:schema`，digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`。
  - static/build: `npm run typecheck`、`npm run build` 均通过。
  - package: `npm run package:release` 通过完整 unit/schema/typecheck/build、manifest、forbidden-file 与 archive integrity gate。
  - installer/update: 从 `0.3.24` archive 执行 fresh `install.sh` 成功；安装目录的 `update.sh --dry-run` 再次完成 `344/343/0/1`、schema、typecheck、build，且明确未修改当前安装。
- manual_or_experience_evidence:
  - 本地只读 status：`20260727-6510`、`20260727-9d36`、既有 `20260727-d6d4` 均为 `terminal/ABORTED`、`outcome=aborted`、`recovery_required=false`、termination `OBSERVED_EXIT`。
  - 目标安装重启后 listener 为 `3071`，cwd 为 `/home/sa/.codex-app-server-worker`，Worker ID `36508966`，instance ID `codex-store-5ea69ced-19e1-4c85-bb68-f8854a81d455`，CLI `0.144.3`，显式 `CODEX_HOME=/home/sa/.codex-app-server-worker/codex-home`；health ready，pool busy/queued 均为 `0`，`retained_context_maintenance=0`。
  - 3053 listener 始终属于既有 PID `791278`，未停止、重启或升级。
  - 隔离候选监听 `13072`，version `0.3.24`、CLI `0.144.3`、独立 CODEX_HOME、pool/retained 均为 `0`；documented `local-smoke` 标记 `NON_PRODUCTION_SMOKE` 且 gate `PASS`，terminal `2/2`、success `100%`、affinity/privacy leakage `0`。候选随后由自身 `stop.sh` 正常停止且无端口残留。
- release_evidence: 待 clean pushed release commit 后执行最终 `package:release -- --upload` 和远端字节复核；AC-7 尚未完成。
- deviations: none
- residual_risks: `thread/compacted` 已 deprecated，因此实现和回归同时覆盖当前 `ContextCompaction` item；未执行用户明确排除的 production soak 或真实付费模型查询。已泄露的平台 Bearer token 仍需环境 owner 轮换。
- reused_evidence: CLI `0.144.3` generated schema；相同源码/lockfile 下重复 gate 的 schema digest。
- omitted_validation_and_reason: 未执行 Windows native launch，由跨平台 operation/package tests 覆盖；未执行 Java/frontend/SDK Worker tests，均不在变更面。
- readiness: release upload and remote verification pending

## References

- requirement / issue: 用户 2026-07-27 明确批准 compact 协议修复、目标安装重启、现场任务取消和 app-server Worker 新版本发布。
- architecture / glossary: retained context maintenance、`thread/compact/start`、`thread/compacted`、`ContextCompaction`、single-instance pool。
- related work items: BUG-004、BUG-013、BUG-015。
