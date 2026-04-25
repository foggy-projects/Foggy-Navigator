# Gemini Worker v1 需求

## 文档作用

- doc_type: requirement
- intended_for: root-controller | execution-agent | reviewer | signoff-owner
- purpose: 明确 `GeminiWorker` 首版接入范围、边界和验收标准，作为 `1.3.0-SNAPSHOT` 的上游基线

## Version

- `1.3.0-SNAPSHOT`

## Priority

- P0

## Status

- Draft
- 2026-04-24 初版记录

## Background

当前平台已经具备两条本地 coding worker 通道：

1. `claude-worker`
2. `codex-worker`

统一任务分发、模型配置、会话投影、SSE relay 与前端 worker 管理界面都已经围绕这两类后端落地。但平台还没有一条可运行的 `Gemini CLI` worker 通道，导致：

1. 不能把 Gemini CLI 纳入统一任务面板和会话体系
2. 不能用平台模型配置把 Gemini 任务路由到指定 worker
3. 不能在相同工作目录下对比 Claude / Codex / Gemini 的执行能力

## Problem Statement

如果直接把 Gemini CLI 当成“临时脚本调用”，会持续有这些问题：

1. 无法复用现有任务状态、SSE 事件与会话恢复链路
2. 前端和调度层不知道 `Gemini` 是一个独立 worker backend
3. 工作目录中的 `.gemini` / `GEMINI.md` / `.gemini/agents` 约定无法沉淀进平台
4. 后续验证 Gemini 能力时无法与 Claude/Codex 做同构对比

## Target Outcome

`1.3.0-SNAPSHOT` 首版目标不是做完整 Gemini 生态管理，而是先交付一条可验证能力的最小闭环：

1. 新增 `GEMINI_CLI` worker backend
2. 新增 `gemini-worker` provider 路由
3. 提供独立 `tools/gemini-agent-worker` headless worker
4. 提供独立 `addons/gemini-worker-agent` Java 适配层
5. 能创建 Gemini 任务、接收流式事件、查看状态、执行中止、按 session 恢复
6. 能把 Gemini session 信息写回统一 `SessionEntity.providerStateJson`
7. 能在工作目录下建立 `.gemini` 指令上下文基础能力

## Scope

### In Scope

- `tools/gemini-agent-worker` 新 worker 服务
- `addons/gemini-worker-agent` 新 Java addon
- `session-module` 中 `GEMINI_CLI -> gemini-worker` 路由
- `metadata-config-module` 对 Gemini backend 的最小模型配置支持
- `navigator-frontend` 的 Gemini backend 枚举与模型下拉映射
- `docs/version-tracker/1.3.0-SNAPSHOT` 文档链路

### Out of Scope

- 首版不做 Gemini worker UI 全量运维能力补齐
- 首版不做完整的 `.gemini/agents` 管理面板
- 首版不做 checkpointing、process management、MCP 配置面板
- 首版不做与现有 `ClaudeWorkerEntity` 完全泛化的 worker 配置重构

## Functional Requirements

### 1. Worker Backend 与 Provider 路由

平台必须新增：

1. `workerBackend = GEMINI_CLI`
2. `providerType = gemini-worker`

并在统一调度链路中支持：

1. `modelConfigId -> GEMINI_CLI -> gemini-worker`
2. direct provider route 创建任务
3. resume 时从 session 绑定中恢复 Gemini provider

### 2. Gemini Worker 服务

首版 `GeminiWorker` 必须提供最小 API：

1. `GET /health`
2. `POST /api/v1/query`
3. `GET /api/v1/tasks/{taskId}/subscribe`
4. `GET /api/v1/tasks/{taskId}/status`
5. `POST /api/v1/tasks/{taskId}/abort`
6. `GET /api/v1/sessions`

### 3. Headless CLI 适配

Worker 必须基于 Gemini CLI 的官方 headless 能力接入：

1. `gemini -p`
2. `--output-format stream-json`
3. `-m/--model`
4. `-r` 或 resume 能力
5. `--yolo` 或 `--approval-mode`

事件要转换成平台统一 `WorkerEvent`。

### 4. 会话与状态投影

平台必须为 Gemini 任务保存：

1. `geminiSessionId`
2. `workerTaskId`
3. `model`
4. `status`
5. token / duration / result / error

并写回：

1. `SessionTaskEntity.taskStateJson`
2. `SessionEntity.providerStateJson`

### 5. 指令上下文

在工作目录执行 Gemini 任务前，首版至少需要支持：

1. 项目级 `.gemini/settings.json`
2. `GEMINI.md`
3. `.gemini/agents` 的基础目录/链接准备

## Non-Functional Requirements

1. 不破坏现有 `claude-worker` 和 `codex-worker`
2. 对 Gemini CLI 未安装、未登录、认证失败要有明确错误事件
3. Worker 事件要兼容现有 SSE relay 和任务页面，不引入新的前端协议
4. 新增 provider 后，旧会话与旧模型配置行为保持兼容

## Acceptance Criteria

本需求达成后的验收标准是：

1. 能通过平台创建一条 Gemini 任务并拿到流式输出
2. 能中止 Gemini 任务，状态变为终态
3. 能基于已有 session 恢复 Gemini 会话继续执行
4. `modelConfig.workerBackend = GEMINI_CLI` 时，统一调度能正确路由到 `gemini-worker`
5. 相关设计、职责、代码清单和实现计划已落盘到 `1.3.0-SNAPSHOT`
