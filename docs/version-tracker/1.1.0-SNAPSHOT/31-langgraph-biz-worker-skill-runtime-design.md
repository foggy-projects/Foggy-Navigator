# LangGraph Biz Worker Skill Runtime 设计

## 文档作用

- doc_type: requirement + implementation-plan
- intended_for: root-controller + worker-runtime + reviewer
- purpose: 定义基于 LangGraph 的业务型 Worker、Skill Runtime、Frame 生命周期、完成协议、上下文隔离与嵌套 Skill 编排方案

## 文档分区

- **§1-§5**：设计目标与架构约束（必须遵守）
- **§6-§15**：已实现设计的存档记录（实现参考，允许在不违反 §4 核心原则的前提下调整）
- **§16-§17**：落地策略与执行方案（当前活跃）

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

## 6. Skill 定义与资源维护模型

### 6.1 归属原则

Skill 的定义与资源由 Worker 维护，不由上游在每次任务请求中内联传入。

职责边界：

1. `LLM` 负责判断是否使用 Skill，以及使用哪个 Skill
2. 上游系统可以传入 `skillName` 或候选 Skill 名称，但仅作为提示信息
3. `langgraph-biz-worker` 负责 Skill 的注册、存储、扫描、加载、版本与权限控制
4. 运行时只按 `skillId/skillName` 引用 Skill，不传递完整 Skill 正文

这样可以保证：

1. Skill 内容可审计、可回放
2. Skill 资源路径稳定可解析
3. 权限与工具白名单由 Worker 统一收口
4. 同一个 Skill 在不同任务中的行为可复现

### 6.2 目录模型

每个业务账号在 Worker 所在服务器上拥有独立目录，作为该账号的私有运行根目录。

建议目录结构：

```text
<worker-root>/
  skills/
    public/
      <skill-name>/
        SKILL.md
        scripts/
        references/
        assets/
    builtin/
      <skill-name>/
        SKILL.md
        scripts/
        references/
        assets/

  accounts/
    <account-id>/
      conversations/
      skills/
        <skill-name>/
          SKILL.md
          scripts/
          references/
          assets/
      data/
      cache/
      logs/
```

说明：

1. `accounts/<account-id>/` 本身就是账号私有根目录，不再额外引入 `private/`
2. 账号私有 Skill 统一放在 `accounts/<account-id>/skills/`
3. 平台公共 Skill 放在 `skills/public/`
4. Worker 运行时内建 Skill 放在 `skills/builtin/`
5. `conversations/` 与 `skills/` 共处同一账号根目录，便于会话、审计、技能版本关联追踪

首版查找优先级：

1. `account skills`
2. `public`
3. `builtin`

若后续引入项目级 Skill，再扩展为：

1. `workspace`
2. `account skills`
3. `public`
4. `builtin`

### 6.3 Skill 格式规范

Skill 的磁盘格式采用业界开放标准 `Agent Skills`，以 `SKILL.md` 作为入口文件，不再自定义平行的 Manifest 生产协议。

标准来源：

1. `agentskills.io` 开放规范
2. OpenAI Skills 说明
3. LangChain Deep Agents Skills 说明

标准目录最小结构：

```text
<skill-name>/
  SKILL.md
```

推荐结构：

```text
<skill-name>/
  SKILL.md
  scripts/
  references/
  assets/
```

其中：

1. `SKILL.md` 为标准入口文件，采用 YAML frontmatter + Markdown body
2. `scripts/` 存放可复用脚本或执行辅助文件
3. `references/` 存放规则说明、操作手册、案例参考
4. `assets/` 存放模板、示例、静态资源

### 6.4 `SKILL.md` 约定

标准 frontmatter 至少包括：

1. `name`
2. `description`

推荐支持的标准字段：

1. `license`
2. `compatibility`
3. `metadata`
4. `allowed-tools`

其中：

1. 标准字段用于兼容开放 Skill 生态
2. 业务 Worker 的专属附加信息统一放入 `metadata`
3. `allowed-tools` 可以作为 Skill 自声明权限，但最终生效权限仍由 Worker 侧策略二次收口

