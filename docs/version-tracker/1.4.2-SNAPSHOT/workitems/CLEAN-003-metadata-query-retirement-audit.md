---
type: cleanup
version: 1.4.2-SNAPSHOT
ticket: CLEAN-003
priority: high
status: planned-reviewed
source: REQ-001
owner: metadata-query-and-launcher-owner
---

# metadata-query dev-only 完整退役

## 文档作用

- doc_type: workitem
- intended_for: project-root-session | metadata-owner | launcher-owner | reviewer | signoff-owner
- purpose: 按 ODR-142-006-MQ 将旧 `metadata-query-module` 从 reactor、launcher、源码、测试、Skill 和当前文档中完整移除，同时保护 `metadata-config-module`。

## 关联文档

- [版本索引](../README.md)
- [REQ-001](../requirements/REQ-001-platform-governance-and-legacy-cleanup.md)
- [实施计划](../implementation-plan.md)
- [ODR-142-006 Owner 决策](../owner-decision-review.md)
- [统一进度记录](../progress.md)

## Owner 决策与当前状态

- decision: `retire`
- environment_scope: `development-only`
- physical_deletion_authorized: `yes`
- dev_data_discard_authorized: `yes`
- production_or_external_compatibility_window: `not-required`
- implementation: `not-started`
- pre_removal_clean_baseline: `passed`
- post_removal_verification: `not-run`
- production_routing_changed: `no`
- external_contract_changed: `no`

Owner 已确认当前项目未生产、上游仍在本机共同孵化，因此本切片不再等待生产流量静默、客户迁移窗口或旧 dev 数据备份。该授权不覆盖后来发现的共享或生产资源；命中此类证据时，只停止相应外部资源动作，不把未知资源随代码批量删除。

## 证据分类

### 已确认事实

1. Navigator 当前不是语义层或数据分析平台。
2. `metadata-query-module` 当前仍在根 reactor 和 `launcher`，本轮尚未物理删除。
3. Owner 已批准 1.4.2 在仓内引用处理和 clean gate 通过后完整删除该模块，旧 dev 数据允许丢弃。
4. `metadata-config-module` 是当前平台配置治理能力，必须保留，不能按相邻 Java package 或模块名称误删。
5. 删除前基线 `mvn -B -pl launcher -am clean test` 已于 `2026-07-14` 通过，16 个 reactor 项全部 `SUCCESS`；这不是删除后的验证证据。

### 静态搜索结论

1. 根 `pom.xml` 含 `<module>metadata-query-module</module>`。
2. `launcher/pom.xml` 直接依赖 `metadata-query-module`。
3. `launcher/src/test/java/com/foggy/navigator/launcher/CommonRepositoryOwnershipContextTest.java` 获取名为 `metadataQueryRestTemplate` 的 bean；删除模块时必须同步调整该专属断言，同时保留其他 RestTemplate ownership 检查。
4. API 基路径为 `/api/metadata/query`，模块内含 controller、service、模型、TM/QM 模板和两组测试。
5. 模块依赖旧 Foggy Dataset/FSScript 查询能力，并使用 `foggy.api.base-url` 访问外部查询服务。
6. 排除模块自身、`metadata-config-module`、version-tracker 和构建产物后，当前仓内未发现业务源码对 `/api/metadata/query` 或 `metadataQueryRestTemplate` 的消费者；项目级 metadata-query Skill 仍描述已计划删除的 API。

### 需要运行态确认

Owner 已免除普通 dev 删除所需的生产流量审计。只有静态或执行中出现以下冲突证据时才暂停对应危险动作：

- 共享/生产 deployment、反向代理或外部仓配置明确引用该模块/API；
- `foggy.api.base-url` 指向需要随本次操作删除的共享服务或凭据；
- 待执行命令会删除共享 datasource、TM/QM、数据库、队列或其他仓外资源。

本 workitem 默认只删除 Git 仓内代码、配置和文档，不主动调用外部服务、不删数据库、不改网关、不撤销凭据。

## 完整功能切片

| 层 | 精确范围 | 当前动作 |
|---|---|---|
| Reactor | 根 `pom.xml` 的 `metadata-query-module` module | 删除条目 |
| Launcher | `launcher/pom.xml` 的 module dependency | 删除 dependency |
| Launcher test | `launcher/src/test/java/com/foggy/navigator/launcher/CommonRepositoryOwnershipContextTest.java` | 只删除 `metadataQueryRestTemplate` 专属断言，保留其余 ownership 测试 |
| Module | `metadata-query-module/pom.xml`、`README.md`、`src/main/**`、`src/test/**` | 完整目录删除 |
| API | `MetadataQueryController.java` 与 `/api/metadata/query/**` | 随模块删除 |
| Service/config | `MetadataQueryService*.java`、`MetadataQueryAutoConfiguration.java`、`RestTemplateConfig.java`、`application.yml` | 随模块删除 |
| Models/templates | `model/**`、`resources/foggy/templates/models/**`、`resources/foggy/templates/queries/**` | 随模块删除 |
| Skill/docs | `.agents/skills/metadata-query-module/**` 与当前模块/安装说明 | 移出活跃发现或删除，并对齐当前文档；历史版本证据不改写 |
| External resources | datasource、TM/QM、外部 Foggy 服务、数据库和凭据 | 本批次不操作 |

