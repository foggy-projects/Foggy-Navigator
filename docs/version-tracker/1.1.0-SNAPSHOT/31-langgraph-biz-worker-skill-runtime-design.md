# LangGraph Biz Worker Skill Runtime 设计

## 文档作用

- doc_type: requirement + implementation-plan
- intended_for: root-controller + worker-runtime + reviewer
- purpose: 定义基于 LangGraph 的业务型 Worker、Skill Runtime、Frame 生命周期、完成协议、上下文隔离与嵌套 Skill 编排方案

## 1. 背景

当前系统已经接入 `claude-worker`、`codex-worker` 两类编程型 Worker，它们适合代码任务，但不适合作为 TMS 等业务系统的默认执行后端。

现状特点：

1. 现有统一任务分发已经按 `providerType`、`TaskQueryProvider`、`A2aAgentProvider` 解耦
2. 编程型 Worker 的能力边界偏向 shell、文件系统、Git、SDK/CLI 编排
3. 业务系统更需要受控工具调用、结构化结果、审批、审计、可恢复执行
4. 后续业务能力会围绕 `skill` 持续扩展，因此需要先定义稳定的 Skill Runtime

结论：

- 不应把编程型 Worker 直接裁剪后复用到业务系统
- 应新增一类 `langgraph-biz-worker`，定位为“受控业务执行后端”
- Skill 不能作为永久常驻 Prompt，而应作为按需加载、可关闭回收的执行单元

## 2. 目标

本设计交付以下目标：

1. 定义 `langgraph-biz-worker` 的角色与能力边界
2. 定义 `skill` 的运行时模型，不依赖长期常驻上下文
3. 定义 `frame` 生命周期、状态机、完成协议与关闭规则
4. 定义嵌套 Skill 的调用栈与结果提升规则
5. 定义 AI 与 Runtime 的职责边界，避免由模型直接控制状态提交
6. 定义 LangGraph 落地方式，包括主图、子图、持久化与审批中断

## 3. 非目标

以下内容不在本设计首版范围：

1. 前端 Skill 配置台完整交互
2. 多租户 Skill 市场与发布系统
3. Skill 版本回滚 UI
4. 多 Frame 并发调度优化
5. 通用跨系统 BPM 编排器替代

## 4. 核心原则

### 4.1 Skill 不是长期记忆

Skill 的正文、Few-shot、私有工作上下文不允许长期驻留在父任务上下文。

仅允许以下内容进入父上下文：

1. `result_summary`
2. `structured_output`
3. `artifact_refs`
4. `evidence_refs`
5. `approval_request`

### 4.2 Skill 完成不由模型直接提交

模型只能提出“候选完成结果”，不能直接写 `COMPLETED`。

只有 Runtime 在完成统一校验后，才允许提交：

1. `frame.status = COMPLETED`
2. 结果提升到父上下文
3. 关闭 Frame

### 4.3 一次 Frame 只允许一个 Active Skill

单个 Frame 内仅允许一个 Skill 合同生效。

因此：

1. 单个 Frame 中不允许同时加载多个 Skill Prompt
2. 同一任务内允许多个 Skill 被调用，但必须通过多个独立 Frame 表达
3. 多个 Skill 之间通过“结果传递”交互，而不是共享同一块私有上下文

### 4.4 Skill 完成依赖显式交卷协议

Skill 执行完成时，模型必须调用专门的结果提交工具：

- `submit_skill_result`

不允许通过自然语言直接宣布完成。

## 5. 总体架构

### 5.1 Java 侧

新增 `providerType = langgraph-biz-worker`，挂入现有统一分发链路：

1. `TaskDispatchFacade`
2. `TaskQueryProvider`
3. `A2aAgentProvider`
4. `WorkerManagementFacade`

建议模块：

- `addons/langgraph-biz-worker`

核心职责域（具体类与包结构由实现阶段决定）：

1. **Controller** — Worker 管理 + 任务审批/恢复入口
2. **Service** — 任务创建/持久化、SSE Relay、审批
3. **Client** — 调用 Python Worker HTTP API
4. **Adapter** — `TaskQueryProvider` + `A2aAgentProvider` 适配

### 5.2 Python 侧

建议新增模块：`tools/langgraph-biz-worker`

核心职责域（具体文件结构由实现阶段决定）：