示例：

```md
---
name: tms-exception-triage
description: Analyze TMS exception orders and return structured handling advice.
license: Proprietary
compatibility: Designed for langgraph-biz-worker
metadata:
  owner: platform
  version: "1.0.0"
  domain: tms
  visibility: account
  output-schema: tms_exception_triage_v1
  approval-mode: required-on-manual-dispatch
allowed-tools: get_order get_vehicle_status search_incidents submit_skill_result request_skill_approval
---

# TMS Exception Triage

## When to use
Use this skill when the task is about exception diagnosis for a TMS order.

## Instructions
1. Read order details first.
2. Collect vehicle and incident evidence if needed.
3. Do not guess without evidence.
4. When finished, call `submit_skill_result`.
5. If manual dispatch is required, call `request_skill_approval`.

## References
Read `references/playbook.md` when classification is unclear.
```

### 6.5 Worker 内部解析规则

虽然 Skill 磁盘格式采用开放标准，但 Worker 运行时仍需统一解析为内部注册表对象。

内部解析时应抽取：

1. `skill_name`
2. `description`
3. `source_scope`（account/public/builtin）
4. `source_path`
5. `metadata`
6. `allowed_tools`
7. `declared_resources`

约束：

1. `SKILL.md` 是唯一入口文件
2. 所有资源路径默认相对当前 Skill 目录解析
3. Skill 不允许默认越目录读取未声明资源
4. Worker 可以缓存 Skill 索引，但正文和资源按需加载

### 6.6 权限与工具白名单

`allowed-tools` 不能直接视为最终权限。

Worker 侧应额外叠加：

1. 平台级工具白名单
2. 账号级工具授权
3. 当前 Skill 声明的 `allowed-tools`

最终生效工具集合：

```text
effective_tools =
  platform_policy
  ∩ account_policy
  ∩ skill_allowed_tools
```

### 6.7 Skill 管理方式

上游系统不直接在 query 请求中传递 Skill 正文，而是通过 Worker 暴露的 Skill 管理 API 维护 Skill。

首版建议支持：

1. 创建账号私有 Skill
2. 更新 `SKILL.md`
3. 上传/删除 `scripts/`、`references/`、`assets/`
4. 查询当前账号可见的 Skills

因此：

1. Skill 的内容归 Worker 管理
2. Skill 的使用时机由 LLM 决定
3. 上游只负责维护和可见性管理，不直接持有运行时 Skill 内容

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

> 注：首版中，审批记录与审计由 Java 侧 JPA 负责（见 §16.4），Worker 侧持久化层仅负责 Frame 状态文件。

1. 保存 Frame 状态 — Worker 侧（JSON 文件）
2. 保存结果产物 — Worker 侧（JSON 文件）
3. 保存审批记录 — Java 侧（JPA）
4. 保存调试/审计痕迹 — Java 侧（JPA）

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

### 16.3 持久化策略

Worker 侧使用 JSON 文件落盘 Frame 状态，**不引入数据库**。

目录结构：

```text
<worker-root>/
  accounts/
    <account-id>/
      frames/
        <task-id>/
          <frame-id>.json
```

写入时机：Frame 状态变更时覆写（创建、运行中、完成、失败、等待审批）。

关键约束：

1. **Worker 重启后不做自动恢复**：内存清空，什么都不做，启动即就绪
2. 恢复由外部触发：用户在前端点"继续" → Java 侧调 Worker `/api/v1/resume`，Worker 从文件读回 Frame 状态继续执行；或 Java 侧自动检测未完成任务主动调 resume
3. 终态 Frame 文件保留可配置天数后清理，或不清理（Java 侧有审计记录即可）

后续数据库预留：

- 首版不做选择，文件方案足够
- 如后期需要（如 Frame 执行历史需复杂查询），再决定 Worker 直连 DB 还是文件同步到 Java 侧

### 16.4 审批职责分工

**原则：Worker 管杀不管埋。**

