# 新增 Worker 开发指南与 Gemini Worker 复盘

## 文档作用

- doc_type: development-guide | retrospective
- intended_for: execution-agent | reviewer | platform-owner
- purpose: 沉淀新增 Worker 的标准接入清单，并复盘 Gemini Worker 暴露出的可抽象优化点

## Version

- `1.3.0-SNAPSHOT`

## 背景

Gemini Worker 首版接入已经证明平台可以继续扩展新的 CLI / Agent Worker，但也暴露出一个问题：当前平台仍有大量 `Claude/Codex/Gemini` 的枚举、配置和 UI 分支散落在 Java、Node Worker、前端、文档和部署脚本里。

本指南用于后续新增 Worker 时减少遗漏，并明确哪些改动属于当前必须做，哪些应逐步抽象，避免每次新增 Worker 都复制同一批改动。

## 一、当前新增 Worker 的必改清单

### 1. 先定义 Worker 身份契约

新增 Worker 前先确定以下稳定标识，不要边实现边临时命名：

1. `providerType`：平台会话与统一调度层使用的 provider 标识，例如 `gemini-worker`
2. `WorkerBackend`：模型配置层使用的后端枚举，例如 `GEMINI_CLI`
3. 默认端口：必须先检查现有 Worker 端口，避免和 LangGraph / Codex / Claude 冲突
4. 模型 alias：前端和 Java 只传稳定 alias，真实模型版本由 Worker 自己映射
5. 会话恢复字段：如果底层 CLI 有 provider-native session id，需要明确字段名和落库位置

判断标准：

1. 上游 Java 不应感知底层真实模型版本号
2. 前端历史会话重新打开时，必须能从历史任务恢复到正确 Worker 配置
3. resume 时必须优先使用 session 绑定的 provider，而不是信任前端传入的旧 `modelConfigId`

### 2. Node Worker 或 Worker Runtime

新增目录通常放在：

```text
tools/<worker-name>-agent-worker/
```

最小接口应保持和现有 Worker 对齐：

1. `GET /health`
2. `POST /api/v1/query`
3. `GET /api/v1/tasks/:taskId/subscribe`
4. `GET /api/v1/tasks/:taskId/status`
5. `POST /api/v1/tasks/:taskId/abort`
6. `DELETE /api/v1/tasks/:taskId`
7. `GET /api/v1/tasks`
8. `GET /api/v1/sessions`
9. `GET /api/v1/processes`
10. `POST /api/v1/processes/:pid/kill`

必须包含的实现点：

1. `.env.example` 与本地 `.env` 变量说明
2. `ALLOWED_CWDS` 或等价目录白名单
3. `AUTH_TOKEN` 校验
4. start / stop 脚本
5. 日志与事件落盘
6. 统一 `WorkerEvent` 映射
7. abort 与孤儿进程清理
8. CLI 不可用、未登录、未授权、模型不存在时的可读错误
9. Windows 与 Linux 的启动差异验证
10. headless / trust / sandbox 相关环境变量处理

Gemini 暴露的问题：

1. Windows 下直接 `spawn(..., { shell: true })` 会导致 CLI 参数解析异常
2. Gemini CLI headless 场景需要 `--skip-trust` 或 `GEMINI_CLI_TRUST_WORKSPACE=true`
3. `stream-json` 事件结构必须用真实 CLI 样本验证，不能按文档或猜测映射
4. 如果 `DELETE /api/v1/tasks/:taskId` 缺失，前端删除历史任务会报 provider 不支持
5. 如果 `/processes` 未接入，前端无法排查遗留 CLI 进程

### 3. Java Addon

新增 Worker 应有独立 addon，避免把 provider 逻辑塞进已有 Codex 或 Claude 模块：

```text
addons/<worker-name>-worker-agent/
```

最小代码结构：

1. client：封装 Worker HTTP API
2. client factory：根据 worker 配置生成 client
3. entity / repository：保存 provider task 状态
4. form / dto：对齐统一调度入参和返回值
5. service：创建任务、恢复任务、取消任务、删除任务、记录进度
6. stream relay：SSE 事件到 session event 的转换
7. provider：实现统一 Agent Provider SPI
8. auto-configuration：接入 Spring 自动装配
9. tests：覆盖 event relay、auth resolution、resume route、delete route

必须验证：

1. provider task id 和平台 task id 的绑定关系
2. provider-native session id 能写回 `DispatchTaskDTO`
3. provider state 能写入 `SessionEntity.providerStateJson`
4. task state 能写入 `SessionTaskEntity.taskStateJson`
5. resume 不依赖前端再次传 provider-native session id
6. abort / delete 不应只更新平台状态，必须向 Worker 发真实请求

