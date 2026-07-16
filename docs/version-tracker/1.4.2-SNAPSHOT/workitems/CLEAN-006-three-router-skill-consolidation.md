---
doc_type: delivery-spec
delivery_type: cross-module
version: 1.4.2-SNAPSHOT
ticket: CLEAN-006-three-router-skill-consolidation
status: READY_FOR_SIGNOFF
canonical: true
execution_mode: ultra
approved_by: user
approved_at: 2026-07-16
open_questions: []
---

# Delivery Spec: 三个路由型技能合并与旧 Worker 生成物治理

## Document Purpose

- intended_for: ultra-implementation / independent-signoff
- purpose: 固定三个技能合并目标、动态部署单一来源、旧版本清理兼容和验收要求。
- canonical_path: `docs/version-tracker/1.4.2-SNAPSHOT/workitems/CLEAN-006-three-router-skill-consolidation.md`

## Goal

- version_goal: 继续降低 Ultra 编码会话的技能发现负担和过时流程误触发。
- target_outcome: 将 8 个现有技能合并为 3 个默认不隐式触发的路由技能；新版 Claude Worker 启动时部署 `navigator-ops` 并安全清理旧 Worker 生成的四个技能。

## Scope

- in_scope:
  - `ask-agent`、`navigator-admin`、`scheduled-task`、`sharing-key` 合并为 `navigator-ops`。
  - `navigator-upstream-cli`、`navigator-upstream-llm-integration` 合并为 `navigator-upstream-integration`。
  - `claude-proxy-deploy`、`claude-worker-deploy` 合并为 `claude-worker-release`。
  - 三个新技能的主 `SKILL.md` 只承担场景路由和安全门槛，详细流程放入 `references/`。
  - Claude Worker 以自身打包资源为 `navigator-ops` 唯一内容来源，在启动和平台同步请求时执行幂等 reconcile。
  - 新 Worker 从 `~/.agents/skills`、`~/.agent/skills`、`~/.claude/skills` 清理可识别的旧平台生成物；未知同名内容、链接、reparse point 和旁路用户文件必须保留。
  - 停止 Java 控制面维护重复的 `ask-agent` 模板；在线同步只请求 Worker 自行 reconcile。
  - 对被替换技能做可恢复备份，并移出当前技能发现目录。
- affected_modules: `tools/claude-agent-worker`、`addons/claude-worker-agent`、`.agents/skills`、个人技能目录
- external_dependencies: 个人技能备份目录，仅用于可逆恢复。

## Non-Goals

- out_of_scope:
  - 不治理 `x3-platform-cli`、`x3-tms-cli`、`payment-openapi-integration` 或其他未列出的技能。
  - 不修改 Navigator 业务 API、数据库、凭据模型、任务执行语义或 Worker 发布版本号。
  - 不重写被合并技能引用的业务流程，只修正合并后失效的技能名和路径。
- do_not_touch: 当前工作区内终止操作、任务生命周期及其他功能开发的未提交修改；个人技能中的真实本地凭据文件不得复制到备份或仓库。

## Confirmed Decisions

| Decision | Rationale | Compatibility / Constraint |
|---|---|---|
| 三个目标技能均采用短路由入口与按需 references | 降低初始发现元数据和误触发，同时保留专项知识 | 主入口必须能明确选择唯一参考文件 |
| 三个目标技能设置 `allow_implicit_invocation: false` | 发布、平台管理和跨系统接入不应因普通编码语义自动触发 | 仍可通过 `$skill-name` 显式调用 |
| `navigator-ops` 内容由 Python Worker 打包资源单一维护 | 消除 Python、Java 模板和在线推送的多源漂移 | Java 可请求 reconcile，但不得再次发送旧技能正文 |
| retired 清理使用平台标记或严格旧版签名 | 满足旧 Worker 升级清理且避免误删用户技能 | 只删除已识别的 `SKILL.md`；目录非空时保留其他文件 |
| 平台同步端点保持兼容 | 允许控制面与 Worker 交叉升级 | 旧控制面向新 Worker 推送 retired 名称时应被拒绝并清理；新控制面对旧 Worker 的空同步为安全无操作 |

