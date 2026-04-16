# LangGraph Biz Worker TMS 时序与 API 契约

## 文档作用

- doc_type: implementation-detail
- intended_for: root-controller + java-backend + python-worker + reviewer
- purpose: 细化 LangGraph Biz Worker 在 TMS 场景下的调用时序、Frame 编排、Java/Python 模块边界与 API 契约

## 1. 文档范围

本文件是 [31-langgraph-biz-worker-skill-runtime-design.md](./31-langgraph-biz-worker-skill-runtime-design.md) 的执行补充，聚焦两件事：

1. `TMS异常分诊 -> 订单取证 -> 规则核验` 的完整时序
2. Java/Python 两侧的接口与模块骨架

## 2. TMS 场景样例

### 2.1 根任务目标

用户请求：

```text
请分析订单 123 为什么延误，并给出处置建议。
```

Root Graph 期望输出：

```json
{
  "order_id": "123",
  "diagnosis": "vehicle_delay",
  "recommended_action": "manual_dispatch",
  "requires_approval": true,
  "summary": "订单延误由车辆异常导致，建议人工改派"
}
```

### 2.2 Skill 拆分

建议拆成三个 Skill：

1. `tms_exception_triage`
2. `tms_order_evidence_collect`
3. `tms_rule_check`

语义：

1. `tms_exception_triage`：负责任务主诊断与最终建议
2. `tms_order_evidence_collect`：负责取证，不做最终结论
3. `tms_rule_check`：负责规则核验，不做最终结论

## 3. 根任务到子 Skill 的时序

### 3.1 主时序

```text
User
  -> NavigatorProxyController
  -> TaskDispatchFacade
  -> LangGraphTaskService.createTaskDirect()
  -> LangGraph Worker /api/v1/query
  -> Root Graph
  -> invoke_skill("tms_exception_triage")
  -> create Frame A
  -> run Skill A
    -> invoke_skill("tms_order_evidence_collect")
    -> create Frame B
    -> run Skill B
    -> submit_skill_result(B)
    -> runtime complete B
    -> close B
    -> result B back to A private state
    -> invoke_skill("tms_rule_check")
    -> create Frame C
    -> run Skill C
    -> submit_skill_result(C)
    -> runtime complete C
    -> close C
    -> result C back to A private state
  -> Skill A aggregate B/C results
  -> request_skill_approval() or submit_skill_result(A)
  -> runtime complete A
  -> promote A result to parent
  -> close A
  -> Root Graph finalize task result
  -> SSE result back to Java
  -> Java relay back to UI/API caller
```

### 3.2 Frame 关闭边界

关键关闭时机：

1. Frame B 完成后立刻关闭，只把 B 的产物保留在 A 的私有状态
2. Frame C 完成后立刻关闭，只把 C 的产物保留在 A 的私有状态
3. Frame A 完成后关闭，只把 A 的最终结果提升到 ParentTaskState

因此：

- Root 不直接接收 B/C 的 scratchpad
- Root 仅接收 A 的聚合结果

## 4. Skill 完成协议细化

### 4.1 `submit_skill_result`

Python Worker 内置系统工具，用于提交当前 Skill 的最终结果。

必填字段：`skill_id`、`summary`、`structured_output`
可选字段：`artifact_refs`、`evidence_refs`

语义：提交成功后 Runtime 将校验 Output Contract 并完成该 Frame。

### 4.2 `request_skill_approval`

Python Worker 内置系统工具，当 Skill 已形成建议但需人工确认时调用。

必填字段：`skill_id`、`approval_type`、`summary`、`payload`

语义：调用后 Frame 状态转为 `AWAITING_APPROVAL`，等待外部审批结果。

> 两个工具的完整 JSON Schema 由 Python 侧实现阶段定义，参见 Doc 31 §9.2/§9.3 的字段语义说明。

### 4.3 Runtime 响应语义

- 成功：返回 `ok=true` + `frame_id` + `status=COMPLETED`
- 失败：返回 `ok=false` + `error` 错误码 + `details` 校验失败明细列表

