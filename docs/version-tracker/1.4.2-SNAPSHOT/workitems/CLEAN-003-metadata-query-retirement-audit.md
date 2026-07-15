---
type: cleanup
version: 1.4.2-SNAPSHOT
ticket: CLEAN-003
priority: high
status: completed-local
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
- implementation: `implementation-complete`
- code_and_assembly_slice: `removed`
- current_document_alignment: `completed`
- pre_removal_clean_baseline: `passed`
- post_removal_verification: `partial-passed-local-and-hosted`
- testing: `partial-passed-local-and-hosted`
- experience: `not-run`
- production_routing_changed: `no`
- external_contract_changed: `no`

Owner 已确认当前项目未生产、上游仍在本机共同孵化，因此本切片不再等待生产流量静默、客户迁移窗口或旧 dev 数据备份。该授权不覆盖后来发现的共享或生产资源；命中此类证据时，只停止相应外部资源动作，不把未知资源随代码批量删除。

## 证据分类

### 已确认事实

1. Navigator 当前不是语义层或数据分析平台。
2. `metadata-query-module` 目录、根 reactor 条目、`launcher` 依赖、launcher context test 中的专属 bean 断言和专属 Skill 已从工作树移除。
3. Owner 已批准 1.4.2 在仓内引用处理和 clean gate 通过后完整删除该模块，旧 dev 数据允许丢弃。
4. `metadata-config-module` 是当前平台配置治理能力，必须保留，不能按相邻 Java package 或模块名称误删。
5. 删除前基线 `mvn -B -pl launcher -am clean test` 已于 `2026-07-14` 通过，16 个 reactor 项全部 `SUCCESS`；这不是删除后的验证证据。

### 静态搜索结论

1. 删除前，根 `pom.xml` 含 `<module>metadata-query-module</module>`；该条目已移除，metadata-query 删除当时根 reactor 从 17 个模块收缩为 16 个模块；后续 Echo 退出后当前根 reactor 为 15 个模块。
2. 删除前，`launcher/pom.xml` 直接依赖 `metadata-query-module`；当前 dependency 已移除。
3. `launcher/src/test/java/com/foggy/navigator/launcher/CommonRepositoryOwnershipContextTest.java` 中 `metadataQueryRestTemplate` 专属断言已移除，其他 RestTemplate ownership 断言保留。
4. 包含 `/api/metadata/query`、controller、service、模型、TM/QM 模板和模块测试的 `metadata-query-module/**` 已删除。
5. 专属 `.agents/skills/metadata-query-module/**` 已退出活跃发现；`metadata-config-module/**` 业务树本批 diff 为 0，仍保留其原有 `com.foggy.navigator.metadata.query.config.*` package，该同名前缀不是删除残留。
6. 排除 version-tracker、历史文档、`metadata-config-module` 的合法 package 与旧 `target` 产物后，当前构建/装配/源码/Skill 树不再命中 `metadata-query-module`、`metadataQueryRestTemplate` 或 `/api/metadata/query`。
7. 当前根 `README.md`、`CLAUDE.md`、系统总览、功能架构与安装文档已移除将 metadata-query 列为当前模块的描述；历史 `module-review-2026-05-31.md` 正文不改写，只增加历史快照提示。

### 需要运行态确认

Owner 已免除普通 dev 删除所需的生产流量审计。只有静态或执行中出现以下冲突证据时才暂停对应危险动作：

- 共享/生产 deployment、反向代理或外部仓配置明确引用该模块/API；
- `foggy.api.base-url` 指向需要随本次操作删除的共享服务或凭据；
- 待执行命令会删除共享 datasource、TM/QM、数据库、队列或其他仓外资源。

本 workitem 默认只删除 Git 仓内代码、配置和文档，不主动调用外部服务、不删数据库、不改网关、不撤销凭据。

## 完整功能切片

