---
type: optimization
version: 1.4.2-SNAPSHOT
ticket: OPT-001
priority: high
status: planned
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
- [ODR-142-001 Owner 决策评审稿](../owner-decision-review.md)
- [统一进度记录](../progress.md)

## 背景与目标

当前 Java launcher 主依赖链有可用的历史构建基线，但前端工具链、根 lockfile、类型检查命令和 CI 覆盖不能保证从 clean 环境稳定复现。本事项只治理构建和合并门禁，不改变业务路由；目标是让同一提交在受支持环境中以固定工具链、冻结依赖和明确矩阵得到一致结果，并把所有未执行或平台条件跳过项如实写入 [进度记录](../progress.md)。

## 证据分类

### 已确认事实

1. 1.4.2 必须选择一个与当前 Vite 7 工具链兼容的明确 Node 支持版本，不继续以 README 中笼统的 Node 18+ 作为支持基线；ODR-142-001 当前建议 Node `22.23.1`，尚待 Owner 批准。
2. Java 使用 JDK 17，根 Maven reactor 和 `launcher` 是当前服务端构建入口。
3. 构建成功必须来自 clean 环境；旧 `target`、`dist`、`node_modules` 或本机缓存不能作为通过证据。
4. 规划阶段没有执行 Java、全仓前端或 Worker build，所有 Testing 状态保持 `not-run`。

### 静态搜索结论

1. 根 `pom.xml` 当前包含 17 个 reactor 模块；`launcher/pom.xml` 直接装配 Session、Business Agent、metadata-config、metadata-query、Claude/Codex/Gemini/LangGraph、Echo 和 Task Assistant。
2. 根 `package.json` 的 `build:frontend` 只覆盖 `@foggy/chat` 与 `@foggy/navigator-frontend`。
3. `pnpm-workspace.yaml` 和根 `package.json` 均把 `packages/*` 纳入 workspace，实际包含 chat-core、chat、mobile、widget 和 navigator-frontend 五个包。
4. 根 `pnpm-lock.yaml` 存在且包含五个 workspace importer，但 `.gitignore` 全局忽略 `pnpm-lock.yaml`，该根 lockfile 当前未纳入 Git。
5. `packages/foggy-chat/pnpm-lock.yaml` 是已跟踪的旧独立 lockfile，需决定是否仍保留独立安装/发布语义。
6. workspace manifests 均未声明 `engines` 或 `packageManager`。
7. 当前 Vite 7 安装结果要求 Node `^20.19.0 || >=22.12.0`；README 的 `Node.js 18+` 与之冲突。
8. `packages/navigator-frontend/package.json` 的 `build` 仅执行 `vite build`；`type-check` 对引用型根 tsconfig 使用 `vue-tsc --noEmit`，不能证明 app project 被实际检查。
9. 显式只读检查 `vue-tsc -p tsconfig.app.json --noEmit` 已定位 `ClaudeWorkerView.vue` 两个错误，但这只是规划期静态/诊断输入，不是通过证据。
10. `.github/workflows` 仅有 Codex Worker release candidate 流程，没有全仓 clean build 门禁。

### 需要运行态确认

1. Linux 与 Windows/WSL clean checkout 下 Node、Corepack、pnpm 的安装和 lockfile 解析一致性。
2. Maven 全 reactor 与 `launcher -am` 的实际耗时、资源上限和测试稳定性。
3. mobile H5、微信小程序、widget Playwright 是否适合作为每 PR 强门禁，还是拆为 required 与 nightly。
4. Claude、Codex、Gemini、LangGraph Worker 的平台依赖、发布脚本和可在 CI 使用的无凭据测试范围。
5. CI 缓存关闭与开启时是否仍能从 frozen lockfile 得到同一依赖图。

### 决策项

| 决策 | 建议基线 | Owner | 最晚时间 |
|---|---|---|---|
| Node 精确版本 | ODR-142-001 建议 CI/版本文件固定 `22.23.1`，`engines >=22.23.1 <23`；当前 pending-decision | root build owner | P1 Step 1 前 |
| pnpm/Corepack | ODR-142-001 建议 `packageManager: pnpm@10.34.5`；Corepack 仅作 bootstrap 并打印实际版本；当前 pending-decision | frontend/build owner | 生成新 lockfile 前 |
| chat 独立 lockfile | 建议确认无独立消费者后移除嵌套 lockfile，从根 workspace pack/publish；若有证据则补独立验证 lane | chat owner | P1 Step 2 前 |
| mobile/widget 门禁层级 | 核心 type/test/build required；完整平台 E2E nightly；真实外部集成进入 RC/受控环境 | frontend/mobile owner | CI 合并前 |
| Worker 矩阵 | 无外部凭据的 unit/type/build required；跨平台/安装包 nightly；真实凭据 smoke 进入 RC/受控环境 | Worker owners | CI 合并前 |

