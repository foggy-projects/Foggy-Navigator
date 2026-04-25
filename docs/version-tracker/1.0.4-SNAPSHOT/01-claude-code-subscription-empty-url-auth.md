# 01 Claude Code 订阅模式支持空 URL / 空 Key 配置

## 文档作用

- doc_type: requirement | implementation-plan
- intended_for: execution-agent | reviewer
- purpose: 明确 `workerBackend=CLAUDE_CODE` 时允许上游传空，并以空 `baseUrl` 表达订阅模式，驱动 `tools/claude-agent-worker` 使用本机 `claude login`。

## 基本信息

- version: `1.0.4-SNAPSHOT`
- date: `2026-04-22`
- source_type: requirement | auth-contract clarification
- priority: `P0`
- status: `in_progress`

## 背景

当前平台已经具备 Claude Worker 订阅执行能力，但平台侧“LLM 配置”与运行期语义仍未完全对齐：

- `tools/claude-agent-worker` 在未注入 `ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN / ANTHROPIC_BASE_URL` 时，会回退到本机 `claude login` 的订阅态
- `SettingsView.vue` 中 `workerBackend=CLAUDE_CODE` 仍沿用普通 OpenAI-compatible 配置校验，要求填写 `API Base URL` 与 `API Key`
- `metadata-config-module` 当前只为 `OPENAI_CODEX` 定义了“空 URL = subscription”的配置语义，Claude Code 尚未对齐
- `ClaudeTaskService` 当前更偏向“有 key 就下发、没 key 就继续回退目录/Worker 默认”，缺少“显式选择订阅模型配置”的一跳语义

这导致用户虽然在 Worker 机器上已经 `claude login`，但平台侧无法通过“空 URL + 空 Key”的模型配置，把“我要用订阅”稳定表达出来。

## 问题陈述

本条需求不是简单地“把必填去掉”，而是要明确一套对上游稳定可用的契约：

1. 上游允许传空
2. 空 `baseUrl` 在 `CLAUDE_CODE` 后端下是合法输入
3. 空 `baseUrl` + 空 `apiKey` 不表示“配置缺失”，而表示“使用订阅模式”
4. 该语义要贯穿前端表单、平台配置存储、运行期 Auth 解析与 Worker 侧最终执行

否则会出现两个风险：

- UI 层拦截，用户根本存不下订阅配置
- 后端误把“显式订阅配置”当成“没有配置，继续回退其他目录默认 Auth”，导致行为不稳定

## 目标

为 `workerBackend=CLAUDE_CODE` 建立正式的 subscription 配置语义：

1. 允许 `baseUrl` 为空字符串或 `null`
2. 允许 `apiKey` 为空字符串或 `null`
3. 当 `baseUrl` 为空且 `apiKey` 为空时，认定为 `SUBSCRIPTION`
4. 对订阅模式，平台运行期不得向 Worker 注入 `apiKey / authToken / baseUrl`
5. Worker 在该场景下使用本机 `claude login` 的订阅态
6. 保持空字符串与 `null` 归一化一致

## 范围

### In Scope

- `LLM 配置` 页面允许在 `CLAUDE_CODE` 后端下保存空 URL / 空 Key
- 平台模型配置存储层新增或泛化 `CLAUDE_CODE` 的 subscription 识别逻辑
- Claude Task 创建时识别“显式订阅模型配置”
- 文案与帮助说明补齐“留空表示订阅模式”
- 测试用例与版本文档回写

### Out Of Scope

- 多订阅账号的按请求切换
- 基于同一 Worker 的订阅账号选择器
- 统一重构整个 Worker Auth 模型
- 在本条中新增新的外部认证类型字段

## 契约定义

### 1. 配置语义

当且仅当满足以下条件时，模型配置被认定为 Claude 订阅模式：

- `workerBackend = CLAUDE_CODE`
- `baseUrl` 为空字符串或 `null`
- `apiKey` 为空字符串或 `null`

此时该配置表达的是：

- 使用 Claude Code 默认端点
- 不注入平台 API Key
- 由 Worker 所在机器的 `claude login` 订阅态提供认证