## Acceptance Criteria

- [x] AC-1: 本次八个旧技能对应的发现入口只保留三个新路由技能；旧技能均有无敏感信息的可恢复备份并已移出。
- [x] AC-2: `navigator-ops` 在 Worker 启动时完整部署；再次运行可幂等更新自身且不覆盖未知同名用户技能。
- [x] AC-3: 新 Worker 可从三个历史根目录删除四个旧技能的已识别 Worker 生成版本，并保留未知内容、链接和同目录用户文件。
- [x] AC-4: 旧 Java 控制面推送 `ask-agent` 到新 Worker 时不能使其复活；新 Java 控制面不再维护或推送重复模板。
- [x] AC-5: 三个新技能均通过结构校验，路由描述覆盖原能力且默认禁止隐式调用。
- [x] AC-6: Python Worker 与 Java addon 的相关自动化测试实际执行并通过，或精确记录环境阻塞和剩余风险。

## Contract / Data / Security Constraints

- API or event contract: 保留 `POST /api/v1/platform-skills/deploy` 及现有 `skills` 请求字段、`deployed` 响应字段；retired 名称不再允许写回。
- data and migration: 无数据库迁移；文件系统迁移在 Worker 启动/reconcile 时幂等执行。
- compatibility and rollback: 备份可恢复旧技能；新旧控制面与 Worker 交叉升级不得造成旧技能复活或启动失败。
- permissions and secrets: 不记录或复制 Token、API Key、密码及 `*.local.env`；日志不得输出技能正文或凭据。

## Test and Evidence Obligations

| Item | Risk | Required Validation | Required Evidence |
|---|---|---|---|
| AC-1 / AC-5 | medium | 备份清单、发现目录检查、三个技能 `quick_validate.py` | 命令、数量、哈希和校验结果 |
| AC-2 / AC-3 | major | Python 单元测试覆盖首次部署、幂等更新、旧签名、未知文件、链接和三根目录 | 精确 pytest 命令和结果 |
| AC-4 | major | route 回归测试与 Java syncer 测试/模块测试 | 旧推送拒绝及空 reconcile 证据 |
| AC-6 | medium | Worker 相关测试及 Java addon Maven 测试 | 实际执行结果或阻塞原因 |

## Risks and Open Questions

- known_risks: 当前已打开的 Codex 会话仍可能显示启动时注入的旧技能列表，需新会话才反映文件系统结果；旧 Worker 不具备 reconcile 能力，只能等待升级重启。
- open_questions: none

## Ultra Execution Contract

- 先读取本文件、项目 `AGENTS.md`、`CLAUDE.md`、相关模块 `AGENTS.md` 和目标技能。
- 在 scope 内自主决定具体文件与函数结构，但不得扩大到其他技能或业务 API。
- 如需改变目标、范围、兼容或安全边界，设置 `NEEDS_REPLAN` 并停止扩展。
- 运行与改动面匹配的验证，不得声称未实际运行的测试通过。
- 完成后填写 `Implementation Result`，并将状态改为 `READY_FOR_SIGNOFF`；不得自行设置 `ACCEPTED`。

## Implementation Result

- implementation_summary:
  - 将四个 Worker/个人运维技能合并为 `navigator-ops`，由 Python Worker 的多文件 bundle 单一维护；启动及同步端点统一执行 reconcile。
  - retired 清理覆盖 `ask-agent` 的通用协作版和 scheduled-only 版，以及 `navigator-admin`、`scheduled-task`、`sharing-key`；继续兼容清理此前已退休的 `cross-project-task`。
  - Java 控制面删除重复模板与本地部署器，只向在线 Worker 发送空 `skills` 映射请求其使用本地 bundle 对账；旧 Worker 对该请求安全无操作。
  - 将两个上游接入技能合并为个人 `navigator-upstream-integration`，将两个 Claude 发布技能合并为项目 `claude-worker-release`；三者均采用短入口、按需 references，且禁止隐式调用。
