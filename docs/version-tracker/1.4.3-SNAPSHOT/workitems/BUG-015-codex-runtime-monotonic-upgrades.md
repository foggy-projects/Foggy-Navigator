---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-015-codex-runtime-monotonic-upgrades
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: user
approved_at: 2026-07-23
open_questions: []
---

# Delivery Spec: Codex Worker runtime dependency monotonic upgrades

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定两类 Codex Worker 在重装和本体升级时不得回退用户已安装 Codex runtime 依赖的兼容契约。
- canonical_path: docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-015-codex-runtime-monotonic-upgrades.md

## Goal

- version_goal: 1.4.3-SNAPSHOT 的 Codex Worker 发布物不会因其 package-lock 锁定版本而降级用户已升级的 Codex runtime；SDK Worker 发布为 `1.0.20`，app-server Worker 发布为 `0.3.23`。
- target_outcome: 重装、OBS 远程安装及 Worker 自更新只会保留或升级已安装的受管 Codex runtime 依赖；不得降级。

## Scope

- in_scope:
  - `tools/codex-agent-worker` release installer / self-update 路径中，对 `@openai/codex-sdk` 实施语义化版本单调保护。
  - `tools/codex-app-server-worker` install、local/remote self-update 路径中，对其实际受管依赖 `@openai/codex` 实施同等单调保护。
  - Linux/macOS shell 与 Windows PowerShell 安装路径、针对性自动化回归测试、操作文档。
  - SDK Worker 的 SDK-only updater 从 `update.*` 明确迁移为 `update-sdk.*`，不保留旧名入口。
- affected_modules:
  - `tools/codex-agent-worker`
  - `tools/codex-app-server-worker`
- external_dependencies: npm registry 中的 `@openai/codex-sdk`、`@openai/codex`。

## Non-Goals

- out_of_scope:
  - 不把 Worker 本体版本改为永远单调升级；现有 Worker 版本、安全校验与回滚行为保持不变。
  - 不自动把所有用户安装升级到 npm latest；仅在已安装版本高于发布包锁定版本时保留该版本。
  - 不改变 `CODEX_WORKER_AUTO_UPDATE_SDK` 的最低兼容修复语义。
- do_not_touch:
  - 不修改上游 Navigator Java 路由、用户密钥、`.env` 内容或持久化状态。
  - 不把 app-server Worker 错误地改为依赖不存在的 `@openai/codex-sdk`；其保护对象是 `@openai/codex`。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 已安装依赖版本高于候选 release 锁定版本时，候选 lockfile 必须提升到已安装版本后才执行依赖安装。 | 避免运行期或最终落盘版本发生回退。 | 使用 SemVer 比较；同版保留候选锁文件，候选更高则正常升级。 |
| SDK Worker 的 SDK-only 更新脚本统一命名为 `update-sdk.sh` / `update-sdk.ps1`；不保留 `update.sh` / `update.ps1` 兼容入口。 | 消除其与 app-server Worker `update.sh`（Worker 发布包更新）之间的语义歧义。 | `update-worker.sh` / `update-worker.ps1` 继续表示 SDK Worker 本体更新；`codex-worker upgrade-sdk` 改为调用新脚本。 |
| SDK Worker 保护 `@openai/codex-sdk`。 | 它是 SDK Worker 的直接 runtime 依赖。 | 仍必须满足该 Worker 声明的最低版本。 |
| app-server Worker 保护 `@openai/codex`。 | app-server Worker 当前不依赖 `@openai/codex-sdk`，而是直接运行 Codex CLI/runtime。 | 不引入 SDK Worker 的依赖到 app-server。 |
| 缺失、无效或无法比较的已安装版本不得阻断正常候选安装。 | 首次安装与损坏安装需可恢复。 | 只在可验证的已安装版本严格更高时触发保留。 |

## Acceptance Criteria

- [x] AC-1: SDK Worker 的 Linux/macOS 和 Windows release 安装/本体升级路径，在已安装 `@openai/codex-sdk` 高于 release 锁定版本时，最终安装版本仍为已安装的较高版本。
- [x] AC-2: app-server Worker 的 Linux 和 Windows install/update 路径，在已安装 `@openai/codex` 高于候选 release 锁定版本时，最终安装版本仍为已安装的较高版本。
- [x] AC-3: 两类 Worker 在候选锁定版本更高、相同、首次安装、缺失或无效已安装版本时保持原有可安装/可升级行为。
- [ ] AC-4: 新增稳定自动化回归覆盖版本比较和至少一个实际安装/升级脚本链路；受影响 Worker 的测试、类型检查和构建记录为通过。SDK Worker 全量测试被一条既有 `install-env` 环境断言失败阻断，详见 Implementation Result。
- [x] AC-5: 运维文档明确说明“保留或升级、绝不回退”的范围，以及 app-server 的保护对象为 Codex CLI/runtime 包。
- [x] AC-6: SDK Worker 的 source/release/installed SDK-only updater 均命名为 `update-sdk.sh` / `update-sdk.ps1`；`update.sh` / `update.ps1` 不再随 SDK Worker 存在或打包，`update-worker.*` 保持本体升级语义。