### 2. 空 URL 的核心语义

本条中最重要的契约是：

- `CLAUDE_CODE` 下允许 `baseUrl` 为空
- 空 `baseUrl` 是一个合法值，不再等同于“缺少必填项”
- 对订阅模式而言，空 `baseUrl` 是必要语义，而不是异常输入

### 3. 运行期语义

如果用户显式选择了一个符合上述条件的模型配置，则运行期应视为“显式订阅”：

- `ClaudeTaskService` 不应继续把它当成“未配置模型 Auth”
- 应直接下发空认证参数到 Worker
- Worker 端最终由 `tools/claude-agent-worker` 回退到本机 `claude login`

## 验收标准

1. 在 `LLM 配置` 中新增或编辑 `CLAUDE_CODE` 模型时，空 `API Base URL` 不再被表单校验拦截。
2. 在 `LLM 配置` 中新增或编辑 `CLAUDE_CODE` 模型时，空 `API Key` 不再被表单校验拦截。
3. 模型配置持久化后，后端能够将该配置识别为 Claude subscription config，而不是普通“无 Key 的坏配置”。
4. 当任务显式使用该模型配置时，平台运行期向 Worker 传递的 `apiKey / authToken / baseUrl` 均为空。
5. Worker 在目标机器已执行 `claude login` 的前提下，能够实际走订阅执行。
6. 现有 `OPENAI_CODEX` 的 subscription 语义不回归。
7. 现有 Claude API Key / 自定义端点配置路径不回归。

## 实现建议

### Stage 1: 前端表单契约对齐

- 调整 [`SettingsView.vue`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/SettingsView.vue) 中对 `CLAUDE_CODE` 的 `baseUrl` / `apiKey` 校验逻辑
- 将“Codex 可留空”的判断泛化为“支持 subscription 的 Worker Backend 可留空”
- 占位文案明确为“留空表示使用本机订阅”

### Stage 2: 配置存储与 DTO 语义对齐

- 将 `metadata-config-module` 当前只面向 Codex 的 `isCodexSubscriptionConfig(...)` 逻辑抽象或复制到 Claude Code
- `toDTO()` 中的 `hasApiKey` 语义要与 Claude subscription config 对齐
- 对空字符串与 `null` 的归一化保持一致，避免前后端来回抖动

### Stage 3: 运行期显式订阅识别

当前风险点在于：

- `ClaudeTaskService` 目前更像是“如果 modelConfig 有 key 就用；没 key 就继续走目录/Worker 默认”

本条建议补齐：

- 当任务显式指定的 `modelConfigId` 对应一个 `CLAUDE_CODE subscription config` 时，直接将其解释为 `SUBSCRIPTION`
- 返回 `[null, null, null]` 给 Worker，而不是继续 fall through 到目录默认 Auth

否则“用户明确选了订阅模型配置”这一事实，在运行期仍然表达不出来。

### Stage 4: 测试连接与提示文案

当前 `testConnection()` 仍是短路成功，后续至少要保证：

- subscription config 不会因为空 URL / 空 Key 被前端或后端测试入口拦截
- 如果目标 Worker 未登录 Claude，最终错误提示应落到“Worker 未完成 `claude login`”而不是“缺少 API Key”

## 代码触点清单

| repo/module | path | role | expected_change | notes |
| --- | --- | --- | --- | --- |
| `navigator-frontend` | `packages/navigator-frontend/src/views/SettingsView.vue` | LLM 配置表单校验与文案 | update | 放开 Claude Code 的空 URL / 空 Key 校验 |
| `metadata-config-module` | `metadata-config-module/src/main/java/com/foggy/navigator/metadata/query/config/service/LlmModelManagerImpl.java` | 模型配置存储与 DTO 归一化 | update | 新增/泛化 Claude subscription 识别 |
| `claude-worker-agent` | `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java` | 任务运行期 Auth 解析 | update | 显式识别 Claude subscription config |
| `claude-agent-worker` | `tools/claude-agent-worker/src/agent_worker/claude/sdk_wrapper.py` | Worker 认证环境注入 | verify | 已具备空 env 回退订阅能力，本次以校验为主 |