| 层 | 精确范围 | 当前动作 |
|---|---|---|
| Reactor | 根 `pom.xml` 的 `metadata-query-module` module | 已删除；本切片完成时根 reactor 为 16 个模块，后续 Echo 退出后当前为 15 个模块 |
| Launcher | `launcher/pom.xml` 的 module dependency | 已删除 dependency；删除后 clean test 已通过 |
| Launcher test | `launcher/src/test/java/com/foggy/navigator/launcher/CommonRepositoryOwnershipContextTest.java` | 已只删除 `metadataQueryRestTemplate` 专属断言，保留其余 ownership 测试 |
| Module | `metadata-query-module/pom.xml`、`README.md`、`src/main/**`、`src/test/**` | 完整目录已删除 |
| API | `MetadataQueryController.java` 与 `/api/metadata/query/**` | 随模块删除 |
| Service/config | `MetadataQueryService*.java`、`MetadataQueryAutoConfiguration.java`、`RestTemplateConfig.java`、`application.yml` | 随模块删除 |
| Models/templates | `model/**`、`resources/foggy/templates/models/**`、`resources/foggy/templates/queries/**` | 随模块删除 |
| Skill/docs | `.agents/skills/metadata-query-module/**` 与当前模块/安装说明 | 专属 Skill 已删除；当前 README/CLAUDE/架构文档已对齐；历史版本证据不改写，历史 module review 只增加快照提示 |
| External resources | datasource、TM/QM、外部 Foggy 服务、数据库和凭据 | 本批次不操作 |

## Do-not-touch 保护清单

- `metadata-config-module/**`
- `com.foggy.navigator.metadata.query.config.*` 下属于 metadata-config 的平台配置代码
- 平台设置中的 Git、LLM、凭据、记忆、Worker、ClientApp 和 Business Agent 配置能力
- LangGraph Biz Worker 自己使用的 FSScript/审批运行时
- 历史 `docs/version-tracker/1.3.*/`、`1.4.0-SNAPSHOT/`、`1.4.1-SNAPSHOT/` 证据

## 实施步骤

### Q0：冻结删除前证据（已完成）

1. 记录上述精确引用扫描结果和当前 commit/worktree 范围。
2. 保留 `2026-07-14` launcher clean test 作为删除前基线。
3. 核对待删除路径不越过完整切片和 do-not-touch 清单。

### Q1：仓内切片删除（已完成）

1. 从根 reactor 和 launcher dependency 中移除模块。
2. 删除 `metadata-query-module/**`。
3. 调整 launcher context test 中专属 bean 断言，不削弱其他 RestTemplate ownership 测试。
4. 移除/更新 metadata-query 专属 Skill 和当前文档；历史版本证据只保留或增加 superseded 链接。
5. 重新扫描 API、package、bean、module、模板和配置键残留。

### Q2：删除后验证（自动化部分通过，启动/浏览器体验待执行）

1. 执行 launcher 依赖链 clean test，确认 application context 不再需要 MetadataQuery bean。
2. 执行 `metadata-config-module` 相关测试和 launcher context test，防止相邻 package 被误删。
3. 检查 Maven dependency tree 中仅由 metadata-query 引入的旧 artifact 是否退出。
4. 启动 smoke 时检查设置、模型、凭据、Git Provider、memory 和 ClientApp/Business Agent 配置入口。
5. 运行 Markdown 链接、`git diff --check` 和工作树范围检查。

## 自动化验证

| 命令/检查 | 当前状态 | 证据边界 |
|---|---|---|
| `mvn -B -pl launcher -am clean test`（删除前） | passed | 2026-07-14；16 reactor SUCCESS，0 failures |
| 精确 `rg` 仓内消费者/残留扫描 | passed-static | 构建/装配/源码/Skill 切片已退出；当前 README/CLAUDE/架构文档已对齐；`metadata-config` 合法 package 不计为残留 |
| `mvn -B -pl launcher -am validate`（删除后） | passed-structural | 2026-07-14；15/15 reactor project `SUCCESS`；只证明 Maven 结构可解析，不是 compile/test 证据 |
| `mvn -B -pl metadata-config-module,launcher -am clean test`（删除后） | passed | 2026-07-14；15/15 reactor project `SUCCESS`，总时 `05:23`；metadata-config 4 suites/52 tests、launcher 3 suites/7 tests，均 0 failure/error/skipped |
| metadata-config 保留与定向测试 | passed | 23 个 tracked files 保留，`metadata-config-module/**` 业务树 diff 为 0；上述删除后 clean test 覆盖其测试与 launcher 装配 |
| Maven dependency tree | passed-static | 删除后 dependency tree 对 `metadata-query`、`foggy-dataset-model`、`foggy-dataset`、`foggy-fsscript` 均无命中 |
| clean target 残留扫描 | passed-static | clean 后 target 未发现 metadata-query、Dataset 或 FSScript 旧依赖残留 |
| Repository CI run `29324741945` | passed-hosted | 截至正式闸门的最新已验证实现 head `9d03bee9` 的 Java、前端和五类 Worker共 7 jobs 全 success；证明该实现快照 clean runner 基线，不替代 metadata-query 专属启动/体验 |
| 启动 smoke | not-run | 删除后尚未执行 |
| Markdown links / `git diff --check` | pending-final-check | 当前文档已收口；本轮结束前执行最终 whitespace/链接检查 |