Gemini 暴露的问题：

1. provider route 容易被旧 `modelConfigId` 误导，需要在 resume normalize 阶段清理跨 provider 的旧配置
2. task 完成但前端没有消息时，通常要同时检查 Java relay、Worker event mapper、前端 pane 渲染三层
3. 删除任务必须走统一 provider delete，不应只在某个 worker addon 内局部实现

### 4. 统一调度与会话模块

新增 Worker 必须检查以下统一层触点：

1. `TaskDispatchFacade`
2. `UnifiedAgentResolver`
3. `DispatchTaskDTO`
4. session provider route
5. resume request normalize
6. cancel / abort / delete route
7. SSE session event 映射
8. provider state projection

必须补的测试：

1. 新 `WorkerBackend` 能 direct route 到新 provider
2. resume 能根据 session 绑定 provider 路由
3. resume 遇到旧的跨 provider `modelConfigId` 时不会误路由
4. provider event 能驱动 task complete / failed / awaiting reply
5. delete / abort 能路由到正确 provider

### 5. 模型配置

新增 Worker 必须采用 alias-first 设计：

1. 前端展示稳定 alias
2. Java 保存稳定 alias
3. Worker 执行前将 alias 解析到真实模型
4. `.env` 允许覆盖 alias 映射
5. `/health` 返回当前 alias 映射，方便排查

需要同步的层：

1. Java `WorkerBackend` 枚举
2. `LlmModelConfigEntity`
3. `LlmModelConfigForm`
4. 模型配置保存与过滤逻辑
5. 前端 `WorkerBackend` 类型
6. 前端 `llmModelOptions`
7. 设置页模型配置 UI
8. Worker 编辑 UI 中的默认模型字段

Gemini 暴露的问题：

1. 如果前端能选 alias，但 Worker 实际未启动在目标端口，现象会像“alias 不生效”
2. 端口和 health 返回必须先验证，否则会误把路由错误判断成模型错误
3. 历史任务如果只记录真实模型，不记录 `modelConfigId`，前端必须能用 provider/model 兜底恢复配置

### 6. 前端 Workers 页面

新增 Worker 后必须检查以下 UI 场景：

1. Worker 注册弹窗
2. Worker 编辑弹窗
3. 设置页 LLM 配置弹窗
4. 主任务创建栏的 API / 模型选择
5. 历史会话打开后的 API / 模型恢复
6. 继续会话时的 `modelConfigId` 处理
7. 处理中 / 已完成 / 待回复状态展示
8. CLI 进程列表
9. 进程 kill
10. 删除历史任务

必须补的前端测试：

1. 新 backend 模型选项可见
2. 新 backend 配置可保存
3. 历史任务无 `modelConfigId` 时仍能恢复到正确 config
4. resume 不会把当前顶部选错的 API 强行传给后端
5. delete / abort 调用正确 provider endpoint

Gemini 暴露的问题：

1. 新增 Worker 时很容易只补 SettingsView，忘记 ClaudeWorkerView 的历史恢复逻辑
2. `providerTypeFromWorkerBackend` 这类硬编码映射一旦漏掉，前端创建任务会路由异常
3. `/api/v1/<provider>/...` 后端权限未配置时，前端会表现为 403
4. 历史会话打开后顶部 config 不切换，会导致用户继续输入时把 Gemini 会话续到 Claude / Codex 配置上

### 7. 部署、安装与本机运维

新增 Worker 必须同步：

1. `start.ps1` / `stop.ps1`
2. Linux start / stop 脚本
3. `.env.example`
4. package scripts
5. 安装包构建脚本
6. 安装 / 升级文档
7. 默认端口表
8. health check checklist
9. CLI 安装与登录说明
10. 底层 CLI 升级说明

必须手动验证：

1. 首次启动
2. 重复启动
3. 端口占用
4. stop 后无遗留 CLI 子进程
5. CLI 未安装时 health 明确失败
6. CLI 未登录时 health 或 query 明确失败
7. Worker 进程和底层 CLI 进程都能被平台识别

### 8. Skill / Agent 能力验证

如果新 Worker 声称支持 skill / agent linkage，需要独立验证，不要默认等价于 Claude / Codex。

验证顺序：

1. CLI 直连验证 skill discovery
2. CLI 直连验证 skill activation
3. Worker HTTP/SSE 验证 tool_use / tool_result
4. 平台前端整链路验证
5. 真实业务 skill 验证