- changed_paths:
  - `tools/claude-agent-worker/src/agent_worker/platform_skills/deployer.py`
  - `tools/claude-agent-worker/src/agent_worker/platform_skills/navigator_ops/**`
  - `tools/claude-agent-worker/src/agent_worker/routes/platform_skills.py`
  - `tools/claude-agent-worker/tests/test_platform_skill_deployer.py`
  - `tools/claude-agent-worker/tests/routes/test_platform_skills.py`
  - 删除 Worker 的 `ask_agent.md`、`navigator_admin.md`、`scheduled_task.md` 旧单文件模板。
  - `addons/claude-worker-agent/.../PlatformSkillSyncer.java` 与 `PlatformSkillSyncerTest.java`
  - 删除 Java `PlatformSkillDeployer`、旧测试及 `platform-skills/ask-agent/SKILL.md.template`。
  - `.agents/skills/claude-worker-release/**`，并删除项目旧 `claude-proxy-deploy`、`claude-worker-deploy` 入口。
  - 个人 `/mnt/c/Users/oldse/.agents/skills/navigator-ops/**` 与 `navigator-upstream-integration/**`，并移除对应六个旧个人入口。
  - `docs/test-cases/ask-agent-test-plan.md`
- tests_and_results:
  - `cd tools/claude-agent-worker && .venv/bin/python -m pytest -q`：`539 passed, 11 skipped in 3.01s`。
  - `cd tools/claude-agent-worker && .venv/bin/python -m pytest tests/test_platform_skill_deployer.py tests/routes/test_platform_skills.py -q`：`23 passed in 0.30s`。
  - `mvn test -pl addons/claude-worker-agent -am -Dtest=PlatformSkillSyncerTest -Dsurefire.failIfNoSpecifiedTests=false`：`BUILD SUCCESS`，定向测试 `1/1` 通过。
  - `mvn -q -Dstyle.color=never test -pl addons/claude-worker-agent -am`：退出码 `0`；Claude addon Surefire 汇总 `383 tests, 0 failures, 0 errors, 0 skipped`。
  - 四个新/安装技能目录执行 `quick_validate.py`：全部 `Skill is valid!`；四个 `agents/openai.yaml` 均确认 `allow_implicit_invocation: false`。
  - 隔离构建 `claude_agent_worker-0.1.7-py3-none-any.whl` 并检查 bundle 文件：通过；最终 wheel SHA-256 `b6502639048e56a8dc924f44d2e0f285642741f776618051b35c051d2ea2ca35`。
  - 路径级 `git diff --check`、旧模板/Java 所有权扫描、占位符渲染和旧发现目录扫描：通过。
- manual_or_experience_evidence:
  - 备份清单：`/mnt/c/Users/oldse/.agents/skill-backups/2026-07-16-three-router-skill-merge/MANIFEST.md`。
  - `personal-skills.tar.gz` SHA-256：`a6c747255a67a15bc60c570968f6b212e3c805eb84999373af2fbffcea6b5a3c`；`repository-skills.tar.gz` SHA-256：`c3f732b53563ae5e99f4eb7c3064c4edbdba75a7fb2ab3850a0d80564fd3d776`。
  - 两个压缩包通过完整性与哈希校验；个人备份清单确认不包含 `*.local.env`。
  - 三个主路由入口合计 82 行（32/27/23），详细流程均留在 references；个人 `navigator-ops` 与 Worker 源包除环境地址渲染外一致。
- deviations: none
- residual_risks:
  - 当前已打开会话仍可能显示启动时注入的旧技能列表，需新会话重新发现文件系统技能。
  - 尚未升级的新旧 Worker 本身不具备 retired reconcile；必须安装并启动本次新版 Worker 后才会自动清理其历史生成物。
  - 本次未执行 Worker 正式发布或线上重启；发布动作仍应通过独立 release 流程完成。
- readiness: READY_FOR_SIGNOFF

## References

- requirement / issue: 用户于 2026-07-16 确认执行技能审计第 4 项，并要求从 Worker 代码治理动态技能及旧版本生成物。
- architecture / glossary: `AGENTS.md`、`CLAUDE.md`、`tools/claude-agent-worker/AGENTS.md`、`addons/claude-worker-agent/AGENTS.md`
- related work items: `CLEAN-005-ultra-skill-governance`
