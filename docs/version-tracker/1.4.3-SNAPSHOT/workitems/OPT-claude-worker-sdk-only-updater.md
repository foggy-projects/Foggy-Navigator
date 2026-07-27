---
doc_type: delivery-spec
delivery_type: optimization
version: 1.4.3-SNAPSHOT
ticket: OPT-claude-worker-sdk-only-updater
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
assurance_level: standard
approved_by: user
approved_at: 2026-07-27
open_questions: []
---

# Delivery Spec: Claude Worker SDK-only Updater

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定 Claude Worker 独立升级 `claude-agent-sdk` 与捆绑 Claude Code 的范围、
  安全边界和验证证据。
- canonical_path:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/OPT-claude-worker-sdk-only-updater.md`

## Goal

- version_goal: 补齐与 Codex Worker 对称的 SDK-only 运维入口。
- target_outcome: 源码和 `0.1.10` Linux/macOS 发布包均提供 `update-sdk.sh`，安装后的
  `claude-worker upgrade-sdk` 可调用它。
- critical_outcomes:
  - 默认解析并升级到包索引中的最新 `claude-agent-sdk`；
  - 可显式指定版本，默认拒绝降级；
  - 同时验证 SDK 与其捆绑 Claude Code CLI；
  - 只在确认 Worker 原本运行时安全停止并恢复，支持 `--no-restart`。
- success_is_sufficient_when: 脚本参数、防降级、当前版本 no-op、发布复制路径和 CLI
  转发均有实际验证，且未操作独立运行实例。

## Scope

- in_scope:
  - Claude Worker Linux/macOS SDK-only updater；
  - 发布包与安装器复制；
  - Unix `claude-worker upgrade-sdk` 命令；
  - 隔离构建并发布 Claude Worker `0.1.10` 到既有 OBS 渠道。
- affected_modules: `tools/claude-agent-worker`、对应版本化交付记录。
- external_dependencies: Python `pip` package index、`claude-agent-sdk` wheel。

## Non-Goals

- out_of_scope:
  - Windows PowerShell SDK updater；
  - 自动更新 `/home/sa/.claude-worker`；
  - 改变 Worker API、模型路由或凭据配置。
- do_not_touch: 当前 `/home/sa/.claude-worker:3033` 和仓库开发 Worker 进程。
- non_blocking_or_waivable_items: 不在目标安装机执行安装/升级 smoke，不阻断发布验收。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 更新 `claude-agent-sdk` 而非全局 npm Claude CLI | Worker 实际执行 SDK wheel 捆绑的 CLI | 不声称改变系统 `claude` |
| 默认只允许单调升级 | 防止误降级破坏 runtime | 显式版本加 `--force` 才允许降级 |
| 支持 `--no-restart` | 便于先升级、人工选择维护窗口 | 默认只恢复原本运行的 Worker |
| 使用隔离干净源构建并发布 `0.1.10` | 用户随后明确要求发布到 OBS，且工作树含无关改动 | 归档只叠加本任务文件，不携带无关 dirty changes |

## Acceptance Criteria

- [x] AC-1: `update-sdk.sh` 支持 latest、显式版本、index URL、no-restart 和显式
  force downgrade 语义。
- [x] AC-2: 脚本输出升级前后 SDK/CLI 版本并在验证失败时保持 Worker 不重启。
- [x] AC-3: Linux/macOS 发布打包和安装路径携带并赋予脚本执行权限。
- [x] AC-4: Unix 安装 CLI 提供 `claude-worker upgrade-sdk` 转发。
- [x] AC-5: 未停止、升级或重启 `/home/sa/.claude-worker:3033`。
- [x] AC-6: `0.1.10` 三平台归档和安装器发布到既有 OBS 渠道，远端回读校验通过。

## Contract / Data / Security Constraints

- API or event contract: 无变更。
- data and migration: 无数据库或持久化迁移。
- compatibility and rollback: 显式安装旧版本需同时提供 `--sdk-version` 与 `--force`；
  更新失败不自动启动已停止的 Worker，避免运行未知半升级状态。
- permissions and secrets: 不读取或输出 Worker token、Anthropic credential 或 pip
  credential；index URL 参数不得内嵌密钥。

## Test and Evidence Obligations

| Item | Classification | Risk | Required Validation | Reusable Evidence | Required Evidence |
|---|---|---|---|---|---|
| AC-1/2 | must-pass | major | Shell syntax、参数失败、当前版本 no-op | 本轮命令输出 | exit code 与版本输出 |
| AC-3/4 | must-pass | major | 发布脚本静态检查、LF staging CLI help | 本轮临时 staging | `upgrade-sdk` help 输出 |
| AC-5 | must-pass | major | 进程未被操作 | 只运行 repo venv no-op | 无 stop/start/update 命令 |
| AC-6 | must-pass | major | 隔离构建、归档内容审计、OBS 上传与远端 SHA-256 回读 | 既有 OBS 发布流程 | `latest.json` 与本地/远端 digest |

## Validation Budget and Evidence Sufficiency

- assurance_level: standard
- lightweight_validation: `<5m` Shell syntax、no-op、错误参数、CLI help、diff check。
- medium_validation: 不要求。
- expensive_validation: 不要求。
- large_authority_or_replay_policy: prohibited-unless-user-approved
- full_chain_recommendation_trigger: none
- estimated_full_chain_wall_clock: not-estimated
- full_chain_prerequisites: none
- user_approval_status: approved-for-obs-publish
- decision_if_not_approved: proceed-with-focused-and-affected-validation
- expensive_validation_trigger: none
- maximum_expensive_attempts: 0
- reusable_evidence: 当前 repo venv `claude-agent-sdk==0.2.111` no-op 验证。
- stop_when_evidence_is_sufficient: AC-1 至 AC-6 的 focused evidence 全部通过。
- validation_not_required: 真实 SDK 升级、目标安装机 Worker restart、全量 pytest。

## Waiver Policy

- waivable_items: 目标安装机安装/升级 smoke。
- authorized_role: repository owner
- non_waivable_guards: 不误操作独立 Worker、不泄露凭据、不默认允许降级。
- required_risk_record: 未升级当前 `/home/sa/.claude-worker:3033`；需由用户选择维护窗口。

## Risks and Open Questions

- known_risks:
  - `pip index versions` 输出格式由 pip 提供；解析失败时脚本 fail closed；
  - SDK 升级可能产生业务兼容变化，脚本只验证 import/CLI，发布流程仍应运行 Worker tests；
  - Windows 尚无对称 PowerShell updater。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md` 和 Claude Worker 模块规则。
