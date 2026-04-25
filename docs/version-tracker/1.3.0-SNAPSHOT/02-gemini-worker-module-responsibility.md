# Gemini Worker v1 模块职责

## 文档作用

- doc_type: module-responsibility
- intended_for: execution-agent | reviewer
- purpose: 划分 GeminiWorker 首版改动的模块边界，避免跨模块职责漂移

## Version

- `1.3.0-SNAPSHOT`

## 模块职责划分

### 1. `tools/gemini-agent-worker/`

职责：

1. 封装 Gemini CLI headless 调用
2. 将 `stream-json` 事件转换为平台统一 `WorkerEvent`
3. 持久化任务事件，支持 SSE 重连
4. 暴露 health / query / tasks / sessions API

不负责：

1. 平台会话实体持久化
2. 前端页面展示
3. 平台模型配置管理

### 2. `addons/gemini-worker-agent/`

职责：

1. 调用 `tools/gemini-agent-worker` API
2. 管理 Gemini 任务实体与 DTO
3. 将 Worker SSE 流转发到平台会话与消息体系
4. 实现 `TaskQueryProvider` 与 `A2aAgentProvider`

不负责：

1. Worker 注册实体的通用化大重构
2. Gemini CLI 本地安装与升级管理

### 3. `session-module/`

职责：

1. 统一任务分发时识别 `GEMINI_CLI`
2. 将 `GEMINI_CLI` 映射到 `gemini-worker`
3. 保持 create/resume/cancel/query 统一路由能力

不负责：

1. Gemini 任务本身的远端调用细节

### 4. `metadata-config-module/`

职责：

1. 允许平台模型配置使用 `GEMINI_CLI`
2. 复用现有 worker backend 存储能力
3. 处理 Gemini 本地登录模式下的 API key 规则

不负责：

1. 维护 Gemini worker 的运行状态

### 5. `packages/navigator-frontend/`

职责：

1. 暴露 `GEMINI_CLI` backend 枚举
2. 在模型下拉中显示 Gemini 可选模型
3. 与现有任务视图兼容显示 Gemini 任务

不负责：

1. 首版完整 Gemini worker 配置面板重构

### 6. `docs/version-tracker/1.3.0-SNAPSHOT/`

职责：

1. 记录需求、职责、代码清单和实现计划
2. 为后续 Gemini 能力验证提供基线

## 设计原则

1. Gemini 首版走独立 provider，不挤进 `codex-worker`
2. 尽量复用 Codex 的 worker API 形状与 Java relay 结构
3. 先保证任务链路跑通，再考虑 worker 配置模型泛化
