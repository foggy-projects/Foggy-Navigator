# OPT-002 Codex GPT-5.6 分组模型目录与 Runtime 边界

## 文档作用

- doc_type: workitem
- intended_for: product-owner | execution-agent | reviewer
- purpose: 固化 Codex Sol/Terra/Luna 与 reasoning 档位的配置、选择、兼容和 Runtime 边界。

## 状态

- version: `1.4.0-SNAPSHOT`
- status: implemented
- reviewed_at: 2026-07-11
- review_result: approved-with-explicit-compatibility-rules
- progress: [OPT-002 progress](./OPT-002-codex-model-catalog-progress.md)

## 需求

> 2026-07-12 演进说明：模型族与 reasoning 目录仍有效；第 7 条“统一 Backend/Provider”的前向设计已被 [OPT-005](./OPT-005-codex-app-server-independent-provider.md) 取代。Low-Max 分别按所选 Backend 执行，Ultra 只属于 `OPENAI_CODEX_APP_SERVER` / `codex-app-server-worker`。

1. 从产品模型目录移除 `codex-mini`。
2. PC 与 APP 保持一个模型选择控件；Codex 在控件内部按 Sol、Terra、Luna 分组展示 reasoning 档位，其他编程 Worker 仍使用普通平铺选项。
3. LLM 设置页同样按模型族分组，不设置 Sol/Terra/Luna 总开关；某组至少勾选一个 reasoning 档位即认为该组已开放。
4. `availableModels` 保存具体的模型族与 reasoning 档位，不新增数据库字段。
5. APP 不新增 LLM 配置入口，只消费平台已有配置；订阅模式配置即使没有 API Key 也必须可选。
6. `codex-agent-worker` 最高支持 Max，并拒绝全部 Ultra 请求；Ultra 由 `codex-app-server-worker` 执行。
7. 平台继续使用统一的 `OPENAI_CODEX` backend；App Server 是独立 runtime，不新增第二套业务 Provider。

## 规范模型值

新 UI、授权比较和任务请求使用以下规范值：

| 分组 | 可选档位 | 规范值示例 | Runtime 边界 |
|---|---|---|---|
| Codex Sol | Low / Medium / High / Extra High / Max / Ultra | `codex-latest:high` | Low-Max 可走 SDK 或 App Server；Ultra 仅 App Server |
| Codex Terra | Low / Medium / High / Extra High / Max / Ultra | `codex-terra:max` | Low-Max 可走 SDK 或 App Server；Ultra 仅 App Server |
| Codex Luna | Low / Medium / High / Extra High / Max | `codex-luna:xhigh` | SDK 或 App Server；当前能力矩阵不开放 Ultra |

- UI 折叠后的选中标签使用 `Codex Terra · High` 这类完整名称。
- 分组展开时只显示档位名称，避免重复模型族名称。
- `xhigh` 的展示名称统一为 `Extra High`。
- 空 `availableModels` 保留现有语义：不限制；一旦勾选任意档位，则按规范值精确限制。

## 兼容归一化

旧配置、旧 Agent 默认模型和历史请求继续可读，但进入授权比较前必须归一化：

| 旧值 | 规范值 |
|---|---|
| `codex-latest` | `codex-latest:medium` |
| `codex-fast` | `codex-latest:low` |
| `codex-deep` | `codex-latest:high` |
| `codex-xhigh` | `codex-latest:xhigh` |
| `codex-max` | `codex-latest:max` |
| `codex-ultra` | `codex-latest:ultra` |
| `codex-terra` | `codex-terra:medium` |
| `codex-luna` | `codex-luna:medium` |
| `gpt-5.6-sol:<effort>` | `codex-latest:<effort>` |
| `gpt-5.6-terra:<effort>` | `codex-terra:<effort>` |
| `gpt-5.6-luna:<effort>` | `codex-luna:<effort>` |

约束：

