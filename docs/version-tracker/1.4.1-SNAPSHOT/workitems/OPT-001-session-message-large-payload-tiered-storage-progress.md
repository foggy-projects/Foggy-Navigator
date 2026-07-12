# OPT-001 会话大消息分层存储进度

## 文档作用

- doc_type: progress
- intended_for: execution-agent | reviewer | signoff-owner
- purpose: 按阶段记录实现、测试、体验、风险和后置评审状态。

## 基本信息

- version: `1.4.1-SNAPSHOT`
- status: planned-reviewed
- requirement: [OPT-001 requirement](./OPT-001-session-message-large-payload-tiered-storage.md)
- implementation_plan: [OPT-001 plan](./OPT-001-session-message-large-payload-tiered-storage-plan.md)
- implementation_started_at: pending
- last_updated_at: 2026-07-12

## 前置条件

| 条件 | 状态 | 说明 |
|---|---|---|
| BUG-021 48 KiB 保护已存在 | completed | 新链路验证前不得移除 |
| 消息类型与加载语义已确认 | completed | 最终回复完整；工具载荷按需读取 |
| 存储方向已确认 | completed | MySQL Descriptor + Payload Store；首期持久卷 |
| 默认阈值/保留期完成容量验证 | pending | 建议 8 KiB / 14 天，实施时确认 |
| 生产多实例存储后端决策 | pending | 共享卷或私有对象存储 |

## Development Progress

| Stage | 范围 | 状态 | 结果/证据 |
|---|---|---|---|
| 0 | 基线、字段盘点、契约和失败测试 | not-started | - |
| 1 | Schema、迁移、Payload Store 基础 | not-started | - |
| 2 | 消息分流、幂等和 ACK 故障安全 | not-started | - |
| 3 | 列表零读取、详情 API 和前端交互 | not-started | - |
| 4 | 保留期、清理、配额和指标 | not-started | - |
| 5 | 生产后端、灰度、压测和切换 | not-started | - |

## Testing Progress

| Test lane | 状态 | Evidence |
|---|---|---|
| Java unit | not-run | 大小边界、Store、重放、故障矩阵 |
| Session integration | not-run | 列表零读取、详情鉴权、清理、删除 |
| MySQL 8.0 migration | not-run | - |
| MySQL 8.4 migration | not-run | - |
| Frontend unit/type-check/build | not-run | - |
| Capacity/compression benchmark | not-run | - |

## Experience Progress

| 体验维度 | 检查项 | 状态 | Evidence |
|---|---|---|---|
| 页面可达性 | 历史消息和工具详情入口正常渲染 | not-run | - |
| 默认加载 | 打开/刷新会话不请求外置载荷 | not-run | - |
| 核心交互 | 点击详情后完整展示并缓存 | not-run | - |
| 异常状态 | EXPIRED/UNAVAILABLE/读取失败可理解且可关闭 | not-run | - |
| 权限可见性 | 无权限用户不可读取或推断载荷路径 | not-run | - |
| 数据一致性 | 最终回复刷新前后完整一致，Preview/详情摘要一致 | not-run | - |

### Playwright 状态

| 用例 | 覆盖维度 | 状态 |
|---|---|---|
| 历史列表零 Payload 请求 | 默认加载 | not-run |
| 点击查看完整工具输出 | 核心交互、数据一致性 | not-run |
| 过期和不可用载荷 | 异常状态 | not-run |
| 跨用户读取拒绝 | 权限可见性 | not-run |
| 大型最终回复刷新恢复 | 数据一致性 | not-run |

## Acceptance Criteria Tracking

| Requirement | 状态 | Evidence |
|---|---|---|
| 大工具输出不再以 48 KiB metadata 入库 | pending | - |
| 列表和恢复零 Payload Store 读取 | pending | - |
| 用户主动操作才读取完整载荷 | pending | - |
| 最终 Assistant 回复完整持久化和展示 | pending | - |
| Store 故障不阻塞 ACK 和终态 | pending | - |
| 重放幂等且孤儿可清理 | pending | - |
| 过期后消息/Preview 仍可用 | pending | - |
| 鉴权、路径和敏感信息边界通过 | pending | - |

## Implementation Self-Check

- [ ] requirement scope 已收口，未扩展到附件和全部日志存储。
- [ ] 最终回复和工具输出使用不同持久化策略。
- [ ] 列表路径没有隐藏的文件读取或签名 URL 生成。
- [ ] Payload Store 失败与 MySQL 失败使用不同 ACK 语义。
- [ ] `PENDING` 只在存在可靠重试来源时使用，并能限时收敛。
- [ ] 重放、事务回滚和清理均幂等。
- [ ] 没有提交真实存储凭据、绝对生产路径或敏感正文。
- [ ] 测试、体验证据和风险已回写。

- self_check_decision: pending
- formal_quality_gate_required: yes-cross-module-shared-capability

## 计划外变更

- none

## 阻塞项与待确认项

| Item | 状态 | Owner/Decision |
|---|---|---|
| 8 KiB Preview 是否为最终默认值 | pending-capacity-evidence | implementation owner + reviewer |
| 14 天是否为最终默认保留期 | pending-capacity-evidence | product/release owner |
| 生产使用共享卷还是对象存储 | pending-deployment-topology | release owner |
| 历史 48 KiB 数据是否迁移 | deferred | 依据真实容量决定 |

## 后续衔接

1. 从 Stage 0 开始，先补失败测试和活动字段盘点。
2. Stage 1/2 完成前，不修改当前 48 KiB 保护默认值。
3. Stage 3 完成并有 Playwright 证据后，才能启用较小 Preview。
4. Stage 4/5 完成后依次执行实现质量检查、覆盖审计和正式验收。
