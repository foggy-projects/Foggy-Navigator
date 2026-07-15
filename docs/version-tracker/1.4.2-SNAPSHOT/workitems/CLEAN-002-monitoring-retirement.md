---
type: cleanup
version: 1.4.2-SNAPSHOT
ticket: CLEAN-002
priority: medium
status: in-progress
source: REQ-001
owner: monitoring-and-deployment-owner
decision: ODR-142-006-MON
implementation_started: yes
testing_status: partial-pass
experience_status: not-run
production_routing_changed: no
production_enablement: not-applicable
---

# Monitoring dev 切片物理移除

## 文档作用

- doc_type: workitem
- intended_for: project-root-session | monitoring-owner | deployment-owner | reviewer | signoff-owner
- purpose: 记录 Owner 已批准的 Monitoring dev-only 完整切片物理移除、已获得的静态与构建证据，以及仍待闭合的文档和体验验证。

## 关联文档

- [版本索引](../README.md)
- [REQ-001 平台治理与历史能力收口](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [实施计划](../implementation-plan.md)
- [ODR-142-006 Owner 决策记录](../owner-decision-review.md)
- [统一进度记录](../progress.md)

## Owner 决策与执行边界

Project Owner 于 `2026-07-14` 确认当前项目仍处于 dev 阶段、尚未生产启用，允许在仓内引用扫描和构建验证通过的前提下直接物理移除 Monitoring；旧 dev 数据允许丢弃，不再把生产流量静默期、旧数据备份、外部客户兼容窗口或生产退役签字作为本次仓内删除的硬门禁。

本次授权不扩大到未知的共享或生产资源。若后续发现 RabbitMQ、数据库、独立部署或仓外消费者实际属于共享/生产环境，必须停止相应外部资源操作并重新请示 Owner。本轮没有操作 RabbitMQ exchange/queue、`monitoring_events` 表、主机虚拟环境、镜像、部署平台或第三方 dashboard。

删除自研 Monitoring 切片不等于取消平台可观测性：应用日志、health/readiness、错误诊断和安全审计仍是平台治理要求，不得因为本事项被删除或降级。

## 证据分类

### 已确认事实

1. 删除前，`monitoring-module` 已不在根 `pom.xml` reactor 中，`launcher/pom.xml` 也未装配该模块；本次没有修改这两个 POM 来“隐藏”依赖。
2. Owner 已批准按 dev-only 假设物理移除该切片，并接受旧开发数据丢弃。
3. 已删除 `monitoring-module` 的 10 个 tracked files、`tools/foggy-monitor` 的 5 个 tracked files、PC `MonitoringView.vue` 和 `api/monitoring.ts`。
4. 已移除 `SecurityConfig` 中 `/api/v1/monitoring/**` 的匿名放行项，以及 `scripts/start-all.sh` 中 `foggy-monitor` 的安装、服务计数、步骤编号和汇总提示。
5. 本轮没有删除 RabbitMQ、数据库、部署或第三方资源，也没有声称这些资源已经不存在。
6. 最终检查发现并删除了仓库目录内被 Git 忽略的旧 `monitoring-module/target`、`tools/foggy-monitor/.venv` 和 `.pytest_cache`；这是 Owner 已授权丢弃的 repo-local dev 构建/环境残留，不是外部主机、部署平台或共享资源操作。

### 静态搜索结论

1. 删除前，PC Monitoring 页面与 API wrapper 之间存在内部引用，但未发现当前 router/menu 对 `MonitoringView` 的引用。
2. 删除后，对当前源码、根构建、launcher、启动脚本和前端入口的扫描未发现 Monitoring 运行时装配或当前源码消费者。
3. 当前权威文档中仍有 Monitoring 历史状态或能力说明，正在由 [DOC-001](./DOC-001-documentation-alignment.md) 同步；在同步完成前不能把本事项标记为完成。
4. 普通业务代码中与 task status、Worker lifecycle 或通用日志相关的 `monitoring` 字样不属于本切片，不得按名称误删。

### 已执行验证

| 检查 | 状态 | 结果边界 |
|---|---|---|
| Monitoring 当前源码与装配引用扫描 | passed-static | 未发现当前源码运行时引用；不等价于查询了仓外运行流量 |
| `bash -n scripts/start-all.sh` | passed | shell 语法通过；未实际启动整套服务 |
| `mvn -B -pl launcher -am clean test` | passed | launcher 依赖链从 clean 状态编译和测试通过 |
| 根前端 full matrix | passed | 纳入范围的类型检查、测试和构建通过，覆盖 PC 删除切片后的编译链 |
| Repository CI run `29324741945` | passed-hosted | 截至正式闸门的最新已验证实现 head `9d03bee9` 的 Java、前端和五类 Worker共 7 jobs 全 success；不替代 Monitoring 专属启动/体验验证 |

### 尚未执行或需要运行态确认

1. 未查询 RabbitMQ 的 exchange、queue、binding、consumer、积压或最后消息时间。
2. 未查询 `monitoring_events` 的表结构、数据量、最后写入或实例归属，也未执行 drop、truncate、导出或备份。
3. 未检查主机 venv、独立镜像、自定义 JAR/classpath、部署配置、dashboard、告警或第三方脚本。
4. 未执行真实 `scripts/start-all.sh` 启动 smoke、浏览器导航/深链体验验证或替代观测路径检查。
5. GitHub Actions Repository CI 7-job 矩阵已在截至正式闸门的最新已验证实现 head 对应 hosted runner 实际通过；branch protection、修复后 nightly 与 Monitoring 专属启动/体验仍未执行。

## 已移除切片

### Java module：10 个 tracked files

- `monitoring-module/pom.xml`
- `monitoring-module/src/main/java/com/foggy/navigator/monitoring/config/MonitoringAutoConfiguration.java`
- `monitoring-module/src/main/java/com/foggy/navigator/monitoring/consumer/LogEventConsumer.java`
- `monitoring-module/src/main/java/com/foggy/navigator/monitoring/controller/MonitoringController.java`
- `monitoring-module/src/main/java/com/foggy/navigator/monitoring/model/dto/MonitorEventDTO.java`
- `monitoring-module/src/main/java/com/foggy/navigator/monitoring/model/entity/MonitorEventEntity.java`
- `monitoring-module/src/main/java/com/foggy/navigator/monitoring/repository/MonitorEventRepository.java`
- `monitoring-module/src/main/java/com/foggy/navigator/monitoring/service/AlertRuleEngine.java`
- `monitoring-module/src/main/java/com/foggy/navigator/monitoring/service/MonitorEventService.java`
- `monitoring-module/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

### Python publisher：5 个 tracked files

- `tools/foggy-monitor/pyproject.toml`
- `tools/foggy-monitor/src/foggy_monitor/__init__.py`
- `tools/foggy-monitor/src/foggy_monitor/config.py`
- `tools/foggy-monitor/src/foggy_monitor/log_handler.py`
- `tools/foggy-monitor/src/foggy_monitor/publisher.py`

### PC、认证和启动脚本

| 层 | 路径 | 已执行变更 | 当前结果 |
|---|---|---|---|
| PC View | `packages/navigator-frontend/src/views/MonitoringView.vue` | 物理删除 | 前端 full matrix passed；浏览器体验 not-run |
| PC API | `packages/navigator-frontend/src/api/monitoring.ts` | 与 View 同批物理删除 | 当前源码扫描无消费者 |
| Auth config | `user-auth-module/src/main/java/com/foggy/navigator/auth/config/SecurityConfig.java` | 删除 `/api/v1/monitoring/`、`/api/v1/monitoring/**` 放行规则 | Java clean reactor passed |
| Startup | `scripts/start-all.sh` | 删除安装步骤、状态项、计数和提示，后续步骤重新编号 | `bash -n` passed；启动 smoke not-run |
| Root/launcher assembly | `pom.xml`、`launcher/pom.xml` | 无变更；删除前即未装配 Monitoring | 静态确认，不推断独立部署状态 |

## 未操作的外部资源

以下资源只作为历史实现线索保留在本 workitem 中，不表示它们仍存在，也不表示已完成清理：

- RabbitMQ：`foggy.events`、`foggy.monitor.logs`、`foggy.monitor.heartbeats` 及相关 bindings；
- database：`monitoring_events`；
- deployment：可能存在的主机 venv、独立镜像/JAR、配置中心 `FOGGY_MONITOR_*` / `foggy.monitoring.*`；
- integration：可能存在的 dashboard、告警、运维脚本或第三方消费者。

当前 dev 假设下，本版本不主动为这些未知资源建立生产静默期或备份流程，也不执行破坏性外部操作。如发现共享/生产资源，则该资源进入独立决策和重建/迁移流程，不能用本 workitem 的代码删除授权直接处理。

## 实施与收口步骤

### D0：范围和删除授权冻结 — completed

- 输入：Owner 对 dev 阶段、无生产上游和旧 dev 数据可丢弃的明确确认。
- 实施：冻结 Monitoring 完整代码切片、保护非本切片的日志、health 和安全审计能力。
- 完成判据：ODR-142-006-MON 已记录；根 reactor/launcher 原装配状态已确认。
- 生产路由或外部契约：不改变生产路由；允许移除未生产的 dev 源码契约。

### D1：仓内物理移除 — completed

- 输入：删除前引用扫描与精确路径清单。
- 涉及模块：Monitoring Java module、Python publisher、PC、auth config、startup script。
- 实施：按上述完整切片成组删除，未操作 RabbitMQ、数据库和部署资源。
- 非目标：不新建通用观测平台，不重构 Spring Security，不删除日志/health/安全审计。
- 回滚：按独立提交使用 `git revert` 恢复源码、放行项和启动脚本；外部资源不由 Git 回滚。
- 完成判据：删除路径无当前源码消费者，脚本语法通过。
- 生产路由或外部契约：生产路由 `no`；dev 源码中的旧 Monitoring API 契约已移除。

### D2：构建与静态回归 — completed

- 自动化测试：Java clean reactor 和根前端 full matrix passed，`bash -n` passed。
- 手工验证：not-run。
- 风险：本地与 hosted CI 通过仍不等于真实启动、部署或替代观测体验通过。
- 回滚：任一后续构建回归可单独 revert Monitoring 切片，不能以恢复匿名放行作为长期修复。
- 完成判据：当前本地自动化基线已通过。
- 生产路由或外部契约：`no`。

### D3：文档与体验闭合 — in-progress

- 输入：本 workitem 和当前权威文档中的 Monitoring 引用清单。
- 实施：同步当前架构/安装/可观测性文档；执行浏览器导航检查、真实启动 smoke 和替代诊断路径检查。
- 非目标：不重写历史版本证据；历史文档可保留当时事实并明确历史语境。
- 自动化测试：Markdown 相对链接、`git diff --check`、最终引用扫描。
- 手工验证：PC 无 Monitoring 导航/深链死链；`start-all.sh` 的四项服务计数和提示正确；至少验证日志或 health 的基本诊断路径。
- 风险：文档仍宣称已删除能力可用，或 UI 存在未扫描的动态入口。
- 回滚：修正文档/入口；若发现真实仓内消费者，停止签收并评估是否 revert D1。
- 完成判据：当前权威文档、静态扫描和体验证据全部闭合。
- 生产路由或外部契约：`no`。

## 风险与回滚

| 风险 | 控制 | 回滚/恢复 |
|---|---|---|
| 静态扫描漏掉反射、动态路由或仓外消费者 | 最终引用扫描 + 浏览器/启动 smoke；发现真实消费者即停止签收 | `git revert` 恢复仓内切片并补消费者清单 |
| 发现共享或生产 RabbitMQ/DB/部署资源 | 本轮不操作外部资源；发现后转独立 Owner 决策 | 按独立资源 runbook 重建，不能依赖 Git revert |
| 旧 dev 数据不可恢复 | Owner 已接受 dev 数据丢弃；本轮未实际删表或消息资源 | 若以后需要该能力，按当前契约重新建模和重建资源，不承诺恢复旧数据 |
| 删除 Monitoring 被误解为取消观测和审计 | 文档明确保留日志、health/readiness、错误诊断和安全审计要求 | 恢复/补齐缺失的基础观测能力，但不恢复已退役自研切片作为默认方案 |
| 启动脚本计数或流程错误 | `bash -n` + D3 真实启动 smoke | revert `scripts/start-all.sh` 变更或修正编号，不恢复无用安装步骤 |
| 当前文档仍引用已删除能力 | DOC-001 当前文档扫描与相对链接检查 | 修正文档；历史证据只加历史标识，不改写结论 |

## 完成判据

- [x] Owner 已批准 dev-only 物理移除，删除边界和停止条件已记录。
- [x] `monitoring-module` 10 个 tracked files 与 `tools/foggy-monitor` 5 个 tracked files 已物理删除。
- [x] PC View/API、SecurityConfig 放行项和 `scripts/start-all.sh` 安装/提示已成组移除。
- [x] 根 POM/launcher 删除前未装配该模块，删除后当前源码静态扫描无运行时引用。
- [x] Java clean reactor、前端 full matrix 和 shell 语法检查通过。
- [x] RabbitMQ、数据库、部署和第三方资源没有被本轮擅自操作。
- [ ] 当前权威文档已完成同步，历史文档与当前状态不混淆。
- [ ] 浏览器体验、真实启动 smoke 和替代日志/health 诊断路径已验证。
- [x] GitHub Actions Repository CI 7-job 矩阵已在截至正式闸门的最新已验证实现 head `9d03bee9` 的远端 runner 实际通过（run `29324741945`，7 jobs success）。
- [ ] 最终 `git diff --check`、Markdown 相对链接和全局引用扫描通过并回写 [Progress](../progress.md)。
- [x] 版本正式质量检查、覆盖审计和签收已执行，结论为 `ready-with-risks / needs-more-tests / rejected`；本工作项仍未单独签收。

## 当前状态

- development_status: `in-progress`（代码切片已移除，当前文档同步中）
- testing_status: `partial-pass-local-and-hosted`（本地 clean Java、前端矩阵和 shell 语法已通过；截至正式闸门的最新已验证实现 head hosted CI 7 jobs success；专属启动/体验未运行）
- experience_status: `not-run`
- external_resource_cleanup: `not-run`
- production_routing_changed: `no`
- dev_source_contract_changed: `yes`（旧 `/api/v1/monitoring/**` 源码已移除）
- production_enablement: `not-applicable`
- acceptance_status: `rejected`
- acceptance_record: [Version Signoff](../acceptance/version-signoff.md)

本 workitem 保持 `in-progress`，直至当前文档和体验证据闭合；本地自动化通过不等同于生产批准，也不证明未知外部资源已经退役。