1. **routes** — HTTP 入口（query / approval / resume / health）
2. **runtime** — Frame 生命周期、Skill Registry、Output Contract 校验
3. **graphs** — Root Graph + Skill Subgraph
4. **tools** — 系统工具（`submit_skill_result` 等）+ 业务工具适配层

### 5.3 执行流

```text
上游系统 / NavigatorProxyController
  -> TaskDispatchFacade
  -> providerType = langgraph-biz-worker
  -> LangGraphTaskService.createTaskDirect()
  -> LangGraph Worker /api/v1/query
  -> Root Graph
  -> invoke_skill(...)
  -> create frame
  -> run skill subgraph in private frame context
  -> submit_skill_result
  -> runtime validate + commit
  -> promote result to parent context
  -> close frame
```

## 6. Skill 定义模型

Skill 建议使用结构化 Manifest，而不是纯文本 `SKILL.md` 作为生产协议。

建议字段：

```yaml
id: tms_exception_triage
name: TMS异常分诊
description: 分析订单异常并给出处置建议
input_schema: TmsExceptionInput
output_schema: TmsExceptionOutput
allowed_tools:
  - get_order
  - get_vehicle_status
  - search_incidents
approval_tools:
  - create_manual_ticket
prompt_ref: skill://tms/exception_triage/prompt
assets:
  - skill://tms/exception_triage/playbook.md
subgraph: tms_exception_triage_graph
promote_to_parent:
  - result.summary
  - result.structured_output
  - result.evidence_refs
  - result.approval_request
business_rules:
  require_evidence: true
  min_evidence_count: 1
```

说明：

1. `description` 仅用于路由与候选匹配
2. `prompt_ref/assets` 按需加载，不允许长期驻留父上下文
3. `promote_to_parent` 决定哪些字段允许上浮
4. `business_rules` 用于补足仅做 Schema 校验的不充分问题

## 7. Frame 模型

### 7.1 ParentTaskState

父任务状态仅保留公共信息：

```json
{
  "task_id": "task_xxx",
  "user_goal": "分析订单异常并给出处置建议",
  "public_messages": [],
  "active_frame_id": "frm_xxx",
  "skill_results": [],
  "artifact_index": [],
  "decision_log": [],
  "final_result": null
}
```

### 7.2 SkillFrameState

Skill 私有执行状态：

```json
{
  "frame_id": "frm_xxx",
  "task_id": "task_xxx",
  "skill_id": "tms_exception_triage",
  "parent_frame_id": null,
  "status": "RUNNING",
  "input": {},
  "private_messages": [],
  "private_working_state": {},
  "tool_calls": [],
  "child_frame_ids": [],
  "output": null,
  "result_summary": null,
  "artifact_refs": [],
  "evidence_refs": [],
  "approval_request": null,
  "started_at": "",
  "ended_at": ""
}
```

约束：

1. `private_messages` 不允许直接进入父上下文
2. `private_working_state` 仅在当前 Frame 可见
3. `output/result_summary/artifact_refs/evidence_refs` 才可能被提升

## 8. Frame 生命周期

### 8.1 状态定义

建议状态机：

- `CREATED`
- `RUNNING`
- `WAITING_CHILD`
- `AWAITING_APPROVAL`
- `COMPLETED`
- `FAILED`
- `CANCELLED`

说明：

1. `COMPLETED`、`FAILED`、`CANCELLED` 为终态
2. `AWAITING_APPROVAL` 为暂停态，不允许关闭 Frame
3. `WAITING_CHILD` 表示当前 Skill 已调用子 Skill，等待子结果回写

### 8.2 状态流转

```text
CREATED
  -> RUNNING

RUNNING
  -> WAITING_CHILD
  -> AWAITING_APPROVAL
  -> COMPLETED
  -> FAILED
  -> CANCELLED

WAITING_CHILD
  -> RUNNING
  -> FAILED
  -> CANCELLED

AWAITING_APPROVAL
  -> RUNNING
  -> FAILED
  -> CANCELLED
```

### 8.3 状态写入责任

状态变更只能由 Runtime 写入，不允许由模型、单个工具或普通图节点直接写最终状态。

推荐单点入口：

1. `invoke_skill()`
2. `mark_waiting_child()`
3. `mark_awaiting_approval()`
4. `complete_frame()`
5. `fail_frame()`
6. `cancel_frame()`
7. `close_frame()`

