---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.2-SNAPSHOT
ticket: BUG-016
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: user
approved_at: 2026-07-17
open_questions: []
---

# Delivery Spec: Codex SDK Worker CODEX_HOME 隔离

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 Codex SDK Worker 默认 Home 被上游 shell 环境污染的修复范围、兼容边界、回归矩阵与交付证据。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-016-sdk-worker-codex-home-isolation.md`

## Goal

- version_goal: 关闭 1.4.2-SNAPSHOT 中 Codex SDK Worker 的进程环境隔离缺口，使未指定业务 scoped home 的任务始终使用该 Worker 自己可预测的 Codex Home。
- target_outcome: 无论 Worker 是由交互 shell、另一 Codex 会话、安装器、更新器还是发布脚本启动，SDK CLI 子进程均不会继承其他 Worker 的 generic `CODEX_HOME`；继续会话可在正确 Home 查找 rollout，健康状态也基于同一有效 Home 判断认证就绪。

## Scope

- in_scope:
  - 新增 SDK Worker 专属配置 `CODEX_WORKER_CODEX_HOME`，未配置时确定性回退到当前用户 `$HOME/.codex`。
  - 未传 `codex_home_key` 的每个 Codex CLI 子进程显式设置解析后的默认 `CODEX_HOME`；generic ambient `CODEX_HOME` 不作为 SDK Worker 配置源。
  - 保留 `codex_home_key + CODEX_BIZ_HOME_ROOT` 的业务 scoped home 行为，并继续从默认 Home 受控复制登录态。
  - Linux/macOS 与 Windows 的开发、发布、安装和更新启动链清理继承的 generic `CODEX_HOME`，同时允许显式的 `CODEX_WORKER_CODEX_HOME` 生效。
  - `/health` 认证就绪与诊断信息改为基于实际有效默认 Home，并只暴露安全的配置来源/就绪状态，不暴露 scoped home 路径。
  - 将 Codex CLI 的 `no rollout found for thread id` 归类为结构化 `CODEX_THREAD_NOT_FOUND`。
  - SDK Worker 版本、配置样例、上游说明、发布候选和自动化回归同步更新。
- affected_modules: `tools/codex-agent-worker`、`docs/version-tracker/1.4.2-SNAPSHOT`。
- external_dependencies: OpenAI Codex SDK/CLI 已有 env 与 thread resume 契约；不改变 Provider API。

## Non-Goals

- out_of_scope:
  - 不改变 GOV-004 中 stream 无 Provider 终态 observation 时保持 active/attention 的生命周期语义。
  - 不把已验证的 CLI exit code 1 自动改成任务终态失败；该选择仍需单独生命周期决策。
  - 不迁移、取消、重提或修补当前卡住任务；不移动不同 Codex Home 之间的 rollout/session 文件。
  - 不发布到 OBS、不安装或升级本机 Worker、不重启 3051/3053 或其他运行实例。
  - 不修改 Codex app-server Worker 的独立 Home 设计或运行配置。
- do_not_touch: `tools/codex-app-server-worker`、现有 GOV-004/BUG-007 与前端/launcher 脏改、运行中 Worker 进程、真实认证文件和任务事件。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| SDK Worker 使用专属 `CODEX_WORKER_CODEX_HOME`。 | generic `CODEX_HOME` 会沿父 shell/工具调用链传播，无法证明属于当前 Worker。 | 旧部署若有意使用自定义 generic `CODEX_HOME`，必须迁移为新变量；不得保留 ambient fallback。 |
| 未配置时固定使用当前用户 `$HOME/.codex`。 | SDK Worker 的既有安装约定与真实 thread/auth 数据均在用户默认 Codex Home。 | 路径解析需兼容 POSIX 和 Windows，不依赖当前工作目录。 |
| 每个 CLI 子进程都显式写入有效 `CODEX_HOME`。 | 仅在 Worker 启动脚本 unset 不足以覆盖直接 `node`/`npm` 启动。 | scoped 请求仍覆盖为其专属 Home；其他环境隔离规则不变。 |
| 启动脚本同时清除 generic `CODEX_HOME`。 | 防止 Worker 自身和后续非任务子进程继续携带错误归属的通用配置。 | 不清除 `CODEX_WORKER_CODEX_HOME`；开发包与发布包、sh 与 PowerShell 保持一致。 |
| Health 使用有效默认 Home 检查 `auth.json`。 | 当前硬编码 `$HOME/.codex/auth.json` 会在有效 Home 被污染时误报 ready。 | 只返回来源和认证布尔状态等安全诊断，不返回 scoped Home 或认证内容。 |
| `no rollout found` 映射为 `CODEX_THREAD_NOT_FOUND`。 | 该错误能确定是 thread 在有效 Home 中不存在，应提供可操作的结构化分类。 | 只改变诊断分类，不改变本次 stream/exit 的终态策略。 |
| SDK Worker 发布候选升级到下一个 patch 版本。 | 运行时、启动链、安装更新与 health 契约均发生可发布变更。 | 本任务仅打包验证，不上传、不部署；app-server Worker 不跟随发版。 |

## Acceptance Criteria

- [x] AC-1: `CODEX_WORKER_CODEX_HOME` 接受合法绝对路径；缺省时解析为当前用户 `.codex`；ambient generic `CODEX_HOME` 不影响解析结果。
- [x] AC-2: 未 scoped 与 scoped 任务的 Codex 子进程分别显式获得默认 Home 和 `CODEX_BIZ_HOME_ROOT` 派生 Home，且现有 API key、Worker credential 与平台路径的环境隔离不回退。
- [x] AC-3: 直接 `node`/`npm`、开发/发布 `start.sh`、开发/发布 `start.ps1`、安装器和更新器重启链在 hostile parent `CODEX_HOME` 下均不会把其传入 SDK Worker CLI 子进程。
- [x] AC-4: `/health` 的 Codex 认证 readiness 基于有效默认 Home，能区分默认/显式配置来源且不泄露默认或 scoped 真实路径、`auth.json` 内容或凭据。
- [x] AC-5: `no rollout found for thread id` 生成 `CODEX_THREAD_NOT_FOUND` 结构化诊断；既有 stream 非终态 observation 行为保持不变。
- [x] AC-6: POSIX、Windows 路径与大小写不敏感环境变量场景有长期自动化回归保护。
- [x] AC-7: SDK Worker 单元测试、typecheck、build 与三平台 release packaging 的 `full` smoke 全部实际执行通过；发布候选 health 证明 hostile generic `CODEX_HOME` 未污染有效 Home。
- [x] AC-8: 只评估并生成 SDK Worker 发布候选；不修改、不打包、不发布 app-server Worker，不操作任何运行实例。

## Contract / Data / Security Constraints

- API or event contract: 保持现有请求字段；`/health` 仅新增向后兼容的安全诊断字段；新增错误码 `CODEX_THREAD_NOT_FOUND` 沿既有结构化错误信封传递。
- data and migration: 无数据库或 session 数据迁移；不得自动复制 rollout/session 文件。scoped Home 继续只按现有规则种子化 `auth.json`。
- compatibility and rollback: 自定义默认 Home 的部署必须从 `CODEX_HOME` 显式迁移到 `CODEX_WORKER_CODEX_HOME`。代码回滚可恢复旧行为，但会重新暴露跨 Worker 污染风险。
- permissions and secrets: 不读取或输出认证文件内容；日志、health、SSE 与文档不得包含 token、API key、Worker credential 或 scoped Home 真实路径。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| 默认/业务 Home 解析与子进程 env | critical | focused unit tests under hostile parent env, including case-insensitive keys | test names and exact command result |
| 开发与发布启动脚本 | critical | POSIX executable tests plus PowerShell static/available-runtime tests | command result and platform limitations |
| 安装/更新重启链 | critical | packaged bootstrap/script regression tests | exact test result |
| Health/auth diagnostics | major | route tests using isolated fake Homes with/without `auth.json` | response assertions proving no path leakage |
| Thread missing classification | major | diagnostic unit/integration test using representative CLI error | structured code assertion and unchanged lifecycle assertion |
| Release candidate | critical | `npm run package:release -- --platform all --smoke full` | unit/type/build output, archive checksums, `smoke-result.json`, candidate health |
| Patch hygiene | low | scoped diff review and `git diff --check` | changed paths and passing result |

## Bug Context

- bug_source: user-report
- severity: major
- environment: Linux/WSL; Codex app-server Worker shell launched both installed `/home/sa/.codex-worker` and source `tools/codex-agent-worker` SDK Workers.
- current_behavior: both SDK Worker processes inherited `CODEX_HOME=/home/sa/.codex-app-server-worker/codex-home`; an unscoped resume could not find a thread that exists only in `/home/sa/.codex`, then the SDK stream ended without a Provider terminal observation and the CLI exited.
- expected_behavior: SDK Worker ownership is independent of its launcher shell. Unscoped tasks use `/home/sa/.codex` by default, scoped tasks use their governed business Home, and missing threads receive an accurate structured diagnostic.
- reproduction_steps:
  1. Set parent `CODEX_HOME` to an unrelated app-server Home.
  2. Start SDK Worker through its source or installed start script without defining generic `CODEX_HOME` in the Worker `.env`.
  3. Resume a thread stored only in the user's normal `~/.codex`.
  4. Observe Codex CLI search the inherited Home and report `no rollout found for thread id`.
- reproduction_status: confirmed
- existing_evidence: installed port 3053 and source port 3051 processes both carried the app-server Home; isolated resume reproduced exit 1 and JSON-RPC `-32600` before any Provider request; `/health` still checked hardcoded `$HOME/.codex/auth.json`.
- existing_tests: sdk env/auth/scoped Home tests and release full smoke exist, but currently accept generic `CODEX_HOME` as default and inject it into packaged smoke rather than proving isolation.
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - deployments intentionally relying on generic `CODEX_HOME` will change behavior until migrated to `CODEX_WORKER_CODEX_HOME`;
  - Windows startup execution may not be available on the Linux build host, so behavior must be covered by script contract tests and the generated Windows archive, with any runtime limitation disclosed;
  - the currently active non-terminal task remains untouched and may still require separate diagnostics or an explicitly authorized restart after this candidate is verified.
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、根 `CLAUDE.md` 与 `codex-worker-deploy` 专项技能。
- 对可稳定复现的污染路径先补失败回归测试，再完成最小、可维护的隔离实现。
- 在 scope 内自主决定具体文件与 helper 结构，但不得引入 generic `CODEX_HOME` fallback 或改变 GOV-004 终态语义。
- 运行与改动面匹配的验证，记录精确命令、结果、生成证据和未运行原因。
- 不停止、重启、安装、升级或发布任何 Worker；这些需要独立授权。
- 如需改变目标、兼容、安全边界或任务生命周期语义，设置 `NEEDS_REPLAN` 并停止扩展。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 新增 SDK Worker 专属绝对路径配置 `CODEX_WORKER_CODEX_HOME`，缺省确定性解析为当前运行用户 `.codex`；generic `CODEX_HOME` 不再参与配置解析。
  - Worker 在 dotenv 加载后和四个开发/发布启动脚本中大小写不敏感地清理 generic `CODEX_HOME`，同时为每个 Codex CLI 子进程显式写入有效默认或业务 scoped Home，并阻止专属控制面变量进入模型可控 CLI/Shell 环境。
  - scoped Home 继续由 `codex_home_key + CODEX_BIZ_HOME_ROOT` 派生，登录态改为从有效默认 Home 受控种子化；默认 Home 不写入 managed MCP 配置。
  - `/health` 新增不含路径的 `codex_home_source`、`codex_home_auth_configured`，认证 readiness 与实际默认 Home 对齐；缺失 rollout 映射为安全的 `CODEX_THREAD_NOT_FOUND / CONFIGURATION`。
  - SDK Worker 候选版本升级为 `1.0.17`，发布 full smoke 在 hostile generic Home 下验证隔离；未改变 GOV-004 的非终态 stream/exit 语义。
- changed_paths:
  - `tools/codex-agent-worker/.env.example`
  - `tools/codex-agent-worker/docs/release.md`
  - `tools/codex-agent-worker/docs/upstream-integration.md`
  - `tools/codex-agent-worker/package.json`
  - `tools/codex-agent-worker/package-lock.json`
  - `tools/codex-agent-worker/start.sh`
  - `tools/codex-agent-worker/start.ps1`
  - `tools/codex-agent-worker/release/start.sh`
  - `tools/codex-agent-worker/release/start.ps1`
  - `tools/codex-agent-worker/scripts/release-smoke.mjs`
  - `tools/codex-agent-worker/src/config.ts`
  - `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`
  - `tools/codex-agent-worker/src/diagnostics.ts`
  - `tools/codex-agent-worker/src/models.ts`
  - `tools/codex-agent-worker/src/routes/health.ts`
  - `tools/codex-agent-worker/tests/config.test.ts`
  - `tools/codex-agent-worker/tests/sdk-wrapper.test.ts`
  - `tools/codex-agent-worker/tests/health.test.ts`
  - `tools/codex-agent-worker/tests/diagnostics.test.ts`
  - `tools/codex-agent-worker/tests/start-environment-isolation.test.ts`
  - `docs/version-tracker/1.4.2-SNAPSHOT/README.md`
  - `docs/version-tracker/1.4.2-SNAPSHOT/workitems/BUG-016-sdk-worker-codex-home-isolation.md`
- tests_and_results:
  - Red/green focused regression: `node --import tsx --test tests/config.test.ts tests/sdk-wrapper.test.ts tests/health.test.ts tests/diagnostics.test.ts tests/start-environment-isolation.test.ts` — 旧实现按预期 12 项失败；实现后 98/98 passed，exit 0。
  - `npm test` — PASS，220 tests；219 passed、0 failed、1 skipped。唯一 skip 是当前 Linux 主机上的既有 Windows PowerShell process-list 运行态测试；本事项新增的 PowerShell starter 顺序契约、Windows 路径与大小写不敏感 env 回归均已执行通过。
  - `npm run typecheck` — PASS，exit 0。
  - `npm run build` — PASS，clean + `tsc`，exit 0。
  - `npm run package:release -- --platform all --smoke full` — PASS；重新执行上述 test/typecheck/build，生成 Linux、macOS、Windows 三平台 1.0.17 候选，完成 archive structure、forbidden-file scan、sidecar、candidate `npm ci` 与 `/health` 检查。
  - `release/output/smoke-result.json` — `requestedLevel=full`、`level=full`、`packageVerificationSkipped=false`；Linux candidate 为 `healthStatus=ok`、`version=1.0.17`、`codexHomeSource=user_default`、`codexHomeAuthConfigured=true`。
  - archive sidecar 复核 — PASS：Linux/macOS SHA-256 `8f7c6cf9415e0b49e076f175ac8862836cb659c867a12f938c42ba5cbe78be11`；Windows SHA-256 `6c183e62a22cf96f2b0e8fe8dfb355061aa691b7c219d784086be65eaf7e6d41`；三项 `sha256sum -c` 均为 `OK`。
  - `git diff --check` — PASS，exit 0。
- manual_or_experience_evidence:
  - packaged-candidate smoke 仅在隔离临时用户的 `$HOME/.codex/auth.json` 写入假登录态，并同时注入 hostile generic `CODEX_HOME`；health 仍从 `user_default` Home 解析到登录态，且 smoke 显式拒绝返回体包含正确或 hostile Home 路径。
  - Linux/macOS tar 与 Windows zip 均包含 `codex-agent-worker@1.0.17`；未发现可用的 `pwsh` 或 `powershell`，因此 Windows 启动脚本采用静态顺序契约、跨平台路径单测和生成 archive 验证，未宣称 Windows 本机运行态已执行。
  - 未运行付费/真实模型 resume smoke；本任务未读取、复制或修改真实 `/home/sa/.codex`、`/home/sa/.codex-app-server-worker/codex-home` 中的认证或 rollout 数据。
  - 实现验证阶段未停止、重启、安装、升级或发布任何 Worker；后续在用户单独授权后仅发布 SDK Worker 1.0.17。`tools/codex-app-server-worker` 的既存用户脏改保持原状，本事项没有对其打包、提交或发布。
- release_and_publish_evidence:
  - source commit `e78d5817dbe365819b9b09bee2d5dd3efe49cdf9` 已推送至 `origin/main`；发布前 `tools/codex-agent-worker` 子目录 clean，且 `HEAD` 与 upstream 完全一致。
  - `npm run publish:obs` 首次在任何对象上传前失败，因为 Linux 默认 `~/.obsutilconfig` 是占位配置；远端 `latest.json` 当时仍为 1.0.16。随后通过临时适配器让 Linux `obsutil` 显式读取本机既有 Windows 用户配置，未改发布脚本、未输出凭据，也未使用 `--allow-dirty`、`--allow-unpushed` 或 `--allow-same-version`。
  - SDK Worker 1.0.17 已成功上传并由发布脚本回读验证；远端 `latest.json` 为 `product=codex-agent-worker`、`schemaVersion=1`、`version=1.0.17`、`gitCommit=e78d5817dbe365819b9b09bee2d5dd3efe49cdf9`。
  - 独立下载复核通过：Linux/macOS 均为 160399 bytes、SHA-256 `8f7c6cf9415e0b49e076f175ac8862836cb659c867a12f938c42ba5cbe78be11`；Windows 为 661231 bytes、SHA-256 `6c183e62a22cf96f2b0e8fe8dfb355061aa691b7c219d784086be65eaf7e6d41`。
  - 远端 `install.sh`、`install.ps1`、`1.0.17/release-evidence.json` 与本地生成资产逐字节一致。
  - 未停止、重启或重装任何运行实例；未发布 codex-app-server Worker。
- deviations: none
- residual_risks:
  - 当前运行中的 3051/3053 或已安装 `/home/sa/.codex-worker` 实例尚未应用已发布的 1.0.17；在用户重新执行安装脚本并按其流程重启前仍保持旧进程行为。
  - Windows 真实 PowerShell 启动链尚需在 Windows 目标机补一次运行态 smoke；当前证据为静态契约、Windows path/env 单测与可校验 archive。
  - 有意依赖 generic `CODEX_HOME` 自定义 SDK Worker 登录态的旧部署必须迁移为 `CODEX_WORKER_CODEX_HOME`；这是已批准的兼容性变化。
  - 本修复不改变 GOV-004 的 provider 非终态 observation 规则；其他原因导致的 `Codex SDK stream ended without a provider terminal observation` 仍会按原契约保持 active/attention。
- readiness: READY_FOR_SIGNOFF

## References

- related_work_items: `GOV-004-cli-non-termination-and-lifecycle-observability.md`, `BUG-005-codex-worker-structured-error-release.md`, `BUG-013-codex-app-server-long-thread-tool-loss.md`
- worker_release_guide: `tools/codex-agent-worker/docs/release.md`
- upstream_contract: `tools/codex-agent-worker/docs/upstream-integration.md`
