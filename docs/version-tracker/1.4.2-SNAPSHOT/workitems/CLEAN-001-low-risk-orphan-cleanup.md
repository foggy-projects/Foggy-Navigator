---
type: cleanup
version: 1.4.2-SNAPSHOT
ticket: CLEAN-001
priority: medium
status: planned
source: REQ-001
owner: root-workspace
---

# 低风险孤儿文件与失效材料清理

## 文档作用

- doc_type: workitem
- intended_for: project-root-session | cleanup-owner | reviewer | signoff-owner
- purpose: 对低风险候选逐项执行引用扫描、替代确认、验证和可逆删除，不把候选清单直接当成删除授权。

## 关联文档

- [版本索引](../README.md)
- [REQ-001 平台治理与历史能力收口](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [实施计划](../implementation-plan.md)
- [统一进度记录](../progress.md)

## 执行原则

1. 本文是 `record`，当前所有候选均为 `planned / not-run`；规划阶段不删除文件。
2. 每个候选必须单独记录引用扫描命令、扫描输出摘要、替代或迁移说明、验证命令、结果、删除提交和回滚方式。
3. “文本搜索无引用”只属于静态结论；动态 import、框架约定、脚本手工入口、外部文档或发布打包仍需人工确认。
4. 如果扫描发现运行消费者、发布依赖或 Owner 反对，该项立即移出低风险切片，转入独立审计，不得为了完成清单强删。
5. 删除按可独立回滚的小组提交；禁止把前端 API、mobile 组件、Worker 生成物和历史文档揉成一个提交。

## 证据分类

### 已确认事实

- 1.4.2 只规划分级清理，本轮不执行实际删除。
- 所有删除必须满足 REQ-001 的 AC-10：引用、迁移/替代、验证和回滚证据齐备。
- `CodingAgentEntity` 与 `/api/v1/coding-agents` 是当前通用 Agent 注册能力，不属于本事项删除范围。

### 静态搜索结论

- 下表中的精确文件当前存在且已被 Git 跟踪。
- `addons/coding-agent` 的可跟踪内容只剩 integration-tests lockfile；旧 addon 源码、manifest 和测试已不存在。
- 两个 memory E2E 脚本仍硬编码已移除的 `tutor-agent` 和本机日志路径。
- 两个 tooltip 文件和根 Worker tab spec 未接入当前声明的测试入口。
- `TaskCard.vue` 未发现源码 import 或模板使用，但项目级 mobile skill 仍列出该组件。
- Navigator 前端目录下三张手工截图未发现仓库引用。
- Claude Worker 的 `egg-info` 是已跟踪生成物，且其中版本元数据已陈旧。
- 文本启发式发现一组仅在定义处出现的前端 API export；该结论不足以直接删除。
- 旧 Coding Agent/OpenHands skills 仍指向已不存在的 addon；若干历史设计文档仍把 tutor/chat-first/OpenHands 写成当前路径。

### 需要运行态确认

- 手工脚本是否仍由团队 runbook、个人自动化或外部 CI 调用。
- mobile 的 easycom/打包约定是否可能动态发现 `TaskCard.vue`。
- chat API export 是否被包外消费者、测试生成器或动态名称调用。
- 旧 skills 是否仍被外部 marketplace、Worker 发布包或其他 checkout 同步。

### 决策项

- 历史设计文档是删除、移动到历史区还是加醒目的失效声明，由 documentation owner 决定。
- `packages/foggy-chat/pnpm-lock.yaml` 属于构建基线决策，不在本事项顺手删除。
- 旧测试 mock 只有形成精确文件/符号清单后才能进入删除批次；没有清单时本项保持 no-op。

## 删除证据登记模板

每一行候选在执行前都要在 [Progress](../progress.md) 或对应 execution check-in 登记：

| 字段 | 必填内容 |
|---|---|
| candidate | 精确路径或符号 |
| owner | 确认无消费者的责任人 |
| git_blob | `git ls-tree -r HEAD -- <path>` 输出 |
| reference_scan | 实际命令、时间、commit SHA、输出摘要 |
| replacement | 替代测试/页面/文档，或明确 not-applicable 及原因 |
| automated_verification | 命令、退出码、报告路径 |
| manual_verification | 步骤和结果；not-applicable 必须说明原因 |
| deletion_commit | 仅包含该可回滚切片的提交 |
| rollback | `git revert <commit>` 或恢复步骤 |

## 候选逐项门禁

### C1：Coding Agent integration-tests 孤儿 lockfile

- candidate: `addons/coding-agent/integration-tests/package-lock.json`
- 静态结论：目录中无已跟踪 `package.json`、测试或源码；旧 addon 删除提交可追溯，但旧 skills 仍引用该目录。
- 引用扫描：

~~~bash
git ls-files addons/coding-agent/integration-tests
find addons/coding-agent/integration-tests -maxdepth 3 -type f -not -path '*/target/*' -print
rg -n --hidden 'addons/coding-agent/integration-tests|coding-agent-integration-tests' . \
  -g '!**/.git/**' -g '!**/target/**' -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
~~~

- 删除前条件：确认不存在独立 npm project、外部 CI cache path 或 marketplace 打包依赖；旧 skills 同批处理或明确先后关系。
- 自动验证：Markdown/skill 相对链接检查、根 `git diff --check`；不存在 manifest，因此不得虚构 `npm test` 结果。
- 手工验证：检查 Codex skill discovery 列表不再暴露已失效的 Coding Agent 测试技能。
- 回滚：记录 blob SHA，lockfile 与失效 skills 分两个可逆提交；失败时分别 `git revert`。

### C2：PowerShell memory E2E 脚本

- candidate: `test-memory-e2e.ps1`
- 静态结论：无文件名调用引用；脚本硬编码 `tutor-agent`、root 默认账号和本地日志位置。
- 引用扫描：

~~~bash
rg -n --hidden 'test-memory-e2e[.]ps1|Memory E2E Test|tutor-agent|config/platform/memories' . \
  -g '!**/.git/**' -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
~~~

- 删除前条件：memory owner 确认已有当前 Agent/Session 架构下的自动化或明确不再维护该手工脚本。
- 自动验证：运行现有 user-memory/Session 相关单测或集成测试；具体命令从当前测试规范读取后回写，当前 `not-run`。
- 手工验证：not-applicable，仅删除失效脚本；若 owner 仍需要 smoke，先迁移为不含默认凭据和固定路径的新测试。
- 回滚：脚本单独提交；需要恢复时 `git revert`，不得从聊天记录复制旧凭据脚本。

### C3：Python memory E2E 脚本

- candidate: `test_memory_e2e.py`
- 静态结论：无文件名调用引用；硬编码 Windows `D:/foggy-projects` 日志路径和 `tutor-agent`。
- 引用扫描：

~~~bash
rg -n --hidden 'test_memory_e2e[.]py|D:/foggy-projects/Foggy-Navigator|tutor-agent|Listed.*memories' . \
  -g '!**/.git/**' -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
~~~

- 删除前条件：与 C2 相同，并确认没有计划任务直接调用文件路径。
- 自动验证：与 C2 共用当前 memory 回归证据，但单独登记扫描和 blob。
- 手工验证：not-applicable；如需替代，替代脚本必须使用环境变量和非生产测试账号。
- 回滚：与 C2 可同属“旧 memory scripts”提交，但进度中分别登记；`git revert` 整组。

### C4：独立 tooltip 手工脚本

- candidate: `packages/navigator-frontend/test-tooltip.ts`
- 静态结论：无 npm script、Vitest 或 Playwright config 引用。
- 引用扫描：

~~~bash
rg -n --hidden 'test-tooltip[.]ts|01-homepage[.]png|05-tooltip-hovered[.]png' \
  packages/navigator-frontend package.json scripts .github \
  -g '!**/node_modules/**' -g '!**/dist/**'
~~~

- 删除前条件：frontend owner 确认 tooltip 缺陷已有正式测试或不再属于当前 UI。
- 自动验证：主前端有效 type-check、Vitest、production build；如果正式 tooltip 行为仍重要，应先在 `e2e/` 补稳定用例。
- 手工验证：悬停当前受影响表格单元格，确认 tooltip 位置与可读性；若功能已不存在则记录 not-applicable 和页面证据。
- 回滚：独立 tooltip 文件提交，`git revert`。

### C5：未被测试框架发现的 tooltip spec

- candidate: `packages/navigator-frontend/tooltip-test.spec.ts`
- 静态结论：`vite.config.ts` 明确排除该文件；`playwright.config.ts` 的 `testDir` 为 `./e2e`。
- 引用扫描：

~~~bash
rg -n --hidden 'tooltip-test[.]spec[.]ts|testDir|exclude' \
  packages/navigator-frontend .agents/skills/testing-guide \
  -g '!**/node_modules/**' -g '!**/dist/**'
~~~

- 删除前条件：同步修正仍把它写成示例的 testing skill；若测试仍有价值，先迁入正式 `e2e` 并稳定选择器。
- 自动验证：`playwright test --list` 确认删除前后正式用例集合符合预期；主前端 type/test/build。
- 手工验证：与 C4 共用 tooltip 体验步骤。
- 回滚：测试文件与对应失效 skill 说明分别可回滚，避免恢复一个仍不可发现的“假测试”。

### C6：根 Worker tab 手工 spec

- candidate: `test-worker-tab.spec.ts`
- 静态结论：无根 Playwright script/config 引用，且硬编码旧端口 `5175`。
- 引用扫描：

~~~bash
rg -n --hidden 'test-worker-tab[.]spec[.]ts|CLI 进程 tab should be default active|localhost:5175' . \
  -g '!**/.git/**' -g '!**/node_modules/**' -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
~~~

- 删除前条件：确认当前 Workers 正式 E2E 已覆盖默认 tab，或先在主前端 `e2e` 中补覆盖。
- 自动验证：主前端 Playwright `--list`、相关 Workers 用例、type/test/build。
- 手工验证：登录当前 `5174` UI，打开 Worker 面板，确认默认 tab 与产品预期一致。
- 回滚：单独提交，`git revert`。

### C7：未引用 mobile TaskCard

- candidate: `packages/foggy-mobile/src/components/TaskCard.vue`
- 静态结论：未发现 import、`<TaskCard>` 或 `<task-card>` 使用；mobile skill 仍列出该组件。
- 引用扫描：

~~~bash
rg -n --hidden 'TaskCard|<task-card|task-card' \
  packages/foggy-mobile/src packages/foggy-mobile/pages.json .agents/skills/foggy-mobile-dev \
  -g '!**/node_modules/**' -g '!**/unpackage/**'
~~~

- 删除前条件：mobile owner 复核 easycom、分包和原生打包约定；同步更新组件清单。
- 自动验证：mobile type-check、Vitest、H5 build；若当前发布包含微信小程序，再执行对应 build。
- 手工验证：任务列表与任务详情的卡片、状态、Provider、费用和时长展示；若页面已改用 `SessionCard.vue`，记录替代路径。
- 回滚：组件和 skill 清单同一可逆提交，`git revert` 后重新运行 mobile build。

### C8：Navigator 前端无引用手工截图

- candidates:
  - `packages/navigator-frontend/no-attention.png`
  - `packages/navigator-frontend/refactored.png`
  - `packages/navigator-frontend/workers-fixed.png`
- 静态结论：三张 1280x720 跟踪图片未发现 Markdown、Vue、CSS 或测试引用。
- 引用扫描：

~~~bash
rg -n --hidden 'no-attention[.]png|refactored[.]png|workers-fixed[.]png' . \
  -g '!**/.git/**' -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
git log --oneline -- packages/navigator-frontend/no-attention.png \
  packages/navigator-frontend/refactored.png packages/navigator-frontend/workers-fixed.png
~~~

- 删除前条件：frontend owner 确认它们不是人工验收的唯一证据；若需要保留证据，迁入对应版本 evidence 并补说明，而非继续散落源码目录。
- 自动验证：Markdown 链接检查、主前端 build；截图删除不以 UI 测试通过替代引用扫描。
- 手工验证：not-applicable，素材未被产品使用。
- 回滚：三张图可作为一个素材提交，`git revert`。

### C9：Claude Worker egg-info 生成物

- candidate: `tools/claude-agent-worker/src/claude_agent_worker.egg-info/`
- 静态结论：目录包含 `PKG-INFO`、`SOURCES.txt` 等生成元数据；只在自身 `SOURCES.txt` 中引用，且版本固定为旧 `0.1.0`。
- 引用扫描：

~~~bash
git ls-files tools/claude-agent-worker/src/claude_agent_worker.egg-info
rg -n --hidden 'claude_agent_worker[.]egg-info|PKG-INFO|SOURCES[.]txt' \
  tools/claude-agent-worker -g '!**/__pycache__/**'
rg -n 'egg-info' .gitignore tools/claude-agent-worker tools/claude-code-proxy/.gitignore
~~~

- 删除前条件：确认 release archive 不显式打包该目录，并补 `*.egg-info/` 忽略规则。
- 自动验证：Claude Worker unit tests、wheel/sdist build、安装后 import/health smoke；命令须从当前 pyproject/发布技能读取后回写。
- 手工验证：检查构建制品清单中只包含当前 `agent_worker` 包和动态版本。
- 回滚：生成物删除与 ignore 同一提交；`git revert` 可恢复，规范打包也应能重新生成，不以手工编辑 PKG-INFO 回滚。

### C10：未引用前端 API 导出

- candidate type: `packages/navigator-frontend/src/api/*.ts` 与 `packages/foggy-mobile/src/api/*.ts` 中仅定义处出现的 export。
- 当前启发式候选包括：
  - `notification.ts#getAgentCard`、`agentTask.ts#listAgentTasksBySession`、`users.ts#getUser`；
  - `platform.ts#getGitProvider/getModelConfig/getCredential/listCredentialsByCategory`；
  - `claudeWorker.ts#getMilestoneSessionCount/listChildDirectories/getTask/searchSessions/listTasksByDirectory/reconnectTask/resyncTask/rewindTask/scanCheckpoints/listWorkerSessions/getWorkerSessionMessages/listActiveTasks`；
  - mobile `platform.ts#getSetupStatus`。
- 引用扫描：

~~~bash
rg -n --hidden -w \
  'getAgentCard|listAgentTasksBySession|getUser|getGitProvider|getModelConfig|getCredential|listCredentialsByCategory|getMilestoneSessionCount|listChildDirectories|getTask|searchSessions|listTasksByDirectory|reconnectTask|resyncTask|rewindTask|scanCheckpoints|listWorkerSessions|getWorkerSessionMessages|listActiveTasks|getSetupStatus' \
  packages -g '*.ts' -g '*.vue' -g '!**/node_modules/**' -g '!**/dist/**'
rg -n --hidden 'from .*/api/|import[(].*/api/' packages -g '*.ts' -g '*.vue' \
  -g '!**/node_modules/**' -g '!**/dist/**'
~~~

- 删除前条件：逐符号确认无动态调用、无库对外 export、无测试生成器和无外部包消费者；不能按整文件删除。
- 自动验证：PC/mobile 有效 type-check、单测、build；API 相关 Vitest 和 E2E；公共包 API 还需 consumer build。
- 手工验证：按符号所属页面验证设置、Worker、Session、通知和 mobile setup 流程；没有对应 UI 时记录 not-applicable。
- 回滚：按 API domain 分提交，保留删除前签名清单；`git revert` 恢复，不通过重新手写旧签名。

### C11：旧测试 mock

- candidate type: 未引用 mock、fixture、`vi.mock` / `jest.mock` setup。
- 当前静态结论：仅按文件名扫描尚未形成可安全删除的精确候选，因此本项当前不得删除任何文件。
- 引用扫描：

~~~bash
rg -n --hidden 'vi[.]mock|jest[.]mock|mockImplementation|mockResolvedValue|__mocks__|fixtures' \
  packages -g '*.ts' -g '*.tsx' -g '*.vue' -g '!**/node_modules/**' -g '!**/dist/**'
rg -n --hidden 'setupFiles|globalSetup|resolve[.]alias|testDir' \
  packages -g 'vite.config.*' -g 'vitest.config.*' -g 'playwright.config.*'
~~~

- 删除前条件：先产出精确 mock 文件/符号、注册方式和消费测试清单；未产出则完成结果为 `no-op`。
- 自动验证：受影响测试的 `--list` / collection 数量、单测、E2E；确保不是因为测试未发现而“通过”。
- 手工验证：not-applicable，除非 mock 对应的浏览器场景需要真实替代验证。
- 回滚：每个测试域单独提交，`git revert`；保留删除前 collected test 数量。

### C12：已删除 tutor/OpenHands addon 的失效 skills 与文档

- skill candidates:
  - `.agents/skills/coding-agent-backend/SKILL.md`、`reference.md`
  - `.agents/skills/coding-agent-frontend/SKILL.md`、`reference.md`
  - `.agents/skills/coding-agent-integration-tests/SKILL.md`、`reference.md`
  - `.agents/skills/test-coding-agent/SKILL.md`、`README.md`
- doc review candidates:
  - `docs/frontend-design/navigator-frontend-proposal.md`
  - `docs/frontend-design/backend-api-requirements.md`
  - `docs/frontend-design/unified-frontend-design.md`
  - `docs/agent-framework-guide.md`
  - `docs/01-overview/business-architecture.md`
  - `docs/01-overview/business-architecture-updated.md`
  - `docs/ceo-architecture-and-installation.md`
- 静态结论：skills 指向不存在的 `addons/coding-agent` 与 OpenHands 前后端；历史文档包含 tutor/chat-first/语义层旧叙述，但不代表整份文档都可删除。
- 引用扫描：

~~~bash
git ls-files | rg 'coding-agent-(backend|frontend|integration-tests)|test-coding-agent'
rg -n --hidden 'addons/coding-agent|OpenHands|tutor-agent|chat-first' \
  .agents docs CLAUDE.md README.md -g '!docs/version-tracker/1.4.2-SNAPSHOT/**'
rg -n --hidden 'coding-agent-backend|coding-agent-frontend|coding-agent-integration-tests|test-coding-agent' \
  . -g '!**/.git/**'
~~~

- 删除前条件：skill owner 确认不存在外部 marketplace 同步；documentation owner 对每份文档决定 `current-update`、`historical-banner`、`archive` 或 `delete`。不得删除当前通用 Coding Agent 注册 API 的说明。
- 自动验证：skill discovery、Markdown 相对链接、docs 索引、`git diff --check`。
- 手工验证：在 Codex/Claude skill 列表中确认不再推荐不存在的 addon；当前 Worker/Agent 开发技能仍可发现。
- 回滚：skills 和文档分提交；历史文档优先加失效声明/归档，若删除则记录原路径与替代链接；均使用 `git revert`。

## 执行顺序

1. P4 开始时记录 commit SHA、Git 状态和 P1 clean build 证据。
2. 按 C1-C12 复跑引用扫描；扫描结果附到 progress，不复用本规划的旧结果。
3. 先处理纯生成物/素材，再处理手工测试，随后是 mobile/API，最后处理 skills/docs。
4. 每组删除后运行对应最小验证和 P1 全仓门禁；失败立即回滚该组。
5. 所有删除完成后执行 Markdown 链接、`git diff --check` 和 clean build。
6. 进入 implementation self-check；若涉及公共 API 或外部 skill delivery，升级正式质量检查。

## 汇总自动化验证

以下为计划命令，当前均 `not-run`：

~~~bash
mvn -B -pl launcher -am clean test
corepack pnpm install --frozen-lockfile
corepack pnpm --filter @foggy/navigator-frontend type-check
corepack pnpm --filter @foggy/navigator-frontend test
corepack pnpm --filter @foggy/navigator-frontend build
corepack pnpm --filter @foggy/mobile type-check
corepack pnpm --filter @foggy/mobile test
corepack pnpm --filter @foggy/mobile build:h5
git diff --check
git status --short
~~~

Worker、Playwright 和 Markdown link 命令按实际删除组追加，不得把不相关测试冒充候选验证。

## 风险与回滚

| 风险 | 控制 | 回滚 |
|---|---|---|
| 动态或外部消费者未被 `rg` 发现 | Owner 确认、发布包/CI/runbook 审计 | 立即 revert 对应小组 |
| 删除“假测试”后实际覆盖进一步下降 | 先确认 collected tests，必要时先迁入正式套件 | revert 或恢复为正式可发现测试 |
| mobile auto component 被误判 | easycom/页面/分包复核及 H5/小程序验证 | revert component + skill 提交 |
| API export 被包外使用 | public export/consumer build 和弃用审计 | revert API domain 提交 |
| 历史文档丢失决策背景 | 优先历史 banner/archive，删除需替代链接 | revert docs 提交 |
| 生成物重新出现 | 补 ignore 并验证规范打包 | revert 或修正 ignore，不手改生成物 |

## 完成判据

- [ ] C1-C12 每项都有实际扫描命令、commit SHA、输出摘要和 Owner。
- [ ] 每个删除项都有替代/迁移说明，或经证据确认 not-applicable。
- [ ] 所有删除组均有自动验证、必要手工验证和退出码。
- [ ] 未发现精确候选的“旧测试 mock”以 no-op 收口，没有笼统删除。
- [ ] skills/docs 不再指向不存在的 tutor/OpenHands addon，同时保留当前通用 Coding Agent 能力。
- [ ] PC/mobile/Worker/Markdown 对应门禁通过。
- [ ] 每组删除都有独立 commit 和已验证的 `git revert` 路径。
- [ ] 删除清单、changed paths 和证据已回写 [Progress](../progress.md)。
- [ ] `git diff --check`、`git status --short` 和相对链接检查已记录。

## 生产路由与外部契约状态

- current_production_routing_changed: no
- current_external_contract_changed: no
- deletion_allowed_to_change_external_contract: no

若任一候选被证明属于运行路由、SDK/包公开 API、外部 skill 或发布制品，该候选必须退出 CLEAN-001，转入带兼容窗口的独立工作项。
