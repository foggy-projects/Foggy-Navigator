# 02 Claude Code 真实模型映射自动化设计

## 文档作用

- doc_type: design-evaluation
- intended_for: execution-agent | reviewer | product-owner
- purpose: 评估如何在尽量少改代码的前提下，避免 Claude Code 模型标签继续硬编码过期，并为未来“真实模型映射”能力预留设计。

## 基本信息

- version: `1.0.4-SNAPSHOT`
- date: `2026-04-22`
- source_type: optimization | design
- priority: `P1`
- status: `draft`

## 背景

当前平台中 Claude Code 模型选择仍然使用静态硬编码：

- 前端把 `opus` 展示为 `Opus 4.6`
- 把 `sonnet` 展示为 `Sonnet 4.6`
- 可用模型复选框也内联写死在页面中

但 Claude Code 当前实际已经升级到 `Opus 4.7`。这说明：

1. 现有静态标签已经过期
2. 模型值与展示名不是同一个问题
3. 若继续把真实版本号写死在前端，后续还会不断过期

同时，当前系统运行时真正传给 Claude Code 的配置值，仍然主要是这些 alias：

- `opus`
- `opus[1m]`
- `sonnet`
- `sonnet[1m]`
- `haiku`

这意味着存量数据层面并没有立刻坏掉，过期的主要是展示语义。

## 问题陈述

本条不是简单改一处文案，而是要回答两个层级的问题：

1. **短期怎么止血**：如何在尽量不改代码、不动 schema 的情况下，不再把过期的 `4.6` 显示给用户
2. **长期怎么做真映射**：如果未来需要“系统自动知道当前 `opus` 实际指向 `4.7` 还是更高版本”，应该如何设计

## 现状评估

### 已确认现状

- [`SettingsView.vue`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/SettingsView.vue) 直接硬编码了 Claude Code 模型选项和标签
- 当前配置值本身使用 alias，而不是写死成 `claude-opus-4-6-...` 这类精确版本串
- `availableModels` 也是 alias 集合，本质上仍然偏“能力开关”而不是“精确版本注册表”
- 当前版本未见稳定的官方平台内置接口，可以在“任务开始前”可靠返回 Claude Code alias 对应的真实 resolved model 版本

### 关键判断

- **短期最优解不是继续追版本号**。只要 UI 继续显示 `4.6`、`4.7` 这类精确版本号，就会反复过期
- **alias 本身是更稳定的契约层**。当前平台存储 `opus` / `sonnet` 的做法本身是合理的
- **“真实模型映射自动化”当前更适合做设计预留，而不是在 1.0.4 强上重实现**，因为当前并没有确认可用的、稳定的官方查询来源

## 目标

为 `1.0.4-SNAPSHOT` 明确一条低风险、最小改动的落地路线：

1. 先消除前端错误展示的版本号
2. 保持现有 alias 值与存量配置兼容
3. 不新增 schema，不做数据迁移
4. 为未来“真实模型映射”预留扩展设计，但不在本版依赖不稳定能力强上

## 推荐方案

### 阶段 0：1.0.4 推荐落地方案

将 Claude Code 的展示标签从“带具体版本号”改为“中性 alias 标签”：

- `Opus`
- `Opus (1M context)`
- `Sonnet`
- `Sonnet (1M context)`
- `Haiku`

保留配置值不变：

- `opus`
- `opus[1m]`
- `sonnet`
- `sonnet[1m]`
- `haiku`

这样做的收益是：

- 几乎不改后端
- 不影响已有模型配置和 `availableModels`
- 不需要迁移数据库
- 即使 Claude Code 的底层真实版本从 4.6 升到 4.7，UI 也不会继续显示错误版本

这是当前“尽量不修改代码”的最优方案。

### 阶段 1：抽离前端模型选项定义

在同一版本内，如果顺手改动范围允许，建议把 Claude Code 模型选项从页面内联抽到统一 helper，例如：

- `packages/navigator-frontend/src/utils/llmModelOptions.ts`

目标是：

- `SettingsView.vue` 不再自己维护一份模型清单
- 其他页面如果也展示 Claude Code 模型选项，可以共用同一来源
- 后续只改一个地方就能更新显示策略

这仍属于低风险、小改动优化，不要求后端配合。

### 阶段 2：未来版本的 Worker Capability 设计

若后续需要“自动建立真实模型映射”，建议走 Worker 能力上报路线，而不是继续在前端拍脑袋写版本号。

推荐方向：