## Contract / Data / Security Constraints

- API or event contract: 无外部 HTTP API 变更。
- data and migration: 不迁移业务数据；不得删除 `.env`、logs、Codex home 或持久状态。
- compatibility and rollback: 继续使用 release 固有的完整性校验、候选验证、Worker 回滚与安装目录保护；包版本策略只提高候选 lockfile 中的受管 Codex runtime 依赖。
- permissions and secrets: 不记录或输出任何 credential、token、API key 或 `.env` 值。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1 | major | SDK Worker unit/regression tests、typecheck、build、release full smoke | 精确命令与结果；release smoke 证明候选安装可用 |
| AC-2 | major | app-server Worker installer/updater regression tests、test、verify:schema、typecheck、build、release package verification | 精确命令与结果；测试记录 |
| AC-3/4 | major | 高/同/低/缺失/无效版本分支测试 | 断言最终依赖版本与候选选择 |
| AC-5 | minor | 文档 review | changed path 与审阅结果 |

验证成本：针对性测试与 build 预计 <30 分钟；两个 release packaging/full smoke 预计 5–30 分钟。按 focused -> module test/build -> package/full smoke 顺序执行。若同一昂贵验证连续两次因环境而非产品失败，停止并标记 `NEEDS_REPLAN`。

## Bug Context

- bug_source: user-report
- severity: major
- environment: 通过 OBS `curl | bash` 重装或 Worker 自更新的 Linux/Windows 发布安装路径。
- current_behavior: release installer / updater 以候选 `package-lock.json` 执行 `npm ci`，会把用户已经升级的 Codex runtime 依赖降回发布包锁定版本。
- expected_behavior: 已安装可验证版本高于候选锁定版本时，最终落盘并启动的 runtime 依赖不低于已安装版本。
- reproduction_steps: 安装较新 `@openai/codex-sdk` 或 `@openai/codex`；运行锁定较旧版本的 Worker 重装或自更新；检查 `node_modules` 中安装版本。
- reproduction_status: confirmed
- existing_evidence: 2026-07-23 对线上 SDK Worker 1.0.19 检查：`npm ci --omit=dev` 锁定 `@openai/codex-sdk` 0.144.1，而 npm latest 为 0.145.0。
- existing_tests: SDK Worker `sdk-preflight.test.ts`；app-server `operations-upgrade.test.ts` 与 `install-defaults.test.ts`。
- regression_protection: required

## Risks and Open Questions

- known_risks: 较新的依赖可能与较旧 Worker 本体不兼容；该风险是用户明确选择的“不降级”优先级，Worker 现有健康检查和回滚仍适用。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和 `codex-worker-deploy` 技能。
- 在 scope 内自主决定具体 helper 与测试结构，但不得改变“只升级或保留”的版本策略。
- 如需改变保护对象、兼容策略、发布范围或新增外部凭证，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: 新增独立的 SemVer/runtime-dependency helper。SDK Worker 在 Linux/PowerShell release 安装前，将候选 lockfile 中较低的 `@openai/codex-sdk` 提升为已安装的较高版本后再执行 `npm ci`；其 source/release `upgrade-sdk` 还会先解析目标版本，拒绝显式降级。SDK-only updater 已从易混淆的 `update.sh` / `update.ps1` 迁移为 `update-sdk.sh` / `update-sdk.ps1`，旧入口未保留；Worker 本体仍由 `update-worker.*` 更新。App Server Worker 在 Linux/PowerShell staged update 验证前，以同一策略提升候选 `@openai/codex` lockfile 条目。两个 Worker 的发布包完整性清单均包含 helper。
- changed_paths:
  - `tools/codex-agent-worker/{release/install.sh,release/install.ps1,update-sdk.sh,update-sdk.ps1,release/update-sdk.sh,release/update-sdk.ps1,update-worker.sh,update-worker.ps1,scripts/runtime-dependency-version.mjs,scripts/release-archive.mjs}`
  - `tools/codex-agent-worker/tests/{runtime-dependency-version.test.ts,release-tooling.test.ts}`
  - `tools/codex-app-server-worker/{update.sh,update.ps1,scripts/runtime-dependency-version.mjs,README.md}`
  - `tools/codex-app-server-worker/tests/{runtime-dependency-version.test.ts,operations-upgrade.test.ts}`
  - `docs/worker-reinstall-and-upgrade-guide.md`