未完成以上决策时不得宣称依赖可复现，也不得提交由未知 pnpm 版本生成的新根 lockfile。

## 精确代码与配置清单

| 路径 | 当前角色 | 计划动作 |
|---|---|---|
| `pom.xml` | Java 根 reactor | 核对全 reactor clean verify 入口，不借机调整业务模块 |
| `launcher/pom.xml` | 生产 launcher 装配 | 建立 `launcher -am` clean test/package 门禁 |
| `package.json` | 根 workspace scripts | 增加有效 type/test/build 聚合命令及工具版本声明 |
| `pnpm-workspace.yaml` | pnpm workspace 范围 | 保持五个包的单一 workspace 定义 |
| `pnpm-lock.yaml` | 根依赖锁定 | 用冻结 pnpm 版本重建、提交并执行 frozen install |
| `packages/foggy-chat/pnpm-lock.yaml` | 旧嵌套 lockfile | 按 Owner 决策保留独立语义或移除 |
| `.gitignore` | 全局忽略规则 | 取消根 lockfile 的错误忽略，保留 node_modules/dist 忽略 |
| `README.md` | 环境要求 | 对齐 Node 22、Corepack/pnpm 和 clean build 命令 |
| `packages/foggy-chat-core/package.json` | chat-core build | 纳入根 build；补必要的 test/type 门禁或明确 not-applicable 及原因 |
| `packages/foggy-chat/package.json` | chat library | 纳入 test/build |
| `packages/navigator-chat-widget/package.json` | 外部 widget 交付物 | 纳入 test/build，按层级执行 Playwright |
| `packages/navigator-frontend/package.json` | 主前端 | 修正有效 type-check，纳入 test/build |
| `packages/foggy-mobile/package.json` | mobile 交付物 | 增加稳定 type-check 入口并纳入 test/H5 build；小程序按矩阵执行 |
| `scripts/build-frontend.sh`、`scripts/build-frontend.ps1` | 本地聚合脚本 | 与根 scripts 和 frozen install 对齐，不再跳过 widget/mobile |
| `.github/workflows` | 当前仅 Worker 发布 | 新增全仓 build workflow，发布流程不能替代 PR 门禁 |
| `tools/*worker*/package.json`、`pyproject.toml` | Worker 构建入口 | 按各自 package manager 和语言建立无凭据 clean lane |

## 实施步骤

### Step 1：冻结工具链

输入与前置条件：

- 必须冻结明确受支持的 Node 版本；ODR-142-001 的 Node `22.23.1` 建议已进入评审但尚未批准；
- build owner 已评审并签署 ODR-142-001 的精确 Node、pnpm/Corepack 约束；
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

## 自动化验证计划

以下命令均为计划命令，当前状态 `not-run`；最终以 Step 1 固化的脚本为准：

~~~bash
mvn -B -pl launcher -am clean test
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

- [ ] 明确的 Node、pnpm/Corepack 版本已由 Owner 冻结并机器校验；若批准 ODR-142-001，则为 Node `22.23.1`、pnpm `10.34.5`。
- [ ] 根 lockfile 已提交，frozen install 在 clean checkout 无漂移。
- [ ] chat 嵌套 lockfile 已有明确保留或移除决策。
- [ ] Java `launcher -am clean test` 和全 reactor clean 验证有可定位证据。
- [ ] chat-core、chat、widget、主前端、mobile 的 type/test/build 均有明确结果。
- [ ] 主前端有效 type-check 会检查 app project，两个存量错误已关闭。
- [ ] Claude/Codex/Gemini/LangGraph Worker 的无凭据 clean lane 已建立。
- [ ] GitHub Actions 全仓矩阵和 required check 策略已生效。
- [ ] 所有命令、版本、退出码和证据路径已回写 [Progress](../progress.md)。
- [ ] 已执行 `git diff --check` 和 Markdown 相对链接检查。

## 生产路由与外部契约状态

- current_production_routing_changed: no
- current_external_contract_changed: no
- merge_gate_changed_when_implemented: yes
- production_enablement_required: no

构建门禁变化不能自动批准生产发布；CI 绿色只证明对应矩阵通过，不等于外部 Worker、ClientApp 或 upstream 链路已获生产批准。
