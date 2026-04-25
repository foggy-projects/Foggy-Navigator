# 04 Codex Worker GPT-5.5 升级与模型映射优化方案

## 文档作用

- doc_type: requirement | implementation-plan
- intended_for: execution-agent | reviewer | product-owner
- purpose: 明确 Codex Worker 在 GPT-5.5 已确认可用后的代码优化方向、本地验证范围与后续模型映射决策门槛，避免在未完成本地验证前草率固化默认值和别名策略。

## 基本信息

- version: `1.0.4-SNAPSHOT`
- date: `2026-04-25`
- source_type: optimization | design | worker-model-contract
- priority: `P1`
- status: `alias_only_signed_off_followup_required`

## 背景

当前平台的 Codex 模型链路存在两个问题：

1. `tools/codex-agent-worker` 的运行时依赖和默认模型仍停留在旧版本语义
2. 前端、移动端、Worker 默认值多处直接写死 `gpt-5.4` 系列，模型目录分散，后续升级会重复改动

同时，外部前提已经发生变化：

- OpenAI 官方已在 `2026-04-24` 明确 `GPT-5.5` 与 `GPT-5.5 Pro` 可用于 API
- 这意味着“5.5 是否存在 / 是否开放”不再是本条阻塞点，本条阻塞点已经转为“我方代码结构是否收口”“默认值如何 rollout”“是否需要 fallback / 模式区分”

已确认的仓库现状：

