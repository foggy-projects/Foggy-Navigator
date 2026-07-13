---
type: cleanup
version: 1.4.2-SNAPSHOT
ticket: CLEAN-002
priority: medium
status: planned
source: REQ-001
owner: monitoring-and-deployment-owner
---

# Monitoring 完整功能切片退役

## 文档作用

- doc_type: workitem
- intended_for: project-root-session | monitoring-owner | deployment-owner | reviewer | signoff-owner
- purpose: 先完成 Monitoring 的运行态、数据和外部依赖审计，再以完整功能切片作保留、归档或退役决定。

## 关联文档

- [版本索引](../README.md)
- [REQ-001 平台治理与历史能力收口](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [实施计划](../implementation-plan.md)
- [ODR-142-006 Owner 决策评审稿](../owner-decision-review.md)
- [统一进度记录](../progress.md)

## 背景

Monitoring Java 模块、Python publisher、前端页面和残余配置当前处于“不完整接入”状态：默认 launcher 不装配 Java 模块，前端页面也没有静态导航入口，但启动脚本和安全放行仍保留。静态不可达不等于没有独立部署、消息队列、数据库、外部 dashboard 或手工消费者，因此本事项禁止仅删除某个目录，必须按完整功能切片治理。

规划审计本身不改变生产路由。只有运行态证据、Owner 决策、数据保留和回滚演练均满足后，实际退役才允许进入独立执行提交，并更新版本状态。

## 证据分类

### 已确认事实

1. Monitoring 不是当前产品主线，不能作为 Navigator 启动前置依赖。
2. 任何退役必须同时覆盖后端、publisher、UI/API、SecurityConfig、脚本、消息队列、数据库、部署和文档。
3. 本轮没有查询生产日志、RabbitMQ、数据库、部署平台或第三方 dashboard；Testing 和 Experience 均为 `not-run`。

### 静态搜索结论

1. `monitoring-module/` 存在，但不在根 `pom.xml` reactor 和 `launcher/pom.xml`。
2. `tools/foggy-monitor/` 存在；主线 Python Worker 未发现 `foggy_monitor` import。
3. `scripts/start-all.sh` 仍执行 `tools/foggy-monitor` 的 pip install。
4. `packages/navigator-frontend/src/views/MonitoringView.vue` 只静态引用 `packages/navigator-frontend/src/api/monitoring.ts`；未发现当前 router/menu 引用。
5. `user-auth-module/src/main/java/com/foggy/navigator/auth/config/SecurityConfig.java` 仍放行 `/api/v1/monitoring/**`。
6. Java API 为 `GET /api/v1/monitoring/events` 与 `GET /api/v1/monitoring/stats`。
7. JPA 表为 `monitoring_events`。
8. RabbitMQ exchange/queue 为 `foggy.events`、`foggy.monitor.logs`、`foggy.monitor.heartbeats`；Python routing key 使用 `monitor.log.<service>.<level>`。
9. `docs/00-system-overview.md`、`CLAUDE.md` 和 observability 文档多处写明能力暂停，但 `scripts/start-all.sh` 与该叙述不一致。

### 需要运行态确认

1. 所有环境的 ingress/access/application log 中是否仍有 `/api/v1/monitoring/**` 请求。
2. RabbitMQ 中 exchange、queue、binding、consumer、publisher、积压和最后消息时间。
3. `monitoring_events` 的行数、最后写入、数据量、数据 owner、合规/审计保留期和备份要求。
4. Python 虚拟环境、镜像、Worker archive 或主机上是否仍安装 `foggy-monitor`。
5. 是否存在默认 launcher 之外的自定义 jar、Maven profile、独立部署或手工 classpath 装配 `monitoring-module`。
6. dashboard、告警、运维脚本、定时任务和第三方消费者是否依赖 Monitoring API、表或 RabbitMQ。
7. 删除 SecurityConfig 放行项后，是否存在仍需保留但应鉴权的替代观测端点。

### 决策项

| 决策 | Owner | 允许值 | 未决处理 |
|---|---|---|---|
| 能力去留 | platform + operations | ODR-142-006-MON 建议目标 `retire`；当前 pending-decision 且无生产退役授权 | 不删除 |
| 流量审计窗口 | operations | 建议不少于 30 天或最长已知调度周期两倍，取更长者；覆盖全部生产/准生产环境 | 不宣布静默 |
| 数据保留 | data/operations | 保留期、导出、删除审批 | 不删表 |
| RabbitMQ 资源处理 | messaging owner | 保留、解绑、延迟删除 | 不删 exchange/queue |
| 替代观测方案 | operations | 应用日志、Actuator/公司观测设施，或无替代但取得明确批准 | 不移除 UI/API 说明 |
| 外部契约窗口 | release owner | 弃用通知、截止时间 | 不返回永久 404 |

## 完整功能切片

| 层 | 精确路径/资源 | 退役时要求 |
|---|---|---|
| Java module | `monitoring-module/pom.xml`、`monitoring-module/src/**` | 成组移除源码、auto-configuration、controller、consumer、entity/repository/service |
| Python publisher | `tools/foggy-monitor/pyproject.toml`、`tools/foggy-monitor/src/foggy_monitor/**` | 审计所有环境安装与 import，先停 publisher 后删库 |
| PC UI | `packages/navigator-frontend/src/views/MonitoringView.vue` | 确认无路由/深链，移除页面和可能残留的菜单/测试 |
| PC API | `packages/navigator-frontend/src/api/monitoring.ts` | 与页面同批，不留下不可达 API wrapper |
| Auth config | `user-auth-module/src/main/java/com/foggy/navigator/auth/config/SecurityConfig.java` | 移除过期放行项；若端点保留则按新边界治理 |
| Startup | `scripts/start-all.sh` | 移除 pip install、状态计数和输出 |
| Messaging | `foggy.events`、`foggy.monitor.logs`、`foggy.monitor.heartbeats` 及 bindings | 先停 publisher/consumer、观察静默，再解绑；延迟删除 |
| Database | `monitoring_events` | 先统计、导出/备份、冻结写入；表删除单独迁移和审批 |
| Deployment | Docker、主机 venv、镜像、配置中心的 `FOGGY_MONITOR_*` / `foggy.monitoring.*` | 全环境扫描，清理 secret/config，不在代码提交中假定完成 |
| Docs | `docs/02-modules/observability-system.md`、`observability-notification-integration.md`、`functional-architecture.md`、`docs/00-system-overview.md`、`CLAUDE.md`、`docs/ceo-architecture-and-installation.md` | 按最终决策对齐当前状态、替代方案和历史说明 |

任何一层未完成，不得把 workitem 标记为 retired。

## 运行态审计命令模板

以下只是执行模板，环境、凭据和实际命令由对应 Owner 提供；当前没有执行结果：

~~~bash
rg -n --hidden 'monitoring-module|foggy-monitor|/api/v1/monitoring|FOGGY_MONITOR_|foggy[.]monitoring' \
  . -g '!**/.git/**' -g '!**/target/**' -g '!**/node_modules/**' -g '!**/dist/**'

rg -n 'foggy_monitor|setup_monitoring' tools \
  -g '*.py' -g '!tools/foggy-monitor/**' -g '!**/.venv/**'

rg -n 'MonitoringView|api/monitoring|/monitoring' packages/navigator-frontend/src \
  -g '*.ts' -g '*.vue'
~~~

运行环境还必须产出以下结构化证据，而不是只贴口头结论：

- access log：环境、时间窗、请求数、调用主体/客户端、最后请求时间；
- RabbitMQ：vhost、exchange、queue、binding、consumer count、message count、最后 publish/consume；
- database：表、行数、最早/最后时间、容量、备份位置、保留决定；
- deployment：实例、镜像/JAR/venv、配置键、Owner；
- external consumer：dashboard/告警/脚本名称、Owner、迁移状态。

敏感 token、RabbitMQ 密码和日志正文不得写入版本文档。

## 实施阶段

### M0：冻结现状与 Owner

1. 记录源码 commit、部署版本和各环境 owner。
2. 对完整切片建立 inventory，确认默认 launcher 未装配不等于所有环境未装配。
3. 冻结审计窗口、数据保留和证据落点。

完成门：所有环境有 Owner；无法确认的环境明确标记 blocked，不得推断无使用。

### M1：静态与运行态消费者审计

1. 执行代码、文档、CI、脚本、发布 archive 和配置扫描。
2. 查询 API access log、RabbitMQ、数据库和部署实例。
3. 访谈 dashboard/告警/运维 Owner，形成 consumer matrix。
4. 区分“当前无默认路由”“实际流量为零”“能力已退役”三种状态。

完成门：每个消费者有 owner、用途、最后使用、迁移/保留决定。

### M2：形成去留决策

可能结论：

- `retain`：补明确模块装配、认证、测试和文档，不以半残留状态继续；
- `archive`：源码移出主线交付但保留可恢复归档、数据和 runbook；
- `retire`：进入 M3-M5；
- `defer`：缺运行证据时保持现状并记录解除条件。

决策必须含日期、Owner、证据窗口和外部契约影响。审计“看起来没人用”不是 retire 批准。

### M3：退役预演与静默窗口

仅在 `retire` 获批后：

1. 停止 publisher/consumer 或通过配置禁用，保留代码和资源以便快速恢复。
2. 对 API 先告警/弃用；如确有外部调用，迁移到替代观测方案。
3. 观察完整静默窗口，确认 API、queue 和表均无新增。
4. 导出数据库，快照 RabbitMQ topology 和部署配置。
5. 演练重新启用 publisher、consumer 和 API 的恢复步骤。

完成门：静默、备份和恢复演练均有可定位证据。

### M4：完整切片移除

1. 同一阶段移除 Java module、Python library、PC View/API、SecurityConfig 放行和 start script。
2. 清理部署配置、镜像/venv 安装和文档。
3. RabbitMQ 先解绑并观察；删除 queue/exchange 是独立运维动作。
4. `monitoring_events` 不随代码提交直接删除；表退役使用独立、可审核迁移。
5. 每层独立提交或可逆运维变更，记录顺序。

非目标：

- 不顺手建立新的全平台 observability；
- 不因删除 Monitoring 重构 Spring Security；
- 不删除 task assistant 的普通 `monitoring` tag 或 Worker 内部 stop monitoring 等同名、不同语义代码。

### M5：验证、质量门与签收

1. Java clean build，确认 launcher 无残留 classpath/config。
2. PC type/test/build，确认导航和设置无死链。
3. `bash -n scripts/start-all.sh` 及启动 smoke，确认服务计数和输出正常。
4. 在获批环境确认旧 API 处于预期状态，替代观测可用。
5. 执行 implementation self-check、正式质量检查、覆盖审计和签收。

## 自动化测试计划

当前均 `not-run`：

~~~bash
mvn -B -pl launcher -am clean test
mvn -B clean verify
corepack pnpm --filter @foggy/navigator-frontend type-check
corepack pnpm --filter @foggy/navigator-frontend test
corepack pnpm --filter @foggy/navigator-frontend build
bash -n scripts/start-all.sh
git diff --check
~~~

如选择 `retain`，必须为 Monitoring module 补独立测试和受控装配 smoke；如选择 `retire`，必须增加 launcher context 不加载 Monitoring bean、前端无死链和旧 API 契约状态测试。

## 手工验证

1. 检查主导航、深链和设置页，不出现 Monitoring 空页或菜单死链。
2. 执行 `scripts/start-all.sh`，确认不再安装/报告已退役 library，其他服务正常。
3. 运维人员在替代观测平台验证至少一个服务的日志/错误诊断路径。
4. 在隔离环境按 runbook 执行一次 rollback，确认 API/consumer/publisher 可恢复。
5. 生产删除 queue/table 前由 Owner 复核备份和保留审批。

## 风险与回滚

| 风险 | 控制 | 回滚 |
|---|---|---|
| 独立部署未被根 POM 扫描发现 | 部署/JAR/镜像/主机清单审计 | 恢复对应部署制品，不继续删数据 |
| 外部 dashboard 依赖旧 API/表 | access log + consumer owner + 迁移窗口 | 恢复 API/module，保留兼容读路径 |
| RabbitMQ 资源过早删除 | 先停发/停收、静默、快照、延迟删除 | 按 topology 快照重建 binding/queue |
| 表数据具备审计价值 | 明确保留期、只读导出和审批 | 从备份恢复；代码回滚不自动恢复数据 |
| 删除 SecurityConfig 后仍有残余端点 | endpoint inventory 和 context test | revert auth/config 提交 |
| 启动脚本计数/流程被破坏 | shell syntax + startup smoke | revert script 提交 |

回滚顺序：先恢复配置和消息资源，再恢复 Python publisher/Java consumer/API，最后恢复 UI；数据库与 RabbitMQ 数据不得依赖 Git revert 恢复。

## 完成判据

- [ ] 完整功能切片 inventory 已覆盖代码、UI、API、auth、脚本、部署、RabbitMQ、数据库和文档。
- [ ] 所有环境和外部消费者均有 Owner，运行态审计窗口和结果可定位。
- [ ] 数据保留、备份、RabbitMQ topology 和替代观测方案已获批准。
- [ ] 去留决策明确为 retain/archive/retire/defer，并有日期与签字。
- [ ] 若 retire，静默窗口和恢复演练已通过。
- [ ] 若 retire，所有切片均成组移除，无残余菜单、放行项、安装步骤或失效文档。
- [ ] Java、PC、脚本与运行态验证有真实结果，未运行项明确。
- [ ] changed paths、运维变更和证据已回写 [Progress](../progress.md)。
- [ ] 正式质量检查、覆盖审计和签收完成。
- [ ] 隔离退役验证没有被解释为生产数据/消息资源删除批准。

## 生产路由与外部契约状态

- audit_production_routing_changed: no
- audit_external_contract_changed: no
- retirement_production_routing_changed: possible
- retirement_external_contract_changed: possible
- production_approval_required_for_retirement: yes

实际移除 `/api/v1/monitoring/**`、独立 deployment、RabbitMQ 或数据库资源时，必须把版本和 workitem 状态更新为真实影响；当前 `no` 仅表示本规划/审计不改生产。