- 在 scope 内自主决定局部实现。
- 如需改变目标、Windows updater 范围或运行实例边界，设置 `NEEDS_REPLAN`。
- OBS 发布已获用户明确批准；真实 Worker restart 或大型验证仍需另行授权。

## Implementation Result

- implementation_summary: 新增 SDK-only 更新脚本，接入 Unix 安装 CLI 和 Linux/macOS
  发布/安装复制路径；脚本实现目标解析、防降级、运行态停止恢复与 SDK/CLI 后验验证。
- changed_paths:
  - `tools/claude-agent-worker/update-sdk.sh`
  - `tools/claude-agent-worker/dist/package.sh`
  - `tools/claude-agent-worker/dist/package.ps1`
  - `tools/claude-agent-worker/dist/install.sh`
  - `tools/claude-agent-worker/dist/install.ps1`
  - `tools/claude-agent-worker/dist/bin/claude-worker`
  - `tools/claude-agent-worker/src/agent_worker/__init__.py`
  - 本 work item
- tests_and_results:
  - PASS: `bash -n` for updater、package.sh、install.sh。
  - PASS: updater `--help`。
  - PASS: repo venv 当前版本 `--sdk-version 0.2.111 --no-restart` 返回
    `Already up to date`，未停止或重启 Worker。
  - PASS: 缺失 `--sdk-version` 值返回 exit 1。
  - PASS: 无显式版本的 `--force` 返回 exit 1。
  - PASS: 按 Linux/macOS staging 规则转换 LF 后，CLI Shell syntax 与 help 中
    `upgrade-sdk` 入口通过。
  - PASS: scoped `git diff --check`。
  - FAILURE-FIRST/PASS: 首次 Linux 归档验证发现无扩展名
    `bin/claude-worker` 保留 CRLF；打包脚本补充非 Windows LF 转换后重新构建并通过
    Shell syntax。
  - PASS: 使用 clean `git archive` 加本任务文件叠加方式构建 `0.1.10` 三平台归档；
    归档不含 `.env`、凭据形态字符串或无关 `files.py` 修改。
  - PASS: OBS 上传 archives、`latest.json`、`install.sh`、`install.ps1` 均返回 200；
    远端回读后 Linux/macOS SHA-256 为
    `4eba49f5fa6c6369ded9a2702baf72296936498e9935ca16980e948286bb2e61`，
    Windows 为
    `73971d89a0bc082ae092e3ff88d0575643b31fadb09a8a68aa47e7f19e5be8ef`，
    与本地构建一致。
- manual_or_experience_evidence: CLI help 显示
  `upgrade-sdk [...]  Upgrade claude-agent-sdk and bundled Claude Code`。
- deviations: Windows PowerShell updater 不在批准范围。
- residual_risks: 尚未在目标安装机执行安装或 SDK 实际升级 smoke；当前
  `/home/sa/.claude-worker:3033` 保持原版本和运行状态。
- reused_evidence: 当前 SDK/CLI 形态审计证明 Worker 使用 SDK wheel 捆绑 CLI。
- omitted_validation_and_reason: 未运行全量 pytest，改动不触及 Python runtime 业务代码；
  未真实升级 SDK 或更新本机 Worker，避免改变现有运行环境。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户要求 Claude Worker 增加与 Codex Worker 类似的
  `update-sdk.sh`。
- architecture / glossary: `tools/claude-agent-worker/AGENTS.md`
- related work items:
  `docs/version-tracker/1.4.3-SNAPSHOT/workitems/BUG-015-codex-runtime-monotonic-upgrades.md`
