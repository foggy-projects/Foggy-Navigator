# OpenAPI Root Agent 默认 BizWorker 路由修复

## 文档作用

- doc_type: bug | workitem | progress
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 记录 #125 后续 ask smoke 失败的根因、MVP 修复范围、测试证据与验收状态。

## 基本信息

- version: 1.1.5-SNAPSHOT
- priority: P0
- status: coding-complete
- source: GitHub issue #125 comment, TMS OpenAPI ask smoke
- scope: `business-agent-module`, `addons/langgraph-biz-worker`

## 背景

TMS 已按新的 OpenAPI 契约改造：`preflight` 不再要求 `context.skillId` 和 `requiredUpstreamRefs`，ask 入口改为 `/api/v1/open/agents/{rootAgentId}/ask`。Navigator 侧 rootAgentId 路由和 preflight 主链路已经可用，但 ask smoke 返回 HTTP 500。

失败日志显示：

```text
DataIntegrityViolationException: not-null property references a null or transient value:
com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity.workerId
```

## 根因

OpenAPI ask 通过 A2A 直接进入 `LanggraphWorkerInnerA2aAgent`，创建 `CreateLanggraphTaskForm` 时使用 root agent 上的 `CodingAgentEntity.workerId`。

但 upstream tenant ensure 在 MVP 契约下允许不传 `workerPoolId`，旧实现会把 `form.workerPoolId` 直接写入 root agent `workerId`，并在未传时留下 null。后续创建 `LanggraphTaskEntity` 时 `workerId` 是 not-null 字段，因此触发数据库约束异常。

## MVP 决策

当前阶段维持“一个 Navigator 实例映射一个 BizWorker”的约束，不引入完整 `workerPoolId -> workerId` 调度模型。

本次修复只做默认路由兜底：

1. root agent 已配置真实 workerId 时优先使用该 worker。
2. root agent 未配置 workerId，或存量数据里 workerId 指向旧 pool id 时，回退到当前 Navi 的默认 BizWorker。
3. 默认 BizWorker 解析优先级：配置项 `navigator.langgraph.worker.default-worker-id` > 唯一已注册 worker > 多 worker 时唯一 ONLINE worker。
4. 无 worker 或多 worker 无法唯一判定时，返回明确业务错误，不再落到数据库 not-null 异常。
5. upstream ensure 不再把缺省 `workerPoolId` 当作 blocker，也不会在未传 `workerPoolId` 时清空已有 root agent workerId。

## 代码变更

- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerService.java`
  - 新增默认 BizWorker 解析。
  - 支持旧 workerId/poolId 无法命中时回退到 MVP 默认 worker。
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerAgentProvider.java`
  - A2A agent 解析阶段计算有效 workerId。
  - preflight 的 `AGENT_REGISTERED` 可以同步暴露 worker 缺失问题。
- `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerInnerA2aAgent.java`
  - 创建 LangGraph task 时使用 provider 已解析的有效 workerId。
- `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/UpstreamTenantClientAppProvisioningService.java`
  - `workerPoolId` 缺省不再产生 blocker。
  - 未传 `workerPoolId` 时不覆盖已有 root agent workerId。

## 验收标准

- [x] TMS minimal ensure 可不传 `workerPoolId`，Navigator 不返回 worker 相关 blocker。
- [x] root agent 没有 workerId 时，OpenAPI ask 可使用当前 Navi 默认 BizWorker 创建 LangGraph task。
- [x] 存量 root agent workerId 指向旧 pool id 且当前只有一个 BizWorker 时，可自动回退。
- [x] 无 BizWorker 或多 BizWorker 无法唯一判定时，返回明确配置错误。
- [x] 不引入完整 worker pool 调度，不改变既有 BusinessAgentTaskService pool 路径。

## Progress Tracking

### Development Progress

- [x] 完成 LangGraph 默认 BizWorker 解析。
- [x] 完成 A2A root agent ask 路径有效 workerId 注入。
- [x] 完成 upstream ensure 缺省 `workerPoolId` 行为调整。
- [x] 补充单测覆盖缺省、旧值回退、多 worker 异常和 provisioning 不清空 worker 场景。

### Testing Progress

| Command | Status | Notes |
| --- | --- | --- |
| `mvn -pl addons/langgraph-biz-worker,business-agent-module -am "-Dtest=LanggraphWorkerServiceTest,LanggraphWorkerInnerA2aAgentTest,LanggraphWorkerAgentProviderTest,UpstreamTenantClientAppProvisioningServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | pass | 受影响 Java 单测通过 |
| `mvn -pl addons/claude-worker-agent,addons/langgraph-biz-worker,business-agent-module -am "-Dtest=OpenApiAgentReadinessServiceTest,OpenApiAgentRouteServiceTest,OpenApiControllerMessageMappingTest,LanggraphWorkerServiceTest,LanggraphWorkerInnerA2aAgentTest,LanggraphWorkerAgentProviderTest,UpstreamTenantClientAppProvisioningServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` | pass | 覆盖 OpenAPI route/readiness 与 LangGraph worker 路由链路 |

### Experience Progress

- N/A: 本次为后端 OpenAPI/A2A 路由修复，不涉及 UI 页面、交互或可视状态。

## Execution Check-in

- completed work summary: 已修复 rootAgentId ask smoke 因 workerId null 导致的 LangGraph task 持久化失败。
- touched code paths:
  - `business-agent-module/src/main/java/com/foggy/navigator/business/agent/service/UpstreamTenantClientAppProvisioningService.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/service/LanggraphWorkerService.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerAgentProvider.java`
  - `addons/langgraph-biz-worker/src/main/java/com/foggy/navigator/langgraph/worker/adapter/LanggraphWorkerInnerA2aAgent.java`
- self-check:
  - [x] bug scope implemented as intended.
  - [x] non-goal preserved: no full worker pool scheduler added.
  - [x] code paths updated are listed.
  - [x] targeted tests passed.
  - [x] progress writeback completed.
- self-check conclusion: self-check-only; no formal quality gate required for this narrow MVP bug fix.
- remaining risk: 如果同一 Navi 注册多个 BizWorker 且没有唯一 ONLINE worker，需要配置 `navigator.langgraph.worker.default-worker-id`。