其中：

- `COMPLETED` 只能通过 `complete_frame()` 提交

## 9. Skill 完成协议

### 9.1 完成交卷工具

每个 Skill 进入 Frame 后，Runtime 必须向模型注入统一约束：

1. Skill 完成时必须调用 `submit_skill_result`
2. 不允许用自然语言直接宣布完成
3. 如果仍需更多信息，则继续调用业务工具
4. 如果需要人工确认，则调用 `request_skill_approval`

### 9.2 `submit_skill_result`

关键字段：

- `skill_id` — 当前 Skill 标识
- `summary` — 人类可读的结果摘要
- `structured_output` — 结构化结果，需满足 Skill Manifest 中定义的 `output_schema`
- `artifact_refs`（可选）— 产物引用列表
- `evidence_refs`（可选）— 证据引用列表

具体 JSON Schema 由实现阶段根据 Skill Manifest 的 `output_schema` 定义。

### 9.3 `request_skill_approval`

关键字段：

- `skill_id` — 当前 Skill 标识
- `approval_type` — 审批类型标识
- `summary` — 审批请求的人类可读描述
- `payload` — 审批所需的业务数据

具体 JSON Schema 由实现阶段定义。

## 10. Skill 完成判定

### 10.1 `subgraph 到达 END` 的界定

`subgraph 到达 END` 仅表示控制流结束，不等于业务完成。

只有满足以下条件，才允许视为“具备提交完成的前提”：

1. 子图已到达 `END`
2. 当前 Frame 没有待执行工具调用
3. 当前 Frame 没有待完成子 Frame
4. 当前 Frame 没有审批挂起

### 10.2 `output_schema 满足` 的界定

不能只做 JSON Schema 校验，应使用 `output contract`：

1. `schema_valid`
2. `business_complete`
3. `state_consistent`

例如 `TmsExceptionOutput` 需要同时满足：

1. `classification` 非空且属于枚举
2. `recommended_action` 非空且属于枚举
3. `confidence` 在 `0~1`
4. `evidence_refs` 数量满足规则
5. 若 `requires_approval = true`，则必须存在 `approval_request`

### 10.3 完成提交函数

只有 Runtime 才允许写 `COMPLETED`。

建议判定函数：

```text
can_complete_frame(frame) =
  frame.graph_ended
  && frame.output != null
  && validate_output_contract(frame).ok
  && no_pending_tool_calls(frame)
  && no_pending_child_frames(frame)
  && no_pending_approval(frame)
```

建议完成提交函数：

```text
complete_frame(frame_id, candidate_output)
  -> load frame
  -> bind output / summary / refs
  -> validate output contract
  -> check pending dependencies
  -> frame.status = COMPLETED
  -> persist
  -> promote_result_to_parent()
  -> close_frame()
```

## 11. 模型与 Runtime 的职责边界

### 11.1 模型负责

1. 判断是否需要 Skill
2. 调用业务工具
3. 调用子 Skill
4. 在准备完成时调用 `submit_skill_result`
5. 在需要审批时调用 `request_skill_approval`

### 11.2 Runtime 负责

1. 创建/销毁 Frame
2. 注入 Skill 合同提示词
3. 隔离 Skill 私有上下文
4. 校验 `submit_skill_result`
5. 写入最终状态
6. 回写父上下文
7. 关闭 Frame
8. 超限重试与失败处理

### 11.3 持久化层负责

1. 保存 Frame 状态
2. 保存结果产物
3. 保存审批记录
4. 保存调试/审计痕迹

## 12. Skill 嵌套与调用栈

### 12.1 调用栈语义

Skill 套 Skill 时，使用调用栈，不共享大上下文。

示意：

```text
root task
  -> Frame A: 异常分诊
    -> Frame B: 订单取证
    -> Frame C: 规则核验
  -> root synthesis
```

规则：

1. B/C 的结果先回写到 A 的私有状态
2. A 完成后，A 的聚合结果再回写给 root
3. child frame 关闭后立即销毁其私有上下文
4. root 不直接消费 B/C 的 scratchpad

### 12.2 子 Skill 返回

子 Skill 完成后：

