---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-022
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: standard
approved_by: user
approved_at: 2026-07-27
open_questions: []
---

# Delivery Spec: Codex app-server Worker 失败锁存恢复启动

## Document Purpose

- intended_for: normal-analysis / ultra-implementation / independent-signoff
- purpose: 固定失败停止锁存场景下的显式恢复启动语义、进程身份安全边界、验证和发布要求。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-022-app-server-force-recovery-start.md`

## Goal

- version_goal: 改善本机 Codex app-server Worker 生命周期故障的可操作性，并发布新的官方 OBS 版本。
- target_outcome: 普通启动遇到 `stop.failed` 时打印可复制的恢复命令；用户显式选择强制恢复后，只终止精确快照绑定的旧 Worker 进程树，证明零残留并重新启动。
- critical_outcomes: 不误杀无身份快照的进程；未解析的 update/lifecycle/runtime 证据继续 fail closed；发布制品可安装且远端完整性可验证。
- success_is_sufficient_when: Linux/Windows 启动参数、提示、回归测试、完整 package gate、安装候选生命周期 smoke、OBS 远端校验和本机升级均通过。

## Scope

- in_scope: app-server Worker start 生命周期脚本、操作文档、回归测试、版本元数据、0.3.25 打包与 OBS 发布、本机安装升级。
- affected_modules: `tools/codex-app-server-worker`、本 canonical work item 与版本索引。
- external_dependencies: 本机 Node/npm、Codex CLI 0.144.3、OBS 发布配置和公网 OBS endpoint。

## Non-Goals

- out_of_scope: 修改 SDK Worker、Java 路由、任务终止 API、自动绕过更新事务、自动清除 runtime process-tree/lifecycle fallback 证据、生产 soak。
- do_not_touch: `/home/sa/.codex-worker` 及其他工作区 Worker；同级 TMS/SIM/Foggy Data MCP 仓库。
- non_blocking_or_waivable_items: 长时间 production canary/soak 不属于本次发布门槛。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 新增显式 `--force-kill-and-start` / `-ForceKillAndStart` | 用户需要一条可复制的恢复命令 | 默认启动仍保持 fail closed，不自动 kill |
| 强制恢复只使用持久化的精确进程树身份 | 防止 PID 复用或误杀无关进程 | 缺少/无效快照时拒绝强制终止 |
| update transaction、runtime process-tree 与 lifecycle failure 证据不由该参数清理 | 这些证据可能代表更广的未解析状态 | 继续要求独立 operator recovery |
| 发布仅包含 app-server Worker | SDK Worker 无改动 | 两个 Worker 版本保持独立 |

## Acceptance Criteria

- [x] AC-1: 普通启动遇到 `stop.failed` 时，错误输出包含当前平台可直接复制的显式强制恢复命令和破坏性说明。
- [x] AC-2: 显式强制恢复对精确快照进程树执行 KILL，验证零残留后清理主 Worker 生命周期证据并启动；仅有无快照 PID 时 fail closed。
- [x] AC-3: update transaction、runtime process-tree 或 lifecycle failure 证据存在时，即使传入强制参数也不得启动或清理这些证据。
- [x] AC-4: Linux/Windows 脚本契约、回归测试、schema、typecheck、build 和 release package gates 全部实际通过。
- [x] AC-5: 0.3.25 从 clean、pushed commit 发布到 app-server Worker OBS prefix，`latest.json`、archive、checksum、installers 和 release evidence 远端校验通过；SDK Worker 不发布。
- [x] AC-6: `/home/sa/.codex-app-server-worker` 升级到 0.3.25 后在配置端口 3071 稳定 `ready=true`，PID cwd、版本和 Worker identity 与目标一致。

## Contract / Data / Security Constraints

- API or event contract: 无 HTTP API/event schema 变化；仅新增本地 lifecycle CLI 参数和诊断输出。
- data and migration: 不迁移任务/事件 journal；保留 `.env`、state、CODEX_HOME 与 termination receipt ledger。
- compatibility and rollback: 旧无参数启动行为保持 fail closed；可用上一版 archive 按现有 updater/回滚流程恢复。
- permissions and secrets: 不读取或输出明文 token/key；OBS 凭据只通过用户本地 obsutil 配置使用。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/AC-2/AC-3 | must-pass | major | focused lifecycle script tests | process-tree helper tests | exact command and pass count |
| AC-4 | must-pass | major | `npm run package:release` | deterministic archive tooling | package output and gate summary |
| AC-5 | must-pass | major | clean/pushed publish and remote byte verification | publisher verification | latest manifest, URLs, SHA-256, bytes |
| AC-6 | must-pass | major | installed candidate update/start/health inspection | current local config | version, PID/cwd/listener/health |

## Validation Budget and Evidence Sufficiency

- assurance_level: standard
- lightweight_validation: focused Node tests and shell/PowerShell static contract checks, expected under 5 minutes.
- medium_validation: full package verification and installed-candidate update/start smoke, expected 5-30 minutes.
- expensive_validation: none automatically required.
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none; change is isolated to one Worker lifecycle package.
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: not-requested
- decision_if_not_approved: proceed-with-focused-and-affected-validation
- expensive_validation_trigger: none.
- maximum_expensive_attempts: 0
- reusable_evidence: package gate results remain valid unless Worker runtime/package inputs change.
- stop_when_evidence_is_sufficient: focused recovery regressions, full package gate, installed lifecycle smoke and publisher remote verification all pass.
- validation_not_required: Java/backend/frontend builds, SDK Worker package, production canary/soak, paid model task.

## Waiver Policy

- waivable_items: production soak only.
- authorized_role: owner.
- non_waivable_guards: exact identity validation, zero-residue verification, no secret exposure, clean/pushed release source, remote integrity verification.
- required_risk_record: any omitted installed smoke or remote verification blocks release completion.

## Bug Context

- bug_source: user-report
- severity: major
- environment: Linux install `/home/sa/.codex-app-server-worker`, configured port 3071, installed 0.3.22, repository baseline 0.3.24.
- current_behavior: `start.sh` reports a latched failed stop but gives no executable recovery command; operator must manually infer which evidence to inspect and remove.
- expected_behavior: output provides an explicit force recovery command, and that command safely kills only the exact recorded Worker tree before restarting.
- reproduction_steps: retain `logs/run/stop.failed` plus prior PID/snapshot, then run `./start.sh`.
- reproduction_status: confirmed
- existing_evidence: user console output and local latch reason `update_found_unresolved_worker_identity`; retained snapshot verified zero live identities.
- existing_tests: process-tree identity/kill tests and lifecycle script tests.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks: explicit force recovery can terminate active tasks; command must state this and require exact persisted identity. Runtime fallback evidence remains a separate manual recovery lane.
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和 `codex-worker-deploy` 技能。
- 在 scope 内自主决定具体文件和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 未经用户明确批准，不得主动运行预计超过 30 分钟的大型链路。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: Linux/Windows 启动脚本新增显式 force recovery 参数；普通 `stop.failed` 阻断输出可复制命令。强制恢复只处理精确快照绑定的主 Worker 进程树，验证零残留后清理主生命周期证据并启动，其他未解析证据继续 fail closed。app-server Worker 0.3.25 已发布并完成本机升级。
- changed_paths: `tools/codex-app-server-worker/start.sh`、`start.ps1`、`README.md`、`tests/force-recovery-start.test.ts`、版本元数据，以及本 work item/版本索引。
- tests_and_results: `bash -n start.sh stop.sh update.sh install.sh release/remote-install.sh` 通过；`npm run typecheck` 通过；focused lifecycle/process-tree suite 25 tests、24 pass、1 Windows skip、0 fail；clean/pushed `npm run package:release` 347 tests、346 pass、1 Windows skip、0 fail，schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`，typecheck/build/package 通过；正式 archive 的 installed-candidate `update.sh --dry-run` 同样 347 tests、346 pass、1 skip、0 fail。
- manual_or_experience_evidence: 正式候选安装在隔离端口 13073 完成 start/health/stop smoke，版本 0.3.25、CLI 0.144.3、active/queued 均为 0；本机 `/home/sa/.codex-app-server-worker` 原地升级后 PID `1527412`、cwd 为目标安装目录、监听 `0.0.0.0:3071`、`ready=true`、Worker ID `36508966`、runtime `codex-app-server-primary` revision 5、instance `codex-store-5ea69ced-19e1-4c85-bb68-f8854a81d455`，无残留 update/lifecycle/stop.failed 证据。
- deviations: none
- residual_risks: 当前 Linux 环境无 `pwsh`，Windows 脚本未做本机动态启动；参数、提示和安全分支由静态跨平台契约测试覆盖，Windows installer 与正式 archive 已通过发布器和远端逐字节校验。
- reused_evidence: 保留并复核既有本机 `.env`、state、Worker identity 和 instance identity；未复用旧 archive/package gate 作为正式发布证据。
- omitted_validation_and_reason: 未运行 production canary/soak 或付费模型任务；本次仅改 lifecycle CLI 和诊断，不改 runtime task/protocol 行为，按批准验证预算不要求。
- release_evidence: commit `38961bad85aacd7e905dc065c9f8a3d7bd14aea1` clean 且已推送；OBS archive SHA-256 `a8db28e653512a93be912459d22b2278e9147501250e59a4b5ca62aee70f2585`、bytes `2466151`；远端 `latest.json`、archive、sidecar、Linux/Windows installer 均回读一致；SDK Worker 未发布。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 2026-07-27 用户现场启动与发布请求。
- architecture / glossary: `tools/codex-app-server-worker/README.md` Operations / Release sections.
- related work items: `BUG-021-app-server-compact-protocol.md`。
