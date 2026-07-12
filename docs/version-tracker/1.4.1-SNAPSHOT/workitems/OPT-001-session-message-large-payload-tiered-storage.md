---
type: optimization
version: 1.4.1-SNAPSHOT
ticket: OPT-001
priority: high
status: planned-reviewed
source: follow-up-from-BUG-021
owner: session-module
---

# 会话大消息分层存储与按需加载

## 文档作用

- doc_type: requirement
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 冻结大型工具输出、最终回复、列表加载、载荷读取和定期清理的产品与架构边界。

## 背景

`1.4.0-SNAPSHOT/BUG-021` 已确认：单条 Codex 工具结果可能超过 MySQL `TEXT` 容量，导致消息持久化失败、事件序号无法 ACK，并在每次重连时重复命中同一 poison event。当前紧急修复将持久化副本限制为 48 KiB，并保留头尾内容。

该修复保证了任务和消息流可以继续，但接近 48 KiB 的 JSON 元数据仍会实际写入 MySQL。大量会话会持续增加表空间、binlog、备份、复制、Java 反序列化、API 响应和浏览器内存成本。

当前已确认的关键事实：

- 工具结果正文位于 `session_messages.metadata` 的 payload 中，而不是只做长度校验。
- 100 条达到 48 KiB 上限的工具结果约占 4.69 MiB；1 万个同规模会话约为 45.8 GiB 原始数据。
- Codex SDK Worker 已保留完整 JSONL 事件，但只保留最近 100 个已完成任务，且依赖具体 Worker 在线和目录不变，不能作为平台长期历史的唯一来源。
- 当前仓库没有可复用的 Mongo/GridFS 或通用对象存储消息载荷实现。

## 与版本目标的关系

本事项是 `1.4.1-SNAPSHOT` 的首要目标：把 BUG-021 的“有界截断兜底”升级为可扩展的分层存储，同时保持流式任务可用性和最终回复完整性。

## 术语

| 术语 | 定义 | 非此概念 |
|---|---|---|
| Session Message | MySQL 中可检索、可分页、可恢复的会话消息记录 | Worker 原始事件文件 |
| Session Message Payload | 与一条消息关联的完整大型正文，通常是工具输出 | 最终 Assistant 回复、附件文件、Worker runtime state |
| Payload Descriptor | MySQL 中描述载荷位置、大小、编码、摘要、状态和过期时间的小型记录 | 完整正文 |
| Payload Store | 保存和读取 Session Message Payload 的后端能力 | LLM Provider、Worker、数据库表本身 |
| Preview | 消息列表可直接返回的有界首尾摘要 | 完整载荷 |

核心命名统一使用 `SessionMessagePayload` / `session_message_payloads` / `payload`。文档中的“外置”只描述存储方式，不作为领域实体名。

## 已确认需求

### 1. 消息类型分流

- 普通用户消息、普通 Assistant 消息和最终 Assistant 回复继续作为会话消息正文完整持久化。
- 大型 `TOOL_CALL_RESULT`、命令输出和同类诊断正文超过内联阈值后，MySQL 只保存 Preview 与 Payload Descriptor。
- 不允许把工具输出的 8 KiB 预览策略套用到最终 Assistant 回复。
- 最终回复相关活动表字段应使用足以覆盖受支持模型输出的明确容量，建议至少为 MySQL `MEDIUMTEXT`；任何超限都必须显式失败或进入受控兜底，禁止静默截断。

### 2. 列表和详情加载

- 消息列表、历史分页、会话恢复、刷新和 SSE snapshot 默认只读取 MySQL，不读取 Payload Store。
- 列表响应只返回 Preview、`payloadId`、原始大小、可用状态、过期时间等描述信息。
- 列表阶段不得读取载荷文件，不得生成 OBS/MinIO/S3 临时 URL，也不得产生 N+1 载荷查询。
- 只有用户主动点击“查看完整输出”“详情”或“下载”后，前端才调用独立的鉴权读取接口。
- 首期详情接口路径冻结为 `GET /api/v1/sessions/{sessionId}/messages/{messageId}/payload`；接口必须同时校验当前用户、Session 和 Message 归属，不能只凭 `payloadId` 读取。
- 前端应缓存当前页面已成功读取的完整载荷，避免重复点击产生重复下载。

### 3. 存储与一致性

- MySQL 保存消息、Preview 和 Payload Descriptor；完整大型正文由 Payload Store 保存。
- 首个实现使用 Navigator Java 服务可访问的持久化目录，必须支持原子写入、幂等键和路径穿越防护，禁止暴露真实磁盘路径。
- 多实例部署不得依赖实例私有临时盘；必须使用共享持久卷或私有对象存储后端。
- 文本载荷默认采用透明压缩；Payload Descriptor 至少记录 `contentEncoding`、`originalBytes`、`storedBytes` 和 `sha256`，摘要按解压后的原始 UTF-8 字节计算，读取时透明解压并校验。
- Payload Store 写入和 MySQL 事务无法形成分布式事务，必须使用稳定 `messageId` 幂等、明确状态和孤儿清理收敛。
- Worker JSONL 可作为短期恢复证据，但 Worker 离线、重装或任务淘汰不得影响已成功保存的平台载荷。

### 4. ACK 与故障语义

- 该优化不得重新引入“大载荷无法持久化导致事件永远不 ACK”的问题。
- 完整载荷保存失败时，平台仍应持久化 Preview 和失败状态，并允许事件 ACK、后续消息处理和任务终态收敛。
- 失败状态至少区分 `READY`、`PENDING`、`UNAVAILABLE`、`EXPIRED`。
- `PENDING` 仅用于完整字节已可靠暂存，或仍持有可重试来源引用的情况；若 ACK 后没有可靠来源可以补写，必须直接标记 `UNAVAILABLE`，不得长期悬空。
- 写入成功但 MySQL 事务失败形成的孤儿载荷，必须可被定期协调任务识别和删除。
- 若未来存在审计级“完整工具输出必须成功后才允许 ACK”的场景，必须另建策略和隔离队列，不得改变本版本的可用性优先默认语义。

