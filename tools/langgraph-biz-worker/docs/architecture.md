# LangGraph Biz Worker — 架构设计

## 文档作用

- doc_type: architecture-overview
- intended_for: 开发者、reviewer、Java 侧对接方
- purpose: 说明 Biz Worker 的核心概念、运行时机制、已实现能力和待实现规划

## 1. 定位

LangGraph Biz Worker 是 Foggy Navigator 体系中的**业务型执行后端**（`providerType = langgraph-biz-worker`）。

与编程型 Worker 的区别：

| 维度 | 编程型 Worker | 业务型 Worker |
|------|--------------|--------------|
| 代表 | claude-worker, codex-worker | langgraph-biz-worker |
| 能力边界 | shell, 文件系统, Git, SDK | 受控工具调用, 结构化结果, 审批 |
| 上下文策略 | 长会话 | Skill 独立 Frame, 按需加载 |
| 完成方式 | 自由文本输出 | 显式交卷协议 (submit_skill_result) |
| 状态控制 | 模型自主 | Runtime 统一提交 |

## 2. 核心概念

### 2.1 Skill

一个可按需加载的业务执行单元，通过 YAML Manifest 定义：

```yaml
id: exception_triage
name: 异常分诊
input_schema: { ... }
output_schema: { ... }      # 结果必须满足的结构
allowed_tools: [...]         # 可调用的工具白名单
promote_to_parent: [...]     # 允许上浮到父上下文的字段
business_rules: { ... }      # 额外业务校验规则
```

关键原则：
- Skill 的正文、Few-shot、私有工作上下文**不允许长期驻留父任务上下文**
- 只有 `result_summary`、`structured_output`、`artifact_refs`、`evidence_refs` 可以上浮

### 2.2 Frame

Skill 的一次执行实例，拥有独立的私有状态空间。

生命周期状态机（7 状态）：

```
CREATED → RUNNING → COMPLETED → (closed)
                  → WAITING_CHILD → RUNNING
                  → AWAITING_APPROVAL → RUNNING
                  → FAILED
                  → CANCELLED
```

终态：`COMPLETED`、`FAILED`、`CANCELLED`（不可逆）。

### 2.3 SkillRuntime

所有 Frame 状态变更的**唯一入口**。模型和图节点不能直接写最终状态。

核心 API：

| 方法 | 说明 |
|------|------|
| `invoke_skill()` | 创建 Frame → RUNNING |
| `mark_waiting_child()` | RUNNING → WAITING_CHILD |
| `resume_from_child()` | WAITING_CHILD → RUNNING |
| `submit_result()` | 校验 → COMPLETED 或返回错误 |
| `fail_frame()` | → FAILED |
| `cancel_frame()` | → CANCELLED |
| `close_frame()` | 销毁私有上下文, 返回 promoted result |
| `write_child_result_to_parent()` | 子结果写入父 Frame 私有状态 |

### 2.4 完成协议

Skill 完成必须通过 `submit_skill_result`，不允许自然语言宣布完成。

流程：

```
模型调用 submit_skill_result(summary, structured_output, ...)
  → Runtime 加载 Skill Manifest
  → 三层校验：
    1. schema_valid      — structured_output 满足 output_schema
    2. business_complete  — 满足 business_rules（如 min_evidence_count）
    3. state_consistent   — 无 pending tool calls / child frames / approvals
  → 全部通过 → COMPLETED
  → 失败 → 返回错误, Frame 保持 RUNNING, 允许有限重试
  → 超过重试上限 → FAILED
```

### 2.5 上下文隔离

Frame 关闭后：

- ✅ 保留：`result_summary`、`output`、`artifact_refs`、`evidence_refs`
- ❌ 销毁：`private_messages`、`private_working_state`、`tool_calls`

父任务**永远不直接消费**子 Frame 的 scratchpad。

## 3. Graph 架构

### 3.1 Root Graph

```
entry → route_skill → [有匹配] → run_skill → close_skill_frame → END
                    → [无匹配] → END (fallback response)
```

使用 LangGraph `StateGraph` + `Annotated[list, operator.add]` 实现事件累加。

### 3.2 Skill 调用栈

以异常分诊为例：

```
Root Task
  └── Frame A: exception_triage
        ├── Frame B: order_evidence_collect  → COMPLETED → close → 结果写入 A
        ├── Frame C: rule_check              → COMPLETED → close → 结果写入 A
        └── A 聚合 B+C 结果 → submit_result → COMPLETED → close → 结果上浮到 Root
```

Root 只看到 A 的聚合结果，不看到 B/C 的 scratchpad。

## 4. 已实现（Phase 1~5）

| Phase | 内容 | 状态 |
|-------|------|------|
| 1 | FastAPI 骨架, /health, /query SSE, RootGraph | ✅ |
| 2 | SkillRuntime, FrameStore, SkillRegistry, OutputContract, 完成协议 | ✅ |
| 3 | exception_triage 示例 Skill, Mock 业务工具 | ✅ |
| 4 | order_evidence_collect 子 Skill, 父子调用 | ✅ |
| 5 | rule_check 第二子 Skill, 多子聚合 | ✅ |

测试覆盖：103 个测试（单元 + 集成 + HTTP E2E），全部通过。

## 5. 待实现规划

### Phase 6: 审批与恢复（Java 侧联调）

- `request_skill_approval` 系统工具
- Frame `AWAITING_APPROVAL` 状态挂起
- `POST /api/v1/approval` + `POST /api/v1/resume` 端点
- 审批后恢复执行

### Java 侧对接（Phase 1 Java）

- `addons/langgraph-biz-worker/` Maven 模块
- `A2aAgentProvider` + `TaskQueryProvider` 适配
- `WorkerClient` 调用 Python Worker HTTP API
- `SSE Relay` 转发事件到前端
- `SecurityConfig` 权限配置

### LLM 集成

- 引入 `langchain-anthropic`，Skill 子图改为 LLM 驱动
- 对接 `tools/mock-llm-service/` 做 E2E 测试
- LLM 决策调用工具 → submit_skill_result 的完整链路

### 后续演进

- FrameStore 持久化（SQLite / 共享数据库）
- Skill 注册中心（动态注册 / 前端配置台）
- 真实业务系统工具适配层
- 多 Frame 并发调度优化

## 6. 首版约束

1. **首版不引入外部业务工具** — 所有 Skill 使用 Mock/Stub 工具
2. **首版无 LLM 调用** — Skill 子图为程序化逻辑，模拟 LLM 行为
3. **FrameStore 为内存实现** — 不持久化，进程重启丢失
4. **Java 侧尚未对接** — Python Worker 可独立启动和测试

## 7. 上游设计文档

详细设计方案见版本跟踪目录：

- [31-架构设计](../../docs/version-tracker/1.1.0-SNAPSHOT/31-langgraph-biz-worker-skill-runtime-design.md)
- [32-时序与 API 契约](../../docs/version-tracker/1.1.0-SNAPSHOT/32-langgraph-biz-worker-tms-sequence-and-api-contract.md)
- [33-实现计划](../../docs/version-tracker/1.1.0-SNAPSHOT/33-langgraph-biz-worker-implementation-plan.md)
