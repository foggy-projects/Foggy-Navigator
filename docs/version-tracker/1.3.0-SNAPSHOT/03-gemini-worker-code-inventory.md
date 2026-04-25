# Gemini Worker v1 代码清单

## 文档作用

- doc_type: code-inventory
- intended_for: execution-agent | reviewer
- purpose: 标记 GeminiWorker 首版需要新增或修改的代码入口

## Version

- `1.3.0-SNAPSHOT`

## 新增目录

### 1. Node Worker

- `tools/gemini-agent-worker/`

预期内容：

1. `src/index.ts`
2. `src/config.ts`
3. `src/models.ts`
4. `src/gemini/cli-wrapper.ts`
5. `src/gemini/event-mapper.ts`
6. `src/routes/*.ts`
7. `src/persistence/event-store.ts`
8. `tests/*.test.ts`

### 2. Java Addon

- `addons/gemini-worker-agent/`

预期内容：

1. `client/`
2. `service/`
3. `controller/`
4. `adapter/`
5. `repository/`
6. `model/entity|dto|form/`
7. `config/`
8. `spi/`

## 需要修改的现有文件

### 1. 根模块

- `pom.xml`

### 2. 统一调度

- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`
- `session-module/src/main/java/com/foggy/navigator/session/registry/UnifiedAgentResolver.java`

### 3. 模型配置

- `navigator-common/src/main/java/com/foggy/navigator/common/entity/LlmModelConfigEntity.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/form/LlmModelConfigForm.java`
- `metadata-config-module/src/main/java/com/foggy/navigator/metadata/query/config/service/LlmModelManagerImpl.java`

### 4. 前端

- `packages/navigator-frontend/src/types/index.ts`
- `packages/navigator-frontend/src/utils/llmModelOptions.ts`

## 参考实现

首版主要参考以下已存在实现：

1. `tools/codex-agent-worker/`
2. `addons/codex-worker-agent/`
3. `addons/claude-worker-agent/` 中 worker 管理与目录能力
4. `session-module/` 中 direct provider route 逻辑

## 风险点

1. 现有 worker 管理配置仍然以 `ClaudeWorkerEntity + CodexConfig` 为中心，不够泛化
2. Gemini CLI 本地未安装或未登录时，运行时只能做错误提示，不能在仓库内完成端到端验证
3. `stream-json` 事件结构与 Codex SDK 不同，需要在 Node worker 内做额外映射容错