## 风险与注意事项

- 如果不区分“显式订阅配置”和“未配置 Auth 的默认回退”，用户会遇到不可预测行为
- 如果 Worker 机器本身未执行 `claude login`，subscription 模式不会成功
- 若将来支持多订阅账号，本条的“空 URL 表示订阅”仍然成立，但还需要额外账号选择维度

## 进展跟踪

### 开发进展

- 当前状态：`in_progress`
- 已完成：
  - [`SettingsView.vue`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/views/SettingsView.vue) 已放开 `CLAUDE_CODE` 的空 `baseUrl` / 空 `apiKey` 校验，并补充“留空表示订阅模式”的说明文案
  - [`LlmModelManagerImpl.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/metadata-config-module/src/main/java/com/foggy/navigator/metadata/query/config/service/LlmModelManagerImpl.java) 已将 subscription 判定从 Codex 泛化到 Claude Code，`hasApiKey` / `getDecryptedApiKey` 已与之对齐
  - [`ClaudeTaskService.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/service/ClaudeTaskService.java) 已支持“显式选择 Claude subscription config 时直接绑定 `SUBSCRIPTION` 并返回空认证参数”
  - [`llmModelOptions.ts`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/utils/llmModelOptions.ts) 已修正会话页平台模型筛选逻辑，`CLAUDE_CODE` 订阅配置不再因 `hasApiKey=false` 被前端过滤
- 待执行：
  - 前端构建验证
  - 联调真实 Worker 的 `claude login` 订阅执行链路
  - 结合真实任务报错文案再确认体验措辞

### 测试进展

- 当前状态：`in_progress`
- 已补充：
  - [`LlmModelManagerImplTest.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/metadata-config-module/src/test/java/com/foggy/navigator/metadata/query/config/service/LlmModelManagerImplTest.java) 新增 Claude subscription config 的保存/DTO/解密语义单测
  - [`ClaudeTaskServiceAuthTest.java`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/addons/claude-worker-agent/src/test/java/com/foggy/navigator/claude/worker/service/ClaudeTaskServiceAuthTest.java) 新增“显式模型配置”和“目录默认模型配置”两条 Claude subscription 运行期分支单测
  - [`llmModelOptions.test.ts`](/D:/foggy-projects/Foggy-Navigator-wt-qd-win11-dev/packages/navigator-frontend/src/__tests__/llmModelOptions.test.ts) 新增会话页模型筛选回归测试，覆盖 Claude / Codex 订阅配置可见性与 Claude 可用模型过滤
- 已执行：
  - `mvn test -pl metadata-config-module -Dtest=LlmModelManagerImplTest`
  - `mvn test -pl addons/claude-worker-agent -Dtest=ClaudeTaskServiceAuthTest`
  - `npm test -- src/__tests__/llmModelOptions.test.ts`
- 当前结果：
  - 两条命令均未进入测试执行阶段，原因是当前本地 Maven 依赖未预装，缺少 `navigator-common`、`navigator-spi`、`session-module`、`agent-framework`、`user-auth-module` 等 `1.0.0-SNAPSHOT` 工件
  - 前端回归测试已通过：`1` 个测试文件、`4` 条用例全部通过，确认 `CLAUDE_CODE` 订阅配置会出现在会话页下拉中
- 待补充：
  - 在具备完整本地 Maven reactor 或先安装依赖后重新执行单测
  - 手工验证：目标 Worker 先执行 `claude auth status` / `claude login`，再用空 URL 配置发起真实任务

### 体验进展

- 当前状态：`in_progress`
- 已完成：
  - 添加模型弹窗已明确区分 Claude Code 的 API 模式与订阅模式
  - Claude Code 模型下拉的显示名已去掉固定 `4.6` 版本号，避免与当前 CLI 实际版本产生误导
  - 会话 / 任务区域的模型下拉已可见 Claude 订阅配置，避免“设置页能保存、会话页不可选”的割裂体验
- 待验证：
  - 添加模型弹窗中，Claude Code 的空 URL 文案是否足够清晰
  - 用户是否能理解“空 URL = 订阅模式”而不是“漏填”
