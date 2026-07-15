# 通知、基础观测与开放集成

## 1. 功能定位

该功能域负责任务实时通知、最低限度的运行诊断以及受治理的上游集成。旧自研 RabbitMQ Monitoring 切片已经在 1.4.2 dev 阶段物理移除，不再提供 Monitoring 页面和 `/api/v1/monitoring/**` 接口。

当前相关模块包括：

- `session-module`
- `addons/task-assistant`
- `addons/claude-worker-agent`
- `business-agent-module`
- `navigator-open-sdk`
- `tools/navigator-chat-observer-bff`

## 2. 当前功能范围

### 2.1 SSE 与任务通知

- 统一消息流与任务状态更新
- 用户通知和任务助手通知
- 订阅管理与连接保活

`UnifiedSseEmitter` 当前是单 JVM 内存态实现；多实例事件总线不属于 1.4.2 范围。

### 2.2 基础运行观测

当前保留的最低观测能力是：

- 应用结构化日志与滚动文件
- Spring Boot 健康检查
- 有限的 Micrometer 指标
- 任务、调用、审批、恢复、拒绝等治理审计（按 1.4.2 计划渐进补齐）

退役旧 Monitoring 不等于取消日志、健康检查、指标或安全审计。若未来需要集中日志、指标和告警，应另立需求选择受维护的观测方案，不原样恢复旧 RabbitMQ 日志采集切片。

### 2.3 Open API、SDK 与上游接入

平台已有 Worker、目录、Agent、BusinessFunction/BusinessTask 等集成能力，以及 Java SDK、上游 CLI 和嵌入式聊天交付物。

当前产品阶段仍以 dev/internal 使用为主。外部运行模式必须显式开启，默认关闭；在调用方身份、tenant/ClientApp/upstream user 映射、task-scoped token、Worker 能力边界、readiness 和审计门禁完成前，不得把内部开发配置解释为外部启用批准。

## 3. 已移除的旧 Monitoring 切片

1. `monitoring-module` Java API、RabbitMQ consumer、事件持久化和告警规则。
2. `tools/foggy-monitor` Python 日志 publisher。
3. PC `MonitoringView.vue` 和 `api/monitoring.ts`。
4. SecurityConfig 中旧 Monitoring 放行项。
5. `scripts/start-all.sh` 中的安装、计数和状态提示。

本次代码变更没有操作数据库表、RabbitMQ queue/exchange、GitLab 或任何独立部署资源。Owner 已允许 dev 数据丢弃；若以后发现共享或生产资源，必须停止自动清理并单独确认目标和回滚方式。

## 4. 典型使用场景

### 场景 1：排查内部环境异常

开发或运维人员先查看应用日志、健康状态和相关任务审计，再按 Worker/Session/Task 标识定位链路。

### 场景 2：接收任务通知

用户通过统一 SSE 获知任务开始、进展、完成、失败或助手建议。

### 场景 3：受控的上游接入

本机孵化的上游通过 ClientApp 和现有 mapping/grant 联调；未来对外开放前必须显式启用 external 模式并满足 1.4.2 信任边界门禁。

## 5. 关联文档

- [系统架构概览](../00-system-overview.md)
- [可观察性系统](./observability-system.md)
- [Session Module](./session-module.md)
- [1.4.2 平台治理与历史能力收口](../version-tracker/1.4.2-SNAPSHOT/README.md)