## Do-not-touch 保护清单

- `metadata-config-module/**`
- `com.foggy.navigator.metadata.query.config.*` 下属于 metadata-config 的平台配置代码
- 平台设置中的 Git、LLM、凭据、记忆、Worker、ClientApp 和 Business Agent 配置能力
- LangGraph Biz Worker 自己使用的 FSScript/审批运行时
- 历史 `docs/version-tracker/1.3.*/`、`1.4.0-SNAPSHOT/`、`1.4.1-SNAPSHOT/` 证据

## 实施步骤

### Q0：冻结删除前证据

1. 记录上述精确引用扫描结果和当前 commit/worktree 范围。
2. 保留 `2026-07-14` launcher clean test 作为删除前基线。
3. 核对待删除路径不越过完整切片和 do-not-touch 清单。

### Q1：仓内切片删除

1. 从根 reactor 和 launcher dependency 中移除模块。
2. 删除 `metadata-query-module/**`。
3. 调整 launcher context test 中专属 bean 断言，不削弱其他 RestTemplate ownership 测试。
4. 移除/更新 metadata-query 专属 Skill 和当前文档；历史版本证据只保留或增加 superseded 链接。
5. 重新扫描 API、package、bean、module、模板和配置键残留。

### Q2：删除后验证

1. 执行 launcher 依赖链 clean test，确认 application context 不再需要 MetadataQuery bean。
2. 执行 `metadata-config-module` 相关测试和 launcher context test，防止相邻 package 被误删。
3. 检查 Maven dependency tree 中仅由 metadata-query 引入的旧 artifact 是否退出。
4. 启动 smoke 时检查设置、模型、凭据、Git Provider、memory 和 ClientApp/Business Agent 配置入口。
5. 运行 Markdown 链接、`git diff --check` 和工作树范围检查。

## 自动化验证

| 命令/检查 | 当前状态 | 证据边界 |
|---|---|---|
| `mvn -B -pl launcher -am clean test`（删除前） | passed | 2026-07-14；16 reactor SUCCESS，0 failures |
| 精确 `rg` 仓内消费者扫描 | partial-passed | 已确认 root/launcher/context test/Skill 命中；删除前须保存最终结果 |
| `mvn -B -pl launcher -am clean test`（删除后） | not-run | 物理删除尚未开始 |
| metadata-config 定向测试 | not-run | 删除后执行 |
| Maven dependency tree | not-run | 删除后执行 |
| 启动 smoke | not-run | 删除后执行 |
| Markdown links / `git diff --check` | not-run-for-slice | 删除后执行 |

不得用删除前的绿色 reactor 证明删除后成功，也不得用静态无引用证明仓外不存在任何资源。

## 手工验证

1. 在可信内网启动 Navigator，检查设置、模型、凭据、Git Provider、memory、ClientApp 和 Business Agent 页面/接口。
2. 检查启动日志没有 metadata-query bean、模板或外部 Foggy API 初始化错误。
3. 请求旧 `/api/metadata/query/**` 时确认结果与当前 dev 删除预期一致；这不是生产弃用契约。
4. 查看 `git diff`，确认没有删除 `metadata-config-module` 或同名但不同职责代码。

## 风险与回滚

| 风险 | 控制 | 回滚 |
|---|---|---|
| 相邻 package 误删 metadata-config | 精确文件清单、do-not-touch 扫描和定向测试 | 只 revert metadata-query 独立批次，恢复误删文件 |
| launcher context 因 bean 断言或自动配置失败 | 同步修改专属断言并执行 clean test | 恢复 module/dependency/test 断言 |
| 隐式仓内消费者漏扫 | API/package/config/resource 多维 `rg` + clean test | 恢复模块并补迁移 |
| 发现共享/生产外部资源 | 本批次不执行外部资源删除，命中即停手 | 保持外部资源不变并单独决策 |
| Git revert 不能恢复外部数据 | 明确代码批次不操作 DB/TM/QM/凭据 | 如曾有独立外部操作，按其独立快照/runbook 恢复 |

回滚采用 metadata-query 独立 Git 提交：恢复 module 目录、根 reactor、launcher dependency、context test 和当前文档。不得以回滚该提交为理由恢复旧语义层为产品主线。

## 完成判据

- [ ] 根 reactor、launcher dependency、module 源码/资源/测试、专属 Skill 和当前文档完整退出。
- [ ] `metadata-config-module` 和其配置入口未被删除且定向回归通过。
- [ ] launcher clean test、context test、依赖树检查和启动 smoke 有真实结果。
- [ ] 仓内 API/package/bean/config/template 引用扫描无未解释残留。
- [ ] 未执行或发现的外部资源明确记录为 `not-run` / `not-found-static`，不虚构流量或删除证据。
- [ ] Git 回滚范围、命令和顺序可定位。
- [ ] 进度与 changed paths 回写 [Progress](../progress.md)。
- [ ] 正式质量检查、覆盖审计和签收按 P7 执行。

## 生产路由与外部契约状态

- production_routing_changed: no
- external_contract_changed: no
- development_contract_changed_after_removal: yes
- production_enablement: not-applicable

旧 API 的 dev 删除不等于生产发布或外部开放批准；如后续发现真实共享/生产部署，本 workitem 的授权不自动适用。