Gemini 当前结论：

1. `.gemini/agents -> .claude/skills` 链接可用
2. Gemini CLI 可以激活项目级 skill
3. Worker 可以把 `activate_skill` 映射为统一工具事件
4. 但 Gemini 对 skill 指令的执行风格不完全等价于 Claude / Codex，复杂 skill 的稳定触发还需要继续观察

## 二、新增 Worker 标准验证矩阵

### L1：Worker 本体

1. `npm run typecheck` 或等价类型检查通过
2. `GET /health` 正常
3. `POST /query` 最小 prompt 正常
4. SSE 能收到 `assistant_text` 和 `result`
5. abort 能真实终止底层 CLI
6. delete 能清理 provider task
7. `/processes` 能列出底层 CLI
8. `/processes/:pid/kill` 能清理遗留进程

### L2：Java Addon

1. addon 模块编译通过
2. stream relay 单元测试通过
3. auth resolution 测试通过
4. provider route 测试通过
5. resume route 测试通过
6. delete / abort route 测试通过

### L3：统一 API

1. 通过模型配置创建任务
2. 通过 session id 继续任务
3. 取消任务
4. 删除任务
5. 查询历史任务
6. 查询进程列表
7. kill 孤儿进程

### L4：前端整链路

1. 设置页能创建新 backend 的 LLM 配置
2. Worker 编辑页能配置新 Worker endpoint
3. Workers 主页面能选择新 API 与模型
4. 创建任务后能看到流式输出
5. 任务完成后历史会话保留
6. 刷新页面后历史会话仍可打开
7. 打开历史会话后顶部 API / 模型自动恢复
8. 历史会话可以继续输入
9. 删除历史任务成功
10. 进程列表与 kill 能正常工作

## 三、本次 Gemini Worker 暴露出的主要问题

### 1. Worker 类型分散硬编码

现状：

1. Java 有 `WorkerBackend`
2. 前端也有 `WorkerBackend`
3. provider type 与 backend 的映射分散在多个文件
4. SettingsView / ClaudeWorkerView 各自维护展示和表单分支

风险：

1. 新增 Worker 时很容易漏一个入口
2. 漏掉后不一定编译失败，往往到联调才暴露
3. 历史会话、resume、delete 这种边缘路径更容易遗漏

### 2. Worker 配置仍挂在 ClaudeWorker 概念下

现状：

1. Worker 管理实体仍以 `ClaudeWorker` 为中心
2. Codex / Gemini 配置以附加 config 的形式挂载
3. 前端页面仍叫 `ClaudeWorkerView`

风险：

1. 语义已经落后于平台能力
2. 新 Worker 越多，表单字段越膨胀
3. 配置清除、默认模型、token 是否已保存等逻辑会越来越重复

### 3. CLI Worker 协议未完全标准化

现状：

1. Codex 和 Gemini 各自实现 query / events / processes
2. Java addon 需要为每个 provider 写一套 client / service / relay
3. Node worker 的事件映射、进程检测、alias、allowed cwd、安全校验重复

风险：

1. 每个新 Worker 都要重新踩 Windows 启动、进程清理、SSE replay、event mapper 的坑
2. delete / processes 这类非主链路能力容易缺失

### 4. 前端状态恢复缺少 provider 级统一模型

现状：

1. 历史会话恢复主要依赖 `modelConfigId`
2. 当历史任务没有 `modelConfigId` 时，需要用 provider / session id / model 兜底
3. 这套逻辑现在仍在页面里处理

风险：

1. 新 Worker 如果真实模型名和 alias 不一致，前端会恢复失败
2. 用户继续会话时可能把请求发到错误 provider

### 5. 权限和路由配置容易漏

现状：

1. 新增 provider API 后，需要同步后端安全白名单
2. 前端调用路径通常是按 provider 拼出的新 URL

风险：

1. 功能实现完成但前端仍 403
2. 排查时容易误判成 Worker 不可用

## 四、建议的后续抽象优化

### 1. 建立 WorkerBackendDescriptor 注册表

目标：

把每个 Worker 的静态元数据集中定义，而不是散落在前后端页面逻辑里。

建议字段：

1. `backend`
2. `providerType`
3. `displayName`
4. `defaultPort`
5. `baseUrlPlaceholder`
6. `defaultModelAlias`
7. `modelOptions`
8. `supportsSubscription`
9. `supportsApiKey`
10. `supportsProcesses`
11. `supportsSkills`

收益：

