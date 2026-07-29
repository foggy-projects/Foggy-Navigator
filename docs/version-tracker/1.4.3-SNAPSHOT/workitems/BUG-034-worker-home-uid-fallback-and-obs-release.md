---
doc_type: delivery-spec
delivery_type: bug
version: 1.4.3-SNAPSHOT
ticket: BUG-034
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: standard
approved_by: user
approved_at: 2026-07-29
open_questions: []
---

# Delivery Spec: Worker 子进程 HOME 按执行 UID 回退与 OBS 发布

## Document Purpose

- intended_for: normal-analysis / ultra-implementation / independent-signoff
- purpose: 固定 Worker 派生模型或命令子进程时的 `HOME` 回退语义、跨 Worker 范围、验证与发布义务。
- canonical_path: `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-034-worker-home-uid-fallback-and-obs-release.md`

## Goal

- version_goal: 保证 Worker 在精简启动环境中仍以实际执行用户的 home 运行模型和命令子进程，同时保持已有环境与凭据边界不变。
- target_outcome: 父进程未设置 `HOME` 时，Worker 按当前执行 UID 对应的系统账户 home 补齐；父进程已设置时原样保留，并发布实际变更的 Worker OBS 制品。
- critical_outcomes:
  - 不再因父进程缺少 `HOME` 导致 Git、CLI 或用户级配置定位失败。
  - 不硬编码 `/root`，不把工作目录、`CODEX_HOME` 或其他应用目录误当用户 home。
  - 不覆盖 operator 显式提供的 `HOME`，不扩大敏感环境变量透传范围。
  - 每个变更 Worker 使用独立版本和发布前缀，远端产物身份可核验。
- success_is_sufficient_when: focused 回归、受影响 Worker 测试/构建、发布包验证、commit/push 和 OBS `latest.json`/归档完整性核验均有实际证据。

## Scope

- in_scope:
  - Codex SDK Worker、Codex app-server Worker、Claude Worker、Gemini Worker、LangGraph Biz Worker 的模型或命令子进程环境构造。
  - POSIX 父环境缺少或空白 `HOME` 时，按当前有效执行 UID 的系统账户记录尝试回退。
  - 已有 `HOME` 保留、无法解析 UID home 时安全维持未设置状态。
  - 稳定自动化回归、独立 Worker 版本递增、打包和官方 OBS 发布核验。
- affected_modules:
  - `tools/codex-agent-worker`
  - `tools/codex-app-server-worker`
  - `tools/claude-agent-worker`
  - `tools/gemini-agent-worker`
  - `tools/langgraph-biz-worker`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- external_dependencies: OS 用户数据库、Node/Python 运行时、各 Worker 既有 OBS 发布工具与凭据。

## Non-Goals

- out_of_scope:
  - 修改 Windows `USERPROFILE`/`HOMEDRIVE`/`HOMEPATH` 语义。
  - 修改 `CODEX_HOME`、Claude/Gemini 配置目录或业务 scoped home 选择。
  - 修改 Worker API、任务协议、Java 路由、数据库或前端。
  - 自动重启、升级或部署本机/跨 WSL 正在运行的 Worker。
  - 付费模型调用、长时间 canary、生产 soak 或真实上游联调。
- do_not_touch:
  - 同级上游仓库。
  - Worker token、API key、OBS secret 与本机未跟踪凭据文件。
  - 与本缺陷无关的现有 dirty worktree 内容。
- non_blocking_or_waivable_items:
  - 某个 Worker 没有安全可用的发布凭据或发布工具时，可记录该 Worker 未发布；不得伪报成功或使用同版本覆盖。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 仅在父环境 `HOME` 缺失或空白时尝试回退 | 尊重 operator 显式配置 | 非空已有值逐字保留 |
| POSIX 回退来源为当前有效 UID 的系统账户 home | 与实际执行身份对齐 | 不硬编码 `/root`，不从 cwd 或应用 home 猜测 |
| UID/home 查询失败时不伪造值 | 容器或最小系统可能无 passwd 记录 | 保持当前行为并留下可测试的 fail-safe |
| 各 Worker 独立递增 patch 版本并独立发布 | 产品线与 OBS 前缀相互独立 | 不为对齐版本而发布未变更 Worker |
| 发布不等于部署 | 避免影响当前运行实例 | 本事项不停止、重启或升级任何 Worker |

## Acceptance Criteria

