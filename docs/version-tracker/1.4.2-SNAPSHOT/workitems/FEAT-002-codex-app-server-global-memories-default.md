---
doc_type: delivery-spec
delivery_type: feature
version: 1.4.2-SNAPSHOT
ticket: FEAT-002
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: repository owner
approved_at: 2026-07-17
open_questions: []
---

# Delivery Spec: Codex App Server 全局 Memories 默认启用

## Document Purpose

- intended_for: ultra implementation / independent signoff
- purpose: 固定当前安装态与后续全新安装默认启用 Codex 本地 Memories 的范围、安全边界、验收与证据义务。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/FEAT-002-codex-app-server-global-memories-default.md`

## Goal

- version_goal: 让独立 Codex App Server Worker 的隔离 `CODEX_HOME` 默认具备跨 Thread 的本地记忆生成与使用能力。
- target_outcome: 当前 `~/.codex-app-server-worker` 明确启用 Memories；后续全新安装在空 `CODEX_HOME` 中生成可审计的默认配置，更新和自定义配置不被覆盖。

## Scope

- in_scope:
  - 验证官方 Codex Memories 配置与固定 CLI `0.144.3` 的真实接受行为。
  - 全新安装创建隔离 `CODEX_HOME` 时写入 `[features].memories=true`，并显式启用 memory generation/use。
  - 当前已授权安装目录的配置核对、必要修正和运行态验证。
  - 安装器回归测试、Worker package gate、安装 dry-run/原位更新与健康检查。
- affected_modules: `tools/codex-app-server-worker`、本 canonical workitem。
- external_dependencies: `@openai/codex 0.144.3`；OpenAI 官方 Codex Memories 配置契约。

## Non-Goals

- out_of_scope:
  - 不升级 Codex CLI，不改变 app-server 协议、Thread 并发、压缩或 Java API。
  - 不实现按 Navigator user、tenant、Session 或 project 隔离的 memory store。
  - 不发布 OBS，不部署或重启 JDK17 Navigator。
- do_not_touch: 现有 `.env`、凭据、模型 provider、base URL、已有 `config.toml` 的非 Memories 配置和无关 dirty worktree。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 使用 `[features] memories = true` | 官方文档和 CLI 0.144.3 均识别该实验性功能开关 | 固定 CLI 升级时必须重新验证 |
| 显式设置 `generate_memories=true` 与 `use_memories=true` | “打开全局记忆”同时包含生成和后续注入 | 记忆写入和注入均发生在该 Worker 的隔离 `CODEX_HOME` |
| `disable_on_external_context=false` | 与 owner 当前安装态一致，允许符合 Codex 条件的工具型会话参与记忆 | 仍由 Codex 自身的 eligibility、secret redaction、idle 和 rate-limit 规则决定是否生成 |
| 仅为不存在的 `config.toml` 创建默认值 | 更新不得覆盖 operator-owned Codex 配置 | 已有配置逐字节保留；显式关闭必须继续生效 |
| 一个物理 Worker 的 Memories 是共享信任域 | Codex 以 `CODEX_HOME` 为本地全局存储，不按 Navigator user 自动分区 | 多用户或跨租户部署必须使用独立 Worker/CODEX_HOME，不能把本默认值当成租户隔离 |

## Acceptance Criteria

- [x] AC-1: 当前安装的 CLI `0.144.3` 报告 `memories` 已启用，配置同时启用 generation 和 use。
- [x] AC-2: 全新安装在隔离 `CODEX_HOME` 不存在 `config.toml` 时创建 mode-private 的默认配置并启用 Memories。
- [x] AC-3: 全新安装指向已有 `config.toml` 时逐字节保留该文件，不覆盖显式关闭或其他 operator 配置。
- [x] AC-4: 重复配置和安装保持幂等，不重复 section/key，不修改现有 `.env` 更新语义。
- [x] AC-5: release archive、Linux/Windows 安装路径均包含同一默认逻辑，测试、schema、typecheck、build 和安装验证通过。
- [x] AC-6: 原位部署后 3071 健康、runtime identity、CLI 版本、既有配置和生命周期 fail-closed 语义不退化。

## Contract / Data / Security Constraints

- API or event contract: 无变更。
- data and migration: 仅全新安装初始化 `CODEX_HOME/config.toml`；不迁移或改写已有 memory SQLite、session、state journal。
- compatibility and rollback: 删除新建默认配置可回到 Codex 默认关闭；更新路径必须保留已有配置。
- permissions and secrets: `config.toml` 使用 owner-private 权限；不写入 key、token、credential 或 provider secret。Memories 可能包含先前工作上下文，物理 Worker/CODEX_HOME 必须视为单一共享信任域。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1 | CLI 配置名猜测 | 固定 CLI 实际 `features list` 与配置解析 | 精确命令和输出摘要 |
| AC-2/3/4 | 安装器覆盖配置或不幂等 | 先补 installer unit/integration regression | 测试数量与结果 |
| AC-5 | 发布包缺脚本或跨平台分歧 | `npm run package:release` | archive、SHA-256、完整 gate |
| AC-6 | 原位更新导致进程/配置退化 | installed updater dry-run、update、health/config verification | 版本、PID ownership、health 和配置摘要 |

## Risks and Open Questions

- known_risks:
  - Memories 在 CLI `0.144.3` 中仍标记 Experimental，生成可能延迟、跳过或随上游版本变化。
  - 同一 `CODEX_HOME` 的记忆可能被后续不同 Thread 使用；共享 Worker 不提供 Navigator user/tenant 级隔离。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md`、Worker README 与 `codex-worker-deploy` 技能。
