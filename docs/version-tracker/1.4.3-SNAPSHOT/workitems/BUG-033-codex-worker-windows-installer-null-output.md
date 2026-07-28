---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-033-codex-worker-windows-installer-null-output
status: ULTRA_EXECUTING
canonical: true
execution_mode: ultra
assurance_level: standard
approved_by: user
approved_at: 2026-07-29
open_questions: []
---

# Delivery Spec: Codex SDK Worker Windows installer null output

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 Codex SDK Worker Windows 安装器空输出崩溃修复、1.0.29 发布边界与验收证据。
- canonical_path: docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-033-codex-worker-windows-installer-null-output.md

## Goal

- version_goal: 发布 Codex SDK Worker `1.0.29`，恢复 Windows 首次安装及无需保留较新 SDK 的升级路径。
- target_outcome: Windows installer 将 runtime dependency helper 的空输出视为“无需保留”，继续完成 `npm ci`，而非对 PowerShell `$null` 调用 `.Trim()`。
- critical_outcomes:
  - Windows 首次安装以及旧 SDK 不高于候选版本的升级不再在依赖安装前崩溃。
  - helper 非零退出仍然 fail closed，不得被空输出兼容逻辑吞掉。
  - 1.0.29 三平台产物、OBS bootstrap、manifest 与远端 archive 身份一致。
- success_is_sufficient_when: targeted regression、SDK Worker 全量测试/typecheck/build、全平台 full release smoke、commit/push 和 OBS 远端逐字节复核均通过。

## Scope

- in_scope:
  - `tools/codex-agent-worker` Windows release installer 的空输出处理。
  - 稳定自动化回归，覆盖 helper 成功但无 stdout 的 PowerShell 语义，并约束退出码检查顺序。
  - SDK Worker 版本提升至 `1.0.29`，三平台打包与 official OBS 发布。
  - 本 work item 与 1.4.3 版本索引中的证据回写。
- affected_modules:
  - `tools/codex-agent-worker`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: npm registry、GitHub `origin/main`、Codex Worker OBS release prefix。

## Non-Goals

- out_of_scope:
  - 不改变 runtime dependency 单调升级策略或 `@openai/codex-sdk` 版本。
  - 不修改 Codex app-server Worker、Java backend、frontend 或其他 Worker。
  - 不升级、重启或部署任何已安装的 Worker 实例。
- do_not_touch:
  - 用户 `.env`、credential、Codex home、termination ledger 与运行中进程。
  - app-server Worker 独立版本及 OBS prefix。
- non_blocking_or_waivable_items:
  - 不执行 live model query、付费调用、目标机器安装或 production soak。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 在检查 helper 退出码后，将无 stdout 规范化为空字符串。 | 空输出是 helper 的合法“无需保留”结果。 | 非零退出必须继续终止安装。 |
| 保持 helper 本身的空输出协议不变。 | Shell installer 和既有调用方已使用该协议。 | 修复仅限 PowerShell 消费端。 |
| 只发布 SDK Worker 1.0.29。 | 变更不涉及 app-server Worker。 | 两类 Worker 版本和 OBS prefix 保持独立。 |
| 使用 full smoke 并发布最终提交对应产物。 | installer/release tooling 属于高风险发布路径。 | 不以 `--skip-verify`、dirty 或 unpushed 例外发布。 |

## Acceptance Criteria

- [ ] AC-1: helper exit `0` 且 stdout 为空时，Windows installer 得到空字符串并继续进入依赖安装。
- [ ] AC-2: helper 非零退出时，Windows installer 仍输出明确错误并以非零状态停止。
- [ ] AC-3: helper 返回较新已安装 SDK 版本时，既有保留逻辑不变。
- [ ] AC-4: `package.json`、`package-lock.json` 与发布物版本均为 `1.0.29`。
- [ ] AC-5: SDK Worker focused/full tests、typecheck、build 与三平台 full release smoke 实际运行通过。
- [ ] AC-6: 修复提交已推送，OBS `latest.json` 指向 `1.0.29`，三个 archive 的长度和 SHA-256 及两个 bootstrap、release evidence 均与本地最终候选一致。
- [ ] AC-7: app-server Worker 未发布，且未操作任何已安装 Worker 实例。

## Contract / Data / Security Constraints