| 职责 | Python Worker | Java 侧 |
|------|--------------|---------|
| Frame 执行 | ✅ 创建/运行/完成/失败 | — |
| Frame 状态恢复 | ✅ 从文件恢复（被动） | ✅ 发起 resume 调用 |
| 审批请求触发 | ✅ 发出 `approval_request` SSE 事件 | 接收并持久化 |
| 审批记录 & 审计 | — | ✅ JPA 实体存储 |
| 审批结果回传 | 接收 resume 指令继续执行 | ✅ 调用 Worker resume API |
| Frame 历史查询 | — | ✅ 通过 Task/审批记录查 |

审批流：

```text
Python Worker                          Java 侧
    |                                     |
    |-- SSE event: skill_approval_request -->|
    |                                     |-- 持久化审批记录 (JPA)
    |                                     |-- 推送前端 (SSE/WS)
    |                                     |
    |                                     |<-- 用户点击审批
    |                                     |-- 更新审批记录
    |<-- POST /api/v1/resume -------------|
    |                                     |
    |-- 从文件恢复 Frame                    |
    |-- 继续执行 Skill Subgraph            |
    |-- SSE event: result --------------->|
    |                                     |-- 更新任务状态
```

Worker 不存审批记录，只做三件事：发事件、挂起 Frame（写文件）、收到 resume 后继续跑。

### 16.5 Resume API 契约

Java 侧调用 Worker resume 接口时，**只传 `taskId`，不传 `frameId`**。

```text
POST /api/v1/resume
{
  "taskId": "task_xxx",
  "approvalResult": "approved",
  "comment": "..."
}
```

Frame 是 Worker 内部概念，Java 侧不感知。Worker 内部根据 `taskId` 找到处于 `AWAITING_APPROVAL` 状态的 Frame 并恢复执行。

### 16.6 先做的

1. `providerType = langgraph-biz-worker`
2. `SkillManifest`
3. `SkillFrameState`
4. `submit_skill_result`
5. `request_skill_approval`
6. `complete_frame()/close_frame()`
7. 单层 Skill 调用
8. 单个示例 Skill（使用 Mock 工具）

### 16.7 第二阶段

1. 子 Skill 调用栈
2. 审批恢复
3. 调试存储
4. Skill 注册中心
5. 前端 Skill 配置台
6. 真实业务系统工具适配

### 16.8 完成定义

每个阶段的交付必须满足：

1. 相关单元测试 / 集成测试 **运行通过**（不仅仅是编写）
2. 测试结果记录到 progress
3. 经过后置评审链路：quality-gate → test-coverage-audit → acceptance

## 17. 当前实现状态与初始执行方案

### 17.1 已完成

Phase 1-5 已在 Java 侧和 Python 侧落地：

| 组件 | 归属 | 状态 |
|------|------|------|
| `LanggraphWorkerAgentProvider` + `InnerA2aAgent` | Java | ✅ |
| `LanggraphTaskService`（TaskQueryProvider SPI） | Java | ✅ |
| `LanggraphStreamRelay`（SSE 中继） | Java | ✅ |
| `LanggraphWorkerClient`（HTTP 客户端） | Java | ✅ |
| `LanggraphTaskController` + `LanggraphWorkerController` | Java | ✅ |
| Entity / Repository / DTO / Form | Java | ✅ |
| FastAPI 应用 + `/health` + `/api/v1/query` | Python | ✅ |
| Frame 状态机（7 状态 + 转换校验） | Python | ✅ |
| `SkillRuntime`（invoke_skill / submit_result / mark_waiting_child / resume_from_child） | Python | ✅ |
| `SkillRegistry` + `OutputContract` 三层校验 | Python | ✅ |
| Root Graph + 3 个 Skill Subgraph（含嵌套子 Skill） | Python | ✅ |
| Mock 业务工具 | Python | ✅ |
| 38 个测试文件 | Python | ✅（需确认运行结果） |

### 17.2 待补齐缺口