- 先补能证明新建/保留/幂等语义的测试，再实现安装默认值。
- 如需覆盖已有 `config.toml`、改变 `.env`、引入多租户记忆隔离或升级 CLI，设置 `NEEDS_REPLAN` 并停止扩展。
- 记录 changed paths、精确命令、结果、deviations 与 residual risks；完成后设置 `READY_FOR_SIGNOFF`，不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary: Worker `0.3.20` 的全新安装初始化会在 `config.toml` 不存在时以 `0600` 创建 Memories 默认配置；文件已存在时使用 exclusive-create 语义直接保留，绝不解析或改写 operator 配置。当前安装本来已显式启用 Memories，因此原位更新逐字节保留其配置。
- changed_paths:
  - `tools/codex-app-server-worker/scripts/configure-install-env.mjs`
  - `tools/codex-app-server-worker/tests/install-defaults.test.ts`
  - `tools/codex-app-server-worker/.env.example`
  - `tools/codex-app-server-worker/README.md`
  - `tools/codex-app-server-worker/package.json`
  - `tools/codex-app-server-worker/package-lock.json`
  - `tools/codex-app-server-worker/src/version.ts`
  - 本 canonical workitem
- tests_and_results:
  - failure-first: `node --import tsx --test tests/install-defaults.test.ts` — 实现前 2 项因缺少 `config.toml` 失败；实现后 4/4 通过。
  - `npm test` — 312 tests，311 passed，1 platform skip，0 failed。
  - `npm run typecheck` — passed。
  - `npm run build` — passed。
  - `npm run package:release` — 完整 test/schema/typecheck/build gate passed；schema digest `6f2550bb528581f17c4c3a3857dca92c860406aa3274e314cfa726c32e395d8f`。
  - `/home/sa/.codex-app-server-worker/update.sh --package tools/codex-app-server-worker/release/output/codex-app-server-worker-0.3.20.zip --dry-run` — candidate gate passed，当前安装未修改。
  - `/home/sa/.codex-app-server-worker/update.sh --package tools/codex-app-server-worker/release/output/codex-app-server-worker-0.3.20.zip` — drain/swap/candidate gate passed，配置和 state preserved；随后对命令会话退出造成的陈旧 PID evidence 执行 `process-tree status/verify`，均为 `clean,count=0`，人工解除已证实死亡的 PID/snapshot 后通过独立宿主会话启动。
- manual_or_experience_evidence:
  - release archive: `tools/codex-app-server-worker/release/output/codex-app-server-worker-0.3.20.zip`，SHA-256 `a5fea81bf640833821f9be3660309c6cc6fa44e4e96df9019d8f448b3864a83c`。
  - installed health: `ready=true`、version `0.3.20`、runtime `codex-app-server-primary` revision `5`、instance `codex-store-5ea69ced-19e1-4c85-bb68-f8854a81d455`、CLI `0.144.3`、active/queued tasks `0/0`。
  - installed ownership: PID command is `node /home/sa/.codex-app-server-worker/dist/index.js` and is parented by WSL init after detached startup。
  - installed `.env` SHA-256 remained `4c3c377cb0a6279bcfb421d0ae0d980c230ab691cead49511ec826c7528408c9`; `config.toml` remained `2f42535148d28881a8cd1d890cd1958b692a02589834dcb58d728dcce8f36828`, mode `0600`。
  - `CODEX_HOME=/home/sa/.codex-app-server-worker/codex-home codex features list` reports `memories experimental true`; the installed config explicitly enables generation/use and permits eligible external-context sessions。
- deviations: none
- residual_risks: Memories remains an experimental upstream capability; generation is asynchronous and may be skipped by Codex eligibility, idle, redaction, or rate-limit rules. A physical Worker/CODEX_HOME remains one shared memory trust domain and is not Navigator tenant isolation.
- readiness: READY_FOR_SIGNOFF; independent signoff may map AC-1 through AC-6 to the recorded automated and installed evidence. Do not mark ACCEPTED in this implementation session.

## References

- requirement / issue: repository owner request on 2026-07-17
- architecture / glossary: OpenAI Codex manual — Memories and configuration reference
- related work items: `BUG-007-app-server-single-instance-containment.md`, `BUG-013-codex-app-server-long-thread-tool-loss.md`
