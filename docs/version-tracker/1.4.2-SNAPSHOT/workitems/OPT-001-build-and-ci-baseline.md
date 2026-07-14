---
type: optimization
version: 1.4.2-SNAPSHOT
ticket: OPT-001
priority: high
status: in-progress
source: REQ-001
owner: root-build-owner
---

# 构建与 CI 可复现基线

## 文档作用

- doc_type: workitem
- intended_for: project-root-session | build-owner | reviewer | signoff-owner
- purpose: 固化 Java、前端与 Worker 的 clean build、工具链、lockfile 和全仓 CI 门禁。

## 关联文档

- [版本索引](../README.md)
- [REQ-001 平台治理与历史能力收口](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [实施计划](../implementation-plan.md)
- [ODR-142 Owner 决策记录](../owner-decision-review.md)
- [统一进度记录](../progress.md)

## 背景与目标

当前 Java launcher 主依赖链有可用的历史构建基线，但前端工具链、根 lockfile、类型检查命令和 CI 覆盖此前不能保证从 clean 环境稳定复现。本事项已于 2026-07-14 开工，只治理构建和合并门禁，不改变业务路由；目标是让同一提交在受支持环境中以固定工具链、冻结依赖和明确矩阵得到一致结果，并把所有未执行或平台条件跳过项如实写入 [进度记录](../progress.md)。

## 证据分类

### 已确认事实

1. Owner 已批准 ODR-142-001：CI 与版本文件固定 Node `22.23.1`，根 `engines` 声明 `>=22.23.1 <23`，包管理器固定为 pnpm `10.34.5`。
2. Java 使用 JDK 17，根 Maven reactor 和 `launcher` 是当前服务端构建入口。
3. 构建成功必须来自 clean 环境；旧 `target`、`dist`、`node_modules` 或本机缓存不能作为通过证据。
4. 本轮已生成根 `pnpm-lock.yaml`、移除 `packages/foggy-chat/pnpm-lock.yaml`，并以精确 Node/pnpm 工具运行 lockfile-only install、frozen install、全前端 typecheck/test/build。
5. `mvn -B -pl launcher -am clean test` 已完成，16 个 reactor project 均为 `SUCCESS`，测试 `0 failures`，命令退出码为 `0`。
6. `.github/workflows/repository-ci.yml` 已落地 Java、前端、Node Worker 与 Python Worker lane；五类 Worker 已在独立 clean worktree 本地实跑，workflow 仍未在 GitHub runner 执行，不能登记为 hosted CI pass。
7. 修复 [BUG-002](./BUG-002-open-sdk-clean-test-baseline.md) 后，根 `mvn -B clean test` 已完成：17 个 reactor project 均为 `SUCCESS`，286 份 Surefire XML 汇总 2304 tests、0 failure/error/skipped，exit `0`；根 `clean verify` 仍未执行。

### 静态搜索结论

1. 根 `pom.xml` 在 metadata-query 切片移除后当前包含 16 个 reactor 模块；`launcher/pom.xml` 已不再装配 metadata-query，仍直接装配 Session、Business Agent、metadata-config、Claude/Codex/Gemini/LangGraph、Echo 和 Task Assistant。删除后 `mvn -B -pl metadata-config-module,launcher -am clean test` 已 15/15 `SUCCESS`；既有删除前 16-reactor launcher 记录继续作为独立历史基线保留。
2. 根 `package.json` 已提供 `typecheck:frontend`、`test:frontend`、`build:frontend` 和 `ci:frontend` 聚合命令，覆盖 chat-core、chat、widget、navigator-frontend 和 mobile。
3. `pnpm-workspace.yaml` 和根 `package.json` 均把 `packages/*` 纳入 workspace，根 lockfile 包含根 importer 及 chat-core、chat、mobile、widget、navigator-frontend 五个 package importer。
4. `.gitignore` 已显式放行根 `pnpm-lock.yaml`，根 lockfile 已作为本轮单一 workspace 依赖记录生成；是否已进入最终提交仍以版本控制提交结果为准。
5. `packages/foggy-chat/pnpm-lock.yaml` 已从本轮变更中移除，不再与根 lockfile 形成双权威。
6. 根 manifest 已声明 `packageManager: pnpm@10.34.5` 和受支持 Node `engines`。
7. 当前 Vite 7 安装结果要求 Node `^20.19.0 || >=22.12.0`；README 已从旧 `Node.js 18+` 声明对齐到本轮 Node 22 基线。
8. `packages/navigator-frontend/package.json` 的有效类型检查已显式检查 app project；此前定位的 `ClaudeWorkerView.vue` 两个 TypeScript 错误已作窄修复并通过聚合 typecheck。
9. `.github/workflows/repository-ci.yml` 已补充全仓构建 workflow；它与 Codex Worker release candidate 发布流程相互独立。

### 需要运行态确认

1. 独立 Linux clean checkout 以及 Windows/WSL clean checkout 下 Node、Corepack、pnpm 的安装和 lockfile 解析一致性；本轮 frozen install 来自当前工作树，不等同于跨 checkout 复现证据。
2. Maven 全 reactor 与 `launcher -am` 的实际耗时、资源上限和测试稳定性。
3. mobile H5、微信小程序、widget Playwright 的 required/nightly 最终分层和实际 runner 时长。
4. Claude、Codex、Gemini、LangGraph Worker 在 GitHub runner 的平台依赖和无凭据测试结果；本机已完成 Node 22.23.1 与 Python 3.12.3 等价命令，GitHub Python 3.11 lane 仍待实跑。
5. CI 缓存关闭与开启时是否仍能从 frozen lockfile 得到同一依赖图。

### 决策项

| 决策 | 结论与实施状态 | Owner | 当前状态 |
|---|---|---|---|
| Node 精确版本 | 已批准并落地：CI/版本文件 `22.23.1`，`engines >=22.23.1 <23` | root build owner | approved/implemented |
| pnpm/Corepack | 已批准并落地：`packageManager: pnpm@10.34.5`；精确工具执行结果见下文 | frontend/build owner | approved/implemented |
| chat 独立 lockfile | 已批准并移除嵌套 lockfile，根 workspace lockfile 成为单一权威 | chat owner | approved/implemented |
| mobile/widget 门禁层级 | 核心 type/test/build 已进入根矩阵；required/nightly 分层仍需用 runner 时长定稿 | frontend/mobile owner | in-progress |
| Worker 矩阵 | Node/Python 无凭据 job 已写入 workflow；本机 clean worktree 逐项通过，GitHub runner 结果待补 | Worker owners | in-progress |

已完成的冻结决策不等同于 workitem 完成。GitHub required check 生效、nightly 分层、Worker 执行和跨 checkout 复现证据补齐前，不得宣称全仓 CI 门禁已经验收。

## Execution Check-in（2026-07-14）

### 已实施

1. 固定 Node `22.23.1` 和 pnpm `10.34.5`，根 `engines` 保持 Node 22 范围约束，CI 使用精确 Node patch。
2. 生成根 `pnpm-lock.yaml` 并解除其忽略规则；移除 chat 独立 lockfile；mobile 对 chat-core 的本地依赖对齐为 workspace 依赖。
3. 根前端矩阵已覆盖 chat-core、chat、widget、navigator-frontend 与 mobile 的有效 typecheck、test 和 build 入口。
4. 新增 `.github/workflows/repository-ci.yml`，包含 Java、全前端、Node Worker 与 Python Worker jobs；现有发布 workflow 保持独立。
5. 对 `ClaudeWorkerView.vue` 的两个已知 TypeScript 错误作窄修复，未通过排除文件或跳过类型检查制造绿色结果。

### 已执行证据

| 命令 | 环境/范围 | 结果 | 证据边界 |
|---|---|---|---|
| `pnpm install --lockfile-only --ignore-scripts` | 通过 Node `22.23.1`、pnpm `10.34.5` 精确工具执行 | passed，exit `0` | 生成根 lockfile；不是第二个 clean checkout 证据 |
| `pnpm install --frozen-lockfile --force` | 同上，根 workspace | passed，exit `0` | lockfile 未被安装命令改写；存在依赖告警，不等同于零告警 |
| `pnpm run typecheck:frontend` | chat-core、chat、widget、PC、mobile 聚合类型检查 | passed，exit `0` | 已关闭主前端两个已知 TypeScript 错误 |
| `pnpm run ci:frontend` | 根 workspace typecheck/test/build 聚合 | passed，exit `0` | 测试存在预期 stderr/构建告警；未在 GitHub runner 执行 |
| `pnpm run build:frontend` | chat-core、chat、widget、PC、mobile H5 独立复跑 | passed，exit `0` | 有 chunk、导入方式及 uni-app 版本提示，未登记为失败 |
| `mvn -B -pl launcher -am clean test` | JDK 17、launcher 依赖链 clean test | passed，exit `0`；16 reactor `SUCCESS`；测试 `0 failures` | 尚未补根 `mvn -B clean verify` 和 GitHub runner 证据 |
| `mvn -B clean test` | JDK 17、根 reactor；关闭 [BUG-002](./BUG-002-open-sdk-clean-test-baseline.md) 后 | passed，exit `0`；17/17 reactor `SUCCESS`；2304 tests、0 failure/error/skipped；总时 `05:43` | launcher 结束时有 Surefire fork JVM 退出超时告警；GitHub runner 与 `clean verify` 未执行 |
| Codex SDK `npm ci && typecheck && test && build` | 独立 clean worktree，Node `22.23.1` | passed，159 pass、1 skip，exit `0` | 无凭据 lane；不含真实 Codex/外部联调 |
| Codex app-server `npm ci && typecheck && test && build` | 独立 clean worktree，Node `22.23.1` | passed，269 pass、1 skip，exit `0` | 长生命周期/安装脚本单测通过；不等于 RC 或生产证据 |
| Gemini `npm ci && typecheck && build` | 独立 clean worktree，Node `22.23.1` | passed，exit `0` | package 无 test script；`npm audit` 报 1 low/4 moderate，登记为非阻断依赖风险 |
| Claude `pip check && pytest -m "not e2e" && build` | 独立 clean worktree，Python `3.12.3` | passed，495 pass、11 deselected，wheel/sdist 成功，exit `0` | 当前机器无 Python 3.11；GitHub 3.11 lane 未跑 |
| LangGraph `pip check && pytest -m "not e2e" && build` | Python `3.12.3`；修复 [BUG-001](./BUG-001-langgraph-progress-event-duplication.md) 后 | passed，758 pass、3 warnings，wheel/sdist 成功，exit `0` | 复跑显式清除本机代理变量；GitHub 3.11 lane未跑 |

### 尚未执行/尚未生效

1. `.github/workflows/repository-ci.yml` 尚未由 GitHub runner 执行，分支保护中的 required check 也尚无生效证据。
2. 五类 Worker 已在独立 clean worktree 按 CI 等价命令本机执行通过；GitHub runner，特别是 Python 3.11，尚未执行。
3. `.github/workflows/repository-nightly.yml` 已建立 full reactor、前端扩展目标和跨平台 Worker 矩阵，但尚未由 runner 执行；required check/分支保护也未生效。
4. 第二个 Linux clean checkout、Windows/WSL clean checkout、根 `mvn -B clean verify` 仍待补证据；根 `clean test` 已通过，不与 `verify` 混同。

## 精确代码与配置清单

| 路径 | 当前角色 | 计划动作 |
|---|---|---|
| `pom.xml` | Java 根 reactor | 核对全 reactor clean verify 入口，不借机调整业务模块 |
| `launcher/pom.xml` | 生产 launcher 装配 | 建立 `launcher -am` clean test/package 门禁 |
| `package.json` | 根 workspace scripts | 已增加有效 type/test/build/ci 聚合命令及工具版本声明；待 runner 验证 |
| `pnpm-workspace.yaml` | pnpm workspace 范围 | 保持五个包的单一 workspace 定义 |
| `pnpm-lock.yaml` | 根依赖锁定 | 已用冻结 pnpm 版本生成并通过 frozen install；待 clean checkout 复验和最终提交确认 |
| `packages/foggy-chat/pnpm-lock.yaml` | 旧嵌套 lockfile | 已移除，统一使用根 workspace lockfile |
| `.gitignore` | 全局忽略规则 | 已取消根 lockfile 的错误忽略，保留 node_modules/dist 忽略 |
| `README.md` | 环境要求 | 已对齐 Node 22、pnpm 和根构建命令 |
| `packages/foggy-chat-core/package.json` | chat-core build | 纳入根 build；补必要的 test/type 门禁或明确 not-applicable 及原因 |
| `packages/foggy-chat/package.json` | chat library | 纳入 test/build |
| `packages/navigator-chat-widget/package.json` | 外部 widget 交付物 | 纳入 test/build，按层级执行 Playwright |
| `packages/navigator-frontend/package.json` | 主前端 | 修正有效 type-check，纳入 test/build |
| `packages/foggy-mobile/package.json` | mobile 交付物 | 增加稳定 type-check 入口并纳入 test/H5 build；小程序按矩阵执行 |
| `scripts/build-frontend.sh`、`scripts/build-frontend.ps1` | 本地聚合脚本 | 与根 scripts 和 frozen install 对齐，不再跳过 widget/mobile |
| `.github/workflows/repository-ci.yml` | PR required 候选 workflow | 已新增 Java/前端/Worker 矩阵并打印工具版本；待 GitHub runner 和 required check 证据 |
| `.github/workflows/repository-nightly.yml` | 周期扩展 workflow | 已新增 full reactor、mobile mp-weixin、widget Playwright 和 Linux/Windows Worker 矩阵；尚未实跑 |
| `tools/*worker*/package.json`、`pyproject.toml` | Worker 构建入口 | 无凭据 clean lane 已写入 workflow并在本机 clean worktree 通过；待 hosted runner |

## 实施步骤

### Step 1：冻结工具链

输入与前置条件：

- Owner 已批准 ODR-142-001 的 Node `22.23.1` 与 pnpm `10.34.5`；
- build owner 按批准值实施精确 Node、pnpm/Corepack 约束；
- 不存在需要继续支持 Node 18 的主前端发布承诺，或已记录例外边界。

实施内容：

1. 在根 manifest 和版本文件中写入精确 Node/pnpm 约束。
2. 本地脚本与 GitHub Actions 统一通过 Corepack 使用同一 pnpm。
3. 增加前置校验，工具版本不满足时 fail fast，并输出期望/实际版本。
4. README 只声明真实支持的版本，不把 Worker 自身可运行于 Node 18 混同为主前端支持 Node 18。

非目标：

- 不在本步骤升级 Vite、Vue、TypeScript 或 Spring 依赖；
- 不为兼容 Node 18 注入临时 `crypto.hash` 垫片。

完成判据：

- clean shell 可机器读取并校验 Node/pnpm 版本；
- Linux 与 Windows/WSL 使用相同约束；
- 工具版本决策已回写 progress。

本轮状态：版本文件、根 manifest 和 CI 精确版本已落地；本地精确工具验证通过，跨平台 fail-fast 行为和 GitHub runner 仍待验证。

### Step 2：建立单一 workspace lockfile

1. 从 clean checkout 使用冻结 pnpm 生成根 lockfile。
2. 取消 `.gitignore` 对根 lockfile 的全局忽略。
3. 核对五个 workspace importer 均存在，且 `workspace:*` / `file:` 依赖语义符合预期。
4. 决定并处理 `packages/foggy-chat/pnpm-lock.yaml`；不得无说明保留两个权威 lockfile。
5. 在第二个 clean 目录运行 frozen install，对比 lockfile 无漂移。

完成判据：

- `corepack pnpm install --frozen-lockfile` 在 clean checkout 成功；
- 安装后 `git status --short` 不出现 lockfile 变化；
- 根 lockfile 成为唯一或明确分层的权威依赖记录。

本轮状态：根 lockfile 已生成并通过 frozen install，chat 独立 lockfile 已移除；第二个 clean checkout 复验尚未执行。

### Step 3：修正前端命令与覆盖范围

1. 让主前端 type-check 明确检查 `tsconfig.app.json` 或等效 build references。
2. 修复当前两个 TypeScript 错误，不允许用排除文件或跳过 type-check 建绿。
3. 根 scripts 按依赖顺序覆盖 chat-core、chat、widget、navigator-frontend 和 mobile。
4. 为缺少统一 `build` / `type-check` 的包增加稳定入口，明确 H5、微信小程序和声明产物边界。
5. 保证 `scripts/build-frontend.*` 与根 scripts 调用同一套命令，不维护第二套漂移矩阵。

完成判据：

- 删除所有旧 dist 后仍可构建；
- 每个纳入包有明确 pass、not-run 或 not-applicable，不能被递归命令静默跳过；
- type-check 的失败样本能使命令和 CI 返回非零。

本轮状态：全前端 typecheck/test/build 聚合命令已通过；GitHub runner 上的失败传播仍待 workflow 首次运行确认。

### Step 4：建立 Java 与 Worker clean lanes

1. Java 必须覆盖 `mvn -B -pl launcher -am clean test`。
2. 全 reactor 以 `mvn -B clean verify` 建立覆盖；如因时长拆 nightly，必须保留 launcher required lane 和差异说明。
3. Node Worker 使用各自已提交的 `package-lock.json` 与 `npm ci`。
4. Python Worker 使用锁定/可审计的依赖安装方式运行 unit tests 和 package build。
5. 真实 LLM、OBS、外部 Git 或生产凭据 smoke 不放入普通 PR lane，另设受控、显式授权的流程。

完成判据：

- Java clean test 和 launcher assembly 均有日志与退出码；
- 每类 Worker 至少有无凭据 unit/type/build 证据；
- 缺少凭据被记录为受控 smoke `not-run`，不伪装 pass。

本轮状态：Java launcher 依赖链和根 reactor `clean test` 均已通过；根 reactor 首轮暴露并关闭 [BUG-002](./BUG-002-open-sdk-clean-test-baseline.md)。Node/Python Worker jobs 已在本机 clean worktree 逐项通过，GitHub runner 与根 `clean verify` 尚未运行。

### Step 5：全仓 GitHub Actions 门禁

建议最小矩阵：

| Lane | 必需内容 | 初始建议 |
|---|---|---|
| java-launcher | JDK 17，`launcher -am clean test` 与 assembly | required |
| java-reactor | 根 `clean verify` | required；如耗时证据不足可先 nightly，但需 Owner 记录 |
| frontend-core | frozen install、chat-core/chat/widget/PC type/test/build | required |
| mobile | type/test/H5 build；小程序 build 按平台证据 | required 或分层 |
| node-workers | Codex、App Server、Gemini 的 `npm ci` + unit/type/build | required by changed paths，并有周期全跑 |
| python-workers | Claude/LangGraph unit/package | required by changed paths，并有周期全跑 |
| e2e | PC/widget/mobile 关键 Playwright/浏览器 smoke | required subset + nightly full |

Workflow 必须：

- 对 lockfile、POM、workflow、公共包变化扩大影响范围；
- 上传测试报告和必要构建产物，但不上传 token、`.env`、keystore 或用户数据；
- 显式打印 Java/Node/pnpm 版本；
- 取消时不登记为 pass；
- 发布 workflow 继续独立存在，不作为合并门禁替代品。

本轮状态：repository CI 与 nightly workflow 文件均已落地；required check 尚未在分支保护中证明生效，两者均未由 GitHub runner 执行。

## 自动化验证计划

下列是本事项的目标命令集；本轮实际执行项及退出码以“Execution Check-in”证据表为准。`mvn -B clean verify` 和所有 GitHub runner 命令仍为 `not-run`：

~~~bash
mvn -B -pl launcher -am clean test
mvn -B clean test
mvn -B clean verify

corepack pnpm install --frozen-lockfile
corepack pnpm --filter @foggy/chat-core build
corepack pnpm --filter @foggy/chat test
corepack pnpm --filter @foggy/chat build
corepack pnpm --filter @foggy/navigator-chat-widget test
corepack pnpm --filter @foggy/navigator-chat-widget build
corepack pnpm --filter @foggy/navigator-frontend type-check
corepack pnpm --filter @foggy/navigator-frontend test
corepack pnpm --filter @foggy/navigator-frontend build
corepack pnpm --filter @foggy/mobile type-check
corepack pnpm --filter @foggy/mobile test
corepack pnpm --filter @foggy/mobile build:h5

git diff --check
git status --short
~~~

Worker 具体命令必须从各自 `package.json`、`pyproject.toml` 和发布规范读取后写入 CI，不在规划阶段虚构统一命令。

## 手工验证

1. 在两个 clean checkout 中运行 frozen install，确认第二次无 lockfile 漂移。
2. 删除本地 dist/target 后运行根构建，确认不会误用旧产物。
3. 人为引入临时 TypeScript 类型错误，确认本地聚合命令和 CI 均失败；验证后撤销临时改动。
4. 检查 GitHub required checks 与分支保护配置，确认 release-only workflow 不会造成错误绿色状态。
5. 检查上传制品内容，不包含环境凭据、keystore、数据库或用户任务数据。

Experience 状态为 `not-applicable`：本事项不直接改变 UI；若修复 TypeScript 错误涉及可见行为，则对应页面体验验证转入实际 changed surface，并改为 `not-run` 后补证据。

## 风险与回滚

| 风险 | 控制 | 回滚 |
|---|---|---|
| 工具版本冻结导致旧开发机不可用 | 提前给出 Corepack/升级说明和 fail-fast 诊断 | 回退版本声明与 workflow 提交；不恢复不可复现安装 |
| 新 root lockfile 引入依赖漂移 | 使用固定 pnpm、clean 双目录 diff 和依赖 review | revert lockfile 提交，恢复前一可验证依赖图 |
| 删除嵌套 lockfile 破坏独立发布 | 先由 chat owner 确认发布入口 | revert 独立 lockfile 提交并恢复独立验证 lane |
| 全矩阵耗时过长 | 基于实际时长拆 required/nightly，公共依赖变化周期全跑 | 回退触发策略，不关闭核心 clean/type 门禁 |
| 有效 type-check 暴露更多存量错误 | 先记录 baseline，按窄修复收口 | 不允许回退为无效 type-check；必要时阶段化但保持失败可见 |
| 缓存掩盖 clean 问题 | 至少一条无缓存/frozen 周期验证 | 禁用缓存重跑，缓存不是唯一回滚手段 |

所有变更按“工具版本、lockfile、scripts、workflow”分提交；回滚使用 `git revert`，不得通过手工改回 lockfile 或删除测试结果掩盖失败。

## 完成判据

- [x] Node `22.23.1`、pnpm `10.34.5` 已由 Owner 冻结并完成本地精确工具校验。
- [ ] 根 lockfile 已生成且当前工作树 frozen install 无漂移；最终提交和第二个 clean checkout 复验待补。
- [x] chat 嵌套 lockfile 已决定并实施移除，统一使用根 workspace lockfile。
- [x] Java `mvn -B -pl launcher -am clean test` 有 16 reactor `SUCCESS`、测试 `0 failures`、exit `0` 的本地证据。
- [x] 根 `mvn -B clean test` 有 17/17 reactor `SUCCESS`、2304 tests、0 failure/error/skipped、exit `0` 的本地证据。
- [ ] 根 `mvn -B clean verify` 尚未执行。
- [x] chat-core、chat、widget、主前端、mobile 的 type/test/build 均有本地明确结果。
- [x] 主前端有效 type-check 会检查 app project，两个存量错误已关闭。
- [x] Claude/Codex/Gemini/LangGraph Worker 的无凭据 clean jobs 已写入 repository CI workflow。
- [x] Node/Python Worker jobs 已有本机 clean worktree 逐项通过证据；GitHub runner 仍待补。
- [ ] GitHub Actions 全仓矩阵和 required check 策略已生效。
- [ ] 所有命令、版本、退出码和证据路径已回写 [Progress](../progress.md)。
- [x] 已执行 `git diff --check` 和 Markdown 相对链接检查；见 `EXEC-142-010`，正式提交后仍须复核工作树状态。

## 生产路由与外部契约状态

- current_production_routing_changed: no
- current_external_contract_changed: no
- merge_gate_changed_when_implemented: yes
- production_enablement_required: no

构建门禁变化不能自动批准生产发布；CI 绿色只证明对应矩阵通过，不等于外部 Worker、ClientApp 或 upstream 链路已获生产批准。