| # | 缺口 | 归属 | 优先级 | 状态 |
|---|------|------|--------|------|
| G1 | Skill 目录结构与设计稿不一致 | Python | 中 | ✅ 已完成（skills/builtin/ + SKILL.md） |
| G2 | Skill 磁盘格式未对齐 | Python | 中 | ✅ 已完成（YAML frontmatter + Markdown body） |
| G3 | Frame 文件持久化 | Python | 高 | ✅ 已完成（FileFrameJournal） |
| G4 | Java 侧审批/恢复 API | Java | 中 | ✅ 已完成（POST /{taskId}/approve） |
| G5 | SecurityConfig 新端点权限 | Java | 中 | ✅ 无需改动（通配符已覆盖） |
| G6 | SSE 审批事件 `skill_approval_request` | Java | 中 | ✅ 已完成（StreamRelay 处理） |
| G7 | Java 侧编译验证 | Java | 中 | ✅ 已完成（mvn compile 全量通过） |
| G8 | Python 测试运行结果 | Python | 低 | ✅ 140 passed, 0 failed |

### 17.3 初始执行方案

#### Phase 6A：核心缺口（优先）

| # | 任务 | 归属 | 完成定义 |
|---|------|------|---------|
| 1 | Frame 文件持久化：状态变更时写 JSON 文件 | Python | 新增持久化写入/读取测试通过 |
| 2 | 运行现有测试并记录结果 | Python | 全部通过，结果记录到 progress |

#### Phase 6B：审批恢复链路 + 架构卫生（并行双轨）

**轨道 A — 审批链路（阻塞后续集成）：**

| # | 任务 | 归属 | 完成定义 |
|---|------|------|---------|
| 3 | Java 审批/恢复 API：`POST /api/v1/langgraph/tasks/{taskId}/approve`、`POST .../resume` | Java | 单元测试通过 + SecurityConfig 已更新 |
| 4 | Python resume 端点：`POST /api/v1/resume`（只接收 taskId），从文件恢复 Frame 继续执行 | Python | 审批 → 恢复 → 完成 全流程测试通过 |
| 5 | SSE Relay 支持 `skill_approval_request` 事件类型 | Java | 集成测试验证事件到达前端 |

**轨道 B — 架构卫生（不阻塞功能，可独立推进）：**

| # | 任务 | 归属 | 完成定义 |
|---|------|------|---------|
| 6 | 对齐 Skill 目录结构：`manifests/` → `skills/builtin/`，`SkillRegistry` 按 `builtin` → `public` 优先级加载 | Python | 现有测试全部通过 + 新增目录结构测试 |
| 7 | Skill 磁盘格式对齐：YAML manifest → `SKILL.md`（YAML frontmatter + Markdown body） | Python | SkillRegistry 能解析 SKILL.md，现有测试适配通过 |

#### Phase 6C：质量收口

| # | 任务 | 归属 | 完成定义 |
|---|------|------|---------|
| 8 | Java 侧集成测试：`LanggraphWorkerAgentProvider` + `LanggraphTaskService`（Mock Python Worker） | Java | 测试运行通过 |
| 9 | 端到端冒烟测试：Java + Python 联调，通过 `/api/v1/agents/{id}/ask` 完成一次完整 Skill 执行 | 联合 | 手动或脚本验证，结果记录 |
| 10 | 文档回写：更新 progress、补充 experience | 文档 | quality-gate 可通过 |

#### 执行顺序

```text
Phase 6A (1→2，串行)
  ↓
Phase 6B 双轨并行:
  轨道 A: 3+4 并行 → 5
  轨道 B: 6→7（独立推进，不阻塞轨道 A）
  ↓
Phase 6C (8+9 并行 → 10)
  ↓
quality-gate → test-coverage-audit → acceptance
```

#### 暂不做

- 账号级 Skill 隔离（`accounts/<id>/skills/`）
- 前端 Skill 配置台
- 真实业务系统工具适配
- 多 Frame 并发调度优化
- Skill 版本回滚
- Worker 重启自动恢复

## 18. 结论

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