1. `tools/claude-agent-worker` 暴露一个 capability / metadata 接口
2. 返回：
   - Claude Code CLI 版本
   - 支持的 alias 列表
   - 如官方未来提供稳定能力，再返回 alias 对应的真实 resolved model
3. Java 侧缓存 capability
4. 前端按 Worker 能力动态显示

但这一步当前**不建议**在 `1.0.4` 直接实现，原因是：

- 当前版本未确认存在稳定官方接口可获取“alias -> 精确版本”映射
- 若依赖私有 CLI 输出或抓取非稳定信息，维护成本高且风险大

### 阶段 3：执行结果反写真实模型

如果业务未来确实需要看到“本次任务实际用的是 Opus 4.7”，一个更稳的方向是：

- 不在任务前预测
- 而是在任务执行结果事件中记录 SDK/CLI 实际返回的 `model`

这样平台拿到的是“真实执行结果”，不是“事前猜测”。这更适合后续做：

- 成本审计
- 问题定位
- 会话历史展示

## 为什么 1.0.4 不建议直接做“自动真实映射”

### 原因 1：官方稳定来源不明确

当前并未确认 Claude Code / Agent SDK 在本项目所用版本中，提供稳定、正式、可预期的“列出所有真实模型及其 alias 映射”的接口。

### 原因 2：现阶段最痛点是错误文案，不是存储坏掉

目前存量配置值仍然是 alias，本质没有坏掉。真正错误的是 UI 把 alias 解释成了过期版本号。

### 原因 3：最小改动目标与“自动真映射”天然冲突

要做到完全自动真映射，通常需要：

- 新接口
- 新缓存
- 新契约
- 新测试

这与“尽量不修改代码”的目标相冲突。对 `1.0.4` 来说，先改展示策略更合理。

## 1.0.4 建议结论

`1.0.4-SNAPSHOT` 建议正式采纳如下落地策略：

1. **本版实现**
   - 去掉 Claude Code 模型展示中的具体版本号
   - 保留 alias 值不变
   - 最多补一个前端共享 helper，避免多处重复硬编码
2. **本版不实现**
   - 不强做 alias -> 真实版本 的自动查询
   - 不依赖非稳定 CLI 私有输出
3. **本版文档预留**
   - 为未来 Worker capability / 执行结果反写真实模型设计留口

## 验收标准

1. 平台 UI 中不再把 `opus` 展示为固定 `4.6`。
2. 平台 UI 中不再把 `sonnet` 展示为固定 `4.6`。
3. 现有 `modelName` 与 `availableModels` 存量数据保持兼容。
4. 本次变更不要求后端 schema 变更。
5. 文档中明确未来若要做“真实映射自动化”，应优先走 capability 或执行结果回写，而不是继续硬编码版本号。

## 代码触点清单

| repo/module | path | role | expected_change | notes |
| --- | --- | --- | --- | --- |
| `navigator-frontend` | `packages/navigator-frontend/src/views/SettingsView.vue` | Claude Code 模型显示与选择 | update | 去掉过期版本号 |
| `navigator-frontend` | `packages/navigator-frontend/src/utils/llmModelOptions.ts` | 模型选项共享定义 | optional update | 若抽离静态配置，可作为统一来源 |
| `claude-agent-worker` | `tools/claude-agent-worker/...` | Worker capability 设计预留 | future | 非 1.0.4 必做项 |
| `claude-worker-agent` | `addons/claude-worker-agent/...` | 结果模型回写设计预留 | future | 若后续采纳执行结果反写方案 |

## 风险与注意事项

- 阶段 0 解决的是“标签过期”，不是“精确识别真实版本”
- 如果未来出现按真实模型版本计费、审计或权限差异需求，仍然需要做阶段 2/3
- 若某天 Claude Code alias 语义本身发生变化，仅靠中性标签不能解决全部问题，但至少不会继续误导用户

## 进展跟踪

### 开发进展

- 当前状态：`recorded`
- 已完成：版本设计方向、短期低风险方案与中长期自动映射路径已落盘
- 待执行：前端标签去版本号、可选抽 helper、后续 capability 方案评估

### 测试进展

- 当前状态：`pending`
- 建议补充：
  - 前端单测：Claude Code 模型选项标签不再带固定 `4.6`
  - 手工验证：确认配置保存后的值仍然是 alias，不受文案修改影响

### 体验进展

- 当前状态：`pending`
- 需要验证：
  - 用户是否能理解 `Opus / Sonnet` 为 Claude Code alias，而非某个写死版本
  - Claude Worker 页面与 LLM 配置页面的模型命名是否保持一致
