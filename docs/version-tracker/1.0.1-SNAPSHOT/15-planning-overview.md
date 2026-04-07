# 15 Planning Overview

## Date

- 2026-04-07

## Scope

对 `docs/version-tracker/1.0.1-SNAPSHOT` 当前已有条目做一次可执行规划整理，目标不是重复原文，而是给出：

- 当前状态
- 建议优先级
- 建议动作
- 是否适合立即进入本轮推进

## Planning Table

| 条目 | 当前状态 | 规划判断 | 建议优先级 | 建议动作 |
| --- | --- | --- | --- | --- |
| [02-app-server-event-stream-lag-session-failure.md](./02-app-server-event-stream-lag-session-failure.md) | 调查单，Deferred | 还未锁定日志源和责任层，不适合直接进入修复 | P1-调查 | 先补 tracing、定位日志源，再决定归属模块 |
| [07-a2a-agent-failure-taxonomy-and-refactor.md](./07-a2a-agent-failure-taxonomy-and-refactor.md) | 架构方案 | 价值高，但范围偏大，不建议和紧急缺陷混批推进 | P2-架构 | 拆成更小的 failure normalization 子任务后再排期 |
| [08-shared-ask-context-concurrency-risk.md](./08-shared-ask-context-concurrency-risk.md) | 高风险缺陷分析，方案已部分收敛 | 已达到缺陷级别，建议尽快进入实现拆分 | P0 | 先落续聊 `sessionId` 锁方案，再补首轮 `contextId/contextAlias` 方案 |
| [09-file-browser-image-preview.md](./09-file-browser-image-preview.md) | 功能方案 | 体验增强项，依赖较少，适合独立小批次交付 | P2-功能 | 可与 PDF 预览合并评估，也可单独快速实现 |
| [10-file-browser-pdf-preview-plan.md](./10-file-browser-pdf-preview-plan.md) | 功能方案 | 复杂度高于图片预览，建议在图片预览后推进 | P2-功能 | 先定技术选型和首版能力边界 |
| [11-session-scroll-to-bottom-plan.md](./11-session-scroll-to-bottom-plan.md) | **Code Verified** (2026-04-07)，待人工体验验收 | 代码审查通过，5 项实现检查点全部确认，仅差人工 UX 体验验收 | P0-验收 | 人工体验验收（上滑按钮、流式跟随、窄 Pane 退化） |
| [11-session-scroll-to-bottom-report.md](./11-session-scroll-to-bottom-report.md) | 开发报告 | 支撑 `11` 验收，不单独排期 | 支撑 | 与 `11 plan` 一起使用 |
| [12-codex-completed-task-cancel-a2a-resolution-failure.md](./12-codex-completed-task-cancel-a2a-resolution-failure.md) | 分析单 | 需要判断是否已被后续修复覆盖，暂不宜直接并行开工 | P1-分析 | 先核对与 `13`、`1.0.0` 取消链路修复的边界差异 |
| [13-cancel-task-mysql-deadlock-fix.md](./13-cancel-task-mysql-deadlock-fix.md) | **Code & Tests Verified** (2026-04-07)，待真实环境手验 | 代码复核 + 单元测试全部通过（62 tests, 0 failures），仅差真实环境回归 | P0-验收 | 真实环境验证”创建任务后立即取消不再 500” |
| [14-shared-ask-context-alias-ttl-unique-conflict.md](./14-shared-ask-context-alias-ttl-unique-conflict.md) | **Code & Tests Verified** (2026-04-07)，待真实环境手验 | TTL 移除已复核确认，SPI/Impl/Repository 全链路干净，测试通过，仅差真实 sharingKey 回归 | P0-验收 | 真实 `sharingKey + contextAlias` 回归跨天复用链路 |

## Suggested Batches

### Batch A: 先闭环已接近完成的事项

- `11` Session Scroll To Bottom
- `13` Cancel Task MySQL Deadlock Fix
- `14` Shared Ask Context Alias TTL Unique Conflict

理由：

- 这三项都已经有实现或修复结果
- 当前主要缺的是人工验收或最终回写
- 闭环成本最低，最容易快速转成“已完成”

### Batch B: 处理高优先级缺陷与责任边界

- `08` Shared Ask Context Concurrency Risk
- `12` Codex Completed Task Cancel A2A Resolution Failure
- `02` App Server Event Stream Lag Causes Session Failure

理由：

- 都属于真实缺陷或高风险问题
- 但当前仍有不同程度的定位或边界问题
- 需要先做责任层收敛，再决定具体修复排期

### Batch C: 功能增强与中期改造

- `09` File Browser Image Preview
- `10` File Browser PDF Preview Plan
- `07` A2A Agent Failure Taxonomy And Refactor

理由：

- `09/10` 是体验增强，不阻断当前主流程
- `07` 是结构性改造，适合在紧急缺陷收敛后推进

## Immediate Recommendation

如果要开始推进 `1.0.1-SNAPSHOT`，建议顺序如下：

1. 先验收闭环 `11/13/14`
2. 然后启动 `08`
3. 再判断 `12` 是否需要单独立项，还是已被其他取消链路修复覆盖
4. 最后再排 `09/10/07`

## Notes

- 当前 `README.md` 之前未列出 `11-report`、`13`、`14`，本次已补齐索引。
- `11` 与 `13/14` 更像“待验收收尾项”，不应继续长期滞留在纯规划状态。
- `02` 与 `12` 都存在“先判断责任边界，再决定是否编码”的前置条件。