具体响应结构由 Python 侧实现阶段定义。

## 5. Java 侧 API 契约

### 5.1 任务创建

沿用统一任务入口，不新增上游独占协议：

`POST /api/v1/tasks`

关键字段：

- `providerType = langgraph-biz-worker`
- `modelConfigId`
- `prompt`
- `directoryId`
- `sessionId`

### 5.2 Worker 私有调用（Java → Python）

`POST /api/v1/query`

关键请求字段：`taskId`、`sessionId`、`userId`、`prompt`、`model`、`modelConfigId`、`context`（可选业务上下文）

返回方式：SSE

SSE 事件类型（建议）：`system`、`assistant_text`、`tool_use`、`tool_result`、`skill_frame_open`、`skill_frame_close`、`approval_request`、`result`、`error`

### 5.3 审批与恢复

审批：`POST /api/v1/langgraph-tasks/{taskId}/approval` — 关键字段：`approved`、`comment`

恢复：`POST /api/v1/langgraph-tasks/{taskId}/resume` — 关键字段：`frameId`、`resumePayload`

> 注意：新增的 `/api/v1/langgraph-tasks/**` 端点需同步更新 `SecurityConfig.java` 权限配置。

具体请求/响应结构由 Java 侧实现阶段定义。

## 6. Python Worker API 契约

### 6.1 Query

`POST /api/v1/query`

职责：

1. 创建 RootTaskContext
2. 启动 Root Graph
3. 将 Skill Frame 事件映射为 SSE
4. 输出最终结果

### 6.2 Approval

`POST /api/v1/approval`

职责：

1. 找到等待审批的 Frame
2. 写入审批结果
3. 恢复被中断的 Graph

### 6.3 Resume

`POST /api/v1/resume`

职责：

1. 按 `taskId/frameId` 恢复执行态
2. 继续后续节点

### 6.4 Health

`GET /health`

返回：

1. Worker 状态
2. Skill Registry 状态
3. Store/Checkpointer 状态

## 7. 模块归属

- **Java 侧**：`addons/langgraph-biz-worker/` — 具体包结构和类设计由实现阶段决定，参见 Doc 31 §5.1 职责域
- **Python 侧**：`tools/langgraph-biz-worker/` — 具体目录结构由实现阶段决定，参见 Doc 31 §5.2 职责域

> 详细的文件骨架不在规划层定义，由开工阶段的子 agent 根据职责域自行设计。

## 9. 首版实现顺序

建议按以下顺序推进（标注侧归属）：

1. **[Java]** `providerType` 接入 + SecurityConfig 权限配置
2. **[Python]** Worker 基础 Query/SSE + Health
3. **[Java+Python]** 联调最小任务链路
4. **[Python]** `SkillManifest` + `SkillFrameState`
5. **[Python]** `submit_skill_result` 系统工具
6. **[Python]** `complete_frame()` + `close_frame()`
7. **[Python]** 单 Skill 示例（使用 Mock 业务工具）
8. **[Python]** 子 Skill 嵌套调用
9. **[Java+Python]** 审批与恢复

> 首版不引入真实外部业务工具，所有业务工具使用 Mock/Stub 实现。外部工具适配层在业务系统集成阶段再开发。

## 10. 验收基线

首版最小可验收标准：

1. 可通过统一任务入口创建 `langgraph-biz-worker` 任务
2. 单个 Skill 能正常创建 Frame、提交结果、关闭 Frame（业务工具使用 Mock 实现）
3. Frame 关闭后父上下文中不再保留 Skill 私有消息
4. 嵌套 Skill 返回后 child frame 会立即关闭
5. `submit_skill_result` 不合格时能有限重试
6. 审批型 Skill 能挂起并恢复
7. Java 侧和 Python 侧的单元测试 + 集成测试必须全部运行通过
8. 通过 `foggy-implementation-quality-gate` 后方可进入验收