- 旧值只开放其对应的单个规范档位，不扩大为整个模型族。
- 历史 `availableModels` 全部是未知真实模型名时，继续使用旧版迁移兜底展示普通档位，但 Max/Ultra 不自动开放。
- 未来未知真实模型串保持动态透传兼容；只有已识别的 GPT-5.6 Sol/Terra/Luna 目录项执行规范值精确授权。
- 任务最终能否执行仍由目标 runtime capability manifest 判定，配置授权不能替代运行时能力检查。
- 已绑定到 SDK runtime 的历史 Ultra thread 无法在原 runtime 续接；请求必须明确失败，不能静默降级为 Max/xhigh，也不能跨 runtime 重放原 prompt。
- `gpt-5.4-mini` 不再提供 alias、目录、动态透传或续接兼容；SDK 与 App Server 均稳定拒绝该模型，禁止静默切换到其他模型。
- 当前运行中的旧版 Worker/前端不会因源码修改自动更新，需要重新构建、发布和重启后才生效。

## 实现范围

### PC

- `llmModelOptions` 提供分组元数据、规范化和授权过滤。
- 设置页的 Codex 默认模型与可用模型按三组渲染。
- Worker 新建任务、紧凑工具栏、转发任务、Agent 默认模型与 `/model` 命令消费同一目录；仍为单一选择值。

### APP

- 使用与 PC 相同的规范值和兼容过滤规则。
- Codex 模型选择改为单个分组选择面板；其他 backend 保持原生 action sheet。
- 平台模型配置使用订阅能力判断，不能只按 `hasApiKey` 过滤。
- 本轮不增加 APP 端 LLM 配置编辑页面。

### Java / Runtime

- 对已识别 Codex 目录项，将请求值与 `availableModels` 同时归一化后精确比较。
- 空名单继续表示不限制；未知动态模型沿用现有透传行为。
- Runtime Registry 继续依据逐模型 `model_reasoning_matrix` 选路，不跨模型族推断 Max/Ultra。

### Worker

- SDK Worker 继续支持 alias 后缀到 Max，并对所有 Ultra fail closed。
- App Server Worker 使用现有逐模型矩阵：Sol/Terra 到 Ultra，Luna 到 Max。
- 本事项不修改用户已有的 Worker 打包、安装和更新脚本变更。

## Review 结论

| 检查项 | 结论 |
|---|---|
| 版本目标一致性 | 通过；属于 1.4.0 全模型/reasoning 迁移范围 |
| UI 可扩展性 | 通过；保持单控件，Codex 仅增加分组元数据，不把所有 Worker 固化成双控件 |
| 数据兼容性 | 通过；复用 `availableModels`，通过规范化兼容旧 alias，无数据库迁移 |
| 授权边界 | 通过；限制到具体模型族与档位，不因组开启扩大授权 |
| Runtime 边界 | 通过；授权与 capability 双重约束，SDK Ultra 仍 fail closed |
| APP 边界 | 通过；只消费平台配置，不扩大为移动端配置管理 |
| 阻断项 | 无代码实现阻断；真实 Ultra 执行仍受 OPT-001 App Server readiness gate 约束 |

## 非目标

以下 Backend/Provider 非目标是 OPT-002 当时的历史边界；相关条目由 OPT-005 覆盖，不再代表当前架构。

- 不把模型族与推理档位拆成两个独立控件。
- 不为每个 Terra/Luna 档位新增固定 alias。
- 不新增 `OPENAI_CODEX_APP_SERVER` backend 或第二套 Provider。
- 不在 APP 新增 LLM 配置管理。
- 不改变 OPT-001 的生产 canary、默认切流和 SDK 退役门禁。

## 验收标准

- [x] 设置页按 Sol/Terra/Luna 分组，组内勾选 reasoning；无独立模型族勾选框。
- [x] PC 所有 Codex 模型入口保持一个下拉框并按模型族分组。
- [x] APP 可选择平台订阅 Codex 配置，并在一个分组面板中选择模型与 reasoning。
- [x] 旧 alias 与真实 GPT-5.6 值按表归一化，且不会扩大授权。
- [x] Sol/Terra Ultra 只路由 App Server；Luna 不显示 Ultra；SDK Worker 继续拒绝全部 Ultra。
- [x] Java、PC、APP 定向单测通过；PC 与 APP 构建通过。
