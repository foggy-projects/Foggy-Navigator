# 1.1.0-SNAPSHOT

下个版本跟踪目录，用于存放 1.1.0-SNAPSHOT 阶段的需求、设计、调查与执行项。

## Release Signoff

- release_status: signed-off
- release_decision: accepted-with-risks
- signed_off_at: 2026-04-28
- acceptance_record: [acceptance/version-signoff.md](./acceptance/version-signoff.md)
- blocking_items: none
- follow_up_version: 1.1.1-SNAPSHOT

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
  - 已补充：Skill 定义归属、账号私有目录、Agent Skills 开放标准、`SKILL.md` 资源组织与 Worker 管理边界
- [32-langgraph-biz-worker-tms-sequence-and-api-contract.md](./32-langgraph-biz-worker-tms-sequence-and-api-contract.md) - 时序图与 Java/Python API 契约
- [33-langgraph-biz-worker-implementation-plan.md](./33-langgraph-biz-worker-implementation-plan.md) - 实现计划 / 模块拆分、阶段交付、测试基线
- [34-skill-registry-design.md](./34-skill-registry-design.md) - Skill 注册中心设计 / 公共 Skill GitLab 同步、账号 Skill 加载、文件写入权限边界
  - 当前状态：1.1.0 范围已收口；账号级 query 加载已完成，写文件权限已延期到 1.1.1
- [35-agent-default-model-fix.md](./35-agent-default-model-fix.md) - Agent 默认模型修复与即时响应补全
  - 当前状态：功能已修复；session-module 定向单测已补跑通过
- [36-mock-llm-skill-test-support.md](./36-mock-llm-skill-test-support.md) - Mock/真实 LLM 支持 Skill tool-call loop 完整测试
  - 当前状态：已完成轻量 quality / coverage / acceptance 收口；真实 LLM 联调已覆盖 OpenAI-compatible Skill Agent 链路

## 当前收口状态

| 条目 | 状态 | 阻塞 1.1.0 封版 | 说明 |
|---|---|---|---|
| 31 | signed-off / accepted-with-risks | 否 | 主线 Skill Runtime 已验收；真实 LLM 补充验证见 36 |
| 34 | closed-with-deferral | 否 | Step 1/2 已完成并通过测试，Step 3 已移入 `1.1.1-SNAPSHOT/02-account-skill-write-file-permission.md` |
| 35 | fixed / verified | 否 | `TaskDispatchFacadeTest` 已补跑通过 |
| 36 | signed-off / accepted-with-followups | 否 | Mock + 真实 LLM Skill Agent 测试闭环已验收 |

## 后续版本承接

以下事项不阻塞 1.1.0，已进入 1.1.1 或后续版本：

1. `write_file` 创建账号私有 Skill 的路径权限与工具闭环：`1.1.1-SNAPSHOT/02-account-skill-write-file-permission.md`
2. Artifact 外部化与长参数上下文治理：`1.1.1-SNAPSHOT/01-biz-worker-artifact-externalization-design.md`
3. Anthropic tool_use 单独验证、真实业务工具注册表、完整 `SKILL.md` body 注入：后续 provider / 业务工具集成阶段处理