1. 前端 Settings / Workers / task form 统一从 descriptor 渲染
2. Java 调度层统一从 descriptor 查 provider
3. 新增 Worker 时优先新增 descriptor，再补 provider 实现

### 2. 抽象 Worker endpoint 配置

目标：

把 `claudeConfig / codexConfig / geminiConfig` 改成通用 backend config 列表。

建议结构：

```text
WorkerEndpointConfig
- workerId
- backend
- baseUrl
- authTokenEncrypted
- defaultModel
- enabled
- metadataJson
```

收益：

1. 新增 Worker 不再改 Worker entity 主表字段
2. 前端编辑弹窗可以按 backend 动态增删配置
3. token configured、清空配置、默认模型等逻辑可复用

### 3. 抽象 CLI Worker Base Runtime

目标：

把 Codex / Gemini 这类 CLI Worker 的通用能力沉到共享包。

可抽象能力：

1. Express 路由骨架
2. auth token middleware
3. allowed cwd 校验
4. event store
5. SSE replay
6. task registry
7. process detection
8. process kill
9. model alias resolver
10. start / stop 脚本模板

收益：

1. 新 Worker 只实现 CLI adapter 和 event mapper
2. delete / processes / health 不再遗漏
3. Windows / Linux 差异统一处理

### 4. 抽象 Java Provider Base

目标：

减少每个 Worker addon 的 client / task service / stream relay 重复。

可抽象能力：

1. Provider HTTP client base
2. Worker event DTO
3. SSE relay base
4. task projection helper
5. provider state persistence helper
6. abort / delete 标准实现
7. auth resolution helper

收益：

1. 新 provider 只写差异化 DTO 与事件适配
2. 统一错误处理与状态落库
3. 更容易补齐测试基类

### 5. 把前端 provider 映射改成数据驱动

目标：

减少 `if workerBackend === 'GEMINI_CLI'` 这类分支。

建议落点：

1. `packages/navigator-frontend/src/utils/workerBackendRegistry.ts`
2. `packages/navigator-frontend/src/utils/llmModelOptions.ts`
3. SettingsView 使用 registry 渲染 backend 选项
4. ClaudeWorkerView 使用 registry 做 provider/model 恢复

收益：

1. 新 Worker 不需要同时修改多个页面
2. 历史会话恢复、模型 alias 匹配可以统一测试
3. UI 标签、颜色、占位符和能力开关集中维护

### 6. 统一 provider API 权限注册

目标：

新增 provider endpoint 时，不再手工找安全白名单。

建议：

1. provider addon 暴露自己的 API pattern
2. 安全配置从 provider registry 聚合允许路径
3. 集成测试覆盖新 provider 的 processes / delete / query 权限

收益：

1. 避免前端 403 类问题
2. 新增 Worker 的验收清单更短

## 五、建议的实施优先级

### P0：立即纳入新增 Worker checklist

1. 固定 provider/backend/port/model alias 命名
2. 必须实现 delete 和 processes
3. 必须补历史会话模型恢复测试
4. 必须验证前端继续会话不会跨 provider
5. 必须提供 start / stop / `.env.example`
6. 必须提供 L1-L4 验证记录

### P1：下一轮优先优化

1. 前端 `workerBackendRegistry`
2. CLI Worker Base Runtime
3. Java provider route / resume / delete 的测试模板
4. Worker health 返回标准化
5. provider API 权限测试

### P2：结构性重构

1. 通用 `WorkerEndpointConfig`
2. Java Provider Base
3. `ClaudeWorkerView` 命名与结构治理
4. 多 Worker endpoint 的动态配置 UI

## 六、后续新增 Worker 的建议执行顺序

1. 先写一页 worker identity doc，确认 provider/backend/port/alias/session 字段
2. 建 Worker runtime，完成 health/query/subscribe/status/abort/delete/processes
3. 用真实 CLI 样本锁定事件结构
4. 接 Java addon，先补 relay 和 route 测试
5. 接模型配置和 alias
6. 接前端 Settings 配置入口
7. 接 Workers 主页面创建、继续、历史恢复、删除、进程管理
8. 跑 L1-L4 验证矩阵
9. 再决定是否支持 skill / agent linkage
10. 最后更新安装、升级、排障文档

## 七、当前结论

Gemini Worker 的接入方式是可行的，但它也说明当前平台“新增 Worker”还不是一个低成本插件化动作。短期内可以依赖本指南降低遗漏；中期应优先把前端 backend registry、CLI Worker Base Runtime、Java provider route 测试模板抽出来；长期再把 Worker endpoint 配置从 `ClaudeWorker` 概念下拆出来。