- tests_and_results:
  - PASS: `tools/codex-agent-worker`: `node --check scripts/runtime-dependency-version.mjs`; `node --import tsx --test tests/runtime-dependency-version.test.ts tests/release-tooling.test.ts` (15/15).
  - PASS: `tools/codex-app-server-worker`: `node --check scripts/runtime-dependency-version.mjs`; focused runtime/release tests (14/14); `node --import tsx --test --test-name-pattern='staged update preserves' tests/operations-upgrade.test.ts` (1/1).
  - PASS: both module `npm run typecheck` and `npm run build`; App Server `npm run verify:schema`; `git diff --check`.
  - PASS: App Server `npm run package:release`, including its full test/verify-schema/typecheck/build pipeline and release zip generation.
  - PASS: SDK `npm run package:release -- --skip-verify --platform all --smoke full`; release smoke succeeded. A prior full package verification exposed an environment-sensitive `install-env` test: its no-override assertion inherited a host-level ledger environment variable. The test now explicitly supplies an empty override when validating preservation of the `.env` value, without changing installer precedence semantics; final release verification is rerun before publication.
  - PASS: SDK updater rename regression: `node --import tsx --test tests/runtime-dependency-version.test.ts tests/release-tooling.test.ts tests/start-environment-isolation.test.ts tests/windows-release-start.test.ts` (25/25); `npm run typecheck`; `npm run build`; `git diff --check`.
  - PASS: SDK `npm run package:release -- --skip-verify --platform all --smoke full` after the rename. `release/output/smoke-result.json` recorded `archive-structure`, `sha256-sidecars`, `forbidden-file-scan`, `candidate-npm-ci`, and `candidate-health`; the isolated Linux candidate returned health `ok`. The archive contains only `update-sdk.*` and `update-worker.*`, with no `update.*` SDK updater.
  - PASS: isolated release installer migration under `temp/test-artifacts/bug-015-sdk-updater-rename.43db4G`: pre-created legacy `update.sh` / `update.ps1`, then ran the packaged Linux `install.sh` with isolated `HOME` and `CODEX_WORKER_HOME`; legacy files were removed, `update-sdk.sh` / `update-sdk.ps1` were installed, and `@openai/codex-sdk` installed successfully at `0.144.1`.
  - BLOCKED (pre-existing/environment-dependent): SDK `npm run package:release -- --platform all --smoke full` fails in `tests/install-env.test.ts` (`upgrade preserves existing identity and ledger unless an explicit override is supplied`): the test expects its temporary `persistent-ledger`, but the observed generated ledger is `/home/sa/.codex-worker/state/termination-operations`. This is unrelated to runtime-version code and was reproduced before the final focused checks.
- manual_or_experience_evidence: Isolated Linux release install used an archive with SDK lock `0.144.1`, pre-created `node_modules/@openai/codex-sdk/package.json` at `0.145.0`, and set both `HOME` and `CODEX_WORKER_HOME` beneath `temp/test-artifacts/bug-015-sdk-install-isolated-jE1AXJ`. After `install.sh`, installed SDK and candidate lockfile were both `0.145.0`. Windows paths have static regression assertions; they were not executable on this Linux host.
- deviations: 一次早期手工验证误传 `--install-dir`（安装器实际使用 `CODEX_WORKER_HOME`），可能触发默认 `~/.codex-worker` 的升级/停止路径。随后未对该目录执行恢复、重启或其他写操作；后续所有验证均隔离 `HOME` 与 `CODEX_WORKER_HOME`。
- residual_risks:
  - 更高的 Codex SDK/CLI 可能与较旧 Worker 本体不兼容；现有 candidate 验证、健康检查和回滚/保留故障证据行为未改变。
  - SDK Worker 发布级验证需在最终提交上重新运行；不以 `--skip-verify` 的临时归档作为发布证据。
  - PowerShell 路径只通过静态/类型级覆盖，未在本 Linux 主机执行。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 2026-07-23 user request
- architecture / glossary: `CLAUDE.md`, `docs/worker-reinstall-and-upgrade-guide.md`
- related work items: none
