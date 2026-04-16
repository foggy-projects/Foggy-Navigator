# 1.0.3-SNAPSHOT

上游接入首版落地跟踪目录，聚焦让上游系统能够真实接入平台 Agent，完成业务可用链路，并交付一个可运行的接入示例 Demo。

本版本当前重点方向：

1. 对外接入能力从“仅能发任务”提升到“可管理会话、可读取消息、可跟踪任务”
2. 明确 Open API、内部 Session API、SSE 能力边界，收敛为面向上游的正式契约
3. 交付一个最小可跑通的上游接入 Demo，验证真实业务可用性

## 条目列表

- [01-upstream-agent-integration-requirement.md](./01-upstream-agent-integration-requirement.md) - 版本主需求 / 上游接入目标、范围、验收标准、Demo 目标
- [02-upstream-agent-integration-current-state-analysis.md](./02-upstream-agent-integration-current-state-analysis.md) - 现状调研 / 当前接口能力、taskId 串联、消息落库与缺口分析
- [03-upstream-agent-integration-module-responsibility.md](./03-upstream-agent-integration-module-responsibility.md) - 模块职责 / session-module、claude-worker-agent、SDK、Demo 的边界划分
- [04-upstream-agent-integration-code-inventory.md](./04-upstream-agent-integration-code-inventory.md) - 代码触点 / 建议修改范围、读模型与 API 出口的主要落点
- [05-upstream-agent-integration-implementation-plan.md](./05-upstream-agent-integration-implementation-plan.md) - 实现计划 / 首版接口策略、阶段交付、测试与 Demo 路线
- [06-upstream-agent-integration-api-contract-draft.md](./06-upstream-agent-integration-api-contract-draft.md) - 接口合同草案 / 会话、消息、任务、增量消息轮询和 taskId 串联规则
- [07-upstream-agent-integration-sequence-and-flow.md](./07-upstream-agent-integration-sequence-and-flow.md) - Mermaid 图示 / 上游调用时序、增量轮询流程、平台内部 taskId 串联、Demo 主流程
- [08-upstream-agent-integration-final-target-and-task-breakdown.md](./08-upstream-agent-integration-final-target-and-task-breakdown.md) - 最终目标与任务拆分 / 拍板首版主键、接口路径、cursor 协议、Java Demo 和第一批开发任务
- [09-upstream-agent-integration-execution-prompt.md](./09-upstream-agent-integration-execution-prompt.md) - 执行提示 / 给执行 agent 的开工说明、边界、测试要求与回写要求
