---
doc_type: delivery-spec
delivery_type: refactor
version: 1.4.2-SNAPSHOT
ticket: CLEAN-005-ultra-skill-governance
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: user
approved_at: 2026-07-16
open_questions: []
---

# Delivery Spec: Ultra 技能治理第一阶段

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 归档已确认不再需要的仓库级开发技能，并把少量仍有效的长期约束下沉到就近仓库指导。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/CLEAN-005-ultra-skill-governance.md`

## Goal

- version_goal: 降低 Foggy Navigator Ultra 编码会话的技能发现负担和过时指令干扰。
- target_outcome: 将已确认的 8 个废弃/临时技能、10 个模块静态开发指南和个人 `integration-test` 静态规范移出技能发现目录，同时保留可恢复备份和必要的模块级约束。

## Scope

- in_scope:
  - 归档已确认的 8 个废弃、临时或被原生能力覆盖的技能。
  - 审核并归档 10 个模块开发技能，只提取当前仍有效、代码本身不明显的长期约束。
  - 归档个人技能目录中的 `integration-test`；其内容绑定 TMS X6 工作区，不迁移到 Foggy Navigator。
  - 为保留约束创建简短、就近的 `AGENTS.md`，并验证目标技能不再处于发现目录。
- affected_modules: `.agents/skills`、`/mnt/c/Users/oldse/.agents/skills/integration-test`、`agent-framework`、`addons/claude-worker-agent`、`tools/claude-agent-worker`、`packages/foggy-mobile`
- external_dependencies: 个人技能备份目录，仅用于可逆恢复。

## Non-Goals

- out_of_scope:
  - 不处理此前审计中的合并、迁移、显式调用策略或 Slack/Calendar 插件治理。
  - 不处理 `x3-platform-cli`、`x3-tms-cli` 和 `payment-openapi-integration`；`async-cloud-notify-guide` 已由用户另行处理。
  - 不修改业务代码、API、数据库、Worker 运行态或当前功能开发内容。
- do_not_touch: 当前工作区中与终止操作、Worker 生命周期及其他功能开发相关的未提交修改。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 目标技能移出 `.agents/skills`，而不是只缩短描述 | 真正减少发现元数据与误触发 | 归档前保存完整备份 |
| 模块结构、文件清单和通用编码建议不迁移 | Ultra 可从当前代码和现行文档获得，更不易漂移 | 只保留安全、跨运行时契约和已验证工具链陷阱 |
| 长期约束优先放入模块级 `AGENTS.md` | 仅在相关目录工作时加载 | 不把旧技能正文复制进根指导 |
| 历史文档中的旧技能名称保持不变 | 保留真实历史证据 | 不批量改写历史版本记录 |

## Acceptance Criteria

- [x] AC-1: 已确认的 19 个技能目录均有完整备份，并从对应技能发现目录移除。
- [x] AC-2: 保留约束简短、可由当前代码或现行文档验证，且位于相关模块的 `AGENTS.md`。
- [x] AC-3: 不修改任何业务源码或用户现有未提交变更。
- [x] AC-4: 剩余技能结构校验和目标目录发现检查有实际执行结果。

## Contract / Data / Security Constraints

- API or event contract: 不变。
- data and migration: 无数据库或数据迁移。
- compatibility and rollback: 可从备份恢复原技能目录。
- permissions and secrets: 备份不得新增凭据或复制仓库外秘密文件。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1 | medium | 文件清单、数量与哈希校验 | 备份目录、manifest、校验输出 |
| AC-2 | medium | 人工对照当前代码、现行文档与模块清单 | changed paths 和约束来源 |
| AC-3 | major | 路径限定的 Git diff/status 审查 | 精确变更清单 |
| AC-4 | low | `quick_validate.py` 与发现目录检查 | 命令和结果 |

## Risks and Open Questions

- known_risks: 历史 Prompt 仍可能显式引用已归档技能；根 `AGENTS.md` 已定义新工作流映射，历史证据不改写。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md` 和目标技能。
- 在 scope 内自主决定备份结构和模块级指导文件的最小内容。
- 如需修改业务代码、运行态、安全边界或扩大到其他技能，设置 `NEEDS_REPLAN` 并停止扩展。
- 完成后记录精确变更、验证结果、偏差与剩余风险，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 将 19 个目标技能的 26 个文件从项目或个人技能发现目录移除，共删除 8,768 行静态技能内容。
  - 新增 4 个模块级 `AGENTS.md`，仅保留框架边界、跨运行时契约、路径/进程安全和 uni-app 工具链约束。
  - 完整备份保存于 `/mnt/c/Users/oldse/.agents/skill-backups/2026-07-16-foggy-ultra-skill-governance-phase1/`。
  - `integration-test` 的独立备份保存于 `/mnt/c/Users/oldse/.agents/skill-backups/2026-07-16-integration-test-retirement/`。