- [x] AC-1: 每个受影响 Worker 在父环境缺少或空白 `HOME` 时，POSIX 子进程环境使用当前有效 UID 对应的系统账户 home。
- [x] AC-2: 每个受影响 Worker 在父环境提供非空 `HOME` 时保持原值，且 UID 回退不会覆盖该值。
- [x] AC-3: UID/home 无法解析时不硬编码、不抛出无关启动异常，并保持 `HOME` 未设置。
- [x] AC-4: 既有环境 allowlist、Worker 控制凭据隔离、`CODEX_HOME` 与任务级认证优先级保持不变。
- [x] AC-5: 稳定自动化回归覆盖保留、回退和失败三类分支，受影响 Worker 的测试与构建实际通过。
- [x] AC-6: 每个发生运行时代码变更且发布前置满足的 Worker 使用新 patch 版本完成官方 OBS 发布；远端 `latest.json`、归档字节数和 SHA-256 与本地候选一致。
- [x] AC-7: 变更已提交并推送，canonical work item 记录 changed paths、精确命令、结果、deviation 和 residual risk，状态为 `READY_FOR_SIGNOFF`。

## Contract / Data / Security Constraints

- API or event contract: 无外部 API、SSE 或 task payload 变化。
- data and migration: 无数据库或文件格式迁移。
- compatibility and rollback: 回滚到上一 Worker 版本即可恢复旧行为；已有非空 `HOME` 的运行环境行为不变。
- permissions and secrets: 不输出或提交凭据；不扩展安全 allowlist，只补齐传统用户目录定位变量；发布使用既有本机 secret。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/2/3 | must-pass | major | 各语言 focused unit tests | 新增回归测试 | 精确测试命令与通过数 |
| AC-4 | must-pass | major | 既有 Worker 全量单元测试/类型检查 | 现有凭据隔离测试 | 命令输出摘要 |
| AC-5 | must-pass | major | 各受影响 Worker build/package gate | 构建产物 | exit code、版本与包清单 |
| AC-6 | must-pass when publish prerequisites exist | major | 官方发布脚本与远端下载/hash 核验 | release evidence/sidecars | latest.json、bytes、SHA-256 |
| AC-7 | must-pass | major | git clean/upstream equality | commit | commit hash、push 状态 |

## Validation Budget and Evidence Sufficiency

- assurance_level: standard
- lightweight_validation: focused 回归、typecheck、Python 单测，单项预计 `<5m`。
- medium_validation: Worker 全量测试、构建、各平台打包与远端完整性核验，单项预计 `5-30m`。
- expensive_validation: 无默认项目；若单个发布链超过 30 分钟，仅在候选或输入变化后最多重试一次。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none；本变更不需要 Navigator 全栈或付费模型验证。
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: not-requested
- decision_if_not_approved: proceed-with-focused-and-affected-validation
- expensive_validation_trigger: 无。
- maximum_expensive_attempts: 1；连续两次非产品失败则 `NEEDS_REPLAN`。
- reusable_evidence: 同一 commit、依赖锁和发布候选未变化时复用已通过的 Worker 测试与 package evidence。
- stop_when_evidence_is_sufficient: AC-1 至 AC-7 均有可审查证据，或发布前置缺失已作为 deviation/residual risk 明确记录。
- validation_not_required: Java reactor、前端构建、Playwright、live model query、生产 soak。

## Waiver Policy

- waivable_items: 仅限因外部凭据/工具不可用而未完成的某条独立 OBS 发布。
- authorized_role: user / release owner
- non_waivable_guards: 不覆盖已有 `HOME`、不泄露凭据、不使用同版本非字节一致覆盖、不伪报发布成功。
- required_risk_record: 未发布的 Worker、失败阶段、受影响用户与后续发布命令。

## Bug Context

- bug_source: user-report
- severity: major
- environment: Worker 由缺少 `HOME` 的精简父环境启动，随后模型控制的命令需要用户级 Git/CLI 配置。
- current_behavior: 多数 Worker 仅继承 `HOME`；父环境缺失时子进程也缺失，用户级配置和 credential-store 定位失败。
- expected_behavior: 仅在缺失时按实际有效 UID 对齐系统 home。
- reproduction_steps: 以不含 `HOME` 的受控基础环境调用各 Worker 子进程环境构造器并检查结果。
- reproduction_status: confirmed
- existing_evidence: 代码审查确认 Codex SDK/app-server、Claude、Gemini 与 Biz Worker 均依赖父环境，只有部分降权路径主动覆盖。
- existing_tests: 既有环境隔离与 LangGraph 降权测试，尚未统一覆盖无 `HOME` 回退。
- regression_protection: required
- waiver_reason_and_risk: N/A

## Risks and Open Questions

- known_risks:
  - OS 用户数据库不可用时无法解析 home；设计要求 fail-safe，不伪造。
  - 发布脚本成熟度不一致；必须逐 Worker 核验远端结果。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、相关模块规范和专项技能。