### 5. 保留与清理

- 工具完整载荷保留期限可配置，建议默认 14 天。
- 清理只删除完整载荷，并把 Descriptor 标记为 `EXPIRED`；消息、Preview、工具状态和最终回复继续保留。
- 会话删除/清理、过期任务清理和存储水位保护必须纳入同一生命周期规则。
- 应提供单载荷大小上限、用户/租户配额和存储总水位指标。

### 6. 权限与敏感内容

- 完整载荷只能通过 Navigator 现有用户/会话归属鉴权读取。
- API 不返回真实文件路径、存储凭据或长期公开 URL。
- 存储目录和对象必须默认私有；工具输出可能包含环境变量、源码、日志和敏感配置。
- 下载、过期、缺失和拒绝访问应留下可诊断日志，但不得记录完整敏感正文。

## 推荐默认值

| 配置 | 推荐初值 | 说明 |
|---|---:|---|
| 工具输出内联 Preview | 8 KiB | 按 UTF-8 序列化后预算，保留首尾 |
| 完整载荷保留期 | 14 天 | 可按环境覆盖 |
| 最终 Assistant 正文列 | `MEDIUMTEXT` | 不走工具输出 Preview 规则 |
| 初始 Payload Store | persistent filesystem | 仅支持持久卷；临时盘不可作为生产来源 |
| 文本载荷编码 | transparent compression | 算法由实现配置，Descriptor 记录实际 `contentEncoding` |
| 后续生产后端 | private object storage | OBS/MinIO/S3 兼容实现，按真实部署选择 |

推荐值不是外部 API 契约，执行时应配置化并通过容量测试确认。

## 方案比较

| 方案 | 结论 | 原因 |
|---|---|---|
| 继续扩大 MySQL 正文列承载全部工具输出 | 不采用 | 可缓解单条容量问题，但不能解决表空间、binlog、备份、列表反序列化和浏览器负载 |
| 直接使用 Worker JSONL | 不采用 | 生命周期短、只保留有限任务、依赖 Worker 在线与目录稳定，不能承担平台历史权威存储 |
| 首期引入 MongoDB/GridFS | 不采用 | 当前无共用基础设施，会增加部署、鉴权、监控和清理技术栈，收益不足以覆盖复杂度 |
| MySQL Descriptor + 可替换 Payload Store | 采用 | 与现有会话查询兼容，可先落持久卷、再按部署拓扑切换私有对象存储 |

## 验收标准

1. 小于阈值的工具结果保持当前展示，不创建外置载荷。
2. 大于阈值的工具结果只在列表返回有界 Preview，MySQL metadata 不再接近 48 KiB。
3. 加载包含大量外置载荷的会话时，Payload Store 读取次数为 0。
4. 用户点击详情后，鉴权接口返回完整、校验摘要一致的内容；重复点击不重复下载。
5. 未授权用户不能读取其他用户/租户的载荷，且不能通过路径参数访问任意文件。
6. 最终 Assistant 回复超过 64 KiB 时仍能完整持久化、刷新恢复并默认完整展示。
7. Payload Store 写入失败、重复事件重放、MySQL 回滚和 Worker 断线均不能产生重复载荷或阻断终态。
8. 过期清理后消息仍可加载，Preview 保留，详情显示“完整输出已过期”。
9. MySQL 8.0/8.4 迁移、Java 测试、前端测试、构建和 Playwright 全部通过。

## 非目标

- 本版本不把全部消息、最终回复或附件统一迁移到对象存储。
- 本版本不引入 MongoDB/GridFS。
- 本版本不将 Worker JSONL 升格为平台历史权威存储。
- 本版本不追溯迁移所有历史 48 KiB Preview；历史迁移仅在容量证据表明必要时执行。
- 本版本不提供公开、匿名或长期有效的载荷下载链接。

## 约束与风险

- BUG-021 的 48 KiB 保护必须保留到新链路通过故障回归并完成切换。
- 本地持久卷适合单实例首期实现，但不构成多实例共享存储方案。
- 载荷正文压缩率和真实读取率未知，开工前必须增加或执行数据分布采样。
- 最终回复当前可能同时存在于消息和任务投影中，执行时必须盘点所有活动 `resultText` 字段，避免只升级其中一处。

## Review 结论

- review_status: passed-with-implementation-gates
- 结论: 分层存储比继续扩大 MySQL `TEXT` 或单独引入 Mongo 更符合当前项目规模；本地持久卷适合作为首期后端，对象存储作为多实例/生产演进方向。
- 必须门槛: 列表零载荷读取、最终回复完整、ACK 可用性优先、幂等/孤儿清理、鉴权与 MySQL 迁移验证。
- 证据缺口: 真实大小分布、压缩率、详情读取率、生产实例拓扑以及 8 KiB/14 天默认值的容量测算，均在 Stage 0/5 补齐，不能在无证据时直接视为生产默认值。

## Progress Tracking

- development: not-started
- testing: not-run
- experience: not-run；涉及工具输出详情入口，必须补 Playwright
- implementation_plan: [OPT-001 plan](./OPT-001-session-message-large-payload-tiered-storage-plan.md)
- progress_record: [OPT-001 progress](./OPT-001-session-message-large-payload-tiered-storage-progress.md)

## 参考

- [1.4.0 BUG-021](../../1.4.0-SNAPSHOT/workitems/BUG-021-codex-oversized-tool-result-poisons-stream-replay.md)
