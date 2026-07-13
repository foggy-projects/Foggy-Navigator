---
type: cleanup
version: 1.4.2-SNAPSHOT
ticket: CLEAN-003
priority: high
status: planned
source: REQ-001
owner: metadata-query-and-launcher-owner
---

# metadata-query 运行依赖与退役审计

## 文档作用

- doc_type: workitem
- intended_for: project-root-session | metadata-owner | deployment-owner | reviewer | signoff-owner
- purpose: 审计旧语义层查询模块的真实流量、外部依赖和数据责任，满足门禁后才允许从 launcher 完整退役。

## 关联文档

- [版本索引](../README.md)
- [REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [实施计划](../implementation-plan.md)
- [ODR-142-006 Owner 决策评审稿](../owner-decision-review.md)
- [统一进度记录](../progress.md)

## 证据分类

### 已确认事实

- Navigator 当前不是语义层或数据分析平台。
- 规划阶段不删除 `metadata-query-module`，也不修改根 reactor 或 launcher。
- 静态无消费者不等于无外部调用；API、部署、配置、数据和第三方审计未完成前禁止退役。

### 静态搜索结论

1. `metadata-query-module` 仍在根 `pom.xml` 和 `launcher/pom.xml`。
2. API 基路径为 `/api/metadata/query`，包含 GET/POST 简单查询、`/execute`、`/available` 和参数定义接口。
3. 模块依赖 `foggy-dataset-model`、`foggy-fsscript`、`foggy-core`，版本来自 `8.1.10.beta`。
4. 服务调用 `foggy.api.base-url` 下的 `/jdbc-model/query-model/v2/*`，内置 datasource/semantic-layer TM/QM 模板。
5. 模块外未发现对 `com.foggy.navigator.metadata.query` 非 config 类型的 Java import，也未发现 PC、Mobile、SDK、CLI 对 `/api/metadata/query` 的静态调用。
6. 当前总览把它描述为“平台配置读接口”，与源码中的旧 Foggy Dataset/语义层查询职责不一致。
7. `metadata-config-module` 使用相邻包名 `com.foggy.navigator.metadata.query.config.*`，是当前平台配置能力，不能按包名前缀误删。

### 需要运行态确认

- 全环境 access/gateway/application log 的 `/api/metadata/query/**` 调用方、次数、最后调用和响应状态。
- 外部客户、脚本、定时任务、SDK fork、反向代理或第三方系统调用。
- `foggy.api.base-url` 在各环境的实际配置、目标服务、网络和凭据。
- datasource/semantic-layer 配置、TM/QM 资源、外部数据库或 Foggy 服务的数据 owner 与保留要求。
- 自定义 launcher、独立 jar、Maven profile、部署清单和依赖扫描。
- 移除模块后 Maven 依赖树、启动时间、自动配置和配置键的变化。

### 决策项

| 决策 | Owner | 未决处理 |
|---|---|---|
| retain / migrate / retire / defer | platform + metadata owner | ODR-142-006-MQ 建议 1.4.2 `defer` 物理删除、以 migrate/retire 为目标；当前不删除 |
| 流量审计窗口 | operations | 建议不少于 60 天或最长业务周期两倍，取更长者；未完成不声明静默 |
| 外部查询替代 API | consumer owners | 不下线旧 API |
| TM/QM 与配置保留 | data owner | 不删除资源/数据 |
| 弃用和截止日期 | release owner | 不返回永久 404 |

## 完整功能切片

| 层 | 精确路径/资源 |
|---|---|
| Reactor | `pom.xml` 中 `<module>metadata-query-module</module>` |
| Launcher | `launcher/pom.xml` 的 `metadata-query-module` dependency |
| Module | `metadata-query-module/pom.xml`、`src/main/java/**`、`src/test/**` |
| API | `metadata-query-module/src/main/java/com/foggy/navigator/metadata/query/controller/MetadataQueryController.java` |
| Service/config | `MetadataQueryService*.java`、`MetadataQueryAutoConfiguration.java`、`RestTemplateConfig.java`、`application.yml` |
| Models/resources | `model/**`、`resources/foggy/templates/models/**`、`queries/**` |
| External dependency | `foggy.api.base-url`、Foggy Dataset/FSScript artifacts 和目标查询服务 |
| Skill/docs | `.agents/skills/metadata-query-module/**`、README、系统总览、模块文档、安装文档 |

`metadata-config-module`、其 entity/repository/service/controller、平台设置 API 和数据表全部为 do-not-touch，除非另有独立需求。

## 审计步骤与运行态门禁

### Q0：冻结基线

1. 记录源码 commit、根/launcher 依赖树和 clean test 基线。
2. 导出所有环境的配置键与 deployment inventory，敏感值只记录存在性和归属。
3. 指定 metadata、deployment、data 和 consumer Owner。

完成门：无法归属的环境或配置标记 blocked，不推断无人使用。

### Q1：静态消费者与契约扫描

~~~bash
rg -n --hidden 'metadata-query-module|/api/metadata/query|foggy[.]api[.]base-url|datasource-latest|semantic-layer-latest' \
  . -g '!**/.git/**' -g '!**/target/**' -g '!**/node_modules/**'
rg -n --hidden 'import com[.]foggy[.]navigator[.]metadata[.]query[.]' \
  . -g '!metadata-query-module/**' -g '!metadata-config-module/**' -g '!**/target/**'
rg -n 'metadata-query-module' pom.xml launcher/pom.xml .github scripts docker deploy docs
~~~

必须记录 PC、Mobile、Open SDK、CLI、Worker、脚本、文档和外部仓消费者矩阵；搜索结果为零也要保留命令和 commit SHA。

### Q2：运行流量、外部服务与数据审计

1. 对所有环境统计精确 API 路径的请求数、主体、最后调用、成功/失败和 queryId。
2. 盘点 `foggy.api.base-url` 目标、第三方服务、网络规则和运维 Owner。
3. 盘点 TM/QM 模板、datasource/semantic-layer 配置及其数据库/外部存储。
4. 检查启动日志中 auto-configuration、模板加载和外部连接行为。
5. 明确数据保留、导出、迁移和删除不属于同一 Git 提交。

运行态证据必须脱敏；不得记录 tenant 数据、数据库凭据或完整查询参数。

完成门：每个真实消费者有替代/迁移决定；目标服务和数据均有 Owner。

### Q3：去留决策

- `retain`：重命名/重文档化真实职责，补认证、测试和 Owner；不得继续冒充平台配置读接口。
- `migrate`：先提供替代 API/SDK，迁移消费者并进入静默窗口。
- `retire`：进入 Q4-Q5。
- `defer`：缺证据时保留模块并写明解除条件。

决策审计本身不改路由；实际 retire 必须单独批准并更新 `production_routing_changed` / `external_contract_changed`。

### Q4：迁移、禁用与回滚演练

仅在 migrate/retire 获批后：

1. 发布弃用通知和替代说明，迁移所有已知消费者。
2. 以可回滚配置或网关策略禁用新调用，观察完整静默窗口。
3. 保留模块二进制、模板、配置和旧路由恢复步骤。
4. 在隔离环境演练重新装配 launcher dependency 和恢复 API。

完成门：无未迁移调用；回滚可在约定时间内完成。

### Q5：完整切片移除

1. 从 launcher 和根 reactor 移除模块。
2. 删除模块 Java、tests、resources 和专属配置。
3. 更新 skill、README、系统总览、模块职责和安装文档。
4. 清理不再需要的 Maven artifacts/config；不得误删 metadata-config。
5. 数据/外部服务下线使用独立运维审批和可恢复备份。

非目标：

- 不删除 `metadata-config-module`；
- 不把 LangGraph Biz Worker 的 FSScript 审批运行时等同于本模块；
- 不重写语义层平台或为退役引入新通用查询框架。

## 自动化验证

当前均 `not-run`：

~~~bash
mvn -B -pl metadata-query-module -am test
mvn -B -pl launcher -am clean test
mvn -B clean verify
git diff --check
~~~

退役后还需：

- launcher application context 启动且不加载 MetadataQuery bean；
- metadata-config 的 controller/service/repository 测试通过；
- Maven dependency tree 不再包含仅由 metadata-query 引入的旧 artifacts；
- 获批环境中旧端点状态符合弃用契约；
- Markdown 相对链接检查通过。

不得用退役前模块测试通过证明退役后 launcher 正常，也不得把预期 404 当作生产批准。

## 手工验证

1. 在可信内网启动 Navigator，检查设置、模型、凭据、Git Provider、memory 等 metadata-config 页面正常。
2. 按 consumer matrix 对替代 API 执行 smoke。
3. 检查启动日志无 metadata-query 模板加载/外部 Foggy API 连接错误。
4. 在隔离环境执行一次模块恢复 runbook。
5. 由 data owner 确认外部 TM/QM 和数据资源的保留/迁移状态。

Experience 在纯审计阶段为 `not-applicable`；若实际退役影响任何 UI/SDK/客户调用，必须改为 `not-run` 并补相应体验证据。

## 风险与回滚

| 风险 | 控制 | 回滚 |
|---|---|---|
| 未知外部消费者 | 多环境流量 + Owner 确认 + 静默窗口 | 恢复 launcher dependency/模块和网关路由 |
| 包名相邻导致误删 metadata-config | 精确目录/依赖清单和禁止前缀删除 | 立即 revert，恢复 config module clean test |
| 外部 Foggy 服务仍承担业务 | 配置、网络和服务 Owner 审计 | 恢复旧 API adapter，延后服务下线 |
| TM/QM 或配置丢失 | 独立备份/导出和 data approval | 从备份恢复；Git revert 不替代数据恢复 |
| Maven 传递依赖变化破坏 launcher | 退役前后 dependency tree + clean verify | revert POM/module 提交 |

源码、POM、文档分为可审查但同一退役阶段的提交；回滚顺序先恢复 module/POM/API，再恢复外部配置。数据和第三方服务必须有独立恢复方案。

## 完成判据

- [ ] 全环境 API 流量、部署、配置、第三方服务和数据审计完成。
- [ ] PC/Mobile/SDK/CLI/Worker/脚本/外部客户矩阵有 Owner 和迁移状态。
- [ ] 去留决策明确，缺证据时以 defer 收口而非伪退役。
- [ ] 若 retire，消费者迁移、弃用窗口、静默和回滚演练完成。
- [ ] 若 retire，reactor、launcher、module、resources、config、skill 和 docs 完整切片退出。
- [ ] metadata-config 明确保留并完成回归。
- [ ] Java clean、context、dependency tree 和手工 smoke 有真实证据。
- [ ] 结果和 changed paths 已回写 [Progress](../progress.md)。
- [ ] 正式质量检查、覆盖审计和签收完成。

## 生产路由与外部契约状态

- audit_production_routing_changed: no
- audit_external_contract_changed: no
- retirement_production_routing_changed: yes
- retirement_external_contract_changed: yes
- production_approval_required_for_retirement: yes

当前 YAML 中的 `no` 只描述规划/审计状态；实际移除 API 或 launcher 模块时必须更新为 `yes`，且隔离验证不能替代生产批准。
