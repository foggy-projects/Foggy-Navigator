# 1.1.0-SNAPSHOT

下个版本跟踪目录，用于存放 1.1.0-SNAPSHOT 阶段的需求、设计、调查与执行项。

本版本当前重点方向：

1. 业务型 Worker 轻量化
2. LangGraph Skill Runtime
3. Skill 生命周期、审批与上下文隔离

## 首版约束

1. **首版不引入外部业务工具**：Skill 子图中的业务工具一律使用 Mock/Stub 实现，仅验证 Runtime 链路正确性
2. **外部工具延迟到集成阶段**：等真实业务系统对接时再设计工具适配层
3. **Java/Python 分工明确**：每个 Phase 的交付项按 Java 侧 / Python 侧分别标注归属
4. **质量门前置**：每个 Phase 测试必须运行通过 → `foggy-implementation-quality-gate` → 下一 Phase

## 条目列表

- [31-langgraph-biz-worker-skill-runtime-design.md](./31-langgraph-biz-worker-skill-runtime-design.md) - 架构设计 / LangGraph Biz Worker + Skill Runtime
- [32-langgraph-biz-worker-tms-sequence-and-api-contract.md](./32-langgraph-biz-worker-tms-sequence-and-api-contract.md) - 时序图与 Java/Python API 契约
- [33-langgraph-biz-worker-implementation-plan.md](./33-langgraph-biz-worker-implementation-plan.md) - 实现计划 / 模块拆分、阶段交付、测试基线