- 在 scope 内自主决定具体文件、类和实现结构。
- 如需改变目标、范围、已确认决策、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 未经用户明确批准，不得主动运行预计超过 30 分钟或包含 authority/replay/rehearsal/source-seal 的大型链路；如认为最终候选需要，只提出一次包含预计耗时、范围和决策价值的建议。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

> 由 Ultra 执行会话填写。

- implementation_summary: 已完成五类 Worker 的 POSIX `HOME` 缺失回退与三分支自动化回归；仅在 `HOME` 缺失/空值时查询有效 UID 的系统账户 home，已有值和既有凭据隔离保持不变。五个 Worker 已从 clean/pushed commit `59f344d3e1a118d87519776830b823c91e8a76e7` 独立打包并发布 OBS。
- changed_paths:
  - `tools/codex-agent-worker`
  - `tools/codex-app-server-worker`
  - `tools/claude-agent-worker`
  - `tools/gemini-agent-worker`
  - `tools/langgraph-biz-worker`
  - `docs/version-tracker/1.4.3-SNAPSHOT`
- tests_and_results:
  - `tools/codex-agent-worker`: `npm test && npm run typecheck && npm run build`，`253 passed / 2 skipped`，typecheck/build PASS。
  - `tools/codex-app-server-worker`: `npm test && npm run typecheck && npm run build`，`348 passed / 1 skipped`，typecheck/build PASS；版本一致性 targeted test PASS。
  - `tools/claude-agent-worker`: `.venv/bin/python -m pytest -q`，`554 passed / 11 skipped`。
  - `tools/gemini-agent-worker`: `node --import tsx --test tests/*.test.ts && npm run typecheck && npm run build`，`15 passed / 1 skipped`，typecheck/build PASS。
  - `tools/langgraph-biz-worker`: `.venv/bin/python -m pytest -q`，`798 passed`。
  - SDK Codex package: `npm run package:release -- --platform all --smoke basic`，archive structure、SHA-256 sidecar、forbidden-file scan PASS。
  - app-server package: `npm run package:release`，`348 passed / 1 skipped`、schema verify、typecheck、build 与 deterministic archive PASS。
  - Claude: `bash dist/package.sh all`；Gemini/Biz: 官方 PowerShell packager `-OS all`，三平台候选生成成功。
- manual_or_experience_evidence:
  - `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-worker/latest.json` -> `1.0.30`。
  - `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/codex-app-server-worker/latest.json` -> `0.3.26`。
  - `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/claude-worker/latest.json` -> `0.1.14`。
  - `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/gemini-worker/latest.json` -> `1.0.1`。
  - `https://obs-fe55.obs.cn-north-4.myhuaweicloud.com/langgraph-biz-worker/latest.json` -> `0.2.2`。
  - 13 个远端 archive 全部重新下载到 `temp/test-artifacts/BUG-034-worker-home-release-20260729/`，逐字节 `cmp` 本地候选并复算 byte length/SHA-256，全部 PASS。
  - 五个产品的远端 `install.sh` / `install.ps1` 均可下载且非空；Codex 两条发布器另已完成内建 archive/bootstrap 字节校验。
- deviations:
  - Gemini 与 LangGraph Biz Worker 的 Windows `obsutil` 使用失效 Access Key，首个 immutable archive 上传返回 `403 InvalidAccessKeyId`，未更新对应 `latest.json`。
  - 随后使用已被 Codex/Claude 发布证明有效的当前 WSL Linux `obsutil` 上传同一官方候选，并将 `latest.json` 最后提交。两者未重复上传未变更的 bootstrap，现有 bootstrap 已验证可访问且继续按通用 `latest.json` 安装。
- residual_risks:
  - 本事项只发布、不部署；当前运行实例仍保持原版本，需由各实例 owner 按既有升级流程选择维护窗口。
  - LangGraph Biz Worker 远端从 `0.1.1` 跳至 `0.2.2`，包含此前已在当前仓库但未发布的累计差异；全量 `798 passed`，未执行本事项明确排除的 live model/upstream smoke。
- reused_evidence: 同一 commit 下首次全量测试结果用于后续 package gate；Codex packager自身重新执行了对应强制验证。
- omitted_validation_and_reason: 未运行 Java/frontend/Playwright/live model/生产 soak；均不受本次 Worker 子进程环境与 OBS 制品变更影响，且 live/soak 属明确 non-goal。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户于 2026-07-29 批准按 UID 对齐缺失 `HOME` 并重新发布 OBS。
- architecture / glossary: `AGENTS.md` Worker ownership boundary；各 Worker 当前环境构造与发布脚本。
- related work items: `BUG-016-sdk-worker-codex-home-isolation`、`REL-001-claude-worker-0.1.8-retired-skill-release`
