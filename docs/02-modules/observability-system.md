# 可观察性系统

> 当前实现与后续治理边界明确区分的可观察性说明

## 1. 文档定位

Foggy Navigator 当前没有自研的完整 Observability Platform。1.4.2 已在 dev 阶段移除旧 `monitoring-module`、`tools/foggy-monitor`、PC Monitoring 页面/API 及其配置残留；本文只描述仍然存在的运行诊断能力。

## 2. 当前可确认的能力

### 2.1 应用日志与上下文

后端使用结构化日志和滚动文件，并在相关链路携带 `traceId`、`sessionId`、`agentId` 等上下文。这是排障基础，不等同于集中式日志平台或完整 tracing。

### 2.2 健康检查与有限指标

Launcher 保留 Spring Boot 健康检查；部分组件通过 Micrometer 暴露有限指标，例如 `UnifiedSseEmitter` 的连接指标。当前没有承诺统一 Prometheus、告警中心或容量看板。

### 2.3 SSE 实时链路

平台采用每用户统一 SSE 连接模型，承载会话消息、任务状态、助手通知和 heartbeat。该实现当前是单 JVM 内存态，多实例事件总线已明确延后。

### 2.4 治理审计

BusinessTask、BusinessFunction、ClientApp、task-scoped token、审批、恢复、取消、拒绝和失败等审计按 1.4.2 计划分级治理。规划目标或待补能力不能写成已经完成，实际状态以版本 Progress 和证据为准。

### 2.5 结构化错误诊断

1.4.2 新增 Provider 无关的结构化错误摘要和独立诊断快照。首批由 Codex SDK Worker 与 Codex App Server Worker 提供稳定错误码、类别、运行阶段和经版本化规则脱敏的安全诊断文本；Task 查询、SSE 和聊天错误卡片继续兼容旧 `error/errorMessage` 字段。

诊断快照按 Task、Session、用户和租户隔离，默认保留 90 天。登录用户可按 `diagnosticRef` 查看允许字段；快照不保存 Prompt、工具输入输出、完整路径、凭据或未脱敏堆栈。该能力是面向单次失败的排障辅助，不是通用日志检索、Tracing 或 Monitoring 平台。

临时匿名分享由登录用户按需签发，默认 7 天、最大 30 天并受快照有效期限制，可单独撤销。服务端只保存 token hash，匿名页面只展示单个脱敏快照并使用 `no-store`、`no-referrer`、`noindex` 和 CSP。`navigator.error-diagnostics.public-sharing-enabled` 默认 `false`，内部部署必须显式开启；关闭、过期和撤销均 fail closed。

## 3. 已移除边界

以下能力不再属于当前产品或默认部署：

- `/api/v1/monitoring/events`、`/api/v1/monitoring/stats`
- Monitoring 前端页面和 API client
- RabbitMQ 日志事件 publisher/consumer
- `monitoring_events` 的应用侧持久化与旧告警规则引擎
- `scripts/start-all.sh` 中的 `foggy-monitor` 安装步骤

本轮没有直接操作数据库或 RabbitMQ 外部资源。dev 数据可按 Owner 授权丢弃；任何共享/生产资源必须另行确认，Git revert 也不会自动恢复外部资源或数据。

## 4. 推荐理解方式

当前运行可见性分为五层：

1. 应用日志：用于排障与问题回溯。
2. 健康检查和有限指标：用于判断实例基本状态。
3. SSE 运行信号：用于用户侧消息、任务和通知实时反馈。
4. 治理审计：用于追溯身份、任务、审批、恢复、拒绝和失败。
5. 结构化错误诊断：用于向有权用户提供单次任务失败的安全摘要、快照和可控临时分享。

## 5. 后续可选方向

以下内容只能作为后续独立需求，不能描述为当前已实现：

- 集中日志与检索
- Prometheus 指标体系和告警规则
- 完整链路追踪
- 多实例 SSE 事件总线
- 资源、容量和性能分析看板

新方案应优先接入受维护的通用观测组件，并与安全审计的权威事实源分开设计；不默认恢复已删除的自研 RabbitMQ Monitoring 切片。

## 6. 关联文档

- [通知、基础观测与开放集成](./observability-notification-integration.md)
- [Session Module](./session-module.md)
- [系统架构概览](../00-system-overview.md)
- [1.4.2 平台治理与历史能力收口](../version-tracker/1.4.2-SNAPSHOT/README.md)