1. `child_frame.status = COMPLETED`
2. `child_result` 回写到 `parent_frame.private_working_state`
3. `close_frame(child_frame)`
4. `parent_frame.status = RUNNING`

## 13. 上下文管理规则

### 13.1 常驻上下文

允许长期保留：

1. 用户目标
2. 任务级公共消息
3. Skill 结果摘要
4. 结构化产物引用

### 13.2 按需加载上下文

只在 Frame 内临时存在：

1. Skill Prompt 正文
2. Few-shot 示例
3. Playbook 资产
4. 私有中间推理
5. 原始工具结果

### 13.3 关闭后的处理

Frame 关闭后：

1. 从运行上下文中移除 `private_messages`
2. 从运行上下文中移除 `private_working_state`
3. 保留 `result_summary/output/artifact_refs` 供父任务继续使用
4. 失败场景可将私有状态转储到调试存储，但不得继续注入模型

## 14. 异常与重试

### 14.1 提交结果不合格

若模型调用 `submit_skill_result`，但参数不满足要求：

1. Runtime 返回结构化错误
2. 模型允许有限次数修正
3. 超过阈值后，Frame 转 `FAILED`

建议错误结构：

```json
{
  "error": "INVALID_SKILL_RESULT",
  "details": [
    "structured_output.recommended_action is required",
    "evidence_refs must contain at least one item"
  ]
}
```

### 14.2 空轮次

若模型既没有继续调用业务工具，也没有调用：

1. `submit_skill_result`
2. `request_skill_approval`
3. `invoke_skill`

则视为未按协议收口。

处理方式：

1. Runtime 返回强约束反馈，要求“继续行动”或“提交结果”二选一
2. 达到重试上限后转 `FAILED`

## 15. LangGraph 落地方式

### 15.1 Root Graph

主图建议节点：

1. `route_skill`
2. `open_frame`
3. `load_skill_assets`
4. `run_skill_subgraph`
5. `promote_artifacts`
6. `close_frame`

### 15.2 Skill Subgraph

子图建议节点：

1. `prepare_skill_context`
2. `decide_next_step`
3. `tool_executor`
4. `child_skill_invoker`
5. `approval_node`
6. `finalize_candidate`

### 15.3 显式控制信号

建议 `decide_next_step` 只允许输出：

1. `CALL_TOOL`
2. `CALL_CHILD_SKILL`
3. `REQUEST_APPROVAL`
4. `RETURN_FINAL`

这样 Runtime 不需要猜测模型是否完成，只需要识别控制信号。

## 16. 首版落地建议

### 16.1 首版约束

1. **首版不引入外部业务工具**：Skill 子图中的业务工具（如 TMS 查询类工具）一律使用 Mock/Stub 实现，仅验证 Runtime 链路正确性
2. **外部工具延迟到集成阶段**：等真实业务系统对接时再设计工具适配层

### 16.2 首版分工原则

- **Java 侧**：负责 providerType 接入、统一任务分发、会话绑定、SSE Relay、审批/恢复 API、SecurityConfig 权限配置
- **Python 侧**：负责 Root Graph / Skill Subgraph 执行、Frame 生命周期、Skill Registry、Output Contract 校验、Mock 业务工具
- 每个阶段的交付项必须按 Java / Python 侧分别标注归属

### 16.3 先做的

1. `providerType = langgraph-biz-worker`
2. `SkillManifest`
3. `SkillFrameState`
4. `submit_skill_result`
5. `request_skill_approval`
6. `complete_frame()/close_frame()`
7. 单层 Skill 调用
8. 单个示例 Skill（使用 Mock 工具）

### 16.4 第二阶段

1. 子 Skill 调用栈
2. 审批恢复
3. 调试存储
4. Skill 注册中心
5. 前端 Skill 配置台
6. 真实业务系统工具适配

## 17. 结论

本方案的核心不是“删除 Skill 上下文”，而是：

1. 从一开始就把 Skill 运行在独立 Frame 内
2. Skill 私有上下文不进入父任务公共上下文
3. 模型必须通过 `submit_skill_result` 显式交卷
4. Runtime 完成校验后才提交 `COMPLETED`
5. Frame 关闭后只保留结果，不保留私有执行上下文

这样才能支持：

1. 业务场景下的高可控 Skill 扩展
2. 嵌套 Skill 调用
3. 审批与恢复
4. 长任务中的上下文稳定性
