# 1.4.1-SNAPSHOT

## 文档作用

- doc_type: version-index
- intended_for: root-controller | execution-agent | reviewer | release-owner
- purpose: 管理会话大消息分层存储、历史按需加载和过期清理的实施与验收。

## 版本状态

- status: planned-reviewed
- primary_workitem: `OPT-001`
- implementation_started: no
- production_migration_started: no

## 版本目标

在不重新引入 Codex durable stream poison event 的前提下，将大型工具输出从 MySQL 消息正文中分离：MySQL 只保存可检索的消息、预览和载荷描述，完整工具输出保存在可配置的持久化载荷存储中，并由用户主动打开详情时读取。最终 Assistant 回复继续完整保存在消息/任务投影中，默认加载即可完整展示。

## 成功标准

1. 超过内联阈值的工具输出不再以接近 48 KiB 的正文写入 `session_messages.metadata`。
2. 消息列表、会话恢复和分页查询不得读取外置载荷，也不得提前生成对象存储签名 URL。
3. 用户点击“查看完整输出”“详情”或“下载”后，才鉴权并读取完整载荷。
4. 最终 Assistant 回复不按工具输出规则截断，受支持范围内完整持久化并默认完整展示。
5. 载荷写入失败不得阻塞事件 ACK、终态收敛或后续消息持久化；失败必须显式可观察。
6. 过期清理不删除消息记录，只清理完整载荷并保留 `EXPIRED` 描述状态。
7. 相关 Java、前端、MySQL 迁移、Playwright 和故障注入测试全部运行通过后，才可进入验收。

## 已确认决策

1. 采用“MySQL 消息/描述 + 外置完整载荷”的分层存储，不通过扩大 `TEXT` 长期承载工具日志。
2. 首个后端实现使用 Navigator Java 服务可访问的持久化目录；多实例/生产共享场景后续接入私有对象存储。Worker 本地 JSONL 只作短期恢复来源，不作为平台历史的长期权威存储。
3. 初始实现不引入 MongoDB/GridFS；若未来已有统一 Mongo 基础设施，再作为可选载荷存储后端单独评估。
4. 工具输出建议以 8 KiB 作为可配置内联预览默认值，完整载荷建议默认保留 14 天；最终值必须通过配置和容量验证确认。
5. 最终 Assistant 回复继续内联；相关活动表字段需要提升到明确足够的容量并禁止静默截断。

## 与 1.4.0 的关系

- [BUG-021](../1.4.0-SNAPSHOT/workitems/BUG-021-codex-oversized-tool-result-poisons-stream-replay.md) 的 48 KiB 有界副本是防止 SSE 重放毒事件的紧急修复，本版本在该安全基线上演进存储设计。
- 在本版本切换并完成故障回归前，不得移除 BUG-021 的有界持久化保护。

## 文档清单

- [OPT-001 需求与架构边界](./workitems/OPT-001-session-message-large-payload-tiered-storage.md)
- [OPT-001 实施计划与代码清单](./workitems/OPT-001-session-message-large-payload-tiered-storage-plan.md)
- [OPT-001 实施进度](./workitems/OPT-001-session-message-large-payload-tiered-storage-progress.md)