- API or event contract: 无 HTTP、SSE、task 或 runtime API 变更。
- data and migration: 无业务数据迁移；安装器继续保留 `.env` 与 termination ledger。
- compatibility and rollback: 保持 1.0.28 archive 不变；1.0.29 作为新 immutable release 发布，远端 pointer 最后更新。
- permissions and secrets: 不读取或输出 `.env`、OBS credential、token 或 Codex auth 内容；发布使用既有本机安全配置。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/2/3 | must-pass | major | failure-first focused regression；Windows 可用时执行 PowerShell 行为测试，否则以可执行表达式测试加 source contract 覆盖 | 既有 helper unit tests | 精确命令、测试计数与结果 |
| AC-4/5 | must-pass | major | `npm test`、`npm run typecheck`、`npm run build`、`npm run package:release -- --platform all --smoke auto` | package scripts | smoke-result、archive checksums |
| AC-6 | must-pass | major | clean/pushed HEAD 发布；publisher remote verification；独立 HTTP 回读 | publish tooling | commit、latest manifest、远端 hash/bytes |
| AC-7 | must-pass | minor | diff/release target review | git diff/status | 未发布模块和未操作实例声明 |

## Validation Budget and Evidence Sufficiency

- assurance_level: standard
- lightweight_validation: focused regression、version一致性、`git diff --check`，单项预期 `<5m`。
- medium_validation: SDK Worker 全量 test/typecheck/build、全平台 package/full smoke 与 OBS publish/verify，单项预期 `5-30m`。
- expensive_validation: none。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none；本次仅执行 SDK Worker affected lane。
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: not-requested
- decision_if_not_approved: proceed-with-focused-and-affected-validation
- expensive_validation_trigger: none
- maximum_expensive_attempts: 0
- reusable_evidence: helper unit tests、release archive structural validation、publisher remote verification。
- stop_when_evidence_is_sufficient: AC-1 至 AC-7 均有最终提交对应的通过证据且 OBS pointer/bytes 已复核。
- validation_not_required: Java/frontend reactor、live model、目标 Worker 安装、production soak、app-server package。

## Waiver Policy

- waivable_items: live target install、付费模型调用与 production soak。
- authorized_role: user/owner
- non_waivable_guards: regression、version一致性、clean pushed source、archive integrity、remote pointer/bytes verification。
- required_risk_record: 若当前环境无法执行原生 Windows PowerShell，必须记录静态/条件执行测试边界；不得将其描述为真实 Windows install smoke。

## Bug Context

- bug_source: user-report
- severity: major
- environment: Windows PowerShell 5.1、Node.js `v20.19.4`，从 OBS `install.ps1` 将 SDK Worker `1.0.17` 升级到 `1.0.28`。
- current_behavior: runtime dependency helper exit `0` 且无 stdout 时，PowerShell 捕获值为 `$null`；installer 第 173 行调用 `.Trim()` 后以 `InvokeMethodOnNull` 终止。
- expected_behavior: 合法空输出规范化为空字符串，跳过较新 SDK 保留分支并继续安装 runtime dependencies。
- reproduction_steps: 在无已安装 `@openai/codex-sdk`，或已安装版本不高于 release lockfile 的 Windows 安装目录执行 1.0.28 `install.ps1`。
- reproduction_status: confirmed
- existing_evidence: 用户错误日志；OBS 1.0.28 Windows zip SHA-256 `7d7b25d63bb1ea16b46f2e10fbcc68ffe9f082901aafb5dbc852e5aecb843771`；解包源码与仓库第 173–176 行一致；helper 已确认 exit `0` 且 stdout 为空。
- existing_tests: helper SemVer tests 与 installer source-order assertion存在，但未执行 PowerShell 空输出语义。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - Linux 发布主机不能替代真实 Windows 安装执行；回归需最大化覆盖 PowerShell 表达式与 source contract，并在证据中披露环境边界。
  - OBS `latest.json` 更新后会立即影响后续远程安装，因此必须最后发布 pointer 并完成远端校验。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和 `codex-worker-deploy` 技能。
- 在 scope 内自主决定具体测试结构，优先建立能复现 `$null.Trim()` 的失败回归后再修复。
- 如需改变目标、范围、runtime dependency 兼容策略、发布模块或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 未经用户明确批准，不得运行大型 authority/replay/full-chain；本次 SDK Worker affected-lane full smoke 已在批准发布范围内。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

> 由 Ultra 执行会话填写。

- implementation_summary:
- changed_paths:
- tests_and_results:
- manual_or_experience_evidence:
- deviations: none
- residual_risks:
- reused_evidence:
- omitted_validation_and_reason:
- readiness: READY_FOR_SIGNOFF | NEEDS_REPLAN | BLOCKED

## References

- requirement / issue: 2026-07-29 user report and approval to fix/publish 1.0.29
- architecture / glossary: `docs/worker-reinstall-and-upgrade-guide.md`
- related work items: `BUG-015-codex-runtime-monotonic-upgrades.md`