- [`tools/codex-agent-worker/package.json`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/codex-agent-worker/package.json) 当前依赖 `@openai/codex-sdk` 为 `^0.116.0`
- [`tools/codex-agent-worker/src/codex/sdk-wrapper.ts`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/tools/codex-agent-worker/src/codex/sdk-wrapper.ts) 默认模型仍为 `gpt-5.4-mini`
- [`packages/navigator-frontend/src/views/SettingsView.vue`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/SettingsView.vue) 直接硬编码 Codex 模型选项与 `availableModels`
- [`packages/navigator-frontend/src/utils/llmModelOptions.ts`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/utils/llmModelOptions.ts) 维护另一份 Codex 静态模型目录
- [`packages/foggy-mobile/src/pages/worker/tasks.vue`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/foggy-mobile/src/pages/worker/tasks.vue) 也单独维护一份 Codex 模型选项
- [`addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java) 对模型本身没有强白名单限制，主要是解析 `modelConfigId -> modelName` 后向下透传

这说明当前真正阻碍升级的不是 Java 主链路，而是：

- Worker 依赖版本与默认值
- 多端模型目录硬编码
- 缺少 Codex 自己的 alias / default model 收口机制

## 问题陈述

本条需求要回答的不是“5.5 是否已开放”，而是三个更基础的问题：

1. 在 `gpt-5.5` 已确认可用的前提下，代码层应该先做哪些低风险优化
2. 平台未来的 Codex 模型映射应该放在哪一层，而不是继续散落在前端
3. 在尚未完成我方本地验证前，如何避免把默认模型、别名和存量配置一次性绑死

## 当前判断

### 已确认判断

- Codex Worker 当前接受的是自由模型字符串，不是严格白名单
- `model:reasoning` 的解析能力已经存在，升级到新模型名不需要重写主流程
- OpenAI 官方层面的 `gpt-5.5` API 可用性已确认，不再需要把“是否已开放”当作决策前提
- 当前最需要优先做的是“代码收口 + rollout 策略明确”，不是“继续等待 5.5 可用性结论”

### 暂不提前拍板的判断

以下事项在你完成我方真实环境验证前，先不固化为正式产品契约：

- 是否立刻将 `gpt-5.5` 设为 Codex 默认模型
- 前端展示层应直接展示真实模型，还是优先展示稳定 alias
- 稳定 alias 是否直接映射到 `gpt-5.5`
- 是否区分订阅模式与 API 模式使用不同默认值或 fallback

## 目标

`1.0.4-SNAPSHOT` 本条的目标分两层：

### 第一层：本版必须完成的代码优化

1. 升级 `tools/codex-agent-worker` 的 Codex SDK 依赖
2. 移除 Worker 内部写死的 `gpt-5.4-mini` 默认值，改为显式配置项
3. 收口前端与移动端的 Codex 模型静态目录，避免多处重复维护
4. 为后续 Codex alias 机制预留结构或接入点，但本版是否正式启用以本地验证结果为准

### 第二层：本版只做前置设计、不先拍板的事项

1. `gpt-5.5` 是否直接作为真实生产默认模型
2. 是否需要像 Claude / Gemini 一样引入稳定 alias 展示层
3. alias / default 是否应该按认证模式区分映射目标

## 范围

### In Scope

- Codex Worker 依赖升级
- Codex Worker 默认模型配置化
- 前端 / 移动端 Codex 模型目录收口
- Codex alias 设计预留
- 5.5 已确认可用后的本地验证、rollout 与 fallback 决策门槛定义

### Out Of Scope

- 本条内直接把所有默认模型切成 `gpt-5.5`
- 本条内完成完整的 Codex alias 发布与数据迁移
- 本条内承诺所有现网环境都可零风险切换到 `gpt-5.5`
- 本条内新增复杂的模型注册中心或数据库 schema

## 推荐方案

### Stage 0: 记录已确认可用性，并补齐我方本地验证证据

外部事实已经确认，当前应直接进入本地验证和代码优化：

- `2026-04-24` OpenAI 官方已宣布 `GPT-5.5` 与 `GPT-5.5 Pro` 可用于 API

在代码优化完成前后，都允许你直接用真实模型串做验证，例如：

- `gpt-5.5`
- `gpt-5.5:low`
- `gpt-5.5:high`

本阶段目标不再是判断“5.5 是否存在”，而是确认：

1. 订阅 / login 模式是否可成功运行 `gpt-5.5`
2. API Key 模式在我方现网环境中是否也可成功运行 `gpt-5.5`
3. 任务结果事件返回的 `model` 字段实际是什么
4. 是否存在模式差异或 region / account 差异

在这一步完成前，不建议把 alias 默认值和全局 fallback 一次性绑死到 `gpt-5.5`

### Stage 1: Worker 代码先收口

先做不改变业务语义、但能降低后续升级成本的调整：

1. 升级 `@openai/codex-sdk`
2. 新增 `CODEX_DEFAULT_MODEL` 配置项
3. 将 `sdk-wrapper.ts` 中的默认模型改为读取配置，而不是内联 `gpt-5.4-mini`
4. 保持“如果请求显式传 model，则优先按请求执行”的现有行为不变

这一阶段的核心原则：

- 先让 Worker 对“新模型名”友好
- 不在这一阶段强行决定“哪个模型应该成为全局默认”

### Stage 2: 抽离前端静态模型目录

当前 Codex 模型目录至少分布在 PC 设置页、PC 会话工具、移动端三处。建议先统一为单一来源：

- `packages/navigator-frontend/src/utils/llmModelOptions.ts` 作为 PC 端统一定义
- 移动端复用同一份常量，或新增共享模块，避免再内联一份

这一阶段先做“代码收口”，不做“版本拍板”：

- 应补上 `gpt-5.5` 作为明确可选项或可输入候选
- 同时保留必要的旧模型或 fallback 选项
- `availableModels` 的维护方式也应从页面内联收回到统一 helper

### Stage 3: 预留或引入 Codex alias 机制，但保持 rollout 开关可控

建议 Codex 最终走和 Gemini 类似的路线：

- Worker 提供 `defaultModel`
- Worker 提供 `modelAliases`
- 前端展示稳定 alias
- Worker 再将 alias 解析为真实模型

但在你完成我方 5.5 本地验证前，本版应保持 rollout 开关可控，不直接把产品语义一次性切死：

- `codex-latest`
- `codex-fast`
- `codex-deep`
- `codex-mini`

原因不是官方可用性不明，而是我方仍需确认这些 alias 在现网中最终应该映射到：

- 全部都是 `gpt-5.5`
- 还是 `login 模式 -> 5.5`，`API 模式 -> 保留 fallback`
- 或者仍需要为特定账户 / 环境保留旧模型回退

### Stage 4: 基于已确认可用性和本地验证结果决定最终 rollout 策略

待你完成本地验证后，再在本条基础上进入下一轮落地：

1. 如果 `gpt-5.5` 在订阅和 API 模式都稳定可用：
   - 可将 `codex-latest` 映射到 `gpt-5.5`
   - 再评估是否把 `CODEX_DEFAULT_MODEL` 默认值切到 `gpt-5.5`
2. 如果 `gpt-5.5` 只在部分模式或部分环境稳定可用：
   - alias 需要按认证模式或部署模式区分
   - 不应把平台默认值直接全局切换
3. 如果官方虽已开放，但我方当前 Worker / CLI / 账户组合仍存在不稳定因素：
   - 保留 5.5 为显式可选项
   - 默认值继续走保守 fallback
   - 先发布结构优化成果，再单独推进 alias 切换

## 1.0.4 本版建议结论

本版建议正式采纳以下原则：

1. **5.5 已确认可用**
   - 不再把“是否开放”作为阻塞条件
2. **先优化代码结构**
   - 升级 Worker 依赖
   - 去掉 Worker 默认模型硬编码
   - 收口前端 / 移动端 Codex 模型目录
3. **补齐我方真实环境验证**
   - 重点验证本地模式差异、回传模型值与 fallback 需求
4. **验证后再定默认值与 alias rollout**
   - 本版不再等待可用性结论，而是等待我方验证证据与发布策略结论

## 验收标准

### 本条当前阶段验收

1. Codex Worker 不再把 `gpt-5.4-mini` 写死在运行时代码中
2. Codex Worker 的默认模型由配置项控制
3. PC 端与移动端的 Codex 模型目录不再各自散落维护
4. 文档中明确：`gpt-5.5` 官方已可用，但正式默认化和 alias 映射仍要以我方本地验证结果与 fallback 策略为前置条件

### 后续决策门槛

只有同时满足以下条件，才建议进入“正式映射切换”：

1. 你已完成至少一轮我方真实环境验证
2. 已确认验证发生在哪种模式：订阅 / API / 两者皆可
3. 已确认任务结果中的实际 `model` 回传值
4. 已明确是否需要 fallback 策略

## 代码触点清单

| repo/module | path | role | expected_change | notes |
| --- | --- | --- | --- | --- |
| `codex-agent-worker` | `tools/codex-agent-worker/package.json` | Codex SDK 依赖版本 | update | 升级 `@openai/codex-sdk` |
| `codex-agent-worker` | `tools/codex-agent-worker/src/config.ts` | Worker 默认模型配置 | update | 新增 `CODEX_DEFAULT_MODEL`，必要时预留 alias 配置 |
| `codex-agent-worker` | `tools/codex-agent-worker/src/codex/sdk-wrapper.ts` | 运行时默认模型解析 | update | 移除硬编码 `gpt-5.4-mini` |
| `navigator-frontend` | `packages/navigator-frontend/src/utils/llmModelOptions.ts` | Codex 模型选项统一定义 | update | 收口静态目录，避免多处维护 |
| `navigator-frontend` | `packages/navigator-frontend/src/views/SettingsView.vue` | 设置页 Codex 模型选择与 `availableModels` | update | 改为复用统一定义 |
| `foggy-mobile` | `packages/foggy-mobile/src/pages/worker/tasks.vue` | 移动端 Codex 模型候选 | update | 与统一定义对齐 |
| `codex-worker-agent` | `addons/codex-worker-agent/src/main/java/com/foggy/navigator/codex/worker/service/CodexTaskService.java` | 模型透传链路 | read-only-analysis | 当前主链路基本可复用，重点是确认无额外白名单限制 |

## 风险与注意事项

- OpenAI 官方已开放 `gpt-5.5`，不等于我方所有现网环境都可零风险全局切换
- 如果 `gpt-5.5` 只在特定认证模式可用，直接全局 alias 会误导用户
- 若前端仍保留多份静态目录，后续即使 Worker 支持新模型，也会持续出现展示和保存不一致

## 进展跟踪

### 开发进展

- 当前状态：`code_done_pending_local_verification`
- 已完成：
  - 现状梳理与代码触点定位
  - 已将外部前提更新为“`gpt-5.5` 官方已可用”
  - 明确本版先做代码优化与 rollout 设计，不先拍板默认模型
  - **（2026-04-25）** Stage 1 Worker 代码收口：
    - `tools/codex-agent-worker/package.json`：`@openai/codex-sdk` 升级 `^0.116.0` → `^0.125.0`，实际安装 `0.125.0`
    - `tools/codex-agent-worker/src/config.ts`：新增 `AppConfig.defaultModel` 与 `parseDefaultModel`，支持 `CODEX_DEFAULT_MODEL` 环境变量覆盖；空值回落到 `gpt-5.5`（1.0.4 新默认）
    - `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`：`runQuery` 中的 `rawModel` 兜底从硬编码 `'gpt-5.4-mini'` 改为 `config.defaultModel`；请求显式 model 的最高优先级保持不变
  - **（2026-04-25）** Stage 2 前端 / 移动端收口：
    - `packages/navigator-frontend/src/utils/llmModelOptions.ts`：扩展 `SelectableModelOption`（增加 `group` / `description`）；新增 `CODEX_REAL_MODEL_OPTIONS`（含 5.5 Low/Medium/High/Extra High + 5.4 存量 + 5.3 Codex）、`CODEX_ALIAS_MODELS`（`codex-latest/fast/deep/mini` 结构预留，不进入 `ALL_MODEL_OPTIONS`）、`getModelOptionsByBackend` / `getGroupedCodexOptions` helper
    - `packages/navigator-frontend/src/views/SettingsView.vue`：Codex / Claude / Gemini 下拉和 `availableModels` 复选框改为 `v-for` 消费统一 helper；Worker 表单 Codex 模型占位符更新为 `gpt-5.5`
    - `packages/foggy-mobile/src/pages/worker/tasks.vue`：`ALL_MODELS` 本地镜像与 PC SSOT 对齐，补齐 5.5/5.4 存量/5.3 Codex/Gemini，注释指向 `packages/navigator-frontend/src/utils/llmModelOptions.ts`
  - **（2026-04-25）** Stage 3 alias 预留：`CODEX_ALIAS_MODELS` 结构已定义并由单测断言“不进入 1.0.4 下拉选项”，rollout 时可在 helper 层一键启用
- **（2026-04-25 后续）** Stage 3 alias-only 落地（Playwright 验收 + 用户对齐方向后追加）：
  - **Worker**：
    - `tools/codex-agent-worker/src/config.ts`：新增 `AppConfig.modelAliases` + `parseModelAliases`（支持 `CODEX_MODEL_ALIASES` JSON 环境变量），默认映射 `codex-latest→gpt-5.5 / codex-fast→gpt-5.5:low / codex-deep→gpt-5.5:high / codex-mini→gpt-5.4-mini`
    - `tools/codex-agent-worker/src/config.ts`：`defaultModel` 默认值由 `gpt-5.5` 改为 alias `codex-latest`（alias-first，本条件最大化前端稳定性）
    - `tools/codex-agent-worker/src/codex/sdk-wrapper.ts`：新增 `resolveModelAlias(rawModel, aliases)` 函数（4 个分支：整串命中 / alias:reasoning 拼接 / alias 自带 reasoning 优先 / 透传），在 `runQuery` 中先经 alias 解析再做 `parseModelString`
    - 日志增强：`requested_model=... alias_hit=... resolved_model=... effective_model=... reasoning=...`，便于运维定位
    - `tests/sdk-wrapper.test.ts`：新增 5 个 `resolveModelAlias` 用例覆盖 4 个分支
    - `tests/config.test.ts`：新增 7 个 `CODEX_MODEL_ALIASES` 用例
  - **前端**：
    - `packages/navigator-frontend/src/utils/llmModelOptions.ts`：完全重写，移除真实模型选项，`ALL_MODEL_OPTIONS` 中 OPENAI_CODEX 仅含 4 个 alias；`resolveModelOptions` 增加旧 availableModels 退化为"不限制"的兼容兜底；`getGroupedCodexOptions` 删除（不再需要分组）
    - `packages/navigator-frontend/src/views/SettingsView.vue`：Codex 模型下拉从分组改为扁平 alias 列表（与 Claude / Gemini 一致），Worker 表单 placeholder 更新
    - `packages/foggy-mobile/src/pages/worker/tasks.vue`：`ALL_MODELS` 切到 alias-only（4 项），同步加上 backward-compat 兜底
    - `packages/navigator-frontend/src/__tests__/llmModelOptions.test.ts`：8 个测试，新增"前端零真实模型 gpt-5.x"+"旧 availableModels 兼容兜底"等断言
- 已落实（Stage 4 大部分目标）：
  - alias 已正式注入 `ALL_MODEL_OPTIONS`，前端无任何真实模型暴露
  - 模型版本升级（5.5 → 5.6 等）只需改 Worker 的 `CODEX_MODEL_ALIASES` 环境变量，前端 + Java 后端 + 数据库零变动
  - 旧配置（11 / codex订阅 数据库 availableModels 含 gpt-5.4 / gpt-5.5）通过兼容兜底自动回退到全部 alias 可选
- 后续运维事项：
  - Worker 主机执行 `codex login` 或在订阅 / API 配置中补 API Key 即可端到端跑真实任务（与 alias-only 改动无关）

### 测试进展

- 当前状态：`alias_only_unit_green + worker_log_verified`
- 已确认：
  - `2026-04-24` OpenAI 官方宣布 `GPT-5.5` 与 `GPT-5.5 Pro` 可用于 API
- 已完成的自动化验证（2026-04-25 第二轮 alias-only 重构）：
  - `tools/codex-agent-worker` `npm test`：**53/53** 通过（在原 41 个的基础上新增 5 个 `resolveModelAlias` 分支测试 + 7 个 `CODEX_MODEL_ALIASES` 配置测试）
  - `tools/codex-agent-worker` `npm run typecheck`：`tsc --noEmit` 无错
  - `packages/navigator-frontend` 目标测试 `vitest run src/__tests__/llmModelOptions.test.ts`：**8/8** 通过（含 alias 严格断言 + 旧 availableModels 兼容兜底用例）
  - `bash scripts/build-frontend.sh`：navigator-frontend `vue-tsc` + `vite build` 全量通过（SettingsView.vue 改为扁平 alias 渲染）
- 已知非相关失败：
  - `useClaudeWorker.test.ts` 7 个 fetch 到 `http://localhost:3000/api/v1/tasks` 失败 — 属测试环境依赖（未启动后端时 axios 报错），与本条改动无关；不阻塞本条验收
- 待验证（Stage 4 门槛）：
  - 我方真实环境手工验证 `gpt-5.5`（订阅 / API 模式分别跑一轮）
  - 观察 `result.model` 事件字段的实际回传值
  - 确认是否需要 fallback 策略（如 5.5 只在部分模式稳定时，`CODEX_DEFAULT_MODEL` 是否回退到 5.4 系列）
  - Playwright 目视验收设置页、ClaudeWorkerView、移动端 tasks.vue 三处下拉的 `gpt-5.5` 选项展示一致性

### 体验进展

- 当前状态：`alias_only_ui_verified_alias_resolution_verified`
- 已就绪：
  - PC 设置页（SettingsView.vue）：Codex 模型下拉按 `getGroupedCodexOptions()` 分组展示 `GPT-5.5（旗舰）` / `GPT-5.4（存量）` / `GPT-5.4 Mini` / `GPT-5.3 Codex`；"可用模型"复选框展示全部 Codex 候选（含 5.5 系列）
  - PC 会话页（ClaudeWorkerView.vue）：直接复用 `resolveModelOptions()`，无需单独改造，随 `ALL_MODEL_OPTIONS` 自动获得 5.5 选项
  - 移动端（foggy-mobile/src/pages/worker/tasks.vue）：`ALL_MODELS` 与 PC SSOT 对齐，补齐 5.5 / 5.4 存量 / 5.3 Codex / Gemini 共 16 项
  - 用户仍可通过 `allow-create` 手工输入任意模型名（包括未来的 `gpt-5.5-mini` 等）

#### Playwright 验收（2026-04-25）

- **SettingsView.vue "添加 AI 模型" 对话框**：切换到 OpenAI Codex backend 后，模型下拉按分组正确展示：
  - GPT-5.5（旗舰）：Low / Medium / High / Extra High
  - GPT-5.4（存量）：Low / Medium / High / Extra High
  - GPT-5.4 Mini：Mini
  - GPT-5.3 Codex：Codex / Spark
  - 可用模型复选框同步展示上述 11 项（5.5 组在最前）
  - 证据：`docs/version-tracker/1.0.4-SNAPSHOT/evidence/settings-codex-gpt55-dropdown.png`
- **SettingsView.vue "编辑"已有 Codex 配置**：旧 config (`11` / `codex订阅`) 的 availableModels 保持不变；新增的 5.5 checkbox 显示为未选中，可手工勾选并保存（已实测：勾选 4 个 5.5 选项保存 → config.availableModels 从 5 项扩展到 9 项）
- **ClaudeWorkerView.vue 任务输入模型下拉**：
  - 使用 `codex订阅`（availableModels 限定 5.4 族）时：下拉只显示 5.4 Low/Medium/High/Extra High（**whitelist 过滤生效**，符合预期）
  - 使用更新后的 `11`（availableModels 含 5.5 族）时：下拉正确显示 `5.5 Low / 5.5 Medium / 5.5 High / 5.5 Extra High / 5.4 Low / ... / 5.4 Mini`，**5.5 分组在 5.4 之前**，与我们在 `getGroupedCodexOptions` 里设定的顺序一致

#### Codex Worker 日志验收（2026-04-25）

从 `tools/codex-agent-worker/logs/worker.log` 捕获三条关键记录（完整证据：`docs/version-tracker/1.0.4-SNAPSHOT/evidence/codex-worker-log-gpt55.txt`）：

| 场景 | 请求字段 | Worker 解析 | 结论 |
|---|---|---|---|
| UI 选择 `5.5 Medium` 发起任务 | `model=gpt-5.5` | `raw_model=gpt-5.5 effective_model=gpt-5.5 reasoning=` | parseModelString 正确（无 reasoning 后缀） |
| UI 选择 `5.5 High` 发起任务 | `model=gpt-5.5:high` | `raw_model=gpt-5.5:high effective_model=gpt-5.5 reasoning=high` | parseModelString 正确拆分 model + reasoning |
| 直接 POST `/api/v1/query` 不带 model | `model=` | `raw_model=gpt-5.5 effective_model=gpt-5.5 reasoning=` | `config.defaultModel` 回落生效（1.0.4 新默认 `gpt-5.5`） |

#### 已知非本条失败项

3 个 Playwright 实测任务最终 FAILED，错误信息：
> Your access token could not be refreshed because your refresh token was already used. Please log out and sign in again.

这是当前 Worker 主机的 `~/.codex/auth.json` 订阅 token 失效问题，**与本条"模型串处理 + 默认值配置化"的代码范围无关**。解决方式：运维在 Worker 主机执行 `codex login` 重新授权，或在 `11` 配置补上 API Key 走 API 模式。`parseModelString` 与 `CODEX_DEFAULT_MODEL` 路径已在 Worker 日志中得到明确证据确认正确工作。

#### Alias-only 验收（2026-04-25 第二轮）

**前端 UI 直接观察**：
- SettingsView.vue · LLM 配置 · 添加 AI 模型对话框（OPENAI_CODEX backend）：
  - 模型名称下拉：4 个 alias（Codex Latest / Fast / Deep / Mini）+ 描述文案
  - 可用模型复选框：4 个 alias，无任何真实模型暴露
  - 证据：`docs/version-tracker/1.0.4-SNAPSHOT/evidence/settings-codex-alias-dropdown.png`
- ClaudeWorkerView.vue · `11` 配置（旧 availableModels 含 gpt-5.4/gpt-5.5 真实模型）：
  - 模型选择器自动展示 "Codex Latest"
  - 下拉打开：4 个 alias（Codex Latest / Codex Fast / Codex Deep / Codex Mini）
  - 触发 `resolveModelOptions` 兼容兜底：旧 availableModels 无 alias 命中 → 退化为不限制
  - 证据：`docs/version-tracker/1.0.4-SNAPSHOT/evidence/clauseworkerview-codex-deep-alias.png`

**Worker alias 解析日志（4 个分支全覆盖）**：

| 场景 | 输入 | resolveModelAlias 行为 | resolved_model |
|---|---|---|---|
| UI 选择 "Codex Deep" | `requested_model=codex-deep` | 整串命中 (Case 1) | `gpt-5.5:high` |
| 直接 POST 不带 model | `requested_model=codex-latest` (来自 config.defaultModel) | 整串命中 + 默认值生效 | `gpt-5.5` |
| `codex-latest:high` 拼接 | `requested_model=codex-latest:high` | alias:reasoning 拼接 (Case 2) | `gpt-5.5:high` |
| 真实模型透传 | `requested_model=gpt-5.5` | 不命中 alias，原样透传 (Case 4) | `gpt-5.5` |

完整 Worker 日志证据：`docs/version-tracker/1.0.4-SNAPSHOT/evidence/codex-worker-alias-resolution.txt`

**Stage 4 决策门槛达成情况**：
- ✓ Worker 端 alias 解析路径完整工作（log 证据）
- ✓ 前端零真实模型暴露，alias 展示与 Claude/Gemini 风格一致
- ✓ 旧 availableModels 数据通过兼容兜底自动迁移
- ✗ 端到端 `gpt-5.5` 真实任务跑通：仍阻塞于 Worker 主机 Codex CLI 订阅 token 失效（运维事项）

#### 待运维事项（与本条改动无关）

Worker 主机 `~/.codex/auth.json` 的订阅 token 已用过期：
> Your access token could not be refreshed because your refresh token was already used. Please log out and sign in again.

解决方式：
1. 在 Worker 主机执行 `codex login` 重新授权
2. 或在 LLM 配置（如 `codex订阅`）中补 API Key 走 API 模式

模型版本升级流程示例（**前端 / Java 后端零改动**）：
```bash
# 当 OpenAI 发布 gpt-5.6 时，仅在 Worker 主机修改环境变量：
export CODEX_MODEL_ALIASES='{"codex-latest":"gpt-5.6","codex-fast":"gpt-5.6:low","codex-deep":"gpt-5.6:high","codex-mini":"gpt-5.5-mini"}'
# 重启 Worker 即可，前端无需任何变动
```

## Acceptance Status

- acceptance_status: signed-off
- acceptance_decision: accepted-with-risks
- signed_off_by: release-owner
- signed_off_at: 2026-04-25
- acceptance_record: docs/version-tracker/1.0.4-SNAPSHOT/acceptance/04-codex-worker-alias-only-acceptance.md
- blocking_items: none
- follow_up_required: yes

## 相关收口文档

- [05-codex-alias-only-release-note.md](./05-codex-alias-only-release-note.md)
- [06-codex-auth-smoke-checklist.md](./06-codex-auth-smoke-checklist.md)
- [acceptance/04-codex-worker-alias-only-acceptance.md](./acceptance/04-codex-worker-alias-only-acceptance.md)
