# Gemini Worker v1 实现计划

## 文档作用

- doc_type: implementation-plan
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 将 `GeminiWorker` 首版接入拆成可执行阶段，作为开发与能力验证基线

## Version

- `1.3.0-SNAPSHOT`

## 上游输入文档

1. [01-gemini-worker-requirement.md](./01-gemini-worker-requirement.md)
2. [02-gemini-worker-module-responsibility.md](./02-gemini-worker-module-responsibility.md)
3. [03-gemini-worker-code-inventory.md](./03-gemini-worker-code-inventory.md)

## 1. 规划判断

### 1.1 接入策略

首版采用独立 provider 路线：

1. `gemini-worker` 独立 addon
2. `gemini-agent-worker` 独立 Node worker
3. 统一调度层只补枚举和映射，不重构所有旧 provider

### 1.2 复用策略

优先复用 `codex-worker` 的以下结构：

1. Node worker 的 HTTP API 形状
2. Java 侧 `TaskQueryProvider` + `StreamRelay`
3. 统一任务投影与 `providerStateJson`

### 1.3 首版能力边界

首版重点验证：

1. 创建任务
2. SSE 流式输出
3. 中止任务
4. session 恢复

暂不追求：

1. 进程管理
2. checkpointing
3. 完整 worker 配置泛化

## 2. 分阶段计划

### Phase 1: 文档与路由基线

- owner: docs + session-module

目标：

1. 建立 `1.3.0-SNAPSHOT` 文档基线
2. 补齐 `GEMINI_CLI` backend 与 provider 路由映射

交付物：

1. 版本文档包
2. backend / provider 映射代码

### Phase 2: Gemini Node Worker MVP

- owner: `tools/gemini-agent-worker/`

目标：

1. 基于 Gemini CLI `-p` + `--output-format stream-json` 跑通 headless 调用
2. 映射 `init/message/tool_use/tool_result/error/result` 事件
3. 支持 query / subscribe / status / abort / sessions

验收标准：

1. worker 能启动
2. worker 能对外持续推送统一 `WorkerEvent`

### Phase 3: Java Addon MVP

- owner: `addons/gemini-worker-agent/`

目标：

1. 新增 Gemini 任务实体、仓储、DTO、表单
2. 新增 Gemini worker client 与 stream relay
3. 实现 direct provider route 与 A2A provider 适配

验收标准：

1. 平台能创建 Gemini 任务
2. 流式消息能进入平台任务和会话投影

### Phase 4: 模型配置与前端最小接入

- owner: `metadata-config-module/` + `packages/navigator-frontend/`

目标：

1. 前端识别 `GEMINI_CLI`
2. 平台模型配置支持 Gemini backend
3. 任务创建时可正确选择 Gemini 模型

验收标准：

1. `modelConfigId` 能驱动统一调度路由到 `gemini-worker`

### Phase 5: 定向验证与回写

- owner: execution-agent | reviewer

目标：

1. 完成编译或测试级别验证
2. 记录阻塞项，例如本机未安装 Gemini CLI
3. 回写版本文档中的执行状态

## 3. 运行时假设

1. Worker 运行主机已安装 `gemini` CLI
2. Worker 运行主机已完成 Google 登录、API key 或 Vertex AI 配置
3. 平台与 worker 之间仍沿用当前 bearer token 保护方式

## 4. 风险控制

1. 对 `gemini` 可执行文件不存在的情况输出明确 `error` 事件
2. 不改动现有 Claude/Codex 任务表和控制器行为
3. 对不确定的 `stream-json` 事件字段采用容错解析，而不是写死单一 schema

## 5. 完成定义

本计划完成后的最低标准是：

1. 仓库内已存在独立 `GeminiWorker` 代码路径
2. 统一调度可以识别 `GEMINI_CLI`
3. 至少完成编译级或单测级验证
4. 文档已记录当前能力、验证结果与后续待办
