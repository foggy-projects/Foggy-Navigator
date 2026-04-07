# 05 Shared Agent API Expansion

## Date

- 2026-04-03

## Type

- Requirement
- Deferred

## Background

当前 `POST /api/v1/shared/ask` 已支持通过 `X-Sharing-Key` 调用 Agent，而且 Shared API 已不再只有单点创建入口，任务查询、任务取消和会话历史查询能力已经开始补齐。

但整体 Shared API 仍不足以支撑更完整的跨机器协作和自动化场景，例如：

- 外部系统无法读取 Agent 生成的文件
- API 返回内容受 output token 限制，长结果可能被截断
- 外部系统缺少权限请求回复、任务产物访问、目录浏览等能力

## Goal

构建一套完整的 Shared Agent API，使外部系统在持有 `X-Sharing-Key` 的前提下，可以安全地完成以下操作：

- 创建任务
- 查询任务状态
- 获取任务产出
- 查询完整会话历史
- 控制任务执行过程
- 在受控范围内与 Agent 工作目录交互

## Current Implementation Sync

当前实现并不是“从零开始”，需要先承认已存在的 Shared API 基础能力。

### 已实现

- `POST /api/v1/shared/ask`
- `GET /api/v1/shared/tasks/{taskId}`
- `POST /api/v1/shared/tasks/{taskId}/cancel`
- `GET /api/v1/shared/sessions/{sessionId}`

### 已有的安全基础

- `SharingKeyService.validateAndConsume(...)` 用于执行类调用
- `SharingKeyService.validateForKeyOnly(...)` 用于查询 / 取消 / 会话读取等不消费额度的操作
- `SharedTaskController` 已做 task / session 与 sharing key 归属校验

### 仍未实现

- `POST /api/v1/shared/tasks/{taskId}/respond`
- `GET /api/v1/shared/tasks/{taskId}/artifacts`
- `GET /api/v1/shared/files/read`
- `GET /api/v1/shared/files/list`
- `GET /api/v1/shared/files/search`
- `POST /api/v1/shared/exec`
- `SharingKeyEntity` / `SharingKeyDTO` 维度的 `allowedOperations`

因此本项应调整为“Shared API 扩展需求”，而不是“从零新增整套 Shared API”。

## Related Code Checklist

### 当前 Shared API

- `session-module/src/main/java/com/foggy/navigator/session/controller/SharedAskController.java`
- `session-module/src/main/java/com/foggy/navigator/session/controller/SharedTaskController.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/SharingKeyService.java`
- `session-module/src/main/java/com/foggy/navigator/session/service/TaskDispatchFacade.java`

### 共享资源归属相关

- `session-module/src/main/java/com/foggy/navigator/session/repository/SessionRepository.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/entity/SharingKeyEntity.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/dto/SharingKeyDTO.java`
- `navigator-common/src/main/java/com/foggy/navigator/common/dto/DispatchTaskDTO.java`

### 可复用的文件 / 执行能力参考点

- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/FileBrowserController.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/SshProxyController.java`
- `addons/claude-worker-agent/src/main/java/com/foggy/navigator/claude/worker/controller/openapi/OpenApiController.java`

### 当前测试

- `session-module/src/test/java/com/foggy/navigator/session/controller/SharedTaskControllerTest.java`
- `session-module/src/test/java/com/foggy/navigator/session/service/SharingKeyServiceTest.java`

## API Draft

建议继续统一挂载在 `/api/v1/shared/` 下：

```text
/api/v1/shared/
├── ask
├── tasks/
│   ├── {taskId}
│   ├── {taskId}/cancel
│   ├── {taskId}/respond
│   └── {taskId}/artifacts
├── sessions/
│   └── {sessionId}
├── files/
│   ├── read?path=xxx
│   ├── list?path=xxx
│   └── search?q=xxx
└── exec
```

## Design Notes

### 1. 保留现有 SharedTaskController，不必为了“扩展”假设一切从头拆

当前 `SharedTaskController` 已经承担：

- task get
- task cancel
- session messages get

后续是否拆分为 `SharedSessionController` / `SharedFileController` / `SharedExecController`，应作为结构优化决策，而不是错误假设这些控制器当前已经存在。

### 2. 查询类能力和执行类能力要继续分层

当前 `validateAndConsume(...)` 与 `validateForKeyOnly(...)` 的分离是合理基础，后续扩展应继续沿用：

- 执行类：`ask`、`respond`、`exec`
- 查询类：`task:get`、`session:get`、`files:read/list/search`

### 3. `allowedOperations` 现在还没有实现

文档里提到的“操作范围控制”目前只是需求，不是现有能力。后续如果要做，应明确落到：

- `SharingKeyEntity`
- `SharingKeyDTO`
- create / update form
- service 级 operation 校验

## Suggested Analysis Checklist

以下清单只是建议技术优先从这些点开始分析，不作为强制拆解要求：

1. 先复核现有 Shared API 已覆盖的行为，避免重复造控制器
2. 明确 `respond` 的语义范围：
   - 只处理权限确认
   - 还是统一承接权限确认、计划评审、用户追问
3. 明确 `artifacts` 的首版范围：
   - 只返回文件列表
   - 还是同时支持下载
4. 明确文件接口的目录边界、软链接策略和 canonical path 校验方案
5. 评估 `exec` 是否首期上线，还是继续 deferred

## Minimal Change List

### 1. Shared API 能力补齐

- 在现有 `SharedTaskController` 基础上评估新增 `respond` / `artifacts`
- 视职责边界新增 `SharedFileController`
- 视风险和开关策略评估 `SharedExecController`

### 2. Sharing Key 权限模型扩展

- `SharingKeyEntity` 增加 `allowedOperations`
- `SharingKeyDTO` 返回 `allowedOperations`
- create / update form 支持配置 allowed operations
- service 增加 operation 级校验

### 3. 资源归属校验

- task -> sharing key / agent 归属校验
- session -> sharing key / agent 归属校验
- file path -> working directory 边界校验

### 4. 配额与审计

- 明确执行类和查询类操作的额度策略
- 补齐 shared API 的审计日志

## Acceptance Criteria

- 已有 Shared API 能力保持兼容，不因扩展而退化
- `respond`、`artifacts`、`files:*` 的资源归属和权限边界明确
- 查询类接口不误消耗创建任务额度
- `allowedOperations` 可限制不同 sharing key 的暴露能力
- 越权 task / session / file 访问会返回明确错误
- 若 `exec` 首期上线，必须具备显式开关和独立权限模型

## Test Scope

### Unit / Controller

- `SharedTaskController` 补充 `respond` / `artifacts` 相关单测
- `SharedFileController` 的路径边界校验测试
- `SharingKeyService` 的 operation 校验测试

### Integration / Regression

- `shared/ask` 仍正常可用
- `shared/tasks/{taskId}` 和 `shared/tasks/{taskId}/cancel` 继续保持兼容
- `shared/sessions/{sessionId}` 继续可读且不消耗额度
- 不同 sharing key 无法访问彼此 task / session / file

### Frontend / Playwright Validation

如果前端会暴露 Shared API 管理或调试页面，建议最小验证路径：

1. 创建一个只允许 `ask` + `task:get` 的 sharing key
2. 发起 shared ask，确认任务创建成功
3. 访问 task 状态成功
4. 尝试调用未授权操作，例如 cancel 或 files，确认被拒绝
5. 换用具备更高权限的 sharing key，再验证对应能力开放

## Delivery Assessment

本项现在可以直接交付技术，但必须以“扩展现有 Shared API”而不是“新建整套 Shared API”的口径推进。

## Status

本项作为 `1.0.0-SNAPSHOT` 版本需求记录，可直接交付技术进入分析与分批设计。

## Implementation Status

### Batch 1: respond + artifacts（2026-04-03）✅

**已实施**：在 `SharedTaskController` 中新增两个端点：

1. **POST /api/v1/shared/tasks/{taskId}/respond**
   - 外部系统通过 Sharing Key 回复 Agent 的权限确认 / 用户问题
   - 使用 `validateForKeyOnly`（不消耗额度）
   - 复用 `taskDispatchFacade.respondToTask()` 路由到对应 Provider
   - 处理 `UnsupportedOperationException`（Agent 不支持 respond）

2. **GET /api/v1/shared/tasks/{taskId}/artifacts**
   - 获取任务产物（A2A artifacts — Agent 生成的文本/代码等输出）
   - 通过 `agent.getTask(taskId)` 获取完整 A2aTask
   - 返回 `List<A2aArtifact>`

安全：复用现有 `findAuthorizedTask()` 归属校验（key → agent → task），`/api/v1/shared/**` 已在 SecurityConfig 中 permitAll。

### Batch 2: allowedOperations 权限模型（2026-04-03）✅

**已实施**：

1. **SharingKeyEntity** 新增 `allowedOperations` 字段（VARCHAR 512，逗号分隔，null = 允许全部）
2. **SharingKeyDTO** 新增 `allowedOperations: List<String>`
3. **SharingKeyCreateForm / UpdateForm** 新增 `allowedOperations: List<String>`
4. **SharingKeyService** 新增 `checkOperation(entity, operation)` 方法
   - null allowedOperations = 允许全部（向后兼容历史 key）
   - create/update 时保存，toDTO 时转换
5. **所有 Shared API 端点**接入 `checkOperation`：
   - `SharedAskController.ask` → `"ask"`
   - `SharedTaskController.getTask` → `"task:get"`
   - `SharedTaskController.cancelTask` → `"task:cancel"`
   - `SharedTaskController.respondToTask` → `"task:respond"`
   - `SharedTaskController.getArtifacts` → `"task:artifacts"`
   - `SharedTaskController.getSessionMessages` → `"session:get"`

有效操作标识：`ask`, `task:get`, `task:cancel`, `task:respond`, `task:artifacts`, `session:get`

### Batch 3: files/read|list|search 文件接口（2026-04-03）✅

**已实施**：

1. **WorkerManagementFacade SPI** 新增 `listFiles`、`readFile`、`searchFiles` 三个 default 方法
2. **ClaudeWorkerFacadeImpl** 实现三个文件操作方法，复用现有 WorkingDirectoryRepository + ClaudeWorkerClient
   - 含 `buildSafePath` 路径穿越防护（禁止 `..`）
   - 含 userId + directoryId 归属校验
3. **SharedFileController** 新建（session-module）：
   - `GET /api/v1/shared/files/list?directoryId=&path=`
   - `GET /api/v1/shared/files/read?directoryId=&path=`
   - `GET /api/v1/shared/files/search?directoryId=&q=`
4. **allowedOperations** 扩展新增：`files:read`, `files:list`, `files:search`
5. 安全：每个端点调用 `checkOperation` + facade 内部 userId/directoryId 校验 + 路径穿越防护

### Batch 4 (移出 1.0.0-SNAPSHOT，转待定)

- `exec` 命令执行 — 高风险，需要独立安全设计（沙箱 + 命令白名单 + feature flag），不在当前版本实施

### 当前版本边界调整（2026-04-07）

- `1.0.0-SNAPSHOT` 的签收范围收敛为 Batch 1-3
- Batch 4 `exec` 从当前版本移出，转入待定事项，不再作为本版本签收前提

### 验证结果

- `mvn -pl session-module -am "-Dtest=SharedTaskControllerTest,SharingKeyServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `SharedTaskControllerTest` 5 passed
- `SharingKeyServiceTest` 23 passed
- 总计 28 tests passed, 0 failures, 0 errors

## 验收签收

- 签收状态：✅ 已签收
- 签收日期：2026-04-07
- 签收方式：版本文档审计签收
- 签收依据：Batch 1-3 已实施，且当前版本范围已明确排除 Batch 4；相关单测通过。
- 关联台账：[12-acceptance-signoff.md](./12-acceptance-signoff.md)