- changed_paths:
  - 删除 `.agents/skills/{coding-agent-backend,coding-agent-frontend,coding-agent-integration-tests,test-coding-agent,testing-guide,codex-skill-recorder-probe,gemini-link-smoke,merge-conflict-guide}/**`。
  - 删除 `.agents/skills/{agent-framework,claude-worker-agent,file-browser-dev,foggy-mobile-dev,metadata-config-module,process-detection-dev,session-module,session-integration-tests,ssh-terminal-dev,user-memory}/**`。
  - 删除个人技能 `/mnt/c/Users/oldse/.agents/skills/integration-test/SKILL.md`；未改动用户指定保留的三个技能。
  - 新增 `agent-framework/AGENTS.md`、`addons/claude-worker-agent/AGENTS.md`、`tools/claude-agent-worker/AGENTS.md`、`packages/foggy-mobile/AGENTS.md`。
  - 新增本 canonical work item；未修改业务源码和现有版本 README/progress/implementation-plan。
- tests_and_results:
  - `sha256sum .../repository-skills.tar.gz`：PASS，`c929c847fc576b4a5c004cb8d74bf7d66ea5471a84f682e6356a4fb713e5ec4d`。
  - `tar -tzf .../repository-skills.tar.gz`：PASS，归档可读，18 个目录、43 个条目。
  - 备份逐文件换行归一化比对：PASS，25/25 文件内容一致；原始字节差异仅来自 Git 基线 LF 与工作区 Windows CRLF 换行形式。
  - 目标目录存在性检查：PASS，18 个技能均不再位于 `.agents/skills`。
  - `integration-test.tar.gz`：PASS，归档可读；SHA-256 为 `26734f340bca1c75849fbf8af318f4c6ec6339140d0fc3ad14de8a2efce97915`。
  - `integration-test/SKILL.md` 源文件与归档内文件 SHA-256 均为 `b5d4e277e8c56a2c430db063aa988d4524d5839c29bd005eb66e6112ef79e299`；个人发现目录已不存在该技能。
  - 剩余项目技能计数：7。
  - `python3 .../quick_validate.py`：6 个剩余技能通过；`worker-env-setup` 因原有 frontmatter 未引用冒号而失败，本事项未修改该技能。
  - `git diff --check -- .agents/skills`：PASS。
- manual_or_experience_evidence:
  - 对照当前代码、`CLAUDE.md`、Session/A2A/Worker 文档和 mobile App-Plus 复盘后，仅提取可验证的长期约束。
  - `async-cloud-notify-guide` 核验时已不在个人技能目录；`x3-platform-cli`、`x3-tms-cli` 与 `payment-openapi-integration` 保持原状。
  - 路径限定的 `git status` 显示本事项只包含技能删除、新增模块指导和本 work item；用户其他脏工作区修改保持不变。
- deviations: none
- residual_risks:
  - 当前已打开的 Codex 会话可能继续显示启动时注入的旧技能列表；新会话或重启后才会反映文件系统变化。
  - `worker-env-setup` 的既有 YAML 校验失败仍待后续治理。
  - 历史版本文档可能保留旧技能名称，这是有意保留的历史证据。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户于 2026-07-16 确认先执行技能审计建议中的第 1、2 类治理。
- architecture / glossary: `AGENTS.md`、`CLAUDE.md`、`docs/00-system-overview.md`
- related work items: none