不得用删除前的绿色 reactor 或删除后 `validate` 证明删除后 compile/test 成功，也不得用静态无引用证明仓外不存在任何资源。本次结论使用删除后 `clean test`、dependency tree、clean target 扫描与已验证实现 head 的 hosted CI；它们仍不等于 metadata-query 专属启动/浏览器体验、运行流量审计或验收通过。版本正式签收已执行并为 `rejected`。

## Execution Check-in（2026-07-14，completed-local）

### 已实施变更

1. 从根 `pom.xml` 移除 `metadata-query-module`；本切片执行当时根 reactor 为 16 个模块，后续 Echo 退出后当前为 15 个模块。
2. 从 `launcher/pom.xml` 移除 metadata-query dependency。
3. 删除 `metadata-query-module/**` 的源码、资源、测试、POM 和 README。
4. 从 launcher context test 移除 `metadataQueryRestTemplate` 专属断言，保留其他 RestTemplate ownership 断言。
5. 删除 `.agents/skills/metadata-query-module/**`；本批未修改 `metadata-config-module/**` 业务树。

### Changed paths

- `pom.xml`
- `launcher/pom.xml`
- `launcher/src/test/java/com/foggy/navigator/launcher/CommonRepositoryOwnershipContextTest.java`
- `metadata-query-module/**`（deleted）
- `.agents/skills/metadata-query-module/**`（deleted）
- `docs/version-tracker/1.4.2-SNAPSHOT/**`（进度回写）

### 当前门禁判断

- development: `implementation-complete`；代码、装配、Skill 与当前权威文档切片均已收口，历史 module review 正文未被改写。
- testing: `partial-passed-local-and-hosted`；删除后 `mvn -B -pl metadata-config-module,launcher -am clean test` 15/15 `SUCCESS`，metadata-config 4 suites/52 tests、launcher 3 suites/7 tests，均 0 failure/error/skipped；dependency tree 和 clean target 扫描无旧查询依赖；截至正式闸门的最新已验证实现 head 对应 Repository CI run `29324741945` 的 7 jobs 全 success。metadata-query 专属启动/浏览器未运行。
- experience: `not-run`；需在 clean 启动后检查设置、模型、凭据、Git Provider、memory、ClientApp 和 Business Agent 主链。
- deviations: 无业务范围扩张；当前文档已同步，历史证据不改写，仅为历史 module review 增加快照提示。
- next_gate: 补启动/浏览器 smoke 与模块级签收；版本 P7 质量/覆盖/签收已执行并拒绝，本地和 hosted CI 通过不等于验收通过。

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

- [x] 根 reactor、launcher dependency、module 源码/资源/测试、launcher 专属 bean 断言和专属 Skill 已退出工作树。
- [x] 当前 README、CLAUDE 与架构文档的 metadata-query 现行描述已收口；历史证据保持不改写。
- [x] `metadata-config-module` 23 个 tracked files 保留、业务树 diff 为 0，删除后定向 clean test 通过。
- [x] launcher clean test、context test、依赖树与 clean target 检查有真实结果。
- [ ] 启动与浏览器 smoke 有真实结果；当前明确记录为 `not-run`。
- [x] 仓内 API/package/bean/config/template 引用扫描无未解释残留。
- [x] 未执行或发现的外部资源明确记录为 `not-run` / `not-found-static`，不虚构流量或删除证据。
- [x] Git 回滚范围、命令和顺序可定位。
- [x] 进度与 changed paths 回写 [Progress](../progress.md)。
- [x] 版本正式质量检查、覆盖审计和签收已按 P7 执行，结论为 `ready-with-risks / needs-more-tests / rejected`；模块级签收仍待补。

## 生产路由与外部契约状态

- production_routing_changed: no
- external_contract_changed: no
- development_contract_changed_after_removal: yes
- production_enablement: not-applicable

旧 API 的 dev 删除不等于生产发布或外部开放批准；如后续发现真实共享/生产部署，本 workitem 的授权不自动适用。
